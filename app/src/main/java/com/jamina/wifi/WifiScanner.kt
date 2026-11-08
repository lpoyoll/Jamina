package com.jamina.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.jamina.core.db.newId
import com.jamina.core.model.Band
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** One set of results delivered by Android. fresh=false means Android returned cached results. */
data class ScanBatch(
    val id: String,
    val seq: Int,
    val timestamp: Long,
    val fresh: Boolean,
    val results: List<ApObservation>,
)

data class ScanState(
    val latest: ScanBatch? = null,
    val lastRequestAt: Long = 0,
    val lastRequestAccepted: Boolean = true,
    val requestsInWindow: Int = 0,
    val throttleEnabled: Boolean? = null,
    val wifiEnabled: Boolean = true,
    val error: String? = null,
)

/**
 * Wraps WifiManager scanning. Honours Android's foreground scan throttle
 * (4 requested scans per 2 minutes on Android 10+) and never fabricates readings.
 */
class WifiScanner(context: Context, @Suppress("unused") private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val requestTimes = ArrayDeque<Long>()
    private var seq = 0

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Per-BSSID RSSI history in this app run (time, rssi), capped. */
    private val _history = MutableStateFlow<Map<String, List<Pair<Long, Int>>>>(emptyMap())
    val history: StateFlow<Map<String, List<Pair<Long, Int>>>> = _history.asStateFlow()

    private val firstSeen = mutableMapOf<String, Long>()
    fun firstSeen(bssid: String): Long? = firstSeen[bssid]

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            publish(fresh = updated)
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        val throttle = if (Build.VERSION.SDK_INT >= 30) runCatching { wifi.isScanThrottleEnabled }.getOrNull() else true
        _state.update { it.copy(throttleEnabled = throttle, wifiEnabled = wifi.isWifiEnabled) }
    }

    private fun prune(now: Long) {
        while (requestTimes.isNotEmpty() && now - requestTimes.first() > WINDOW_MS) requestTimes.removeFirst()
    }

    /** Milliseconds until a scan request will not exceed the throttle (0 = now). */
    fun msUntilAllowed(): Long {
        val now = SystemClock.elapsedRealtime()
        prune(now)
        if (_state.value.throttleEnabled == false) {
            val last = requestTimes.lastOrNull() ?: return 0
            return (MIN_SPACING_MS - (now - last)).coerceAtLeast(0)
        }
        if (requestTimes.size < MAX_PER_WINDOW) return 0
        return (WINDOW_MS - (now - requestTimes.first())).coerceAtLeast(0)
    }

    /** Requests a scan if allowed. Returns true if Android accepted the request. */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean {
        refreshEnvironment()
        if (msUntilAllowed() > 0) return false
        val now = SystemClock.elapsedRealtime()
        requestTimes.addLast(now)
        val ok = try {
            wifi.startScan()
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied", e)
            _state.update { it.copy(error = "Scanning needs location permission") }
            false
        }
        _state.update {
            it.copy(lastRequestAt = System.currentTimeMillis(), lastRequestAccepted = ok, requestsInWindow = requestTimes.size)
        }
        return ok
    }

    /** Reads whatever results Android currently holds and publishes them as a cached (non-fresh) batch. */
    fun readCached(): ScanBatch = publish(fresh = false)

    @SuppressLint("MissingPermission")
    private fun publish(fresh: Boolean): ScanBatch {
        val raw: List<ScanResult> = try {
            wifi.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            _state.update { it.copy(error = "Location permission is required to read Wi-Fi scan results") }
            emptyList()
        }
        val nowBootUs = SystemClock.elapsedRealtime() * 1000
        val now = System.currentTimeMillis()
        val results = raw.map { r -> r.toObservation(nowBootUs) }.sortedByDescending { it.rssi }
        val batch = ScanBatch(newId(), ++seq, now, fresh, results)
        val hist = _history.value.toMutableMap()
        for (r in results) {
            firstSeen.putIfAbsent(r.bssid, now - r.ageMs)
            val list = (hist[r.bssid] ?: emptyList())
            val t = now - r.ageMs
            if (list.lastOrNull()?.first != t) hist[r.bssid] = (list + (t to r.rssi)).takeLast(300)
        }
        _history.value = hist
        _state.update { it.copy(latest = batch, error = if (results.isNotEmpty()) null else it.error, wifiEnabled = wifi.isWifiEnabled) }
        return batch
    }

    /**
     * Obtains a scan for a survey measurement.
     * If [requireFresh] it waits for the throttle window (or for a system-initiated scan) and then
     * waits for Android to deliver new results. Otherwise it returns cached results, clearly flagged.
     */
    suspend fun acquire(requireFresh: Boolean, onStatus: (String) -> Unit, maxWaitMs: Long = 150_000): ScanBatch {
        val startSeq = _state.value.latest?.seq ?: 0
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            val latest = _state.value.latest
            if (latest != null && latest.seq > startSeq && latest.fresh) return latest
            val wait = msUntilAllowed()
            if (wait == 0L) break
            if (!requireFresh) return readCached()
            if (SystemClock.elapsedRealtime() > deadline) return readCached()
            onStatus("Android scan limit reached — fresh scan possible in ${(wait + 999) / 1000}s (or when the system scans)")
            delay(minOf(wait, 1000))
        }
        onStatus("Scanning…")
        val accepted = requestScan()
        if (!accepted) {
            onStatus("Android rejected the scan request; using latest available results")
            return readCached()
        }
        val got = withTimeoutOrNull(12_000) {
            state.first { (it.latest?.seq ?: 0) > startSeq }.latest
        }
        return got ?: readCached()
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toObservation(nowBootUs: Long): ApObservation {
        val width = WifiMath.widthFromCode(channelWidth)
        val std = if (Build.VERSION.SDK_INT >= 30) WifiMath.standardLabel(wifiStandard) else "Unknown"
        val ssidStr = (SSID ?: "").removeSurrounding("\"")
        val age = ((nowBootUs - timestamp) / 1000).coerceAtLeast(0)
        return ApObservation(
            bssid = (BSSID ?: "").lowercase(),
            ssid = ssidStr,
            rssi = level,
            frequency = frequency,
            channel = WifiMath.channelOf(frequency),
            band = Band.fromFreq(frequency),
            widthMhz = width,
            centerFreq0 = centerFreq0,
            standard = std,
            security = WifiMath.securityOf(capabilities ?: ""),
            capabilities = capabilities ?: "",
            vendor = WifiMath.vendorOf(BSSID ?: ""),
            ageMs = age,
        )
    }

    companion object {
        private const val TAG = "WifiScanner"
        const val WINDOW_MS = 120_000L
        const val MAX_PER_WINDOW = 4
        const val MIN_SPACING_MS = 3_000L
    }
}
