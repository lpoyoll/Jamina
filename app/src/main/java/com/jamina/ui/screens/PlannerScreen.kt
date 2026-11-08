package com.jamina.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jamina.core.db.VirtualApEntity
import com.jamina.core.model.Band
import com.jamina.core.model.fmt
import com.jamina.core.model.pct
import com.jamina.heatmap.HeatColors
import com.jamina.heatmap.PlanRenderer
import com.jamina.planner.ApCatalog
import com.jamina.planner.OptimiseRequest
import com.jamina.ui.components.Dropdown
import com.jamina.ui.components.EmptyState
import com.jamina.ui.components.LabeledSwitch
import com.jamina.ui.components.LegendRow
import com.jamina.ui.components.NumberField
import com.jamina.ui.components.PlanCanvas
import com.jamina.ui.components.PlanGestureHandler
import com.jamina.ui.components.PredictedTag
import com.jamina.ui.components.appViewModel
import com.jamina.ui.components.drawLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(projectId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "planner-$projectId") { PlannerViewModel(it, projectId) }
    val ui by vm.ui.collectAsState()
    val out by vm.out.collectAsState()
    val floors by vm.floors.collectAsState()
    val floor by vm.floor.collectAsState()
    val walls by vm.allWalls.collectAsState()
    val rooms by vm.allRooms.collectAsState()
    val aps by vm.aps.collectAsState()
    val plan by vm.planImage.collectAsState()
    val scenarios by vm.scenarios.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val optimising by vm.optimising.collectAsState()
    val opt by vm.optResult.collectAsState()
    val cal by vm.calResult.collectAsState()
    var showOptimise by remember { mutableStateOf(false) }
    var showCalibrate by remember { mutableStateOf(false) }
    var showScenario by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val handler = remember(vm) {
        object : PlanGestureHandler {
            override fun onTap(p: Offset) = vm.onTap(p)
            override fun onDragStart(p: Offset) = vm.onDragStart(p)
            override fun onDrag(p: Offset) = vm.onDrag(p)
            override fun onDragEnd() = vm.onDragEnd()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Planner") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        val f = floor
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (f == null) { EmptyState("Add a floor (draw a blueprint or import a plan) first."); return@Column }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                floors.forEach { fl -> FilterChip(fl.id == f.id, { vm.selectFloor(fl.id) }, { Text(fl.name) }); Spacer(Modifier.width(4.dp)) }
            }
            val floorWalls = walls.filter { it.floorId == f.id }
            val floorRooms = rooms.filter { it.floorId == f.id }
            val scenarioAps = aps.filter { it.scenario == ui.scenario }
            PlanCanvas(f, plan, Modifier.weight(1f).fillMaxWidth(), heat = out.image, walls = floorWalls, rooms = floorRooms, showGrid = false, handler = handler) { t ->
                scenarioAps.forEach { a ->
                    val af = floors.firstOrNull { it.id == a.floorId } ?: return@forEach
                    val same = af.id == f.id
                    val ux = if (same) a.x else ((a.x * af.metersPerUnit + af.offsetXm) - f.offsetXm) / f.metersPerUnit
                    val uy = if (same) a.y else ((a.y * af.metersPerUnit + af.offsetYm) - f.offsetYm) / f.metersPerUnit
                    val s = t.toScreen(ux, uy)
                    val idx = scenarioAps.filter { it.enabled }.indexOf(a)
                    val col = if (ui.view == PredictView.BEST_AP && idx >= 0) Color(HeatColors.category(idx)) else Color(0xFF6A1B9A)
                    if (same) {
                        if (a.id == ui.selectedApId) drawCircle(primary, 30f, s, style = Stroke(6f))
                        drawCircle(Color.White, 22f, s)
                        drawCircle(if (a.enabled) col else Color.Gray, 18f, s)
                        drawLabel("${a.name} • ch ${a.channel}", Offset(s.x + 26f, s.y - 10f), Color(0xFF311B92), 28f, true)
                    } else {
                        drawCircle(col.copy(alpha = 0.4f), 14f, s, style = Stroke(4f))
                        drawLabel("${a.name} (${af.name})", Offset(s.x + 18f, s.y - 6f), Color(0x99311B92), 24f)
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PredictedTag()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            out.coverage?.let { "${it.pct()} of ${f.name} predicted ≥ ${settings.thresholds.good} dBm on ${ui.band.label}" } ?: "Tap 'Add AP' then tap the plan to place a virtual access point.",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                    }
                    if (out.computing || optimising) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ui.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(ui.addMode, { vm.setAddMode(!ui.addMode) }, { Text(if (ui.addMode) "Tap plan to place…" else "Add AP") })
                        FilledTonalButton(onClick = { showOptimise = true }) { Text("Optimise coverage") }
                        OutlinedButton(onClick = { showCalibrate = true }) { Text("Calibrate") }
                        OutlinedButton(onClick = { showModel = true }) { Text("Model") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Dropdown("Scenario", scenarios, ui.scenario, { it }, vm::setScenario)
                        TextButton(onClick = { showScenario = true }) { Text("New") }
                        if (ui.scenario != "Default") IconButton(onClick = vm::deleteScenario) { Icon(Icons.Default.Delete, "Delete scenario") }
                        Dropdown("View", PredictView.entries, ui.view, { it.label }, vm::setView)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Band.entries.forEach { b -> FilterChip(ui.band == b, { vm.setBand(b) }, { Text(b.label) }) }
                    }
                    when (ui.view) {
                        PredictView.SIGNAL -> LegendRow(PlanRenderer.signalLegend(settings.thresholds).map { Color(it.first) to it.second })
                        PredictView.BEST_AP -> LegendRow(out.apLabels.mapIndexed { i, n -> Color(HeatColors.category(i)) to n })
                        PredictView.CO_CHANNEL -> LegendRow(listOf(Color(HeatColors.ramp(0f)) to "0 other APs", Color(HeatColors.ramp(0.5f)) to "2", Color(HeatColors.ramp(1f)) to "4+ overlapping"))
                    }
                    if (out.scenarioCoverage.size > 1) {
                        Text("Scenario comparison (all floors, ≥ ${settings.thresholds.good} dBm):", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        out.scenarioCoverage.forEach { (s, cv) -> Text("• $s: ${cv.pct()}", fontWeight = if (s == ui.scenario) FontWeight.Bold else FontWeight.Normal) }
                    }
                    Text(
                        "Predictions use a log-distance model (n = ${settings.pathLossExponent.fmt(2)}, offset ${settings.modelOffsetDb.fmt(1)} dB) with wall and floor losses. They are estimates, not measurements — calibrate against a survey for better accuracy.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    aps.firstOrNull { it.id == ui.selectedApId }?.let { a ->
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        ApEditor(a, vm::updateAp) { vm.deleteAp(a.id) }
                    }
                }
            }
        }
    }

    if (showScenario) {
        var name by remember { mutableStateOf("") }
        var copy by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showScenario = false },
            title = { Text("New scenario") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Router B)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copy, { copy = it }); Text("Copy APs from \"${ui.scenario}\"") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.newScenario(name, copy); showScenario = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showScenario = false }) { Text("Cancel") } },
        )
    }

    if (showOptimise) {
        var count by remember { mutableStateOf("2") }
        var minRssi by remember { mutableStateOf(settings.thresholds.good.toString()) }
        var tx by remember { mutableStateOf("20") }
        var replace by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showOptimise = false },
            title = { Text("Optimise coverage") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NumberField("Number of APs", count, { count = it })
                    NumberField("Minimum RSSI (dBm)", minRssi, { minRssi = it })
                    NumberField("AP transmit power (dBm)", tx, { tx = it })
                    Text("Band: ${ui.band.label} (change with the band chips)", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }); Text("Ignore existing APs in this scenario") }
                    Text("Rooms marked 'No AP here' are avoided; rooms marked 'Needs strong coverage' are weighted double. Searches every floor.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptimise = false
                    vm.optimise(
                        OptimiseRequest(
                            apCount = count.toIntOrNull()?.coerceIn(1, 8) ?: 2, minRssi = minRssi.toFloatOrNull() ?: -67f, band = ui.band,
                            txPowerDbm = tx.toFloatOrNull() ?: 20f, widthMhz = if (ui.band == Band.B24) 20 else 80,
                        ),
                        replace,
                    )
                }) { Text("Search") }
            },
            dismissButton = { TextButton(onClick = { showOptimise = false }) { Text("Cancel") } },
        )
    }

    opt?.let { (req, r) ->
        AlertDialog(
            onDismissRequest = vm::dismissOptimisation,
            title = { Text("Suggested layout") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    r.suggestions.forEachIndexed { i, s -> Text("AP ${i + 1}: ${vm.locationName(s.level, s.xM, s.yM)} — channel ${s.channel}") }
                    Spacer(Modifier.width(4.dp))
                    Text("Predicted area ≥ ${req.minRssi.toInt()} dBm: before ${r.coverageBefore.pct()} → after ${r.coverageAfter.pct()}", fontWeight = FontWeight.SemiBold)
                    Text("Evaluated ${r.evaluatedCandidates} candidate positions. These are PREDICTED results; verify with a survey after installing.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyOptimisation(true) }) { Text("Apply as new scenario") } },
            dismissButton = { TextButton(onClick = vm::dismissOptimisation) { Text("Discard") } },
        )
    }

    if (showCalibrate) {
        var sessionId by remember { mutableStateOf(sessions.firstOrNull()?.id) }
        var ssid by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCalibrate = false },
            title = { Text("Calibrate model") },
            text = {
                Column {
                    Text("Compares predicted RSSI from this scenario's ${ui.band.label} APs with a measured survey, then fits the path-loss exponent and offset. Place the virtual APs where the real ones are first.", style = MaterialTheme.typography.bodySmall)
                    Dropdown("Survey", sessions, sessions.firstOrNull { it.id == sessionId }, { it.name }, { sessionId = it.id })
                    OutlinedTextField(ssid, { ssid = it }, label = { Text("Network name (SSID) to match") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { sessionId?.let { vm.calibrate(it, ssid.ifBlank { null }) }; showCalibrate = false }) { Text("Fit") } },
            dismissButton = { TextButton(onClick = { showCalibrate = false }) { Text("Cancel") } },
        )
    }

    cal?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissCalibration,
            title = { Text("Calibration result") },
            text = {
                Column {
                    Text("${r.samples} measured points compared.")
                    Text("Current model: RMS error ${r.rmseBefore.fmt(1)} dB (mean bias ${r.meanErrorBefore.fmt(1)} dB)")
                    Text("Fitted: exponent ${r.exponent.fmt(2)}, offset ${r.offsetDb.fmt(1)} dB → RMS error ${r.rmseAfter.fmt(1)} dB", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = { TextButton(onClick = vm::applyCalibration) { Text("Apply") } },
            dismissButton = { TextButton(onClick = vm::dismissCalibration) { Text("Keep current") } },
        )
    }

    if (showModel) {
        var exp by remember { mutableStateOf(settings.pathLossExponent.toString()) }
        var off by remember { mutableStateOf(settings.modelOffsetDb.toString()) }
        var rx by remember { mutableStateOf(settings.receiverHeightM.toString()) }
        AlertDialog(
            onDismissRequest = { showModel = false },
            title = { Text("Propagation model") },
            text = {
                Column {
                    NumberField("Path-loss exponent (2 = free space)", exp, { exp = it })
                    NumberField("Offset (dB)", off, { off = it })
                    NumberField("Receiver height (m)", rx, { rx = it })
                    Text("Wall losses per material and band can be edited in Settings → Advanced mode.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.resetModel(); showModel = false }) { Text("Reset to defaults") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setModel(exp.toFloatOrNull()?.coerceIn(1.5f, 5f), off.toFloatOrNull(), rx.toFloatOrNull()); showModel = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showModel = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApEditor(a: VirtualApEntity, onChange: (VirtualApEntity) -> Unit, onDelete: () -> Unit) {
    var name by remember(a.id) { mutableStateOf(a.name) }
    var ch by remember(a.id, a.channel) { mutableStateOf(a.channel.toString()) }
    var tx by remember(a.id, a.txPowerDbm) { mutableStateOf(a.txPowerDbm.toString()) }
    var gain by remember(a.id, a.antennaGainDbi) { mutableStateOf(a.antennaGainDbi.toString()) }
    var h by remember(a.id) { mutableStateOf(a.mountHeightM.toString()) }
    var az by remember(a.id) { mutableStateOf(a.azimuthDeg.toString()) }
    var bw by remember(a.id) { mutableStateOf(a.beamwidthDeg.toString()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("AP name") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete AP") }
        }
        Dropdown("Model", ApCatalog.profiles, ApCatalog.find(a.model), { it.label }, { p ->
            val band = Band.of(a.band) ?: Band.B5
            onChange(
                a.copy(
                    vendor = p.vendor, model = p.model, standard = p.standard,
                    txPowerDbm = when (band) { Band.B24 -> p.tx24; Band.B5 -> p.tx5; Band.B6 -> p.tx6 ?: p.tx5 },
                    antennaGainDbi = when (band) { Band.B24 -> p.gain24; Band.B5 -> p.gain5; Band.B6 -> p.gain6 ?: p.gain5 },
                ),
            )
        })
        Text("${a.vendor} • ${a.standard}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Band.entries.forEach { b -> FilterChip(a.band == b.name, { onChange(a.copy(band = b.name, channel = com.jamina.planner.Optimizer.channelPlan(b, 0), widthMhz = if (b == Band.B24) 20 else a.widthMhz)) }, { Text(b.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("Channel", ch, { ch = it }, Modifier.weight(1f))
            NumberField("Tx dBm", tx, { tx = it }, Modifier.weight(1f))
            NumberField("Gain dBi", gain, { gain = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Height m", h, { h = it }, Modifier.weight(1f))
            Dropdown("Width", listOf(20, 40, 80, 160, 320), a.widthMhz, { "$it MHz" }, { onChange(a.copy(widthMhz = it)) })
        }
        LabeledSwitch("Enabled", a.enabled, { onChange(a.copy(enabled = it)) })
        LabeledSwitch("Directional antenna", a.directional, { onChange(a.copy(directional = it)) })
        if (a.directional) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("Azimuth °", az, { az = it }, Modifier.weight(1f))
            NumberField("Beamwidth °", bw, { bw = it }, Modifier.weight(1f))
        }
        FilledTonalButton(onClick = {
            onChange(
                a.copy(
                    name = name.ifBlank { a.name }, channel = ch.toIntOrNull() ?: a.channel, txPowerDbm = tx.toFloatOrNull() ?: a.txPowerDbm,
                    antennaGainDbi = gain.toFloatOrNull() ?: a.antennaGainDbi, mountHeightM = h.toFloatOrNull() ?: a.mountHeightM,
                    azimuthDeg = az.toFloatOrNull() ?: a.azimuthDeg, beamwidthDeg = bw.toFloatOrNull() ?: a.beamwidthDeg,
                ),
            )
        }) { Text("Apply changes") }
        Text("Drag the AP on the plan to move it; coverage recalculates as you go.", style = MaterialTheme.typography.bodySmall)
    }
}
