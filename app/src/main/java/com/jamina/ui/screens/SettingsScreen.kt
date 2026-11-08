package com.jamina.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.jamina.AppContainer
import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.MaterialTable
import com.jamina.core.model.SignalThresholds
import com.jamina.core.settings.AppSettings
import com.jamina.ui.components.LabeledSwitch
import com.jamina.ui.components.NumberField
import com.jamina.ui.components.SectionCard
import com.jamina.ui.components.appViewModel
import kotlinx.coroutines.launch

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    suspend fun save(s: AppSettings) = c.settings.update { s }
    suspend fun prune() { c.dao.pruneUnreferencedObservations(); c.dao.pruneUnreferencedBatches() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val current by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember(current) { mutableStateOf(current) }
    var t by remember(current) { mutableStateOf(listOf(current.thresholds.excellent, current.thresholds.veryGood, current.thresholds.good, current.thresholds.fair, current.thresholds.weak).map { it.toString() }) }
    var power by remember(current) { mutableStateOf(current.idwPower.toString()) }
    var radius by remember(current) { mutableStateOf(current.idwRadiusM.toString()) }
    var table by remember(current) { mutableStateOf(current.materialTable) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            SectionCard("Signal thresholds (dBm, lower bound)") {
                val labels = listOf("Excellent ≥", "Very good ≥", "Good ≥", "Fair ≥", "Weak ≥")
                labels.forEachIndexed { i, l ->
                    NumberField(l, t[i], { v -> t = t.toMutableList().also { it[i] = v } }, Modifier.fillMaxWidth())
                }
                Text("Anything below 'Weak' is shown as Poor. -67 dBm is a common minimum for voice/video.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Survey") {
                LabeledSwitch("Wait for fresh scans (recommended)", s.requireFreshScan, { s = s.copy(requireFreshScan = it) })
                LabeledSwitch("Measure latency/loss at each point", s.latencyAtPoints, { s = s.copy(latencyAtPoints = it) })
                LabeledSwitch("Measure throughput at each point", s.throughputAtPoints, { s = s.copy(throughputAtPoints = it) })
                Text("Android limits apps to 4 scans per 2 minutes. For faster surveys enable Developer options → 'Wi-Fi scan throttling' OFF.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Heatmap interpolation") {
                NumberField("IDW power", power, { power = it }, Modifier.fillMaxWidth())
                NumberField("Max distance from a measurement (m)", radius, { radius = it }, Modifier.fillMaxWidth())
                LabeledSwitch("Wall-aware interpolation", s.wallAware, { s = s.copy(wallAware = it) })
                LabeledSwitch("Advanced mode (show RF model settings)", s.advancedMode, { s = s.copy(advancedMode = it) })
            }
            SectionCard("Diagnostics endpoints") {
                OutlinedTextField(s.internetHost, { s = s.copy(internetHost = it) }, label = { Text("Internet latency host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.downloadUrl, { s = s.copy(downloadUrl = it) }, label = { Text("Download test URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.uploadUrl, { s = s.copy(uploadUrl = it) }, label = { Text("Upload test URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (s.advancedMode) {
                SectionCard("Wall attenuation by band (dB)") {
                    for (m in Material.entries) {
                        Text(m.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (b in Band.entries) {
                                var txt by remember(table, m, b) { mutableStateOf(table.loss(m, b).toString()) }
                                NumberField(b.label, txt, { v -> txt = v; v.toFloatOrNull()?.let { table = table.with(m, b, it) } }, Modifier.weight(1f))
                            }
                        }
                    }
                    OutlinedButton(onClick = { table = MaterialTable() }) { Text("Reset to defaults") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val th = t.mapNotNull { it.trim().toIntOrNull() }
                    val thresholds = if (th.size == 5) SignalThresholds(th[0], th[1], th[2], th[3], th[4]) else null
                    if (thresholds == null || !thresholds.isValid()) {
                        Toast.makeText(ctx, "Thresholds must be numbers in descending order", Toast.LENGTH_LONG).show(); return@Button
                    }
                    val ns = s.copy(
                        thresholds = thresholds,
                        idwPower = power.toFloatOrNull()?.coerceIn(0.5f, 6f) ?: s.idwPower,
                        idwRadiusM = radius.toFloatOrNull()?.coerceIn(1f, 50f) ?: s.idwRadiusM,
                        materialOverrides = table.encode(),
                    )
                    scope.launch { vm.save(ns); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() }
                }) { Text("Save") }
                OutlinedButton(onClick = { scope.launch { vm.prune(); Toast.makeText(ctx, "Scanner history not used by surveys was cleared", Toast.LENGTH_SHORT).show() } }) { Text("Clear scanner history") }
            }
        }
    }
}
