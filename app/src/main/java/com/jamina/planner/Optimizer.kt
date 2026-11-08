package com.jamina.planner

import com.jamina.core.model.Band
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class OptFloor(
    val geom: FloorGeom,
    /** Coverage weight of a location: 0 = outside building, 1 = normal, 2 = needs strong coverage. */
    val weight: (Float, Float) -> Float,
    /** Whether an AP may be mounted at a location. */
    val allowed: (Float, Float) -> Boolean,
)

data class OptimiseRequest(
    val apCount: Int = 2,
    val minRssi: Float = -67f,
    val band: Band = Band.B5,
    val txPowerDbm: Float = 20f,
    val gainDbi: Float = 4f,
    val mountHeightM: Float = 2.4f,
    val widthMhz: Int = 80,
    val cols: Int = 36,
    val candidateSpacingM: Float = 1.2f,
)

data class Suggestion(val level: Int, val xM: Float, val yM: Float, val channel: Int)

data class OptimiseResult(
    val suggestions: List<Suggestion>,
    val coverageBefore: Float,
    val coverageAfter: Float,
    val evaluatedCandidates: Int,
)

object Optimizer {
    private class Cell(val floor: Int, val x: Float, val y: Float, val w: Float)

    fun channelPlan(band: Band, i: Int): Int = when (band) {
        Band.B24 -> listOf(1, 6, 11)[i % 3]
        Band.B5 -> listOf(36, 52, 100, 149)[i % 4]
        Band.B6 -> listOf(37, 69, 101, 133, 165, 197)[i % 6]
    }

    fun optimise(
        floors: List<OptFloor>,
        existing: List<PlannedAp>,
        params: PropagationParams,
        req: OptimiseRequest,
    ): OptimiseResult {
        val floorMap = floors.associate { it.geom.level to it.geom }
        val cells = mutableListOf<Cell>()
        for ((fi, f) in floors.withIndex()) {
            val w = max(f.geom.widthM, 1f); val h = max(f.geom.heightM, 1f)
            val cols = req.cols
            val rows = max(4, (cols * h / w).toInt())
            for (r in 0 until rows) for (c in 0 until cols) {
                val x = f.geom.originX + (c + 0.5f) * w / cols; val y = f.geom.originY + (r + 0.5f) * h / rows
                val wt = f.weight(x, y)
                if (wt > 0f) cells += Cell(fi, x, y, wt)
            }
        }
        if (cells.isEmpty()) return OptimiseResult(emptyList(), 0f, 0f, 0)
        val totalW = cells.sumOf { it.w.toDouble() }.toFloat()

        fun rssiAt(ap: PlannedAp, cell: Cell) = Propagation.rssi(ap, cell.x, cell.y, floors[cell.floor].geom, floorMap, params)

        val baseBest = FloatArray(cells.size) { Float.NEGATIVE_INFINITY }
        for (ap in existing.filter { it.band == req.band }) for ((i, c) in cells.withIndex()) baseBest[i] = max(baseBest[i], rssiAt(ap, c))

        fun coverage(best: FloatArray): Float {
            var s = 0f
            for (i in cells.indices) if (best[i] >= req.minRssi) s += cells[i].w
            return s / totalW
        }

        fun score(best: FloatArray): Float {
            var s = 0f; var soft = 0f
            for (i in cells.indices) {
                if (best[i] >= req.minRssi) s += cells[i].w
                soft += cells[i].w * (min(best[i], req.minRssi + 10f) - (req.minRssi - 30f)).coerceAtLeast(0f) / 40f
            }
            return s / totalW + 0.05f * soft / totalW
        }

        // Candidate positions
        val cands = mutableListOf<PlannedAp>()
        for (f in floors) {
            val w = max(f.geom.widthM, 1f); val h = max(f.geom.heightM, 1f)
            val step = max(req.candidateSpacingM, sqrt(w * h / 120f))
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    val bx = f.geom.originX + x; val by = f.geom.originY + y
                    if (f.allowed(bx, by) && f.weight(bx, by) > 0f) {
                        cands += PlannedAp("cand${cands.size}", bx, by, f.geom.level, req.mountHeightM, req.txPowerDbm, req.gainDbi, req.band, channelPlan(req.band, 0), req.widthMhz)
                    }
                    x += step
                }
                y += step
            }
        }
        if (cands.isEmpty()) return OptimiseResult(emptyList(), coverage(baseBest), coverage(baseBest), 0)

        val matrix = Array(cands.size) { ci -> FloatArray(cells.size) { i -> rssiAt(cands[ci], cells[i]) } }

        fun bestWith(chosen: List<Int>): FloatArray {
            val b = baseBest.copyOf()
            for (ci in chosen) { val row = matrix[ci]; for (i in b.indices) if (row[i] > b[i]) b[i] = row[i] }
            return b
        }

        // Greedy selection
        val chosen = mutableListOf<Int>()
        repeat(min(req.apCount, cands.size)) {
            val current = bestWith(chosen)
            var bestCi = -1; var bestScore = -1f
            for (ci in cands.indices) {
                if (ci in chosen) continue
                val row = matrix[ci]
                val trial = FloatArray(current.size) { i -> max(current[i], row[i]) }
                val s = score(trial)
                if (s > bestScore) { bestScore = s; bestCi = ci }
            }
            if (bestCi >= 0) chosen += bestCi
        }
        // Local refinement: try swapping each chosen AP with every candidate
        repeat(2) {
            for (k in chosen.indices) {
                var bestScore = score(bestWith(chosen))
                for (ci in cands.indices) {
                    if (ci in chosen) continue
                    val trial = chosen.toMutableList().also { it[k] = ci }
                    val s = score(bestWith(trial))
                    if (s > bestScore + 1e-5f) { bestScore = s; chosen[k] = ci }
                }
            }
        }
        val after = bestWith(chosen)
        val suggestions = chosen.mapIndexed { i, ci -> Suggestion(cands[ci].level, cands[ci].xM, cands[ci].yM, channelPlan(req.band, i)) }
        return OptimiseResult(suggestions, coverage(baseBest), coverage(after), cands.size)
    }
}

data class CalibrationResult(
    val exponent: Float,
    val offsetDb: Float,
    val rmseBefore: Float,
    val rmseAfter: Float,
    val meanErrorBefore: Float,
    val samples: Int,
)

object Calibration {
    /**
     * Fits path-loss exponent and a constant offset so predicted RSSI best matches measured RSSI.
     * [predict] returns predictions (without offset) for each measurement for a given exponent.
     */
    fun fit(measured: List<Float>, currentExponent: Float, currentOffset: Float, predict: (Float) -> List<Float>): CalibrationResult? {
        if (measured.size < 3) return null
        fun rmse(pred: List<Float>, off: Float): Float {
            var s = 0.0
            for (i in measured.indices) { val e = pred[i] + off - measured[i]; s += e * e }
            return sqrt(s / measured.size).toFloat()
        }
        val base = predict(currentExponent)
        val before = rmse(base, currentOffset)
        val meanErr = measured.indices.map { base[it] + currentOffset - measured[it] }.average().toFloat()
        var bestN = currentExponent; var bestOff = currentOffset; var bestR = before
        var n = 1.6f
        while (n <= 4.0001f) {
            val p = predict(n)
            val off = measured.indices.map { measured[it] - p[it] }.average().toFloat().coerceIn(-20f, 20f)
            val r = rmse(p, off)
            if (r < bestR) { bestR = r; bestN = n; bestOff = off }
            n += 0.05f
        }
        return CalibrationResult(bestN, bestOff, before, bestR, meanErr, measured.size)
    }
}
