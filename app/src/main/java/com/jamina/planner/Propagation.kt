package com.jamina.planner

import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.MaterialTable
import com.jamina.floorplan.Geometry
import com.jamina.floorplan.WallSeg
import com.jamina.heatmap.Grid
import com.jamina.wifi.WifiMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A planned AP in building coordinates (metres). */
data class PlannedAp(
    val id: String,
    val xM: Float,
    val yM: Float,
    val level: Int,
    val mountHeightM: Float,
    val txPowerDbm: Float,
    val gainDbi: Float,
    val band: Band,
    val channel: Int,
    val widthMhz: Int = 20,
    val directional: Boolean = false,
    val azimuthDeg: Float = 0f,
    val beamwidthDeg: Float = 90f,
)

/** A floor in building coordinates. elevationM = floor height above ground level. */
data class FloorGeom(
    val level: Int,
    val elevationM: Float,
    val walls: List<WallSeg>,
    val slab: Material,
    val widthM: Float = 0f,
    val heightM: Float = 0f,
    /** Building-frame position of this floor's local (0,0). */
    val originX: Float = 0f,
    val originY: Float = 0f,
)

data class PropagationParams(
    val exponent: Float = 2.2f,
    val offsetDb: Float = 0f,
    val rxHeightM: Float = 1.0f,
    val table: MaterialTable = MaterialTable(),
)

data class PredictionGrid(
    val best: Grid,
    val bestAp: IntArray,
    val coChannel: IntArray,
)

/**
 * Simplified log-distance indoor path-loss model:
 *   RSSI = Ptx + Gant - FSPL(1 m, f) - 10·n·log10(d) - Σ wall losses(f) - Σ floor losses(f) + offset
 * Predictions are estimates and must always be labelled as such.
 */
object Propagation {
    fun fspl1m(freqMhz: Float): Float = (20.0 * log10(freqMhz.toDouble()) - 27.55).toFloat()

    fun freqOf(ap: PlannedAp): Float = WifiMath.freqOf(ap.channel, ap.band).toFloat().takeIf { it > 0 } ?: ap.band.refFreqMhz.toFloat()

    fun antennaGain(ap: PlannedAp, rxX: Float, rxY: Float): Float {
        if (!ap.directional) return ap.gainDbi
        val ang = Math.toDegrees(atan2((rxY - ap.yM).toDouble(), (rxX - ap.xM).toDouble())).toFloat()
        var diff = abs(((ang - ap.azimuthDeg) % 360f + 540f) % 360f - 180f)
        diff = min(diff, 180f)
        val bw = max(ap.beamwidthDeg, 10f)
        val atten = min(12f * (diff / bw) * (diff / bw), 25f)
        return ap.gainDbi - atten
    }

    /** Floor slab loss between two levels (sum of slabs crossed). */
    fun floorLoss(apLevel: Int, rxLevel: Int, floors: Map<Int, FloorGeom>, band: Band, table: MaterialTable): Float {
        if (apLevel == rxLevel) return 0f
        val lo = min(apLevel, rxLevel); val hi = max(apLevel, rxLevel)
        var loss = 0f
        for (lvl in (lo + 1)..hi) {
            val slab = floors[lvl]?.slab ?: Material.TIMBER_FLOOR
            loss += table.loss(slab, band)
        }
        return loss
    }

    fun rssi(
        ap: PlannedAp,
        rxX: Float,
        rxY: Float,
        rxFloor: FloorGeom,
        floors: Map<Int, FloorGeom>,
        params: PropagationParams,
    ): Float {
        val apFloor = floors[ap.level]
        val apZ = (apFloor?.elevationM ?: 0f) + ap.mountHeightM
        val rxZ = rxFloor.elevationM + params.rxHeightM
        val dx = rxX - ap.xM; val dy = rxY - ap.yM; val dz = rxZ - apZ
        val d = max(sqrt(dx * dx + dy * dy + dz * dz), 0.5f)
        val f = freqOf(ap)
        val pl = fspl1m(f) + 10f * params.exponent * log10(max(d, 1f))
        val walls = Geometry.pathLoss(ap.xM, ap.yM, rxX, rxY, rxFloor.walls, ap.band, params.table)
        // For cross-floor paths also count walls on the AP's own floor near the AP.
        val apWalls = if (apFloor != null && apFloor.level != rxFloor.level) {
            Geometry.pathLoss(ap.xM, ap.yM, rxX, rxY, apFloor.walls, ap.band, params.table) * 0.5f
        } else 0f
        val fl = floorLoss(ap.level, rxFloor.level, floors, ap.band, params.table)
        return ap.txPowerDbm + antennaGain(ap, rxX, rxY) - pl - walls - apWalls - fl + params.offsetDb
    }

    /** Predicts best-server RSSI over a floor. [aps] may be on any floor. */
    fun predict(
        aps: List<PlannedAp>,
        rxFloor: FloorGeom,
        floors: Map<Int, FloorGeom>,
        widthM: Float,
        heightM: Float,
        cols: Int,
        params: PropagationParams,
        band: Band? = null,
        coChannelMinRssi: Float = -82f,
        originX: Float = rxFloor.originX,
        originY: Float = rxFloor.originY,
    ): PredictionGrid {
        val grid = Grid.forSize(widthM, heightM, cols)
        val bestAp = IntArray(grid.cols * grid.rows) { -1 }
        val co = IntArray(grid.cols * grid.rows)
        val list = aps.filter { band == null || it.band == band }
        val vals = FloatArray(list.size)
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val x = originX + grid.cx(c); val y = originY + grid.cy(r)
            var best = Float.NEGATIVE_INFINITY; var bi = -1
            for (i in list.indices) {
                val v = rssi(list[i], x, y, rxFloor, floors, params)
                vals[i] = v
                if (v > best) { best = v; bi = i }
            }
            val idx = grid.idx(c, r)
            if (bi >= 0) {
                grid.values[idx] = best
                grid.confidence[idx] = 1f
                bestAp[idx] = aps.indexOf(list[bi])
                var cnt = 0
                for (i in list.indices) if (i != bi && vals[i] >= coChannelMinRssi && list[i].band == list[bi].band && channelsOverlap(list[i], list[bi])) cnt++
                co[idx] = cnt
            }
        }
        return PredictionGrid(grid, bestAp, co)
    }

    fun channelsOverlap(a: PlannedAp, b: PlannedAp): Boolean {
        if (a.band != b.band) return false
        val sa = WifiMath.span(WifiMath.freqOf(a.channel, a.band), 0, a.widthMhz)
        val sb = WifiMath.span(WifiMath.freqOf(b.channel, b.band), 0, b.widthMhz)
        return WifiMath.overlaps(sa, sb)
    }
}
