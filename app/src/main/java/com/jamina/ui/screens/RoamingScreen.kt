package com.jamina.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.ConnectionSampleEntity
import com.jamina.core.db.RoamingEventEntity
import com.jamina.diagnostics.NetProbe
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.Sparkline
import com.jamina.ui.components.WifiPrereqBanner
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoamState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val rssi: List<Int> = emptyList(),
    val probes: Int = 0,
    val failures: Int = 0,
    val status: String = "",
)

/**
 * Records connected BSSID once per second while probing the gateway, so an observed BSSID change
 * can be paired with any observed loss of connectivity around it. The true 802.11 reassociation
 * time is not exposed by Android and is not claimed.
 */
class RoamingViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(RoamState())
    val events = c.dao.recentRoaming(100)
    val settings = c.settings.settings
    private var job: Job? = null

    fun toggle() {
        if (job?.isActive == true) { job?.cancel(); return }
        val tag = "roam-${System.currentTimeMillis()}"
        state.value = RoamState(true, System.currentTimeMillis(), status = "Walk between access points…")
        c.connection.start()
        job = viewModelScope.launch {
            var lastOk = System.currentTimeMillis()
            var outageStart: Long? = null
            var prev = c.connection.poll()
            var pendingRoam: RoamingEventEntity? = null
            var pendingAt = 0L
            try {
                while (isActive) {
                    val now = c.connection.poll()
                    val gw = now.gateway
                    val rtt = gw?.let { NetProbe.quickProbe(it) }
                    val t = System.currentTimeMillis()
                    if (rtt == null) { if (outageStart == null) outageStart = lastOk } else {
                        if (outageStart != null && pendingRoam != null && t - pendingAt < 15_000) {
                            pendingRoam = pendingRoam.copy(interruptionMs = t - outageStart)
                        }
                        outageStart = null; lastOk = t
                    }
                    state.update { s -> s.copy(rssi = (s.rssi + listOfNotNull(now.rssi)).takeLast(300), probes = s.probes + 1, failures = s.failures + if (rtt == null) 1 else 0) }
                    c.dao.insertSample(ConnectionSampleEntity(timestamp = t, tag = tag, ssid = now.ssid, bssid = now.bssid, rssi = now.rssi, frequency = now.frequency, linkSpeedMbps = now.linkSpeedMbps, latencyMs = rtt, lossPct = if (rtt == null) 100f else 0f))
                    if (prev.bssid != null && now.bssid != null && prev.bssid != now.bssid) {
                        pendingRoam?.let { c.dao.insertRoaming(it) }
                        val interruption = outageStart?.let { t - it } ?: 0L
                        pendingRoam = RoamingEventEntity(timestamp = t, ssid = now.ssid, fromBssid = prev.bssid, toBssid = now.bssid, rssiBefore = prev.rssi, rssiAfter = now.rssi, freqBefore = prev.frequency, freqAfter = now.frequency, interruptionMs = interruption, note = roamNote(prev.rssi, now.rssi))
                        pendingAt = t
                    }
                    if (pendingRoam != null && t - pendingAt > 5_000 && outageStart == null) {
                        c.dao.insertRoaming(pendingRoam); pendingRoam = null
                    }
                    prev = now
                    delay(1000)
                }
            } finally {
                pendingRoam?.let { launch { c.dao.insertRoaming(it) } }
                c.connection.stop()
                state.update { it.copy(running = false, status = "Stopped") }
            }
        }
    }

    private fun roamNote(before: Int?, after: Int?): String {
        val t = settings.value.thresholds
        return when {
            before != null && before < t.weak -> "Late handover: phone held on until ${before} dBm before roaming."
            before != null && after != null && after < before -> "Moved to a weaker AP (${before} → ${after} dBm) — possible poor roaming decision."
            else -> ""
        }
    }

    fun clear() { viewModelScope.launch { c.dao.clearRoaming() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoamingScreen(onBack: () -> Unit) {
    val vm = appViewModel { RoamingViewModel(it) }
    val st by vm.state.collectAsState()
    val events by vm.events.collectAsState(emptyList())
    val settings by vm.settings.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Roaming analysis") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            WifiPrereqBanner()
            SectionCard("Roaming session") {
                Row {
                    FilledTonalButton(onClick = vm::toggle) { Text(if (st.running) "Stop" else "Start roaming session") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = vm::clear) { Text("Clear events") }
                }
                if (st.running || st.probes > 0) {
                    Text("${st.status}  Probes: ${st.probes}, failed: ${st.failures}", style = MaterialTheme.typography.bodySmall)
                    Sparkline(st.rssi)
                }
                Text(
                    "Keep the screen on and walk slowly between APs. Interruptions are measured from gateway probes once per second, so they reflect observed connectivity gaps — not the precise reassociation time, which Android does not expose.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SectionCard("Timeline") {
                if (events.isEmpty()) Text("No roaming events recorded yet.", style = MaterialTheme.typography.bodySmall)
                events.forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${Fmt.dateTime(e.timestamp)}  ${e.ssid ?: ""}", fontWeight = FontWeight.SemiBold)
                        Text("Previous AP: ${settings.aliasOf(e.fromBssid) ?: e.fromBssid} (${e.rssiBefore ?: "—"} dBm, ${e.freqBefore ?: "—"} MHz)")
                        Text("New AP: ${settings.aliasOf(e.toBssid) ?: e.toBssid} (${e.rssiAfter ?: "—"} dBm, ${e.freqAfter ?: "—"} MHz)")
                        Text("Observed connectivity interruption: ${e.interruptionMs?.let { if (it == 0L) "none detected (≤1 s resolution)" else "$it ms" } ?: "unknown"}")
                        if (e.note.isNotBlank()) Text("⚠ ${e.note}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
