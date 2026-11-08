package com.jamina.wifi

import com.jamina.core.model.Band

data class ChannelLoad(val channel: Int, val apCount: Int, val score: Float, val strongest: Int?)

data class ChannelReport(
    val band: Band,
    val loads: List<ChannelLoad>,
    val recommended: List<Int>,
    val findings: List<String>,
)

/**
 * Infers *potential* contention from beacons seen in scans. It does not measure airtime
 * utilisation or non-Wi-Fi interference, which Android scans cannot observe.
 */
object ChannelAnalysis {
    private val candidates24 = (1..13).toList()
    private val preferred24 = listOf(1, 6, 11)
    private val candidates5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165)
    private val candidates6 = (1..233 step 4).toList()

    /** Weight an AP by how loud it is: -50 dBm ≈ 1.0, -90 dBm ≈ 0. */
    fun weight(rssi: Int): Float = ((rssi + 90f) / 40f).coerceIn(0f, 1.2f)

    fun analyse(results: List<ApObservation>, band: Band, minRssi: Int = -88): ChannelReport {
        val aps = results.filter { it.band == band && it.rssi >= minRssi }
        val channels = when (band) {
            Band.B24 -> candidates24
            Band.B5 -> candidates5
            Band.B6 -> candidates6
        }
        val loads = channels.map { ch ->
            val f = WifiMath.freqOf(ch, band)
            val span = WifiMath.span(f, 0, 20)
            val overlapping = aps.filter { WifiMath.overlaps(it.span(), span) }
            ChannelLoad(ch, overlapping.size, overlapping.sumOf { weight(it.rssi).toDouble() }.toFloat(), overlapping.maxOfOrNull { it.rssi })
        }
        val pool = if (band == Band.B24) loads.filter { it.channel in preferred24 } else loads
        val recommended = pool.sortedWith(compareBy({ it.score }, { it.channel })).take(3).map { it.channel }

        val findings = mutableListOf<String>()
        if (aps.isEmpty()) {
            findings += "No ${band.label} access points detected in the latest scan."
        } else {
            val byPrimary = aps.groupBy { it.channel }
            for ((ch, list) in byPrimary.entries.sortedByDescending { it.value.size }) {
                val ssids = list.map { it.displaySsid }.distinct()
                if (list.size >= 3) {
                    findings += "Channel $ch: ${list.size} BSSIDs share this primary channel (${ssids.take(4).joinToString()})."
                }
            }
            if (band == Band.B24) {
                val offGrid = aps.filter { it.channel !in preferred24 }
                if (offGrid.isNotEmpty()) {
                    findings += "${offGrid.size} AP(s) on non-standard 2.4 GHz channels (${offGrid.map { it.channel }.distinct().sorted().joinToString()}) overlap two of 1/6/11 — poor channel planning nearby."
                }
                val wide = aps.filter { it.widthMhz >= 40 }
                if (wide.isNotEmpty()) findings += "${wide.size} AP(s) use 40 MHz on 2.4 GHz, which overlaps most of the band."
            }
            val loud = aps.filter { it.rssi >= -65 }.groupBy { it.ssid }.keys.size
            if (loud >= 4) findings += "$loud different networks are heard above -65 dBm; neighbouring networks are close."
        }
        return ChannelReport(band, loads, recommended, findings)
    }
}
