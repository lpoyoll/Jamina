package com.jamina.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jamina.AppContainer
import com.jamina.JaminaApp
import com.jamina.core.model.SignalThresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as JaminaApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            if (title != null || action != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, color: Color? = null, onClick: (() -> Unit)? = null) {
    Card(
        modifier.padding(4.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color ?: MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grid of tiles, two per row. */
@Composable
fun MetricGrid(items: List<@Composable (Modifier) -> Unit>) {
    Column {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SignalGauge(rssi: Int?, thresholds: SignalThresholds, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(220.dp, 130.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(220.dp, 130.dp)) {
            val stroke = 22.dp.toPx()
            val d = size.width - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 180f, 180f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            if (rssi != null) {
                val frac = ((rssi + 100f) / 70f).coerceIn(0f, 1f)
                val c = Color(thresholds.levelOf(rssi).colorArgb)
                drawArc(c, 180f, 180f * frac, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rssi?.let { "$it dBm" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = onSurface)
            Text(rssi?.let { thresholds.levelOf(it).label } ?: "Not connected", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier, min: Float = -100f, max: Float = -30f, color: Color = MaterialTheme.colorScheme.primary) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        for (i in 0..2) {
            val y = size.height * i / 2f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (values.size < 2) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1).toFloat()
            val y = size.height * (1f - ((v - min) / (max - min)).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${selected?.let(display) ?: "—"}", maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, title = { Text(title) }, text = { Text(text) })
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun MeasuredTag(modifier: Modifier = Modifier) = Tag("MEASURED", Color(0xFF2E7D32), modifier)

@Composable
fun PredictedTag(modifier: Modifier = Modifier) = Tag("PREDICTED", Color(0xFF6A1B9A), modifier)

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LegendRow(items: List<Pair<Color, String>>) {
    Column {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (c, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(c, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text(t, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

object Fmt {
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)
    private val dateTime = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.UK)
    fun time(ts: Long): String = time.format(Date(ts))
    fun dateTime(ts: Long): String = dateTime.format(Date(ts))
    fun age(ms: Long): String = when {
        ms < 1000 -> "just now"
        ms < 60_000 -> "${ms / 1000}s ago"
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        else -> "${ms / 3_600_000}h ago"
    }
}

// ---------------- Permissions & prerequisites ----------------

fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

fun hasWifiPermissions(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun locationEnabled(ctx: Context): Boolean = ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

fun wifiEnabled(ctx: Context): Boolean = ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

/** Banner explaining and resolving anything that prevents real Wi-Fi measurements. */
@Composable
fun WifiPrereqBanner(onResolved: () -> Unit = {}) {
    val ctx = LocalContext.current
    var perms by remember { mutableStateOf(hasWifiPermissions(ctx)) }
    var loc by remember { mutableStateOf(locationEnabled(ctx)) }
    var wifi by remember { mutableStateOf(wifiEnabled(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = hasWifiPermissions(ctx)
        if (perms) onResolved()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        perms = hasWifiPermissions(ctx); loc = locationEnabled(ctx); wifi = wifiEnabled(ctx)
    }
    if (perms && loc && wifi) return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("Wi-Fi measurements unavailable", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            if (!wifi) {
                Text("Wi-Fi is switched off. Scanning and connection measurements need Wi-Fi enabled.")
                TextButton(onClick = { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }) { Text("Turn on Wi-Fi") }
            }
            if (!perms) {
                Text("Android only reveals nearby access points (and the connected AP's BSSID) to apps with location permission. Jamina never uses or stores your GPS location.")
                TextButton(onClick = { launcher.launch(requiredPermissions()) }) { Text("Grant permission") }
            }
            if (!loc) {
                Text("Location services are off. Android returns no Wi-Fi scan results while location is disabled.")
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Open location settings") }
            }
        }
    }
}
