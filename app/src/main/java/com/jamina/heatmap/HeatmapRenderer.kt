package com.jamina.heatmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.RoomEntity
import com.jamina.core.db.WallEntity
import com.jamina.core.model.Material
import com.jamina.core.model.SignalLevel
import com.jamina.core.model.SignalThresholds
import kotlin.math.max
import kotlin.math.min

object HeatColors {
    private val categorical = intArrayOf(
        0xFF1E88E5.toInt(), 0xFFD81B60.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(),
        0xFF00ACC1.toInt(), 0xFFFDD835.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFFC0CA33.toInt(),
    )

    fun category(i: Int): Int = categorical[((i % categorical.size) + categorical.size) % categorical.size]

    fun signal(v: Float, t: SignalThresholds): Int = t.levelOf(v).colorArgb.toInt()

    /** frac 0 = best (green) … 1 = worst (red). */
    fun ramp(frac: Float): Int {
        val f = frac.coerceIn(0f, 1f)
        return if (f < 0.5f) lerp(0xFF43A047.toInt(), 0xFFFDD835.toInt(), f * 2) else lerp(0xFFFDD835.toInt(), 0xFFE53935.toInt(), (f - 0.5f) * 2)
    }

    fun lerp(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    fun withAlpha(c: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}

object HeatmapRenderer {
    /** Renders a heatmap result into a small bitmap (one pixel per grid cell). */
    fun render(result: HeatmapResult, t: SignalThresholds, alpha: Int = 175, fadeLowConfidence: Boolean = true): Bitmap? {
        val cols = if (result.grid != null) result.grid.cols else result.cols
        val rows = if (result.grid != null) result.grid.rows else result.rows
        if (cols <= 0 || rows <= 0) return null
        val px = IntArray(cols * rows)
        val grid = result.grid
        if (grid != null) {
            val range = max(result.maxValue - result.minValue, 1e-3f)
            for (i in px.indices) {
                val v = grid.values[i]
                if (v.isNaN()) { px[i] = Color.TRANSPARENT; continue }
                val color = when (result.type) {
                    HeatmapType.SIGNAL -> HeatColors.signal(v, t)
                    HeatmapType.CONFIDENCE -> HeatColors.ramp(1f - v)
                    else -> {
                        val frac = (v - result.minValue) / range
                        HeatColors.ramp(if (result.higherIsBetter) 1f - frac else frac)
                    }
                }
                val conf = if (result.type == HeatmapType.CONFIDENCE || !fadeLowConfidence) 1f else grid.confidence[i]
                px[i] = HeatColors.withAlpha(color, (alpha * (0.35f + 0.65f * conf)).toInt())
            }
        } else {
            val cats = result.categories ?: return null
            for (i in px.indices) px[i] = if (cats[i] < 0) Color.TRANSPARENT else HeatColors.withAlpha(HeatColors.category(cats[i]), alpha)
        }
        return Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
    }

    /** Renders predicted best-server RSSI. */
    fun renderSignalGrid(grid: Grid, t: SignalThresholds, alpha: Int = 170): Bitmap {
        val px = IntArray(grid.cols * grid.rows) { i ->
            val v = grid.values[i]
            if (v.isNaN()) Color.TRANSPARENT else HeatColors.withAlpha(HeatColors.signal(v, t), alpha)
        }
        return Bitmap.createBitmap(px, grid.cols, grid.rows, Bitmap.Config.ARGB_8888)
    }

    fun renderCategories(cats: IntArray, cols: Int, rows: Int, alpha: Int = 160): Bitmap {
        val px = IntArray(cols * rows) { i -> if (cats[i] < 0) Color.TRANSPARENT else HeatColors.withAlpha(HeatColors.category(cats[i]), alpha) }
        return Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
    }

    fun renderCounts(counts: IntArray, cols: Int, rows: Int, alpha: Int = 160): Bitmap {
        val px = IntArray(cols * rows) { i ->
            val frac = min(counts[i], 4) / 4f
            HeatColors.withAlpha(HeatColors.ramp(frac), alpha)
        }
        return Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
    }
}

data class MarkerSpec(val xU: Float, val yU: Float, val color: Int, val label: String? = null, val hollow: Boolean = false, val square: Boolean = false)

/** Off-screen composite renderer for PNG/PDF export. */
object PlanRenderer {
    fun composite(
        floor: FloorEntity,
        plan: Bitmap?,
        heat: Bitmap?,
        walls: List<WallEntity>,
        rooms: List<RoomEntity>,
        markers: List<MarkerSpec>,
        title: String,
        subtitle: String,
        legend: List<Pair<Int, String>>,
        maxDim: Int = 1800,
    ): Bitmap {
        val scale = maxDim / max(floor.widthUnits, floor.heightUnits)
        val w = (floor.widthUnits * scale).toInt().coerceAtLeast(200)
        val h = (floor.heightUnits * scale).toInt().coerceAtLeast(200)
        val header = 110
        val legendH = if (legend.isEmpty()) 0 else 60 + ((legend.size + 2) / 3) * 44
        val out = Bitmap.createBitmap(w, h + header + legendH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f; isFakeBoldText = true }
        c.drawText(title, 24f, 52f, text)
        text.textSize = 28f; text.isFakeBoldText = false; text.color = Color.DKGRAY
        c.drawText(subtitle, 24f, 92f, text)
        c.save()
        c.translate(0f, header.toFloat())
        val dst = RectF(0f, 0f, floor.widthUnits * scale, floor.heightUnits * scale)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (plan != null) c.drawBitmap(plan, null, dst, bmpPaint)
        if (heat != null) c.drawBitmap(heat, Rect(0, 0, heat.width, heat.height), dst, bmpPaint)
        val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(90, 0, 0, 0); strokeWidth = 2f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 30, 30); textSize = 26f }
        for (r in rooms) {
            val rect = RectF(min(r.x, r.x + r.w) * scale, min(r.y, r.y + r.h) * scale, max(r.x, r.x + r.w) * scale, max(r.y, r.y + r.h) * scale)
            if (r.kind == "STAIRS") {
                roomPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                c.drawRect(rect, roomPaint)
                roomPaint.pathEffect = null
            }
            c.drawText(r.name, rect.left + 8f, rect.top + 30f, label)
        }
        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        for (wl in walls) {
            val m = Material.of(wl.material)
            wallPaint.color = when (wl.type) {
                "DOOR" -> 0xFFA1887F.toInt()
                "WINDOW" -> 0xFF4FC3F7.toInt()
                else -> m.colorArgb.toInt()
            }
            wallPaint.strokeWidth = max(3f, m.thicknessM / floor.metersPerUnit * scale)
            c.drawLine(wl.x1 * scale, wl.y1 * scale, wl.x2 * scale, wl.y2 * scale, wallPaint)
        }
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (mk in markers) {
            val x = mk.xU * scale; val y = mk.yU * scale
            mp.style = Paint.Style.FILL; mp.color = Color.WHITE
            if (mk.square) c.drawRect(x - 16f, y - 16f, x + 16f, y + 16f, mp) else c.drawCircle(x, y, 13f, mp)
            mp.color = mk.color
            mp.style = if (mk.hollow) Paint.Style.STROKE else Paint.Style.FILL
            mp.strokeWidth = 4f
            if (mk.square) c.drawRect(x - 12f, y - 12f, x + 12f, y + 12f, mp) else c.drawCircle(x, y, 10f, mp)
            mk.label?.let { c.drawText(it, x + 16f, y - 10f, label) }
        }
        c.restore()
        if (legend.isNotEmpty()) {
            val top = header + h + 20f
            val sw = Paint(Paint.ANTI_ALIAS_FLAG)
            val lt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f }
            legend.forEachIndexed { i, (color, txt) ->
                val col = i % 3; val row = i / 3
                val x = 24f + col * (w / 3f); val y = top + row * 44f
                sw.color = color
                c.drawRect(x, y, x + 32f, y + 32f, sw)
                c.drawText(txt, x + 44f, y + 26f, lt)
            }
        }
        return out
    }

    fun signalLegend(t: SignalThresholds): List<Pair<Int, String>> =
        SignalLevel.entries.map { it.colorArgb.toInt() to "${it.label} (${t.bandLabel(it)})" }
}
