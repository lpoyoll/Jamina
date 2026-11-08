package com.jamina.floorplan

import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.MaterialTable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Wall segment in metres. Openings (doors/windows) replace the wall they sit on. */
data class WallSeg(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val material: Material,
    val opening: Boolean = false,
)

object Geometry {
    /** Returns parameter t along (a->b) of intersection with (c->d), or null. */
    fun intersectT(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float? {
        val rX = bx - ax; val rY = by - ay
        val sX = dx - cx; val sY = dy - cy
        val denom = rX * sY - rY * sX
        if (abs(denom) < 1e-9f) return null
        val qpX = cx - ax; val qpY = cy - ay
        val t = (qpX * sY - qpY * sX) / denom
        val u = (qpX * rY - qpY * rX) / denom
        return if (t in 0f..1f && u in 0f..1f) t else null
    }

    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean = intersectT(ax, ay, bx, by, cx, cy, dx, dy) != null

    fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    fun projectOnSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12f) return x1 to y1
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return (x1 + t * dx) to (y1 + t * dy)
    }

    fun length(x1: Float, y1: Float, x2: Float, y2: Float): Float = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    /**
     * Total attenuation (dB) along the straight path a->b crossing [walls].
     * A door/window crossing replaces any solid wall crossing within [openingTolM] of it.
     */
    fun pathLoss(
        ax: Float, ay: Float, bx: Float, by: Float,
        walls: List<WallSeg>, band: Band, table: MaterialTable, openingTolM: Float = 0.3f,
    ): Float {
        if (walls.isEmpty()) return 0f
        val pathLen = length(ax, ay, bx, by)
        if (pathLen < 1e-4f) return 0f
        var openings: MutableList<Float>? = null
        var total = 0f
        // First collect openings
        for (w in walls) {
            if (!w.opening) continue
            val t = intersectT(ax, ay, bx, by, w.x1, w.y1, w.x2, w.y2) ?: continue
            if (openings == null) openings = mutableListOf()
            openings.add(t * pathLen)
            total += table.loss(w.material, band)
        }
        for (w in walls) {
            if (w.opening) continue
            val t = intersectT(ax, ay, bx, by, w.x1, w.y1, w.x2, w.y2) ?: continue
            val d = t * pathLen
            if (openings != null && openings.any { abs(it - d) <= openingTolM }) continue
            total += table.loss(w.material, band)
        }
        return total
    }

    /** Number of solid walls crossed (used for wall-aware interpolation). */
    fun crossings(ax: Float, ay: Float, bx: Float, by: Float, walls: List<WallSeg>): Int {
        var n = 0
        for (w in walls) {
            if (w.opening) continue
            if (segmentsIntersect(ax, ay, bx, by, w.x1, w.y1, w.x2, w.y2)) n++
        }
        return n
    }
}
