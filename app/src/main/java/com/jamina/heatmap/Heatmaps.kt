package com.jamina.heatmap

import com.jamina.core.model.Band
import com.jamina.wifi.WifiMath

/** Lightweight observation used by analysis code. */
data class ObsLite(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val widthMhz: Int,
    val centerFreq0: Int = 0,
) {
    val band: Band? get() = Band.fromFreq(frequency)
    fun span() = WifiMath.span(frequency, centerFreq0, widthMhz)
}

/** A survey point with its raw observations, in plan units and metres. */
data class PointMeasurement(
    val id: String,
    val sessionId: String,
    val xU: Float,
    val yU: Float,
    val xM: Float,
    val yM: Float,
    val timestamp: Long,
    val source: String,
    val accuracyM: Float,
    val connectedSsid: String?,
    val connectedBssid: String?,
    val connectedRssi: Int?,
    val latencyMs: Float?,
    val lossPct: Float?,
    val throughputMbps: Float?,
    val fresh: Boolean,
    val observations: List<ObsLite>,
)

data class SignalFilter(val ssid: String? = null, val bssid: String? = null, val band: Band? = null) {
    fun matches(o: ObsLite): Boolean =
        (ssid == null || o.ssid == ssid) && (bssid == null || o.bssid == bssid) && (band == null || o.band == band)

    val isSpecific: Boolean get() = ssid != null || bssid != null
}

object Measures {
    /** Value used when a scan ran but the filtered AP was not heard (shown as "not detected"). */
    const val NOT_DETECTED = -95f

    fun bestObs(p: PointMeasurement, f: SignalFilter): ObsLite? = p.observations.filter(f::matches).maxByOrNull { it.rssi }

    /** Strongest RSSI matching the filter; NOT_DETECTED if scanned but absent; null if no scan data. */
    fun bestRssi(p: PointMeasurement, f: SignalFilter): Float? {
        if (p.observations.isEmpty()) return null
        return bestObs(p, f)?.rssi?.toFloat() ?: NOT_DETECTED
    }

    /** Number of other BSSIDs heard above [minRssi] whose spectrum overlaps the strongest matching AP. */
    fun coChannelCount(p: PointMeasurement, f: SignalFilter, minRssi: Int = -82): Float? {
        val best = bestObs(p, f) ?: return null
        val span = best.span()
        return p.observations.count { it.bssid != best.bssid && it.rssi >= minRssi && WifiMath.overlaps(it.span(), span) }.toFloat()
    }
}

enum class HeatmapType(val label: String, val unit: String) {
    SIGNAL("Signal strength", "dBm"),
    BEST_AP("Best AP coverage", ""),
    CHANNEL_OVERLAP("Channel overlap", "APs"),
    LATENCY("Latency", "ms"),
    PACKET_LOSS("Packet loss", "%"),
    THROUGHPUT("Throughput", "Mbps"),
    ROAMING("Roaming / connected AP", ""),
    CONFIDENCE("Coverage confidence", ""),
}

data class HeatmapResult(
    val type: HeatmapType,
    val grid: Grid? = null,
    val categories: IntArray? = null,
    val cols: Int = 0,
    val rows: Int = 0,
    val categoryLabels: List<String> = emptyList(),
    val minValue: Float = 0f,
    val maxValue: Float = 1f,
    /** Higher values are better (e.g. RSSI, throughput); false for latency/loss/overlap. */
    val higherIsBetter: Boolean = true,
    val sampleCount: Int = 0,
    val roamMarkers: List<Pair<Float, Float>> = emptyList(),
    val message: String? = null,
)

object HeatmapBuilder {
    fun build(
        type: HeatmapType,
        points: List<PointMeasurement>,
        filter: SignalFilter,
        widthM: Float,
        heightM: Float,
        opt: IdwOptions,
        cols: Int = 140,
    ): HeatmapResult {
        fun scalar(sel: (PointMeasurement) -> Float?, higherBetter: Boolean, fixedMin: Float? = null, fixedMax: Float? = null): HeatmapResult {
            val samples = points.mapNotNull { p -> sel(p)?.let { Sample(p.xM, p.yM, it) } }
            if (samples.isEmpty()) return HeatmapResult(type, message = "No ${type.label.lowercase()} measurements in the selected survey(s).")
            val grid = Idw.interpolate(samples, Grid.forSize(widthM, heightM, cols), opt)
            val mn = fixedMin ?: samples.minOf { it.value }
            val mx = fixedMax ?: samples.maxOf { it.value }
            return HeatmapResult(type, grid, cols = grid.cols, rows = grid.rows, minValue = mn, maxValue = if (mx - mn < 1e-3f) mn + 1f else mx, higherIsBetter = higherBetter, sampleCount = samples.size)
        }

        return when (type) {
            HeatmapType.SIGNAL -> scalar({ Measures.bestRssi(it, filter) }, true, -95f, -30f)
            HeatmapType.CHANNEL_OVERLAP -> scalar({ Measures.coChannelCount(it, filter) }, false, 0f, null)
            HeatmapType.LATENCY -> scalar({ it.latencyMs }, false, 0f, null)
            HeatmapType.PACKET_LOSS -> scalar({ it.lossPct }, false, 0f, 20f)
            HeatmapType.THROUGHPUT -> scalar({ it.throughputMbps }, true, 0f, null)
            HeatmapType.CONFIDENCE -> {
                val samples = points.mapNotNull { p -> Measures.bestRssi(p, filter)?.let { Sample(p.xM, p.yM, it) } }
                if (samples.isEmpty()) return HeatmapResult(type, message = "No survey points yet.")
                val grid = Idw.interpolate(samples, Grid.forSize(widthM, heightM, cols), opt.copy(extrapolate = true))
                for (i in grid.values.indices) grid.values[i] = grid.confidence[i]
                HeatmapResult(type, grid, cols = grid.cols, rows = grid.rows, minValue = 0f, maxValue = 1f, higherIsBetter = true, sampleCount = samples.size)
            }
            HeatmapType.BEST_AP, HeatmapType.ROAMING -> {
                val labels = mutableListOf<String>()
                val samples = points.mapNotNull { p ->
                    val key = if (type == HeatmapType.BEST_AP) Measures.bestObs(p, filter)?.bssid else p.connectedBssid
                    key?.let {
                        var idx = labels.indexOf(it)
                        if (idx < 0) { labels += it; idx = labels.size - 1 }
                        CategorySample(p.xM, p.yM, idx)
                    }
                }
                if (samples.isEmpty()) return HeatmapResult(type, message = "No AP information in the selected survey(s).")
                val g = Grid.forSize(widthM, heightM, cols)
                val cats = Idw.nearestCategory(samples, g.cols, g.rows, widthM, heightM, opt.radiusM)
                val markers = if (type == HeatmapType.ROAMING) {
                    points.sortedBy { it.timestamp }.zipWithNext().filter { (a, b) ->
                        a.connectedBssid != null && b.connectedBssid != null && a.connectedBssid != b.connectedBssid
                    }.map { (_, b) -> b.xU to b.yU }
                } else emptyList()
                HeatmapResult(type, null, cats, g.cols, g.rows, labels, sampleCount = samples.size, roamMarkers = markers)
            }
        }
    }
}
