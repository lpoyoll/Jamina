package com.jamina.heatmap

import com.jamina.floorplan.Geometry
import com.jamina.floorplan.WallSeg
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A measured value at a location (metres). */
data class Sample(val x: Float, val y: Float, val value: Float)

/** A categorical measurement (e.g. strongest BSSID index) at a location (metres). */
data class CategorySample(val x: Float, val y: Float, val category: Int)

/**
 * Regular grid over a floor in metres. values = NaN where no estimate is made.
 * confidence ∈ [0,1] describes how well-supported each cell is by nearby measurements.
 */
class Grid(val cols: Int, val rows: Int, val widthM: Float, val heightM: Float) {
    val values = FloatArray(cols * rows) { Float.NaN }
    val confidence = FloatArray(cols * rows)
    val cellW: Float get() = widthM / cols
    val cellH: Float get() = heightM / rows
    fun cx(c: Int) = (c + 0.5f) * cellW
    fun cy(r: Int) = (r + 0.5f) * cellH
    fun idx(c: Int, r: Int) = r * cols + c
    operator fun get(c: Int, r: Int) = values[idx(c, r)]

    fun valueAt(xM: Float, yM: Float): Float {
        val c = (xM / cellW).toInt().coerceIn(0, cols - 1)
        val r = (yM / cellH).toInt().coerceIn(0, rows - 1)
        return values[idx(c, r)]
    }

    companion object {
        fun forSize(widthM: Float, heightM: Float, maxCols: Int): Grid {
            val w = max(widthM, 0.1f); val h = max(heightM, 0.1f)
            val cols = maxCols.coerceAtLeast(4)
            val rows = (cols * h / w).toInt().coerceIn(4, maxCols * 3)
            return Grid(cols, rows, w, h)
        }
    }
}

data class IdwOptions(
    val power: Float = 2f,
    /** Cells further than this from every sample are left empty unless [extrapolate]. */
    val radiusM: Float = 6f,
    val extrapolate: Boolean = false,
    /** Weight multiplier per wall between sample and cell (1 = ignore walls). */
    val wallWeightFactor: Float = 0.3f,
    val walls: List<WallSeg> = emptyList(),
    /** Max samples considered per cell (nearest first). */
    val maxNeighbours: Int = 12,
)

object Idw {
    /**
     * Inverse distance weighting with optional wall-aware weights.
     * Confidence is derived from distance to the nearest sample and the local sample count.
     */
    fun interpolate(samples: List<Sample>, grid: Grid, opt: IdwOptions = IdwOptions()): Grid {
        if (samples.isEmpty()) return grid
        val wallAware = opt.walls.isNotEmpty() && opt.wallWeightFactor < 0.999f
        val n = samples.size
        val dists = FloatArray(n)
        val order = IntArray(n)
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val x = grid.cx(c); val y = grid.cy(r)
            var nearest = Float.MAX_VALUE
            for (i in 0 until n) {
                val dx = samples[i].x - x; val dy = samples[i].y - y
                val d = sqrt(dx * dx + dy * dy)
                dists[i] = d
                if (d < nearest) nearest = d
            }
            val i0 = grid.idx(c, r)
            if (nearest > opt.radiusM && !opt.extrapolate) {
                grid.values[i0] = Float.NaN
                grid.confidence[i0] = 0f
                continue
            }
            // nearest-k selection
            for (i in 0 until n) order[i] = i
            val k = min(opt.maxNeighbours, n)
            partialSort(order, dists, k)
            var exact = Float.NaN
            var wSum = 0.0
            var vSum = 0.0
            var within = 0
            for (j in 0 until k) {
                val i = order[j]
                val d = dists[i]
                if (d <= opt.radiusM) within++
                if (d < 1e-3f) { exact = samples[i].value; break }
                var w = 1.0 / d.toDouble().pow(opt.power.toDouble())
                if (wallAware) {
                    val walls = Geometry.crossings(samples[i].x, samples[i].y, x, y, opt.walls)
                    if (walls > 0) w *= opt.wallWeightFactor.toDouble().pow(walls)
                }
                wSum += w
                vSum += w * samples[i].value
            }
            grid.values[i0] = if (!exact.isNaN()) exact else if (wSum > 0) (vSum / wSum).toFloat() else Float.NaN
            val distConf = (1f - nearest / opt.radiusM).coerceIn(0f, 1f)
            val countConf = 0.6f + 0.4f * ((within - 1).coerceIn(0, 2) / 2f)
            grid.confidence[i0] = (distConf * countConf).coerceIn(0f, 1f)
        }
        return grid
    }

    /** Selection-sorts the first k entries of [order] by [dists]. */
    private fun partialSort(order: IntArray, dists: FloatArray, k: Int) {
        for (a in 0 until k) {
            var best = a
            for (b in a + 1 until order.size) if (dists[order[b]] < dists[order[best]]) best = b
            val t = order[a]; order[a] = order[best]; order[best] = t
        }
    }

    /** Nearest-neighbour categorical map (e.g. best AP); -1 where beyond radius. */
    fun nearestCategory(samples: List<CategorySample>, cols: Int, rows: Int, widthM: Float, heightM: Float, radiusM: Float): IntArray {
        val out = IntArray(cols * rows) { -1 }
        if (samples.isEmpty()) return out
        val cw = widthM / cols; val ch = heightM / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5f) * cw; val y = (r + 0.5f) * ch
            var best = -1; var bestD = Float.MAX_VALUE
            for (s in samples) {
                val dx = s.x - x; val dy = s.y - y
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = s.category }
            }
            if (sqrt(bestD) <= radiusM) out[r * cols + c] = best
        }
        return out
    }
}

/** Summary statistics over a grid, optionally masked. */
data class GridStats(val cells: Int, val covered: Int, val aboveThreshold: Int, val min: Float, val max: Float, val mean: Float) {
    val fractionAbove: Float get() = if (covered == 0) 0f else aboveThreshold.toFloat() / covered
}

fun Grid.stats(threshold: Float, mask: ((Float, Float) -> Boolean)? = null, minConfidence: Float = 0f): GridStats {
    var cells = 0; var covered = 0; var above = 0
    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0
    for (r in 0 until rows) for (c in 0 until cols) {
        if (mask != null && !mask(cx(c), cy(r))) continue
        cells++
        val i = idx(c, r)
        val v = values[i]
        if (v.isNaN() || confidence[i] < minConfidence) continue
        covered++
        if (v >= threshold) above++
        mn = min(mn, v); mx = max(mx, v); sum += v
    }
    return GridStats(cells, covered, above, if (covered > 0) mn else Float.NaN, if (covered > 0) mx else Float.NaN, if (covered > 0) (sum / covered).toFloat() else Float.NaN)
}
