package com.jamina.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.jamina.core.model.Band
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address

data class ConnectionInfo(
    val wifiEnabled: Boolean = false,
    val connected: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequency: Int? = null,
    val channel: Int? = null,
    val band: Band? = null,
    val widthMhz: Int? = null,
    val linkSpeedMbps: Int? = null,
    val txLinkMbps: Int? = null,
    val rxLinkMbps: Int? = null,
    val standard: String? = null,
    val ipAddress: String? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val timestamp: Long = 0,
    val bssidHidden: Boolean = false,
)

data class RoamEvent(
    val timestamp: Long,
    val ssid: String?,
    val from: String?,
    val to: String?,
    val rssiBefore: Int?,
    val rssiAfter: Int?,
    val freqBefore: Int?,
    val freqAfter: Int?,
)

/** Polls the connected Wi-Fi link and detects observed BSSID changes (roams). */
class ConnectionMonitor(context: Context, private val scope: CoroutineScope, private val scanner: WifiScanner) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _info = MutableStateFlow(ConnectionInfo())
    val info: StateFlow<ConnectionInfo> = _info.asStateFlow()

    private val _rssiHistory = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val rssiHistory: StateFlow<List<Pair<Long, Int>>> = _rssiHistory.asStateFlow()

    private val _roams = MutableSharedFlow<RoamEvent>(extraBufferCapacity = 32)
    val roams: SharedFlow<RoamEvent> = _roams.asSharedFlow()

    private var job: Job? = null
    @Volatile var intervalMs: Long = 2000
    private var holders = 0

    @Synchronized
    fun start() {
        holders++
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                poll()
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            job?.cancel()
            job = null
        }
    }

    fun poll(): ConnectionInfo {
        val prev = _info.value
        val now = readNow()
        _info.value = now
        if (now.connected && now.rssi != null) {
            _rssiHistory.value = (_rssiHistory.value + (now.timestamp to now.rssi)).takeLast(600)
        }
        if (prev.connected && now.connected && !now.bssidHidden && !prev.bssidHidden &&
            prev.bssid != null && now.bssid != null && prev.bssid != now.bssid
        ) {
            _roams.tryEmit(
                RoamEvent(now.timestamp, now.ssid, prev.bssid, now.bssid, prev.rssi, now.rssi, prev.frequency, now.frequency),
            )
        }
        return now
    }

    @Suppress("DEPRECATION")
    fun readNow(): ConnectionInfo {
        val enabled = wifi.isWifiEnabled
        val ts = System.currentTimeMillis()
        if (!enabled) return ConnectionInfo(wifiEnabled = false, timestamp = ts)
        val wi = try { wifi.connectionInfo } catch (_: SecurityException) { null }
        if (wi == null || wi.networkId == -1 && (wi.bssid == null || wi.rssi <= -127)) {
            return ConnectionInfo(wifiEnabled = true, connected = false, timestamp = ts)
        }
        val rawSsid = wi.ssid?.removeSurrounding("\"")
        val ssid = if (rawSsid == null || rawSsid == "<unknown ssid>") null else rawSsid
        val rawBssid = wi.bssid?.lowercase()
        val hidden = rawBssid == null || rawBssid == "02:00:00:00:00:00"
        val freq = wi.frequency.takeIf { it > 0 }
        val width = rawBssid?.let { b -> scanner.state.value.latest?.results?.firstOrNull { it.bssid == b }?.widthMhz }
        var ip: String? = null
        var gw: String? = null
        var dns: List<String> = emptyList()
        try {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val lp = cm.getLinkProperties(net)
                ip = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
                    ?: lp?.linkAddresses?.firstOrNull()?.address?.hostAddress
                gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                    ?: lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
                dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            }
        } catch (_: Exception) { }
        if (gw == null) {
            val g = try { wifi.dhcpInfo?.gateway ?: 0 } catch (_: Exception) { 0 }
            if (g != 0) gw = "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
        }
        val standard = if (Build.VERSION.SDK_INT >= 30) WifiMath.standardLabel(wi.wifiStandard) else null
        return ConnectionInfo(
            wifiEnabled = true,
            connected = true,
            ssid = ssid,
            bssid = if (hidden) null else rawBssid,
            rssi = wi.rssi.takeIf { it > -127 },
            frequency = freq,
            channel = freq?.let { WifiMath.channelOf(it) },
            band = freq?.let { Band.fromFreq(it) },
            widthMhz = width,
            linkSpeedMbps = wi.linkSpeed.takeIf { it > 0 },
            txLinkMbps = wi.txLinkSpeedMbps.takeIf { it > 0 },
            rxLinkMbps = wi.rxLinkSpeedMbps.takeIf { it > 0 },
            standard = standard,
            ipAddress = ip,
            gateway = gw,
            dns = dns,
            timestamp = ts,
            bssidHidden = hidden,
        )
    }
}
