package com.jamina.reports

import com.jamina.core.db.RoomEntity
import com.jamina.core.model.Band
import com.jamina.core.model.SignalThresholds
import com.jamina.heatmap.Measures
import com.jamina.heatmap.PointMeasurement
import com.jamina.heatmap.SignalFilter
import com.jamina.wifi.ApObservation
import com.jamina.wifi.ChannelAnalysis
import kotlin.math.hypot

enum class Severity { INFO, ADVISORY, PROBLEM }

/**
 * A finding. [observation] states what was measured; [explanation] offers a *possible* cause.
 */
data class Insight(val severity: Severity, val title: String, val observation: String, val explanation: String)

data class SurveySummary(
    val points: Int,
    val avgRssi: Float?,
    val minRssi: Float?,
    val maxRssi: Float?,
    val fractionGood: Float?,
    val deadZones: Int,
    val avgLatency: Float?,
    val avgLoss: Float?,
    val avgThroughput: Float?,
    val distinctBssids: Int,
    val estimatedPositions: Int,
    val cachedScans: Int,
)

object SurveyStats {
    fun primarySsid(points: List<PointMeasurement>): String? =
        points.mapNotNull { it.connectedSsid }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun summarise(points: List<PointMeasurement>, filter: SignalFilter, t: SignalThresholds): SurveySummary {
        val rssis = points.mapNotNull { Measures.bestRssi(it, filter) }
        return SurveySummary(
            points = points.size,
            avgRssi = rssis.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            minRssi = rssis.minOrNull(),
            maxRssi = rssis.maxOrNull(),
            fractionGood = rssis.takeIf { it.isNotEmpty() }?.let { l -> l.count { it >= t.good }.toFloat() / l.size },
            deadZones = rssis.count { it < t.weak },
            avgLatency = points.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            avgLoss = points.mapNotNull { it.lossPct }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            avgThroughput = points.mapNotNull { it.throughputMbps }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            distinctBssids = points.flatMap { p -> p.observations.filter(filter::matches).map { it.bssid } }.distinct().size,
            estimatedPositions = points.count { it.source != "MANUAL" },
            cachedScans = points.count { !it.fresh },
        )
    }
}

data class ComparisonResult(
    val a: SurveySummary,
    val b: SurveySummary,
    val matched: List<Pair<PointMeasurement, PointMeasurement>>,
    val deltas: List<Float>,
    val unmatchedA: Int,
    val unmatchedB: Int,
    val warnings: List<String>,
)

object Comparison {
    /** Pairs each point in [a] with the nearest unused point in [b] within [radiusM]. */
    fun matchPoints(a: List<PointMeasurement>, b: List<PointMeasurement>, radiusM: Float): List<Pair<PointMeasurement, PointMeasurement>> {
        val candidates = mutableListOf<Triple<Int, Int, Float>>()
        for ((i, pa) in a.withIndex()) for ((j, pb) in b.withIndex()) {
            val d = hypot(pa.xM - pb.xM, pa.yM - pb.yM)
            if (d <= radiusM) candidates += Triple(i, j, d)
        }
        candidates.sortBy { it.third }
        val usedA = mutableSetOf<Int>(); val usedB = mutableSetOf<Int>()
        val out = mutableListOf<Pair<PointMeasurement, PointMeasurement>>()
        for ((i, j, _) in candidates) {
            if (i in usedA || j in usedB) continue
            usedA += i; usedB += j
            out += a[i] to b[j]
        }
        return out
    }

    fun compare(
        a: List<PointMeasurement>,
        b: List<PointMeasurement>,
        filter: SignalFilter,
        t: SignalThresholds,
        radiusM: Float = 1.0f,
    ): ComparisonResult {
        val matched = matchPoints(a, b, radiusM)
        val ma = matched.map { it.first }; val mb = matched.map { it.second }
        val deltas = matched.mapNotNull { (pa, pb) ->
            val ra = Measures.bestRssi(pa, filter); val rb = Measures.bestRssi(pb, filter)
            if (ra != null && rb != null) rb - ra else null
        }
        val warnings = mutableListOf<String>()
        if (matched.isEmpty()) warnings += "No survey points are within ${radiusM} m of each other — the surveys cannot be compared like-for-like."
        val ua = a.size - matched.size; val ub = b.size - matched.size
        if (ua > 0 || ub > 0) warnings += "$ua point(s) in A and $ub in B had no equivalent location and were excluded."
        val ssidA = SurveyStats.primarySsid(a); val ssidB = SurveyStats.primarySsid(b)
        if (ssidA != null && ssidB != null && ssidA != ssidB) warnings += "Phone was connected to different networks (\"$ssidA\" vs \"$ssidB\")."
        val cachedA = a.count { !it.fresh }; val cachedB = b.count { !it.fresh }
        if (cachedA + cachedB > 0) warnings += "Some readings came from cached scans (A: $cachedA, B: $cachedB) and may be older than their timestamp suggests."
        val latA = ma.count { it.latencyMs != null }; val latB = mb.count { it.latencyMs != null }
        if ((latA == 0) != (latB == 0)) warnings += "Latency was only measured in one survey; latency comparison is not possible."
        val ta = a.minOfOrNull { it.timestamp }; val tb = b.minOfOrNull { it.timestamp }
        if (ta != null && tb != null && kotlin.math.abs(ta - tb) > 7L * 24 * 3600 * 1000) warnings += "Surveys were taken more than a week apart; neighbouring networks and furniture may have changed."
        return ComparisonResult(
            SurveyStats.summarise(ma, filter, t), SurveyStats.summarise(mb, filter, t),
            matched, deltas, ua, ub, warnings,
        )
    }
}

object Insights {
    private fun roomName(p: PointMeasurement, rooms: List<RoomEntity>): String? = rooms.firstOrNull { it.kind == "ROOM" && it.contains(p.xU, p.yU) }?.name

    fun forSurvey(points: List<PointMeasurement>, rooms: List<RoomEntity>, t: SignalThresholds, ssid: String?): List<Insight> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf<Insight>()
        val filter = SignalFilter(ssid = ssid)
        val label = ssid?.let { "\"$it\"" } ?: "any network"

        // Weak coverage, grouped by room
        val weak = points.filter { (Measures.bestRssi(it, filter) ?: 0f) < t.weak }
        if (weak.isNotEmpty()) {
            val byRoom = weak.groupBy { roomName(it, rooms) ?: "Unlabelled area" }
            for ((room, list) in byRoom) {
                val worst = list.mapNotNull { Measures.bestRssi(it, filter) }.minOrNull()
                out += Insight(
                    Severity.PROBLEM, "Poor coverage: $room",
                    "${list.size} survey point(s) in $room measured $label below ${t.weak} dBm (weakest ${worst?.toInt()} dBm).",
                    "The area may be too far from an access point or behind attenuating walls. Consider moving an AP closer or adding one.",
                )
            }
        }
        // 5 GHz gaps where 2.4 GHz is present
        val only24 = points.filter { p ->
            val obs = p.observations.filter { ssid == null || it.ssid == ssid }
            obs.any { it.band == Band.B24 } && obs.none { it.band == Band.B5 || it.band == Band.B6 }
        }
        if (only24.isNotEmpty()) {
            val rooms5 = only24.mapNotNull { roomName(it, rooms) }.distinct()
            out += Insight(
                Severity.ADVISORY, "No 5/6 GHz reception in some places",
                "${only24.size} point(s)${if (rooms5.isNotEmpty()) " (${rooms5.joinToString()})" else ""} only detected $label on 2.4 GHz.",
                "Higher bands are attenuated more by walls; devices there will fall back to slower 2.4 GHz.",
            )
        }
        // Sticky client: connected AP much weaker than another AP of the same SSID
        val sticky = points.filter { p ->
            val conn = p.connectedBssid ?: return@filter false
            val connRssi = p.connectedRssi ?: p.observations.firstOrNull { it.bssid == conn }?.rssi ?: return@filter false
            val alt = p.observations.filter { it.ssid == p.connectedSsid && it.bssid != conn }.maxOfOrNull { it.rssi } ?: return@filter false
            alt - connRssi >= 10 && connRssi < t.good
        }
        if (sticky.isNotEmpty()) {
            out += Insight(
                Severity.ADVISORY, "Potential roaming issue",
                "At ${sticky.size} point(s) the phone stayed connected to an AP at least 10 dB weaker than another visible AP with the same network name.",
                "The phone may be 'sticky'. Roaming assistance (802.11k/v/r) or lower AP transmit power could help; this needs further testing.",
            )
        }
        // Roams observed during survey
        val roams = points.sortedBy { it.timestamp }.zipWithNext().count { (a, b) -> a.connectedBssid != null && b.connectedBssid != null && a.connectedBssid != b.connectedBssid }
        if (roams > 0) out += Insight(Severity.INFO, "Roaming observed", "The connected BSSID changed $roams time(s) during the survey.", "Normal in multi-AP networks. Use the Roaming monitor to check for interruptions.")
        // Latency/loss
        val highLat = points.filter { (it.latencyMs ?: 0f) > 50f }
        if (highLat.isNotEmpty()) out += Insight(Severity.ADVISORY, "High latency spots", "${highLat.size} point(s) measured gateway latency above 50 ms.", "Often caused by weak signal, retries or congestion near those locations.")
        val lossy = points.filter { (it.lossPct ?: 0f) >= 2f }
        if (lossy.isNotEmpty()) out += Insight(Severity.PROBLEM, "Packet loss observed", "${lossy.size} point(s) lost 2% or more probe packets.", "Loss usually indicates an unstable link at that location.")
        val cached = points.count { !it.fresh }
        if (cached > 0) out += Insight(Severity.INFO, "Some readings used cached scans", "$cached of ${points.size} point(s) used results Android had cached because of scan throttling.", "Disable 'Wi-Fi scan throttling' in Developer options for faster fresh scans.")
        if (out.none { it.severity == Severity.PROBLEM }) {
            val good = points.count { (Measures.bestRssi(it, filter) ?: -100f) >= t.good }
            out.add(0, Insight(Severity.INFO, "Coverage looks healthy", "$good of ${points.size} point(s) measured $label at ${t.good} dBm or better.", "No locations below the weak threshold were measured. Unsurveyed areas are not assessed."))
        }
        return out
    }

    fun forScan(results: List<ApObservation>): List<Insight> {
        val out = mutableListOf<Insight>()
        for (band in Band.entries) {
            val rep = ChannelAnalysis.analyse(results, band)
            if (rep.loads.none { it.apCount > 0 }) continue
            for (f in rep.findings) out += Insight(Severity.ADVISORY, "Possible channel congestion (${band.label})", f, "Inferred from scan results only — actual airtime use and non-Wi-Fi interference are not measured.")
            if (rep.recommended.isNotEmpty()) out += Insight(Severity.INFO, "Least-crowded ${band.label} channels", "Based on detected APs: ${rep.recommended.joinToString()}.", "A suggestion only; check router auto-channel and regulatory/DFS constraints.")
        }
        return out
    }
}
