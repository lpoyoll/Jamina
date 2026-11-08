package com.jamina.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.DiagnosticResultEntity
import com.jamina.core.model.fmt
import com.jamina.diagnostics.LatencyStats
import com.jamina.diagnostics.NetProbe
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.InfoDialog
import com.jamina.ui.components.MetricGrid
import com.jamina.ui.components.MetricTile
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.SignalGauge
import com.jamina.ui.components.Sparkline
import com.jamina.ui.components.WifiPrereqBanner
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class QuickTestState(val running: Boolean = false, val gateway: LatencyStats? = null, val internet: LatencyStats? = null, val at: Long = 0)

class DashboardViewModel(val c: AppContainer) : ViewModel() {
    val info = c.connection.info
    val history = c.connection.rssiHistory
    val settings = c.settings.settings
    val quick = MutableStateFlow(QuickTestState())

    fun runQuickTest() {
        if (quick.value.running) return
        quick.value = quick.value.copy(running = true)
        viewModelScope.launch {
            val gw = c.connection.info.value.gateway
            val gs = gw?.let { NetProbe.ping(it, 10) }
            val inet = NetProbe.ping(settings.value.internetHost, 10)
            quick.value = QuickTestState(false, gs, inet, System.currentTimeMillis())
            gs?.let { c.dao.insertDiagnostic(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "GATEWAY_LATENCY", target = it.target, valueMs = it.avg, lossPct = it.lossPct, jitterMs = it.jitter, success = it.received > 0, method = it.method)) }
            c.dao.insertDiagnostic(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "INTERNET_LATENCY", target = inet.target, valueMs = inet.avg, lossPct = inet.lossPct, jitterMs = inet.jitter, success = inet.received > 0, method = inet.method))
        }
    }

    suspend fun createQuickProject(name: String): String {
        val p = c.repo.createProject(name)
        val f = c.repo.createFloor(p.id, "Ground floor", 0)
        return f.id
    }
}

object Explain {
    fun rssi(v: Int?): String = when {
        v == null -> "Signal strength (RSSI) is the received power of the access point's signal, in dBm. Closer to 0 is stronger."
        v >= -50 -> "$v dBm is excellent. You are close to the access point; speed is limited by other factors."
        v >= -60 -> "$v dBm is very good and supports high data rates."
        v >= -67 -> "$v dBm is good — the commonly used minimum for reliable voice/video calls."
        v >= -70 -> "$v dBm is fair. Browsing works but higher data rates and video calls may suffer."
        v >= -75 -> "$v dBm is relatively weak. You may experience reduced speeds, increased latency or instability. Consider moving closer to the access point or improving coverage here."
        else -> "$v dBm is poor. Expect slow, unreliable connections and drop-outs. Coverage needs improving in this area."
    }
    const val frequency = "The radio band in use. 2.4 GHz travels further through walls but is slower and more crowded; 5 GHz and 6 GHz are faster but have shorter range."
    const val channel = "The Wi-Fi channel the access point is transmitting on. Neighbouring networks on the same or overlapping channels compete for airtime."
    const val width = "Channel width (from the latest scan of this AP). Wider channels allow higher speeds but are more exposed to interference."
    const val link = "The PHY rate negotiated between your phone and the AP. Real throughput is typically 50–70% of this figure."
    const val latency = "Round-trip time to the gateway (your router) and to an internet host. Gateway latency reflects the Wi-Fi link; internet latency adds your broadband connection."
    const val jitter = "Variation in latency between consecutive probes. High jitter disrupts calls and gaming."
    const val loss = "Percentage of probe packets that received no reply. Any sustained loss on the gateway indicates a Wi-Fi problem."
    const val bssid = "BSSID is the unique hardware address of the specific AP radio you are connected to. Several APs can share one network name (SSID)."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenFloor: (String, String) -> Unit,
    onDiagnostics: () -> Unit,
    onRoaming: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = appViewModel { DashboardViewModel(it) }
    val info by vm.info.collectAsState()
    val hist by vm.history.collectAsState()
    val settings by vm.settings.collectAsState()
    val quick by vm.quick.collectAsState()
    var explain by remember { mutableStateOf<Pair<String, String>?>(null) }
    var quickSurvey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val aliases = settings.aliases

    Scaffold(topBar = {
        TopAppBar(title = { Text("Jamina") }, actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            WifiPrereqBanner()
            Button(onClick = { quickSurvey = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Map, null); Spacer(Modifier.width(8.dp)); Text("Quick Survey", style = MaterialTheme.typography.titleMedium)
            }
            SectionCard(if (info.connected) "Connected network: ${info.ssid ?: "(name hidden)"}" else "Not connected to Wi-Fi") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SignalGauge(info.rssi, settings.thresholds, Modifier.padding(4.dp))
                }
                Text("Signal history (last ${hist.size} samples)", style = MaterialTheme.typography.labelMedium)
                Sparkline(hist.map { it.second }.takeLast(150))
                if (info.connected && info.bssidHidden) {
                    Text("AP identity hidden — grant location permission to see the BSSID.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            val bandLabel = info.band?.label ?: "—"
            MetricGrid(listOf(
                { m -> MetricTile("Signal strength", info.rssi?.let { "$it dBm" } ?: "—", m, sub = info.rssi?.let { settings.thresholds.levelOf(it).label }, color = info.rssi?.let { Color(settings.thresholds.levelOf(it).colorArgb) }) { explain = "Signal strength" to Explain.rssi(info.rssi) } },
                { m -> MetricTile("Frequency", bandLabel, m, sub = info.frequency?.let { "$it MHz" }) { explain = "Frequency" to Explain.frequency } },
                { m -> MetricTile("Channel", info.channel?.toString() ?: "—", m) { explain = "Channel" to Explain.channel } },
                { m -> MetricTile("Channel width", info.widthMhz?.let { "$it MHz" } ?: "—", m, sub = "from scan") { explain = "Channel width" to Explain.width } },
                { m -> MetricTile("Link speed", info.linkSpeedMbps?.let { "$it Mbps" } ?: "—", m, sub = listOfNotNull(info.txLinkMbps?.let { "TX $it" }, info.rxLinkMbps?.let { "RX $it" }).joinToString(" / ").ifBlank { null }) { explain = "Link speed" to Explain.link } },
                { m -> MetricTile("Wi-Fi standard", info.standard ?: "—", m) },
                { m -> MetricTile("Gateway latency", quick.gateway?.avg?.let { "${it.fmt(1)} ms" } ?: "—", m, sub = quick.gateway?.method) { explain = "Latency" to Explain.latency } },
                { m -> MetricTile("Internet latency", quick.internet?.avg?.let { "${it.fmt(1)} ms" } ?: "—", m, sub = settings.internetHost) { explain = "Latency" to Explain.latency } },
                { m -> MetricTile("Jitter", quick.gateway?.jitter?.let { "${it.fmt(1)} ms" } ?: "—", m) { explain = "Jitter" to Explain.jitter } },
                { m -> MetricTile("Packet loss", quick.gateway?.let { "${it.lossPct.fmt(0)}%" } ?: "—", m, sub = quick.internet?.let { "Internet ${it.lossPct.fmt(0)}%" }) { explain = "Packet loss" to Explain.loss } },
                { m -> MetricTile("Connected AP", info.bssid ?: "—", m, sub = vm.settings.value.aliasOf(info.bssid) ?: aliases[info.bssid ?: ""]) { explain = "BSSID" to Explain.bssid } },
                { m -> MetricTile("IP / gateway", info.ipAddress ?: "—", m, sub = info.gateway?.let { "GW $it" }) },
            ))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = vm::runQuickTest, enabled = !quick.running && info.connected) { Text(if (quick.running) "Testing…" else "Run latency test") }
                if (quick.at > 0) Text("Measured ${Fmt.time(quick.at)}", style = MaterialTheme.typography.bodySmall)
            }
            if (quick.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDiagnostics, modifier = Modifier.weight(1f)) { Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(6.dp)); Text("Diagnostics") }
                FilledTonalButton(onClick = onRoaming, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Roaming") }
            }
            Text(
                "Readings come from Android's Wi-Fi APIs on this phone. Link speed is the negotiated PHY rate, not measured throughput.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    explain?.let { (t, s) -> InfoDialog(t, s) { explain = null } }

    if (quickSurvey) {
        var name by remember { mutableStateOf("My home") }
        var method by remember { mutableStateOf("import") }
        AlertDialog(
            onDismissRequest = { quickSurvey = false },
            title = { Text("Quick Survey") },
            text = {
                Column {
                    Text("1. Name the building", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(name, { name = it }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Text("2. Floor plan", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(method == "import", { method = "import" }); Text("Import an image or PDF") }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(method == "draw", { method = "draw" }); Text("Draw a simple blueprint") }
                    Text("Next you'll calibrate the scale, then tap your position as you walk around to collect measurements.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    quickSurvey = false
                    scope.launch { onOpenFloor(vm.createQuickProject(name), method) }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { quickSurvey = false }) { Text("Cancel") } },
        )
    }
}
