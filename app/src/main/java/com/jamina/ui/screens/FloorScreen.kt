package com.jamina.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jamina.core.db.FloorEntity
import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.SurveyMode
import com.jamina.core.model.WallType
import com.jamina.core.model.fmt
import com.jamina.core.model.pct
import com.jamina.floorplan.Geometry
import com.jamina.heatmap.HeatColors
import com.jamina.heatmap.HeatmapType
import com.jamina.heatmap.PlanRenderer
import com.jamina.ui.components.Dropdown
import com.jamina.ui.components.Fmt
import com.jamina.ui.components.LabeledSwitch
import com.jamina.ui.components.LegendRow
import com.jamina.ui.components.MeasuredTag
import com.jamina.ui.components.NumberField
import com.jamina.ui.components.PlanCanvas
import com.jamina.ui.components.PlanGestureHandler
import com.jamina.ui.components.PlanTransform
import com.jamina.ui.components.WifiPrereqBanner
import com.jamina.ui.components.appViewModel
import com.jamina.ui.components.drawLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloorScreen(floorId: String, initialMode: String?, onBack: () -> Unit) {
    val vm = appViewModel(key = "floor-$floorId") { FloorViewModel(it, floorId) }
    val floor by vm.floor.collectAsState()
    val ctx = LocalContext.current
    var mode by rememberSaveable {
        mutableStateOf(
            when (initialMode) {
                "import", "draw" -> FloorMode.BLUEPRINT
                "heatmap" -> FloorMode.HEATMAP
                else -> FloorMode.SURVEY
            },
        )
    }
    var showProps by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importPlan(uri) }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialMode) {
        if (!launched) {
            launched = true
            when (initialMode) {
                "import" -> picker.launch(arrayOf("image/*", "application/pdf"))
                "draw" -> { vm.setTool(Tool.ROOM); vm.editor.value = vm.editor.value.copy(message = "Drag to draw rooms (or use Wall for single walls). Two fingers zoom and pan. Then switch to Survey.") }
            }
        }
    }

    val f = floor ?: return
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(f.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { showProps = true }) { Icon(Icons.Default.Tune, "Floor properties") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(mode.ordinal) {
                FloorMode.entries.forEach { m -> Tab(mode == m, { mode = m }, text = { Text(m.label) }) }
            }
            when (mode) {
                FloorMode.BLUEPRINT -> BlueprintPanel(vm, f) { picker.launch(arrayOf("image/*", "application/pdf")) }
                FloorMode.SURVEY -> SurveyPanel(vm, f) { mode = FloorMode.HEATMAP }
                FloorMode.HEATMAP -> HeatmapPanel(vm, f)
            }
        }
    }
    if (showProps) FloorPropsDialog(f, onDismiss = { showProps = false }) { nf -> vm.updateFloor { nf }; showProps = false; Toast.makeText(ctx, "Floor updated", Toast.LENGTH_SHORT).show() }
}

// ============================================================================ Blueprint

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BlueprintPanel(vm: FloorViewModel, f: FloorEntity, onImport: () -> Unit) {
    val e by vm.editor.collectAsState()
    val walls by vm.walls.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val plan by vm.planImage.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    val handler = remember(vm) {
        object : PlanGestureHandler {
            override fun onTap(p: Offset) = vm.onEditorTap(p)
            override fun onDragStart(p: Offset) = vm.onEditorDragStart(p)
            override fun onDrag(p: Offset) = vm.onEditorDrag(p)
            override fun onDragEnd() = vm.onEditorDragEnd()
        }
    }
    PlanCanvas(
        f, plan, Modifier.weight(1f).fillMaxWidth(), walls = walls, rooms = rooms, selectedId = e.selectedId, handler = handler,
    ) { t ->
        val a = e.draftA; val b = e.draftB
        if (a != null && b != null) {
            val sa = t.toScreen(a); val sb = t.toScreen(b)
            when (e.tool) {
                Tool.ROOM, Tool.STAIRS -> drawRect(primary, Offset(minOf(sa.x, sb.x), minOf(sa.y, sb.y)), Size(kotlin.math.abs(sb.x - sa.x), kotlin.math.abs(sb.y - sa.y)), style = Stroke(4f))
                else -> drawLine(primary, sa, sb, 8f)
            }
            val dx = kotlin.math.abs(b.x - a.x) * f.metersPerUnit; val dy = kotlin.math.abs(b.y - a.y) * f.metersPerUnit
            val txt = if (e.tool == Tool.ROOM || e.tool == Tool.STAIRS) "${dx.fmt(2)} × ${dy.fmt(2)} m" else "${((b - a).getDistance() * f.metersPerUnit).fmt(2)} m"
            drawLabel(txt, Offset(sb.x + 16f, sb.y - 16f), primary, 36f, true)
        }
        val ca = e.calA; val cb = e.calB
        if (ca != null) {
            val sa = t.toScreen(ca)
            drawCircle(Color(0xFFE53935), 12f, sa)
            if (cb != null) { val sb = t.toScreen(cb); drawLine(Color(0xFFE53935), sa, sb, 5f); drawCircle(Color(0xFFE53935), 12f, sb) }
        }
        vm.selectedWall()?.let { w ->
            drawCircle(primary, 14f, t.toScreen(w.x1, w.y1)); drawCircle(primary, 14f, t.toScreen(w.x2, w.y2))
            val mid = t.toScreen((w.x1 + w.x2) / 2, (w.y1 + w.y2) / 2)
            drawLabel("${(Geometry.length(w.x1, w.y1, w.x2, w.y2) * f.metersPerUnit).fmt(2)} m", Offset(mid.x + 12f, mid.y - 12f), primary, 32f, true)
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool.entries.forEach { t -> FilterChip(e.tool == t, { vm.setTool(t) }, { Text(t.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Dropdown("Material", Material.wallMaterials, e.material, { it.label }, vm::setMaterial)
                Spacer(Modifier.width(6.dp))
                Text("Snap"); Checkbox(e.snap, vm::setSnap)
                IconButton(onClick = vm::undo, enabled = e.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                OutlinedButton(onClick = onImport) { Text(if (f.imagePath == null) "Import plan" else "Replace plan") }
            }
            e.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Plan ${f.widthM.fmt(1)} × ${f.heightM.fmt(1)} m • ${if (f.calibrated) "calibrated" else "scale not calibrated"} • ${walls.size} walls, ${rooms.size} rooms",
                style = MaterialTheme.typography.bodySmall,
            )
            when (e.tool) {
                Tool.CALIBRATE -> Text("Tap (or drag between) the two ends of a known length, then enter the real distance.", style = MaterialTheme.typography.bodySmall)
                Tool.WALL -> Text("Drag to draw a wall. Snaps to 25 cm grid, wall ends and right angles.", style = MaterialTheme.typography.bodySmall)
                Tool.ROOM -> Text("Drag a rectangle to create a room with four walls; tap a room to rename it.", style = MaterialTheme.typography.bodySmall)
                Tool.DOOR, Tool.WINDOW -> Text("Drag along an existing wall to place a ${e.tool.label.lowercase()}.", style = MaterialTheme.typography.bodySmall)
                Tool.SELECT -> Text("Tap a wall or room to edit it; drag a selected wall's ends to reshape.", style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
            val w = vm.selectedWall()
            val r = vm.selectedRoom()
            if (w != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WallType.entries.forEach { wt -> FilterChip(w.type == wt.name, { vm.changeWall(w.copy(type = wt.name, material = when (wt) { WallType.DOOR -> Material.WOOD_DOOR.name; WallType.WINDOW -> Material.GLASS.name; else -> w.material })) }, { Text(wt.label) }) }
                }
                Text("Material: ${Material.of(w.material).label}", style = MaterialTheme.typography.bodySmall)
                var len by remember(w.id, w.x2, w.y2) { mutableStateOf((Geometry.length(w.x1, w.y1, w.x2, w.y2) * f.metersPerUnit).fmt(2)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField("Length (m)", len, { len = it }, Modifier.width(140.dp))
                    TextButton(onClick = { len.toFloatOrNull()?.let { vm.setWallLength(w, it) } }) { Text("Set") }
                    Checkbox(w.exterior, { vm.changeWall(w.copy(exterior = it)) }); Text("Exterior")
                    IconButton(onClick = vm::deleteSelected) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
            if (r != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                var name by remember(r.id) { mutableStateOf(r.name) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.changeRoom(r.copy(name = name)) }) { Text("Save") }
                    IconButton(onClick = vm::deleteSelected) { Icon(Icons.Default.Delete, "Delete") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.noApZone, { vm.changeRoom(r.copy(noApZone = it)) }); Text("No AP here")
                    Checkbox(r.priority, { vm.changeRoom(r.copy(priority = it)) }); Text("Needs strong coverage")
                }
                Text("${(kotlin.math.abs(r.w) * f.metersPerUnit).fmt(2)} × ${(kotlin.math.abs(r.h) * f.metersPerUnit).fmt(2)} m", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (e.askCalibration) {
        var meters by remember { mutableStateOf("") }
        val a = e.calA; val b = e.calB
        AlertDialog(
            onDismissRequest = vm::cancelCalibration,
            title = { Text("Calibrate scale") },
            text = {
                Column {
                    Text("Currently ${if (a != null && b != null) ((b - a).getDistance() * f.metersPerUnit).fmt(2) else "?"} m. Enter the real distance between the two points:")
                    NumberField("Metres", meters, { meters = it })
                }
            },
            confirmButton = { TextButton(onClick = { meters.toFloatOrNull()?.let(vm::applyCalibration) }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = vm::cancelCalibration) { Text("Cancel") } },
        )
    }
    e.renameRoomId?.let { id ->
        val room = rooms.firstOrNull { it.id == id }
        if (room != null) {
            var name by remember(id) { mutableStateOf(room.name) }
            AlertDialog(
                onDismissRequest = vm::dismissRename,
                title = { Text("Room name") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.renameRoom(id, name) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = vm::dismissRename) { Text("Skip") } },
            )
        }
    }
}

// ============================================================================ Survey

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SurveyPanel(vm: FloorViewModel, f: FloorEntity, onShowHeatmap: () -> Unit) {
    val st by vm.survey.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val walls by vm.walls.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val plan by vm.planImage.collectAsState()
    val points by vm.activePoints.collectAsState()
    val scan by vm.scanState.collectAsState()
    var newSession by remember { mutableStateOf(sessions.isEmpty()) }
    val active = sessions.firstOrNull { it.id == st.activeSessionId }
    val mode = active?.let { SurveyMode.valueOf(it.mode) }
    val handler = remember(vm) { object : PlanGestureHandler { override fun onTap(p: Offset) = vm.onSurveyTap(p) } }
    val primary = MaterialTheme.colorScheme.primary

    PlanCanvas(f, plan, Modifier.weight(1f).fillMaxWidth(), walls = walls, rooms = rooms, handler = handler, showGrid = false) { t ->
        // route
        if (st.route.isNotEmpty()) {
            val path = Path()
            st.route.forEachIndexed { i, p -> val s = t.toScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
            drawPath(path, primary, style = Stroke(5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f))))
            st.route.forEachIndexed { i, p ->
                val s = t.toScreen(p)
                val reached = i < st.checkpointTimes.size && st.running || (i < st.checkpointTimes.size)
                drawCircle(if (reached) primary else Color.White, 18f, s)
                drawCircle(primary, 18f, s, style = Stroke(4f))
                drawLabel("${i + 1}", Offset(s.x - 8f, s.y + 9f), if (reached) Color.White else primary, 26f, true)
            }
        }
        points.forEach { p ->
            val s = t.toScreen(p.xU, p.yU)
            val col = Color(vm.pointColor(p))
            drawCircle(Color.White, 15f, s)
            if (p.source == "MANUAL") drawCircle(col, 11f, s) else drawCircle(col, 10f, s, style = Stroke(5f))
            if (!p.fresh) drawCircle(Color.Black, 3f, s)
        }
        st.pendingPoint?.let { drawCircle(primary, 22f, t.toScreen(it), style = Stroke(6f)) }
        st.pin?.let { val s = t.toScreen(it); drawCircle(Color(0xFFE53935), 16f, s); drawCircle(Color.White, 6f, s) }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
            WifiPrereqBanner()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Dropdown("Survey", sessions, active, { "${it.name} (${SurveyMode.valueOf(it.mode).label})" }, { vm.selectSession(it.id) })
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = { newSession = true }) { Text("New survey") }
                active?.let { a -> IconButton(onClick = { vm.deleteSession(a.id) }) { Icon(Icons.Default.Delete, "Delete survey") } }
            }
            if (active == null) {
                Text("Create a survey to start collecting measurements.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${points.size} points • ${scan.latest?.let { "last scan ${Fmt.age(System.currentTimeMillis() - it.timestamp)}" } ?: "no scan yet"}", style = MaterialTheme.typography.bodySmall)
                when (mode) {
                    SurveyMode.POINT -> Text("Stand still, then tap your position on the plan. All visible APs are recorded at that spot. Tap an existing dot to inspect or re-measure it.", style = MaterialTheme.typography.bodyMedium)
                    SurveyMode.WALK -> {
                        if (!st.running) {
                            Text("Tap the plan to lay out checkpoints along your walking route, then press Start at checkpoint 1 and walk at a steady pace, tapping 'Reached' at each one.", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                Button(onClick = vm::startRun, enabled = st.route.size >= 2) { Text("Start walk") }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(onClick = vm::undoRoutePoint, enabled = st.route.isNotEmpty()) { Text("Undo checkpoint") }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(onClick = vm::clearRoute) { Text("Clear") }
                            }
                        } else {
                            val next = st.checkpointTimes.size + 1
                            Text("Walking to checkpoint $next of ${st.route.size} • ${st.runBatches} scans captured", fontWeight = FontWeight.SemiBold)
                            Row {
                                Button(onClick = vm::reachedCheckpoint) { Text("Reached checkpoint $next") }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(onClick = { vm.stopRun(true) }) { Text("Finish here") }
                            }
                            Text("Positions between checkpoints are estimated from timing and shown as hollow dots.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SurveyMode.CONTINUOUS -> {
                        Text("Scans are recorded continuously. Tap the plan whenever you stop to set your position; scans are stored at the pinned position.", style = MaterialTheme.typography.bodyMedium)
                        Row { Button(onClick = { if (st.running) vm.stopRun(true) else vm.startRun() }) { Text(if (st.running) "Stop recording" else "Start recording") } }
                        if (st.running) Text("${st.runBatches} scans recorded", style = MaterialTheme.typography.bodySmall)
                    }
                    null -> {}
                }
                if (scan.throttleEnabled != false && (mode == SurveyMode.WALK || mode == SurveyMode.CONTINUOUS)) {
                    Text("Android throttles scans to 4 per 2 min. Turn off 'Wi-Fi scan throttling' in Developer options for denser walking surveys.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (st.collecting || st.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                st.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                st.lastResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.padding(top = 6.dp)) {
                    Button(onClick = { vm.finishSession(); onShowHeatmap() }, enabled = points.isNotEmpty() && !st.running) { Text("Finish & view heatmap") }
                }
            }
        }
    }

    if (newSession) {
        var name by remember { mutableStateOf("Survey ${sessions.size + 1}") }
        var m by remember { mutableStateOf(SurveyMode.POINT) }
        var baseline by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newSession = false },
            title = { Text("New survey") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Router A)") }, singleLine = true)
                    SurveyMode.entries.forEach { sm ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(m == sm, { m = sm }); Text(sm.label + when (sm) { SurveyMode.POINT -> " — most reliable"; SurveyMode.WALK -> " — route + checkpoints"; SurveyMode.CONTINUOUS -> " — pin as you go" }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(baseline, { baseline = it }); Text("Mark as baseline for comparisons") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.createSession(name, m, baseline); newSession = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newSession = false }) { Text("Cancel") } },
        )
    }

    st.selectedPointId?.let { id ->
        val p = points.firstOrNull { it.id == id }
        if (p != null) {
            AlertDialog(
                onDismissRequest = vm::dismissPoint,
                title = { Text("Survey point ${Fmt.time(p.timestamp)}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Position: ${p.xM.fmt(1)}, ${p.yM.fmt(1)} m (${p.source.lowercase()}, ±${p.accuracyM.fmt(1)} m)")
                        Text("Scan: ${if (p.fresh) "fresh" else "cached by Android"}")
                        Text("Connected: ${p.connectedSsid ?: "—"} ${p.connectedBssid ?: ""} ${p.connectedRssi?.let { "$it dBm" } ?: ""}")
                        p.latencyMs?.let { Text("Latency ${it.fmt(1)} ms, loss ${p.lossPct?.fmt(0)}%") }
                        p.throughputMbps?.let { Text("Throughput ${it.fmt(1)} Mbps") }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        p.observations.sortedByDescending { it.rssi }.take(25).forEach { o ->
                            Text("${o.rssi} dBm  ${o.ssid.ifBlank { "(hidden)" }}  ch ${o.channel}  ${o.bssid}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.remeasure(id) }) { Text("Measure again here") } },
                dismissButton = { TextButton(onClick = { vm.deletePoint(id) }) { Text("Delete point") } },
            )
        }
    }
}

// ============================================================================ Heatmap

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HeatmapPanel(vm: FloorViewModel, f: FloorEntity) {
    val h by vm.heat.collectAsState()
    val out by vm.heatOut.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val walls by vm.walls.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val plan by vm.planImage.collectAsState()
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportPng(uri, ctx) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
    PlanCanvas(f, plan, Modifier.weight(1f).fillMaxWidth(), heat = out.image, walls = walls, rooms = rooms, showGrid = false) { t ->
        if (h.showPoints) out.points.forEach { p ->
            val s = t.toScreen(p.xU, p.yU)
            drawCircle(Color.White, 9f, s)
            drawCircle(Color.Black, 6f, s, style = if (p.source == "MANUAL") androidx.compose.ui.graphics.drawscope.Fill else Stroke(3f))
        }
        out.result?.roamMarkers?.forEach { (x, y) ->
            val s = t.toScreen(x, y)
            drawCircle(Color(0xFFE53935), 16f, s, style = Stroke(5f))
            drawLabel("roam", Offset(s.x + 18f, s.y), Color(0xFFE53935), 26f, true)
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeasuredTag()
                Spacer(Modifier.width(8.dp))
                Text(
                    out.result?.let { r -> r.message ?: "${r.type.label} interpolated from ${r.sampleCount} measured points" } ?: if (out.computing) "Computing…" else "No data",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
            }
            if (out.computing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Map", HeatmapType.entries, h.type, { it.label }, { t -> vm.setHeat { it.copy(type = t) } })
                Spacer(Modifier.width(6.dp))
                Dropdown("SSID", listOf<String?>(null) + out.ssids, h.ssid, { it ?: "All networks" }, { s -> vm.setHeat { it.copy(ssid = s, bssid = null) } })
                Spacer(Modifier.width(6.dp))
                Dropdown("AP", listOf<Pair<String, String>?>(null) + out.bssids, out.bssids.firstOrNull { it.first == h.bssid }, { it?.second ?: "Strongest AP" }, { b -> vm.setHeat { it.copy(bssid = b?.first) } })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(h.band == null, { vm.setHeat { it.copy(band = null) } }, { Text("All bands") })
                Band.entries.forEach { b -> FilterChip(h.band == b, { vm.setHeat { it.copy(band = b) } }, { Text(b.label) }) }
            }
            Text("Surveys included:", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.forEach { s ->
                    FilterChip(s.id in h.sessionIds, {
                        vm.setHeat { it.copy(sessionIds = if (s.id in it.sessionIds) it.sessionIds - s.id else it.sessionIds + s.id) }
                    }, { Text(s.name + if (s.isBaseline) " ★" else "") })
                }
            }
            LabeledSwitch("Show survey points", h.showPoints, { v -> vm.setHeat { it.copy(showPoints = v) } })
            LabeledSwitch("Extend estimate beyond ${settings.idwRadiusM.fmt(0)} m of measurements (lower confidence)", h.extrapolate, { v -> vm.setHeat { it.copy(extrapolate = v) } })
            out.stats?.let { s ->
                Text(
                    "Area ≥ ${settings.thresholds.good} dBm: ${s.fractionAbove.pct()} of estimated area • min ${s.min.fmt(0)} • mean ${s.mean.fmt(0)} dBm",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val res = out.result
            when (res?.type) {
                HeatmapType.SIGNAL -> LegendRow(PlanRenderer.signalLegend(settings.thresholds).map { Color(it.first) to it.second })
                HeatmapType.BEST_AP, HeatmapType.ROAMING -> LegendRow(res.categoryLabels.mapIndexed { i, b -> Color(HeatColors.category(i)) to (settings.aliasOf(b) ?: b) })
                HeatmapType.CONFIDENCE -> LegendRow(listOf(Color(HeatColors.ramp(0f)) to "Well sampled", Color(HeatColors.ramp(1f)) to "Sparse"))
                null -> {}
                else -> LegendRow(listOf(Color(HeatColors.ramp(0f)) to "Better (${if (res.higherIsBetter) res.maxValue.fmt(0) else res.minValue.fmt(0)} ${res.type.unit})", Color(HeatColors.ramp(1f)) to "Worse (${if (res.higherIsBetter) res.minValue.fmt(0) else res.maxValue.fmt(0)} ${res.type.unit})"))
            }
            Text("Faded colour = lower confidence. Blank areas are too far from any measurement to estimate. Hollow dots are estimated positions.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { exporter.launch("${f.name}-${h.type.name.lowercase()}-heatmap.png") }, enabled = out.result?.message == null && out.result != null) { Text("Export PNG") }
            }
        }
    }
}

@Composable
private fun FloorPropsDialog(f: FloorEntity, onDismiss: () -> Unit, onSave: (FloorEntity) -> Unit) {
    var name by remember { mutableStateOf(f.name) }
    var level by remember { mutableStateOf(f.level.toString()) }
    var ceil by remember { mutableStateOf(f.ceilingHeightM.toString()) }
    var slab by remember { mutableStateOf(Material.of(f.floorMaterial)) }
    var w by remember { mutableStateOf(f.widthM.fmt(1)) }
    var hgt by remember { mutableStateOf(f.heightM.fmt(1)) }
    var ox by remember { mutableStateOf(f.offsetXm.toString()) }
    var oy by remember { mutableStateOf(f.offsetYm.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Floor properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                NumberField("Level (0 = ground, 1 = first…)", level, { level = it })
                NumberField("Ceiling height (m)", ceil, { ceil = it })
                Dropdown("Floor/ceiling below", Material.floorMaterials, slab, { it.label }, { slab = it })
                if (f.imagePath == null) {
                    NumberField("Drawing width (m)", w, { w = it })
                    NumberField("Drawing height (m)", hgt, { hgt = it })
                }
                Text("Vertical alignment with other floors (m):", style = MaterialTheme.typography.labelMedium)
                Row {
                    NumberField("Offset X", ox, { ox = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    NumberField("Offset Y", oy, { oy = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var nf = f.copy(
                    name = name.ifBlank { f.name }, level = level.toIntOrNull() ?: f.level,
                    ceilingHeightM = ceil.toFloatOrNull()?.coerceIn(1.5f, 10f) ?: f.ceilingHeightM, floorMaterial = slab.name,
                    offsetXm = ox.toFloatOrNull() ?: f.offsetXm, offsetYm = oy.toFloatOrNull() ?: f.offsetYm,
                )
                if (f.imagePath == null) {
                    val wm = w.toFloatOrNull(); val hm = hgt.toFloatOrNull()
                    if (wm != null && wm > 1f) nf = nf.copy(widthUnits = wm / f.metersPerUnit)
                    if (hm != null && hm > 1f) nf = nf.copy(heightUnits = hm / f.metersPerUnit)
                }
                onSave(nf)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun PlanTransform.debug() = "scale=$scale"
