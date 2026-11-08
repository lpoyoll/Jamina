package com.jamina.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.SurveySessionEntity
import com.jamina.core.model.fmt
import com.jamina.core.model.pct
import com.jamina.heatmap.HeatmapBuilder
import com.jamina.heatmap.HeatmapRenderer
import com.jamina.heatmap.HeatmapType
import com.jamina.heatmap.IdwOptions
import com.jamina.heatmap.MarkerSpec
import com.jamina.heatmap.PlanRenderer
import com.jamina.heatmap.PointMeasurement
import com.jamina.heatmap.SignalFilter
import com.jamina.heatmap.HeatColors
import com.jamina.heatmap.Measures
import com.jamina.heatmap.stats
import com.jamina.planner.Propagation
import com.jamina.planner.PropagationParams
import com.jamina.reports.Comparison
import com.jamina.reports.ComparisonResult
import com.jamina.reports.CsvExport
import com.jamina.reports.Insight
import com.jamina.reports.Insights
import com.jamina.reports.PdfReport
import com.jamina.reports.ReportSection
import com.jamina.reports.Severity
import com.jamina.reports.SurveyStats
import com.jamina.reports.SurveySummary
import com.jamina.ui.components.Dropdown
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionReport(val session: SurveySessionEntity, val floor: FloorEntity, val ssid: String?, val summary: SurveySummary, val coverage: Float?)

data class ReportsUi(
    val loading: Boolean = false,
    val busy: String? = null,
    val sessions: List<SessionReport> = emptyList(),
    val insights: List<Pair<String, Insight>> = emptyList(),
    val compareA: String? = null,
    val compareB: String? = null,
    val comparison: ComparisonResult? = null,
    val compareImages: Pair<Bitmap?, Bitmap?>? = null,
    val compareCoverage: Pair<Float?, Float?>? = null,
)

class ReportsViewModel(private val c: AppContainer, val projectId: String) : ViewModel() {
    val project = c.dao.projectFlow(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val state = MutableStateFlow(ReportsUi())
    val settings = c.settings.settings

    fun refresh() {
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val floors = c.dao.floorsList(projectId)
            val t = settings.value.thresholds
            val reports = mutableListOf<SessionReport>()
            val insights = mutableListOf<Pair<String, Insight>>()
            for (f in floors) {
                val rooms = c.dao.roomsList(f.id)
                val walls = c.dao.wallsList(f.id)
                for (s in c.dao.sessionsList(f.id)) {
                    val pts = c.repo.loadMeasurements(listOf(s.id), f)
                    val ssid = SurveyStats.primarySsid(pts)
                    val filter = SignalFilter(ssid = ssid)
                    val summary = SurveyStats.summarise(pts, filter, t)
                    val cov = withContext(Dispatchers.Default) { measuredGrid(pts, f, walls, filter)?.let { g -> g.stats(t.good.toFloat()).fractionAbove } }
                    reports += SessionReport(s, f, ssid, summary, cov)
                    Insights.forSurvey(pts, rooms, t, ssid).forEach { insights += "${f.name} / ${s.name}" to it }
                }
            }
            c.scanner.state.value.latest?.results?.let { r -> Insights.forScan(r).forEach { insights += "Latest scan" to it } }
            state.update { it.copy(loading = false, sessions = reports, insights = insights) }
        }
    }

    private fun measuredGrid(pts: List<PointMeasurement>, f: FloorEntity, walls: List<com.jamina.core.db.WallEntity>, filter: SignalFilter): com.jamina.heatmap.Grid? {
        val s = settings.value
        val opt = IdwOptions(s.idwPower, s.idwRadiusM, walls = if (s.wallAware) c.repo.wallSegs(walls, f.copy(offsetXm = 0f, offsetYm = 0f)) else emptyList())
        return HeatmapBuilder.build(HeatmapType.SIGNAL, pts, filter, f.widthM, f.heightM, opt, 100).grid
    }

    private suspend fun measuredImage(f: FloorEntity, sessionIds: List<String>, filter: SignalFilter?, title: String): Pair<Bitmap?, Float?> = withContext(Dispatchers.Default) {
        val pts = c.repo.loadMeasurements(sessionIds, f)
        if (pts.isEmpty()) return@withContext null to null
        val fl = filter ?: SignalFilter(ssid = SurveyStats.primarySsid(pts))
        val walls = c.dao.wallsList(f.id); val rooms = c.dao.roomsList(f.id)
        val s = settings.value
        val opt = IdwOptions(s.idwPower, s.idwRadiusM, walls = if (s.wallAware) c.repo.wallSegs(walls, f.copy(offsetXm = 0f, offsetYm = 0f)) else emptyList())
        val res = HeatmapBuilder.build(HeatmapType.SIGNAL, pts, fl, f.widthM, f.heightM, opt, 100)
        val heat = HeatmapRenderer.render(res, s.thresholds)
        val markers = pts.map { p -> MarkerSpec(p.xU, p.yU, HeatColors.signal(Measures.bestRssi(p, fl) ?: -95f, s.thresholds), hollow = p.source != "MANUAL") }
        val bmp = PlanRenderer.composite(
            f, c.repo.loadPlanBitmap(f.imagePath), heat, walls, rooms, markers, title,
            "MEASURED • ${fl.ssid ?: "all networks"} • IDW from ${res.sampleCount} points (faded = lower confidence)",
            PlanRenderer.signalLegend(s.thresholds), maxDim = 1400,
        )
        bmp to res.grid?.stats(s.thresholds.good.toFloat())?.fractionAbove
    }

    fun setCompare(a: String?, b: String?) {
        state.update { it.copy(compareA = a, compareB = b, comparison = null, compareImages = null) }
        if (a == null || b == null || a == b) return
        viewModelScope.launch {
            val sa = c.dao.session(a) ?: return@launch
            val sb = c.dao.session(b) ?: return@launch
            val f = c.dao.floor(sa.floorId) ?: return@launch
            val fb = c.dao.floor(sb.floorId) ?: return@launch
            val pa = c.repo.loadMeasurements(listOf(a), f)
            val pb = c.repo.loadMeasurements(listOf(b), fb)
            val ssid = SurveyStats.primarySsid(pa) ?: SurveyStats.primarySsid(pb)
            val filter = SignalFilter(ssid = ssid)
            val cmp = withContext(Dispatchers.Default) { Comparison.compare(pa, pb, filter, settings.value.thresholds) }
            val warn = if (sa.floorId != sb.floorId) cmp.copy(warnings = listOf("Surveys are on different floors — results are not comparable.") + cmp.warnings) else cmp
            val ia = measuredImage(f, listOf(a), filter, "A: ${sa.name}")
            val ib = measuredImage(fb, listOf(b), filter, "B: ${sb.name}")
            state.update { it.copy(comparison = warn, compareImages = ia.first to ib.first, compareCoverage = ia.second to ib.second) }
        }
    }

    private fun busy(label: String?) = state.update { it.copy(busy = label) }

    fun exportPdf(uri: Uri, ctx: Context, done: (String) -> Unit) {
        busy("Building PDF…")
        viewModelScope.launch {
            try {
                val p = project.value ?: return@launch
                val st = state.value
                val s = settings.value
                val conn = c.connection.readNow()
                val sections = mutableListOf<ReportSection>()
                sections += ReportSection(
                    "Project information",
                    listOf(
                        "Building: ${p.name}",
                        "Report generated: ${Fmt.dateTime(System.currentTimeMillis())}",
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "Network at report time: ${conn.ssid ?: "—"} (${conn.bssid ?: "BSSID unavailable"}), ${conn.rssi ?: "—"} dBm, ${conn.band?.label ?: ""}",
                        "Signal thresholds: excellent ≥${s.thresholds.excellent}, very good ≥${s.thresholds.veryGood}, good ≥${s.thresholds.good}, fair ≥${s.thresholds.fair}, weak ≥${s.thresholds.weak} dBm.",
                    ),
                )
                sections += ReportSection("Survey summary", st.sessions.flatMap { r ->
                    val m = r.summary
                    listOf(
                        "${r.floor.name} — ${r.session.name} (${r.session.mode.lowercase()}, ${Fmt.dateTime(r.session.startedAt)}${if (r.session.isBaseline) ", baseline" else ""}) on ${r.session.deviceModel}",
                        "   ${m.points} points, ${m.distinctBssids} APs detected for ${r.ssid ?: "all networks"}; avg ${m.avgRssi?.fmt(1) ?: "—"} dBm, min ${m.minRssi?.fmt(0) ?: "—"} dBm; ${m.fractionGood?.pct() ?: "—"} of points ≥ ${s.thresholds.good} dBm; ${m.deadZones} weak/dead point(s); estimated area ≥ ${s.thresholds.good} dBm: ${r.coverage?.pct() ?: "—"}" +
                            (m.avgLatency?.let { ", avg latency ${it.fmt(1)} ms" } ?: "") + (m.avgLoss?.let { ", avg loss ${it.fmt(1)}%" } ?: "") + (m.avgThroughput?.let { ", avg throughput ${it.fmt(1)} Mbps" } ?: "") +
                            (if (m.estimatedPositions > 0) "; ${m.estimatedPositions} estimated positions" else "") + (if (m.cachedScans > 0) "; ${m.cachedScans} cached scans" else ""),
                    )
                }.ifEmpty { listOf("No surveys recorded.") })
                // Measured heatmaps, latest session per floor
                for (r in st.sessions.groupBy { it.floor.id }.values.map { it.maxBy { s -> s.session.startedAt } }) {
                    val (img, _) = measuredImage(r.floor, listOf(r.session.id), SignalFilter(ssid = r.ssid), "${r.floor.name} — ${r.session.name}")
                    if (img != null) sections += ReportSection("Measured signal heatmap: ${r.floor.name}", image = img, caption = "MEASURED. Colours between survey points are interpolated (inverse distance weighting); blank areas were not sampled closely enough to estimate.")
                }
                // Predicted coverage (Default scenario)
                val floors = c.dao.floorsList(projectId)
                val aps = c.dao.apsForProjectList(projectId).filter { it.enabled }
                if (aps.isNotEmpty()) {
                    val walls = floors.associate { it.id to c.dao.wallsList(it.id) }
                    val g = c.repo.floorGeoms(floors, walls)
                    val params = PropagationParams(s.pathLossExponent, s.modelOffsetDb, s.receiverHeightM, s.materialTable)
                    for (scenario in aps.map { it.scenario }.distinct()) {
                        val list = aps.filter { it.scenario == scenario }.mapNotNull { a -> floors.firstOrNull { it.id == a.floorId }?.let { c.repo.plannedAp(a, it) } }
                        for (f in floors) {
                            val geom = g[f.level] ?: continue
                            val img = withContext(Dispatchers.Default) {
                                val pg = Propagation.predict(list, geom, g, f.widthM, f.heightM, 100, params)
                                val heat = HeatmapRenderer.renderSignalGrid(pg.best, s.thresholds)
                                val markers = aps.filter { it.scenario == scenario && it.floorId == f.id }.map { MarkerSpec(it.x, it.y, 0xFF6A1B9A.toInt(), "${it.name} ch${it.channel}", square = true) }
                                PlanRenderer.composite(f, c.repo.loadPlanBitmap(f.imagePath), heat, walls[f.id] ?: emptyList(), c.dao.roomsList(f.id), markers, "PREDICTED — ${f.name} — $scenario", "Simulated with log-distance model (n=${s.pathLossExponent.fmt(2)}, offset ${s.modelOffsetDb.fmt(1)} dB). Not measured.", PlanRenderer.signalLegend(s.thresholds), maxDim = 1400)
                            }
                            sections += ReportSection("Predicted coverage: ${f.name} ($scenario)", image = img, caption = "PREDICTED. Estimates depend on wall materials and AP settings entered; verify with a survey.")
                        }
                    }
                }
                sections += ReportSection("Findings & recommendations", st.insights.map { (src, i) -> "[${i.severity.name}] ${i.title} ($src): ${i.observation} Possible explanation: ${i.explanation}" }.ifEmpty { listOf("No findings.") })
                val diags = c.dao.recentDiagnosticsList(25)
                sections += ReportSection("Diagnostics (most recent)", diags.map { d ->
                    "${Fmt.dateTime(d.timestamp)} ${d.kind.lowercase().replace('_', ' ')} ${d.target}: " + listOfNotNull(d.valueMs?.let { "${it.fmt(1)} ms" }, d.jitterMs?.let { "jitter ${it.fmt(1)} ms" }, d.lossPct?.let { "loss ${it.fmt(1)}%" }, d.mbps?.let { "${it.fmt(1)} Mbps" }, d.method.ifBlank { null }).joinToString(", ")
                }.ifEmpty { listOf("No diagnostic tests recorded.") })
                val roams = c.dao.recentRoamingList(20)
                sections += ReportSection("Roaming observations", roams.map { e ->
                    "${Fmt.dateTime(e.timestamp)} ${s.aliasOf(e.fromBssid) ?: e.fromBssid} (${e.rssiBefore} dBm) → ${s.aliasOf(e.toBssid) ?: e.toBssid} (${e.rssiAfter} dBm); observed interruption ${e.interruptionMs ?: "?"} ms. ${e.note}"
                }.ifEmpty { listOf("No roaming events recorded.") })
                sections += ReportSection("Method & limitations", listOf(
                    "Measurements were taken with an ordinary Android phone using public Wi-Fi APIs. RSSI values vary between devices; compare surveys taken with the same phone.",
                    "Android limits apps to 4 Wi-Fi scans per 2 minutes unless throttling is disabled; readings flagged as cached came from Android's most recent scan.",
                    "Channel congestion findings are inferred from beacons in scan results. Airtime utilisation and non-Wi-Fi interference are not measured.",
                    "Heatmaps labelled MEASURED interpolate between real survey points. Maps labelled PREDICTED are simulations and were not observed.",
                ))
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        PdfReport.write(out, "Wi-Fi Survey Report — ${p.name}", "Generated by Jamina on ${Fmt.dateTime(System.currentTimeMillis())}", sections)
                    }
                }
                done("PDF report saved")
            } catch (e: Exception) {
                done("PDF failed: ${e.message}")
            } finally { busy(null) }
        }
    }

    fun exportCsv(uri: Uri, ctx: Context, done: (String) -> Unit) {
        busy("Exporting CSV…")
        viewModelScope.launch {
            try {
                val floors = c.dao.floorsList(projectId)
                val sessions = floors.flatMap { c.dao.sessionsList(it.id) }
                val ids = sessions.map { it.id }
                val pts = if (ids.isEmpty()) emptyList() else c.dao.pointsForSessionsList(ids)
                val obs = c.dao.observationsForSessions(ids).groupBy { it.batchId }
                val mpu = sessions.associate { s -> s.id to (floors.firstOrNull { it.id == s.floorId }?.metersPerUnit ?: 1f) }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { CsvExport.write(it, sessions.associate { s -> s.id to s.name }, pts, obs, mpu) }
                }
                done("CSV exported (${pts.size} points)")
            } catch (e: Exception) { done("CSV failed: ${e.message}") } finally { busy(null) }
        }
    }

    fun exportJson(uri: Uri, ctx: Context, done: (String) -> Unit) {
        busy("Creating backup…")
        viewModelScope.launch {
            try {
                val text = c.repo.exportProject(projectId)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                done("Project backup saved")
            } catch (e: Exception) { done("Backup failed: ${e.message}") } finally { busy(null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(projectId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "reports-$projectId") { ReportsViewModel(it, projectId) }
    val st by vm.state.collectAsState()
    val project by vm.project.collectAsState()
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(projectId) { vm.refresh() }
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let { u -> vm.exportPdf(u, ctx, toast) } }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.exportCsv(u, ctx, toast) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.exportJson(u, ctx, toast) } }
    val name = project?.name ?: "project"

    Scaffold(topBar = {
        TopAppBar(title = { Text("Reports — $name") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionCard("Export") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pdf.launch("$name-wifi-report.pdf") }) { Text("PDF report") }
                    OutlinedButton(onClick = { csv.launch("$name-measurements.csv") }) { Text("CSV data") }
                    OutlinedButton(onClick = { json.launch("$name.jamina.json") }) { Text("Project backup (JSON)") }
                }
                st.busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                Text("Backups include floor plans, drawings, planned APs and all raw measurements; restore with the folder icon on the project list. PNG heatmaps are exported from a floor's Heatmap tab.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Surveys") {
                if (st.sessions.isEmpty()) Text("No surveys yet.")
                st.sessions.forEach { r ->
                    val m = r.summary
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${r.floor.name} — ${r.session.name}${if (r.session.isBaseline) " ★" else ""}", fontWeight = FontWeight.SemiBold)
                        Text("${Fmt.dateTime(r.session.startedAt)} • ${m.points} points • ${r.ssid ?: "all networks"} • ${m.distinctBssids} APs", style = MaterialTheme.typography.bodySmall)
                        Text("Avg ${m.avgRssi?.fmt(1) ?: "—"} dBm • min ${m.minRssi?.fmt(0) ?: "—"} • ${m.fractionGood?.pct() ?: "—"} of points ≥ ${settings.thresholds.good} • area ≥ ${settings.thresholds.good}: ${r.coverage?.pct() ?: "—"} • weak points ${m.deadZones}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SectionCard("Insights") {
                if (st.insights.isEmpty()) Text("No findings yet — complete a survey first.")
                st.insights.forEach { (src, i) ->
                    val col = when (i.severity) { Severity.PROBLEM -> MaterialTheme.colorScheme.error; Severity.ADVISORY -> MaterialTheme.colorScheme.tertiary; Severity.INFO -> MaterialTheme.colorScheme.primary }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(i.title, color = col, fontWeight = FontWeight.SemiBold)
                        Text("Observed: ${i.observation}", style = MaterialTheme.typography.bodySmall)
                        Text("Possible explanation: ${i.explanation}", style = MaterialTheme.typography.bodySmall)
                        Text(src, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            SectionCard("Compare surveys") {
                val sessions = st.sessions.map { it.session }
                Text("Survey the same locations with each router/configuration, then compare. Only points within 1 m of each other are compared.", style = MaterialTheme.typography.bodySmall)
                Dropdown("A (baseline)", sessions, sessions.firstOrNull { it.id == st.compareA }, { it.name }, { vm.setCompare(it.id, st.compareB) })
                Dropdown("B", sessions, sessions.firstOrNull { it.id == st.compareB }, { it.name }, { vm.setCompare(st.compareA, it.id) })
                st.comparison?.let { cmp ->
                    val cov = st.compareCoverage
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Metric", Modifier.weight(1.4f), fontWeight = FontWeight.Bold); Text("A", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("B", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    }
                    listOf(
                        Triple("Matched points", "${cmp.matched.size}", "${cmp.matched.size}"),
                        Triple("Average RSSI", cmp.a.avgRssi?.let { "${it.fmt(1)} dBm" } ?: "—", cmp.b.avgRssi?.let { "${it.fmt(1)} dBm" } ?: "—"),
                        Triple("Points ≥ ${settings.thresholds.good} dBm", cmp.a.fractionGood?.pct() ?: "—", cmp.b.fractionGood?.pct() ?: "—"),
                        Triple("Area ≥ ${settings.thresholds.good} dBm", cov?.first?.pct() ?: "—", cov?.second?.pct() ?: "—"),
                        Triple("Weak/dead points", "${cmp.a.deadZones}", "${cmp.b.deadZones}"),
                        Triple("Avg latency", cmp.a.avgLatency?.let { "${it.fmt(1)} ms" } ?: "—", cmp.b.avgLatency?.let { "${it.fmt(1)} ms" } ?: "—"),
                        Triple("Avg throughput", cmp.a.avgThroughput?.let { "${it.fmt(1)} Mbps" } ?: "—", cmp.b.avgThroughput?.let { "${it.fmt(1)} Mbps" } ?: "—"),
                    ).forEach { (k, a, b) -> Row(Modifier.fillMaxWidth()) { Text(k, Modifier.weight(1.4f)); Text(a, Modifier.weight(1f)); Text(b, Modifier.weight(1f)) } }
                    if (cmp.deltas.isNotEmpty()) {
                        val better = cmp.deltas.count { it >= 3 }; val worse = cmp.deltas.count { it <= -3 }
                        Text("Per-location change (B − A): mean ${cmp.deltas.average().toFloat().fmt(1)} dB; $better location(s) ≥3 dB better, $worse ≥3 dB worse.", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    }
                    cmp.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    st.compareImages?.let { (a, b) ->
                        a?.let { Image(it.asImageBitmap(), "Survey A heatmap", Modifier.fillMaxWidth().padding(top = 6.dp), contentScale = ContentScale.FillWidth) }
                        b?.let { Image(it.asImageBitmap(), "Survey B heatmap", Modifier.fillMaxWidth().padding(top = 6.dp), contentScale = ContentScale.FillWidth) }
                    }
                }
            }
        }
    }
}
