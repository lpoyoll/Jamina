package com.jamina.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.RoomEntity
import com.jamina.core.db.WallEntity
import com.jamina.core.model.Material
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PlanTransform(val scale: Float, val offset: Offset) {
    fun toScreen(p: Offset) = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
    fun toScreen(x: Float, y: Float) = Offset(x * scale + offset.x, y * scale + offset.y)
    fun toPlan(s: Offset) = Offset((s.x - offset.x) / scale, (s.y - offset.y) / scale)
}

/** Callbacks receive positions in plan units. */
interface PlanGestureHandler {
    fun onTap(p: Offset) {}
    /** Return true to consume a one-finger drag (e.g. drawing); false lets it pan the view. */
    fun onDragStart(p: Offset): Boolean = false
    fun onDrag(p: Offset) {}
    fun onDragEnd() {}
}

object NoGestures : PlanGestureHandler

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }

fun DrawScope.drawLabel(text: String, pos: Offset, color: Color, sizePx: Float = 30f, bold: Boolean = false) {
    labelPaint.color = color.toArgb()
    labelPaint.textSize = sizePx
    labelPaint.isFakeBoldText = bold
    drawContext.canvas.nativeCanvas.drawText(text, pos.x, pos.y, labelPaint)
}

/**
 * Zoomable/pannable floor plan. One-finger drags go to [handler] (tools) or pan; two fingers zoom/pan.
 */
@Composable
fun PlanCanvas(
    floor: FloorEntity,
    planImage: ImageBitmap?,
    modifier: Modifier = Modifier,
    heat: ImageBitmap? = null,
    heatAlpha: Float = 1f,
    walls: List<WallEntity> = emptyList(),
    rooms: List<RoomEntity> = emptyList(),
    selectedId: String? = null,
    showGrid: Boolean = true,
    handler: PlanGestureHandler = NoGestures,
    overlay: DrawScope.(PlanTransform) -> Unit = {},
) {
    var scale by remember(floor.id) { mutableFloatStateOf(1f) }
    var offset by remember(floor.id) { mutableStateOf(Offset.Zero) }
    var fitted by remember(floor.id) { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val h by rememberUpdatedState(handler)
    val planW = floor.widthUnits
    val planH = floor.heightUnits
    val gridColor = Color(0x22000000)
    val selColor = MaterialTheme.colorScheme.primary
    val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest

    fun fit(size: IntSize) {
        if (size.width == 0 || size.height == 0) return
        val s = min(size.width / planW, size.height / planH) * 0.94f
        scale = s
        offset = Offset((size.width - planW * s) / 2f, (size.height - planH * s) / 2f)
        fitted = true
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it; if (!fitted) fit(it) }
                .pointerInput(floor.id) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var multi = false
                        var dragging = false
                        var panning = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (dragging) { h.onDragEnd(); dragging = false }
                                multi = true
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid(useCurrent = true)
                                val minS = min(viewSize.width / planW, viewSize.height / planH) * 0.3f
                                val newScale = (scale * zoom).coerceIn(max(minS, 0.01f), 40f)
                                val planC = Offset((c.x - offset.x) / scale, (c.y - offset.y) / scale)
                                offset = Offset(c.x - planC.x * newScale + pan.x, c.y - planC.y * newScale + pan.y)
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            } else if (!multi) {
                                val ch = pressed[0]
                                val t = PlanTransform(scale, offset)
                                if (!dragging && !panning && (ch.position - start).getDistance() > slop) {
                                    if (h.onDragStart(t.toPlan(start))) dragging = true else panning = true
                                }
                                if (dragging) {
                                    h.onDrag(t.toPlan(ch.position)); ch.consume()
                                } else if (panning) {
                                    offset += ch.position - ch.previousPosition; ch.consume()
                                }
                            }
                        }
                        if (dragging) h.onDragEnd()
                        else if (!multi && !panning) h.onTap(PlanTransform(scale, offset).toPlan(start))
                    }
                },
        ) {
            drawRect(backdrop)
            val t = PlanTransform(scale, offset)
            val origin = t.toScreen(0f, 0f)
            val sizePx = Size(planW * scale, planH * scale)
            drawRect(Color.White, origin, sizePx)
            if (planImage != null) {
                drawImage(
                    planImage, IntOffset.Zero, IntSize(planImage.width, planImage.height),
                    IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    IntSize(sizePx.width.roundToInt().coerceAtLeast(1), sizePx.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (showGrid) {
                val stepUnits = 1f / floor.metersPerUnit
                val stepPx = stepUnits * scale
                if (stepPx >= 8f) {
                    var x = 0f
                    while (x <= planW) {
                        val sx = t.toScreen(x, 0f).x
                        drawLine(gridColor, Offset(sx, origin.y), Offset(sx, origin.y + sizePx.height), 1f)
                        x += stepUnits
                    }
                    var y = 0f
                    while (y <= planH) {
                        val sy = t.toScreen(0f, y).y
                        drawLine(gridColor, Offset(origin.x, sy), Offset(origin.x + sizePx.width, sy), 1f)
                        y += stepUnits
                    }
                }
            }
            if (heat != null) {
                drawImage(
                    heat, IntOffset.Zero, IntSize(heat.width, heat.height),
                    IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    IntSize(sizePx.width.roundToInt().coerceAtLeast(1), sizePx.height.roundToInt().coerceAtLeast(1)),
                    alpha = heatAlpha, filterQuality = FilterQuality.High,
                )
            }
            for (r in rooms) {
                val tl = t.toScreen(min(r.x, r.x + r.w), min(r.y, r.y + r.h))
                val sz = Size(kotlin.math.abs(r.w) * scale, kotlin.math.abs(r.h) * scale)
                val sel = r.id == selectedId
                when {
                    r.kind == "STAIRS" -> {
                        drawRect(Color(0x228D6E63), tl, sz)
                        val steps = 8
                        for (i in 1 until steps) {
                            val x = tl.x + sz.width * i / steps
                            drawLine(Color(0x668D6E63), Offset(x, tl.y), Offset(x, tl.y + sz.height), 2f)
                        }
                    }
                    r.noApZone -> drawRect(Color(0x14E53935), tl, sz)
                    r.priority -> drawRect(Color(0x141E88E5), tl, sz)
                }
                if (sel) drawRect(selColor, tl, sz, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                drawLabel(r.name + if (r.noApZone) " (no AP)" else "", Offset(tl.x + 8f, tl.y + 34f), Color(0xCC222222), 28f)
            }
            for (w in walls) {
                val m = Material.of(w.material)
                val color = when (w.type) {
                    "DOOR" -> Color(0xFFA1887F)
                    "WINDOW" -> Color(0xFF4FC3F7)
                    else -> Color(m.colorArgb)
                }
                val px = max(3f, m.thicknessM / floor.metersPerUnit * scale)
                val a = t.toScreen(w.x1, w.y1); val b = t.toScreen(w.x2, w.y2)
                if (w.id == selectedId) drawLine(selColor, a, b, px + 8f, cap = StrokeCap.Round)
                drawLine(
                    color, a, b, px, cap = StrokeCap.Round,
                    pathEffect = if (w.type == "DOOR") PathEffect.dashPathEffect(floatArrayOf(14f, 8f)) else null,
                )
                if (w.exterior && w.type == "WALL") drawLine(Color(0x55000000), a, b, max(1f, px / 3f))
            }
            overlay(t)
        }
    }
}
