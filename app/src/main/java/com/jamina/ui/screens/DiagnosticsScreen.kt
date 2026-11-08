package com.jamina.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.ConnectionSampleEntity
import com.jamina.core.db.DiagnosticResultEntity
import com.jamina.core.model.fmt
import com.jamina.diagnostics.LanTestServer
import com.jamina.diagnostics.NetProbe
import com.jamina.diagnostics.SpeedTest
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.WifiPrereqBanner
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SessionRow(val time: Long, val rssi: Int?, val latency: Float?, val loss: Float?, val bssid: String?, val flagged: String?)

data class DiagState(
    val busy: String? = null,
    val log: List<String> = emptyList(),
    val verdict: String? = null,
    val liveMbps: Float? = null,
    val sessionRunning: Boolean = false,
    val sessionRows: List<SessionRow> = emptyList(),
    val serverInfo: String? = null,
)

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(DiagState())
    val settings = c.settings.settings
    val history = c.dao.recentDiagnostics(100)
    private var sessionJob: Job? = null

    private fun log(s: String) = state.update { it.copy(log = (listOf("${Fmt.time(System.currentTimeMillis())}  $s") + it.log).take(80)) }

    private suspend fun save(d: DiagnosticResultEntity) { c.dao.insertDiagnostic(d) }

    private fun task(label: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        state.update { it.copy(busy = label, liveMbps = null) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { log("$label failed: ${e.message}") }
            state.update { it.copy(busy = null, liveMbps = null) }
        }
    }

    fun gatewayLatency() = task("Gateway latency") {
        val gw = c.connection.readNow().gateway ?: return@task log("No gateway known — are you on Wi-Fi?")
        val s = NetProbe.ping(gw, 20)
        log("Gateway $gw (${s.method}): avg ${s.avg?.fmt(1) ?: "—"} ms, min ${s.min?.fmt(1) ?: "—"}, max ${s.max?.fmt(1) ?: "—"}, jitter ${s.jitter?.fmt(1) ?: "—"} ms, loss ${s.lossPct.fmt(0)}%")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "GATEWAY_LATENCY", target = gw, valueMs = s.avg, lossPct = s.lossPct, jitterMs = s.jitter, success = s.received > 0, method = s.method))
    }

    fun internetLatency() = task("Internet latency") {
        val host = settings.value.internetHost
        val s = NetProbe.ping(host, 20)
        log("Internet $host (${s.method}): avg ${s.avg?.fmt(1) ?: "—"} ms, jitter ${s.jitter?.fmt(1) ?: "—"} ms, loss ${s.lossPct.fmt(0)}%")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "INTERNET_LATENCY", target = host, valueMs = s.avg, lossPct = s.lossPct, jitterMs = s.jitter, success = s.received > 0, method = s.method))
    }

    fun dns() = task("DNS") {
        val results = (1..5).map { NetProbe.dns("example.com") }
        val avg = results.map { it.ms }.average().toFloat()
        log("DNS (uncached lookups via ${c.connection.readNow().dns.joinToString().ifBlank { "system resolver" }}): avg ${avg.fmt(1)} ms over ${results.size} queries")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "DNS", target = "example.com", valueMs = avg, method = "Uncached random-label lookup"))
    }

    fun http() = task("HTTP") {
        val r = NetProbe.http()
        log(if (r.ms != null) "HTTP response from ${r.url}: ${r.ms.fmt(0)} ms (HTTP ${r.code})" else "HTTP request failed: ${r.error}")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "HTTP", target = r.url, valueMs = r.ms, success = r.ms != null, details = r.error ?: "HTTP ${r.code}"))
    }

    fun packetLoss() = task("Packet loss") {
        val gw = c.connection.readNow().gateway
        val g = gw?.let { NetProbe.ping(it, 50) }
        val i = NetProbe.ping(settings.value.internetHost, 50)
        g?.let { log("Gateway loss: ${it.lossPct.fmt(1)}% of ${it.sent} (${it.method})") }
        log("Internet loss: ${i.lossPct.fmt(1)}% of ${i.sent} (${i.method})")
        g?.let { save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "PACKET_LOSS", target = it.target, lossPct = it.lossPct, valueMs = it.avg, method = it.method)) }
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "PACKET_LOSS", target = i.target, lossPct = i.lossPct, valueMs = i.avg, method = i.method))
    }

    /** Distinguishes a Wi-Fi/LAN problem from an internet/WAN problem. */
    fun fullCheck() = task("Full check") {
        val conn = c.connection.readNow()
        if (!conn.connected) { state.update { it.copy(verdict = "Not connected to Wi-Fi.") }; return@task }
        val gw = conn.gateway
        val g = gw?.let { NetProbe.ping(it, 20) }
        val i = NetProbe.ping(settings.value.internetHost, 20)
        val d = NetProbe.dns("example.com")
        val h = NetProbe.http()
        val gwOk = g != null && g.received > 0
        val inetOk = i.received > 0
        val verdict = buildString {
            append("Wi-Fi signal: ${conn.rssi ?: "—"} dBm. ")
            when {
                !gwOk -> append("The gateway did not respond — this points to a Wi-Fi/LAN connectivity problem (or a router that blocks probes).")
                (g!!.lossPct >= 5f || (g.avg ?: 0f) > 30f) -> append("Gateway link is degraded (loss ${g.lossPct.fmt(0)}%, ${g.avg?.fmt(0)} ms) — the Wi-Fi hop is the likely bottleneck.")
                !inetOk && h.ms == null -> append("Gateway is fine but the internet is unreachable — likely a broadband/ISP or router WAN problem.")
                i.lossPct >= 5f || (i.avg ?: 0f) > 120f -> append("Gateway is fine; internet path shows loss/latency (${i.lossPct.fmt(0)}%, ${i.avg?.fmt(0)} ms) — likely beyond your Wi-Fi.")
                else -> append("Gateway (${g.avg?.fmt(0)} ms) and internet (${i.avg?.fmt(0)} ms) both healthy.")
            }
            append(" DNS ${d.ms.fmt(0)} ms; HTTP ${h.ms?.fmt(0) ?: "failed"} ms.")
        }
        log(verdict)
        state.update { it.copy(verdict = verdict) }
    }

    fun internetSpeed() = task("Internet speed") {
        val s = settings.value
        val dl = SpeedTest.download(s.downloadUrl) { v -> state.update { it.copy(liveMbps = v) } }
        log(if (dl.error == null) "Download: ${dl.mbps.fmt(1)} Mbps (${dl.bytes / 1_000_000} MB in ${dl.seconds.fmt(1)} s)" else "Download failed: ${dl.error}")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "DOWNLOAD", target = s.downloadUrl, mbps = dl.mbps, success = dl.error == null, details = dl.error ?: ""))
        val ul = SpeedTest.upload(s.uploadUrl) { v -> state.update { it.copy(liveMbps = v) } }
        log(if (ul.error == null) "Upload: ${ul.mbps.fmt(1)} Mbps" else "Upload failed: ${ul.error}")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "UPLOAD", target = s.uploadUrl, mbps = ul.mbps, success = ul.error == null, details = ul.error ?: ""))
    }

    fun lanSpeed() = task("LAN speed") {
        val url = settings.value.lanUrl
        if (url.isBlank()) { log("Set a LAN test URL first (e.g. http://192.168.1.10:8000/bigfile.bin)."); return@task }
        val dl = SpeedTest.download(url) { v -> state.update { it.copy(liveMbps = v) } }
        log(if (dl.error == null) "LAN download from $url: ${dl.mbps.fmt(1)} Mbps" else "LAN test failed: ${dl.error}")
        save(DiagnosticResultEntity(timestamp = System.currentTimeMillis(), kind = "LAN_DOWNLOAD", target = url, mbps = dl.mbps, success = dl.error == null, details = dl.error ?: ""))
    }

    fun setLanUrl(u: String) { viewModelScope.launch { c.settings.update { it.copy(lanUrl = u) } } }

    fun toggleServer() {
        if (c.lanServer.running) {
            c.lanServer.stop(); state.update { it.copy(serverInfo = null) }
        } else {
            val port = runCatching { c.lanServer.start() }.getOrElse { log("Could not start server: ${it.message}"); return }
            val ip = LanTestServer.localIpv4() ?: "phone-ip"
            state.update { it.copy(serverInfo = "Serving on http://$ip:$port/download — from a wired PC run:  curl -o NUL http://$ip:$port/download") }
        }
    }

    fun toggleSession() {
        if (sessionJob?.isActive == true) { sessionJob?.cancel(); state.update { it.copy(sessionRunning = false) }; return }
        val tag = "diag-${System.currentTimeMillis()}"
        state.update { it.copy(sessionRunning = true, sessionRows = emptyList()) }
        c.connection.start()
        sessionJob = viewModelScope.launch {
            try {
                while (isActive) {
                    val conn = c.connection.poll()
                    val probe = conn.gateway?.let { NetProbe.ping(it, 5, 0.2f, 1) }
                    val prev = state.value.sessionRows
                    val medianLat = prev.mapNotNull { it.latency }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                    val lastRssi = prev.firstOrNull()?.rssi
                    val flags = mutableListOf<String>()
                    if (conn.rssi != null && lastRssi != null && lastRssi - conn.rssi >= 10) flags += "RSSI dropped ${lastRssi - conn.rssi} dB"
                    if (conn.rssi != null && conn.rssi < settings.value.thresholds.weak) flags += "weak signal"
                    if (probe != null && probe.lossPct > 0) flags += "loss ${probe.lossPct.fmt(0)}%"
                    if (probe?.avg != null && medianLat != null && probe.avg!! > 3 * medianLat && probe.avg!! > 20) flags += "latency spike"
                    if (prev.firstOrNull()?.bssid != null && conn.bssid != null && prev.first().bssid != conn.bssid) flags += "roamed"
                    if (!conn.connected) flags += "disconnected"
                    val row = SessionRow(System.currentTimeMillis(), conn.rssi, probe?.avg, probe?.lossPct, conn.bssid, flags.joinToString().ifBlank { null })
                    state.update { it.copy(sessionRows = (listOf(row) + it.sessionRows).take(500)) }
                    c.dao.insertSample(ConnectionSampleEntity(timestamp = row.time, tag = tag, ssid = conn.ssid, bssid = conn.bssid, rssi = conn.rssi, frequency = conn.frequency, linkSpeedMbps = conn.linkSpeedMbps, latencyMs = probe?.avg, lossPct = probe?.lossPct))
                    delay(5000)
                }
            } finally {
                c.connection.stop()
            }
        }
    }

    override fun onCleared() { sessionJob?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm = appViewModel { DiagnosticsViewModel(it) }
    val st by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val hist by vm.history.collectAsState(emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("Network diagnostics") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            WifiPrereqBanner()
            SectionCard("Connection tests") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::fullCheck) { Text("Full check") }
                    OutlinedButton(onClick = vm::gatewayLatency) { Text("Gateway latency") }
                    OutlinedButton(onClick = vm::internetLatency) { Text("Internet latency") }
                    OutlinedButton(onClick = vm::dns) { Text("DNS timing") }
                    OutlinedButton(onClick = vm::http) { Text("HTTP response") }
                    OutlinedButton(onClick = vm::packetLoss) { Text("Packet loss (50)") }
                }
                st.busy?.let {
                    Text("$it running…${st.liveMbps?.let { v -> "  ${v.fmt(1)} Mbps" } ?: ""}")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                st.verdict?.let { Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                Text("ICMP ping is used when the system allows it; otherwise TCP connection timing is used and labelled as such.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Speed tests") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::internetSpeed) { Text("Internet speed") }
                    OutlinedButton(onClick = vm::lanSpeed) { Text("LAN speed") }
                    OutlinedButton(onClick = vm::toggleServer) { Text(if (st.serverInfo != null) "Stop LAN server" else "Start LAN server") }
                }
                OutlinedTextField(settings.lanUrl, vm::setLanUrl, label = { Text("LAN test URL (file on a local PC/NAS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                st.serverInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Internet test: ${settings.downloadUrl.substringBefore("?")}. Broadband speed alone does not show Wi-Fi performance — use a LAN test for that.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Diagnostic session", action = {
                FilledTonalButton(onClick = vm::toggleSession) { Text(if (st.sessionRunning) "Stop" else "Start recording") }
            }) {
                Text("Logs RSSI, gateway latency and loss every 5 s and highlights degradations.", style = MaterialTheme.typography.bodySmall)
                if (st.sessionRows.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        listOf("Time", "RSSI", "Latency", "Loss").forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold) }
                    }
                }
                st.sessionRows.take(60).forEach { r ->
                    Column(Modifier.fillMaxWidth().then(if (r.flagged != null) Modifier.background(Color(0x33E53935)) else Modifier)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(Fmt.time(r.time), Modifier.weight(1f))
                            Text(r.rssi?.let { "$it dBm" } ?: "—", Modifier.weight(1f))
                            Text(r.latency?.let { "${it.fmt(0)} ms" } ?: "—", Modifier.weight(1f))
                            Text(r.loss?.let { "${it.fmt(0)}%" } ?: "—", Modifier.weight(1f))
                        }
                        r.flagged?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            SectionCard("Log") {
                if (st.log.isEmpty()) Text("No tests run yet.", style = MaterialTheme.typography.bodySmall)
                st.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            SectionCard("Saved results") {
                if (hist.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                hist.take(30).forEach { d ->
                    val v = listOfNotNull(d.valueMs?.let { "${it.fmt(1)} ms" }, d.lossPct?.let { "loss ${it.fmt(0)}%" }, d.mbps?.let { "${it.fmt(1)} Mbps" }).joinToString(", ")
                    Text("${Fmt.dateTime(d.timestamp)}  ${d.kind.lowercase().replace('_', ' ')} → ${d.target}: ${v.ifBlank { d.details }}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
