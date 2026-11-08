package com.jamina.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.model.Band
import com.jamina.heatmap.HeatColors
import com.jamina.wifi.ApObservation
import com.jamina.wifi.ChannelAnalysis
import com.jamina.wifi.WifiMath
import com.jamina.wifi.WifiScanner
import com.jamina.ui.components.EmptyState
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.WifiPrereqBanner
import com.jamina.ui.components.appViewModel
import com.jamina.ui.components.drawLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScannerViewModel(val c: AppContainer) : ViewModel() {
    val scan = c.scanner.state
    val history = c.scanner.history
    val settings = c.settings.settings
    val connection = c.connection.info
    val auto = MutableStateFlow(true)
    val pinned = MutableStateFlow<Set<String>>(emptySet())
    private var job: Job? = null

    init { startAuto() }

    fun setAuto(on: Boolean) { auto.value = on; if (on) startAuto() else job?.cancel() }

    private fun startAuto() {
        job?.cancel()
        job = viewModelScope.launch {
            if (c.scanner.state.value.latest == null) c.scanner.readCached()
            while (isActive) {
                val throttled = c.scanner.state.value.throttleEnabled != false
                if (c.scanner.msUntilAllowed() == 0L) c.scanner.requestScan()
                delay(if (throttled) 30_000 else 10_000)
            }
        }
    }

    fun scanNow(): Boolean = c.scanner.requestScan()
    fun msUntilAllowed() = c.scanner.msUntilAllowed()
    fun togglePin(b: String) { pinned.value = if (b in pinned.value) pinned.value - b else pinned.value + b }
    fun rename(bssid: String, name: String) { viewModelScope.launch { c.settings.setAlias(bssid, name) } }
    fun firstSeen(b: String) = c.scanner.firstSeen(b)
    override fun onCleared() { job?.cancel() }
}

private fun bssidColor(b: String): Color = Color(HeatColors.category(b.hashCode()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen() {
    val vm = appViewModel { ScannerViewModel(it) }
    val st by vm.scan.collectAsState()
    val hist by vm.history.collectAsState()
    val settings by vm.settings.collectAsState()
    val conn by vm.connection.collectAsState()
    val auto by vm.auto.collectAsState()
    val pinned by vm.pinned.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var band by remember { mutableStateOf<Band?>(null) }
    var group by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf<ApObservation?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val results = st.latest?.results ?: emptyList()
    val filtered = results.filter { band == null || it.band == band }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Scanner") }, actions = {
            Text("Auto", style = MaterialTheme.typography.labelMedium)
            Switch(auto, vm::setAuto, Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { vm.scanNow() }, enabled = vm.msUntilAllowed() == 0L) { Icon(Icons.Default.Refresh, "Scan now") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                WifiPrereqBanner()
                val latest = st.latest
                val wait = vm.msUntilAllowed()
                Text(
                    buildString {
                        if (latest == null) append("No scan results yet. ") else append("${results.size} BSSIDs • results ${Fmt.age(now - latest.timestamp)} • ${if (latest.fresh) "fresh scan" else "cached by Android"}. ")
                        append(if (wait > 0) "Next requested scan in ${(wait + 999) / 1000}s (Android throttle)." else "Scan available now.")
                        if (st.throttleEnabled == false) append(" Throttling disabled.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(band == null, { band = null }, { Text("All") })
                    Band.entries.forEach { b -> FilterChip(band == b, { band = b }, { Text(b.label) }) }
                    FilterChip(group, { group = !group }, { Text("Group by SSID") })
                }
            }
            TabRow(tab) {
                listOf("Networks", "Channels", "History", "Interference").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> NetworkList(filtered, group, conn.bssid, settings.aliases, settings.thresholds.let { t -> { r: Int -> Color(t.levelOf(r).colorArgb) } }) { details = it }
                1 -> ChannelGraphs(filtered, band)
                2 -> HistoryTab(results, hist, pinned, conn.bssid, vm::togglePin)
                3 -> InterferenceTab(results)
            }
        }
    }

    details?.let { ap ->
        var alias by remember(ap.bssid) { mutableStateOf(settings.aliasOf(ap.bssid) ?: "") }
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(ap.displaySsid) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val first = vm.firstSeen(ap.bssid)
                    listOf(
                        "BSSID" to ap.bssid,
                        "Signal" to "${ap.rssi} dBm (${settings.thresholds.levelOf(ap.rssi).label})",
                        "Band" to (ap.band?.label ?: "?"),
                        "Frequency" to "${ap.frequency} MHz",
                        "Primary channel" to ap.channel.toString(),
                        "Channel width" to "${ap.widthMhz} MHz",
                        "Standard" to ap.standard,
                        "Security" to ap.security,
                        "Capabilities" to ap.capabilities,
                        "Manufacturer" to ap.vendor,
                        "Reading age" to Fmt.age(ap.ageMs),
                        "First seen" to (first?.let { Fmt.dateTime(it) } ?: "—"),
                        "Connected" to if (ap.bssid == conn.bssid) "Yes" else "No",
                    ).forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 2.dp)) { Text(k, Modifier.width(120.dp), fontWeight = FontWeight.SemiBold); Text(v) }
                    }
                    OutlinedTextField(alias, { alias = it }, label = { Text("Name this AP (e.g. Living room)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.rename(ap.bssid, alias); details = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun NetworkList(
    results: List<ApObservation>,
    group: Boolean,
    connected: String?,
    aliases: Map<String, String>,
    levelColor: (Int) -> Color,
    onClick: (ApObservation) -> Unit,
) {
    if (results.isEmpty()) { EmptyState("No networks detected yet. Make sure Wi-Fi and location are on."); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (group) {
            val groups = results.groupBy { it.ssid }.entries.sortedByDescending { e -> e.value.maxOf { it.rssi } }
            groups.forEach { (ssid, aps) ->
                item(key = "h-$ssid") {
                    Text(
                        "${ssid.ifBlank { "(hidden network)" }}  •  ${aps.size} AP radio${if (aps.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(aps.sortedByDescending { it.rssi }, key = { "g-${it.bssid}" }) { ApRow(it, it.bssid == connected, aliases[it.bssid], levelColor, onClick) }
            }
        } else {
            items(results.sortedByDescending { it.rssi }, key = { it.bssid }) { ApRow(it, it.bssid == connected, aliases[it.bssid], levelColor, onClick) }
        }
    }
}

@Composable
private fun ApRow(ap: ApObservation, connected: Boolean, alias: String?, levelColor: (Int) -> Color, onClick: (ApObservation) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick(ap) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (connected) "● " else "") + ap.displaySsid + (alias?.let { "  ($it)" } ?: ""),
                fontWeight = if (connected) FontWeight.Bold else FontWeight.Normal,
            )
            Text("${ap.bssid}  •  ch ${ap.channel} (${ap.band?.label ?: "?"}, ${ap.widthMhz} MHz)  •  ${ap.security}", style = MaterialTheme.typography.bodySmall)
            Text("${ap.standard}  •  ${ap.vendor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${ap.rssi}", style = MaterialTheme.typography.titleLarge, color = levelColor(ap.rssi), fontWeight = FontWeight.Bold)
        Text(" dBm", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

@Composable
private fun ChannelGraphs(results: List<ApObservation>, band: Band?) {
    val bands = if (band != null) listOf(band) else Band.entries.filter { b -> results.any { it.band == b } }.ifEmpty { listOf(Band.B24, Band.B5) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        bands.forEach { b -> ChannelGraph(results.filter { it.band == b }, b) }
        Text("Each shape is one BSSID drawn across the spectrum it occupies; height is signal strength.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChannelGraph(aps: List<ApObservation>, band: Band) {
    val (fMin, fMax, ticks) = when (band) {
        Band.B24 -> Triple(2400, 2495, (1..13).toList())
        Band.B5 -> Triple(5150, 5850, listOf(36, 52, 64, 100, 116, 132, 149, 165))
        Band.B6 -> Triple(5925, 7125, listOf(1, 33, 65, 97, 129, 161, 193, 225))
    }
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("${band.label} channels (${aps.size} BSSIDs)") {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 40f; val bottom = size.height - 30f
            fun fx(f: Int) = left + (f - fMin).toFloat() / (fMax - fMin) * (size.width - left - 8f)
            fun fy(r: Int) = bottom - ((r + 100f) / 75f).coerceIn(0f, 1f) * (bottom - 10f)
            drawLine(axis, Offset(left, bottom), Offset(size.width, bottom), 2f)
            for (db in listOf(-90, -70, -50, -30)) {
                drawLine(axis.copy(alpha = 0.2f), Offset(left, fy(db)), Offset(size.width, fy(db)), 1f)
                drawLabel("$db", Offset(0f, fy(db) + 8f), axis, 22f)
            }
            ticks.forEach { ch ->
                val x = fx(WifiMath.freqOf(ch, band))
                drawLabel("$ch", Offset(x - 10f, size.height - 4f), axis, 22f)
            }
            aps.sortedBy { it.rssi }.forEach { ap ->
                val (lo, hi) = ap.span()
                val x0 = fx(lo); val x1 = fx(hi); val top = fy(ap.rssi)
                val path = Path().apply {
                    moveTo(x0, bottom)
                    cubicTo(x0 + (x1 - x0) * 0.15f, top, x1 - (x1 - x0) * 0.15f, top, x1, bottom)
                }
                val col = bssidColor(ap.bssid)
                drawPath(path, col.copy(alpha = 0.18f))
                drawPath(path, col, style = Stroke(3f))
                drawLabel(ap.displaySsid.take(14), Offset((x0 + x1) / 2 - 30f, top + 18f), col, 22f)
            }
        }
    }
}

@Composable
private fun HistoryTab(results: List<ApObservation>, hist: Map<String, List<Pair<Long, Int>>>, pinned: Set<String>, connected: String?, toggle: (String) -> Unit) {
    val defaultSel = (listOfNotNull(connected) + results.take(3).map { it.bssid }).toSet()
    val selected = if (pinned.isEmpty()) defaultSel else pinned
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("RSSI history from scans received this session. Gaps mean no new scan result.", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val series = selected.mapNotNull { b -> hist[b]?.let { b to it } }
            val tMin = series.minOfOrNull { s -> s.second.minOf { it.first } } ?: return@Canvas
            val tMax = maxOf(series.maxOf { s -> s.second.maxOf { it.first } }, tMin + 1000)
            for (db in listOf(-90, -70, -50, -30)) {
                val y = size.height * (1f - (db + 100f) / 75f)
                drawLine(axis.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), 1f)
                drawLabel("$db", Offset(0f, y - 4f), axis, 22f)
            }
            series.forEach { (b, pts) ->
                val path = Path()
                pts.forEachIndexed { i, (t, r) ->
                    val x = (t - tMin).toFloat() / (tMax - tMin) * size.width
                    val y = size.height * (1f - ((r + 100f) / 75f).coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(bssidColor(b), 4f, Offset(x, y))
                }
                drawPath(path, bssidColor(b), style = Stroke(3f))
            }
        }
        Spacer(Modifier.height(8.dp))
        results.forEach { ap ->
            Row(Modifier.fillMaxWidth().clickable { toggle(ap.bssid) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ap.bssid in selected, { toggle(ap.bssid) })
                Text("${ap.displaySsid} • ${ap.bssid}", color = bssidColor(ap.bssid), modifier = Modifier.weight(1f))
                Text("${ap.rssi} dBm")
            }
        }
    }
}

@Composable
private fun InterferenceTab(results: List<ApObservation>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            "Potential contention inferred from Wi-Fi scans. Android cannot measure actual airtime utilisation or non-Wi-Fi interference (microwaves, baby monitors, etc.).",
            style = MaterialTheme.typography.bodySmall,
        )
        for (b in Band.entries) {
            val rep = ChannelAnalysis.analyse(results, b)
            if (rep.loads.none { it.apCount > 0 }) continue
            SectionCard(b.label) {
                rep.findings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text("Least crowded: ${rep.recommended.joinToString()}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                val busy = rep.loads.filter { it.apCount > 0 }.sortedByDescending { it.score }.take(8)
                busy.forEach { l ->
                    Row { Text("Ch ${l.channel}", Modifier.width(70.dp)); Text("${l.apCount} overlapping AP(s), strongest ${l.strongest} dBm, load score ${"%.1f".format(l.score)}") }
                }
            }
        }
        if (results.isEmpty()) EmptyState("No scan results yet.")
        Text("Scan throttle: ${WifiScanner.MAX_PER_WINDOW} requests / ${WifiScanner.WINDOW_MS / 1000}s on Android 10+.", style = MaterialTheme.typography.bodySmall)
    }
}
