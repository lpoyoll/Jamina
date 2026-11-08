package com.jamina

import androidx.compose.ui.geometry.Offset
import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.MaterialTable
import com.jamina.core.model.SignalLevel
import com.jamina.core.model.SignalThresholds
import com.jamina.diagnostics.LatencyStats
import com.jamina.diagnostics.PingParser
import com.jamina.floorplan.Geometry
import com.jamina.floorplan.WallSeg
import com.jamina.heatmap.Grid
import com.jamina.heatmap.HeatmapBuilder
import com.jamina.heatmap.HeatmapType
import com.jamina.heatmap.Idw
import com.jamina.heatmap.IdwOptions
import com.jamina.heatmap.Measures
import com.jamina.heatmap.ObsLite
import com.jamina.heatmap.PointMeasurement
import com.jamina.heatmap.Sample
import com.jamina.heatmap.SignalFilter
import com.jamina.heatmap.stats
import com.jamina.planner.Calibration
import com.jamina.planner.FloorGeom
import com.jamina.planner.OptFloor
import com.jamina.planner.OptimiseRequest
import com.jamina.planner.Optimizer
import com.jamina.planner.PlannedAp
import com.jamina.planner.Propagation
import com.jamina.planner.PropagationParams
import com.jamina.reports.Comparison
import com.jamina.reports.Insights
import com.jamina.reports.Severity
import com.jamina.ui.screens.WalkPositioner
import com.jamina.wifi.ApObservation
import com.jamina.wifi.ChannelAnalysis
import com.jamina.wifi.WifiMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WifiMathTest {
    @Test fun channels() {
        assertEquals(1, WifiMath.channelOf(2412))
        assertEquals(6, WifiMath.channelOf(2437))
        assertEquals(13, WifiMath.channelOf(2472))
        assertEquals(14, WifiMath.channelOf(2484))
        assertEquals(36, WifiMath.channelOf(5180))
        assertEquals(149, WifiMath.channelOf(5745))
        assertEquals(1, WifiMath.channelOf(5955))
        assertEquals(37, WifiMath.channelOf(6135))
        assertEquals(-1, WifiMath.channelOf(1000))
        assertEquals(5180, WifiMath.freqOf(36, Band.B5))
        assertEquals(2437, WifiMath.freqOf(6, Band.B24))
    }

    @Test fun bands() {
        assertEquals(Band.B24, Band.fromFreq(2462))
        assertEquals(Band.B5, Band.fromFreq(5500))
        assertEquals(Band.B6, Band.fromFreq(6415))
        assertNull(Band.fromFreq(900))
    }

    @Test fun overlap24() {
        val ch1 = WifiMath.span(2412, 0, 20)
        val ch6 = WifiMath.span(2437, 0, 20)
        val ch3 = WifiMath.span(2422, 0, 20)
        assertFalse(WifiMath.overlaps(ch1, ch6))
        assertTrue(WifiMath.overlaps(ch1, ch3))
        assertTrue(WifiMath.overlaps(ch3, ch6))
    }

    @Test fun bondedSpanUsesCentre() {
        // ch36 primary, 80 MHz centred on 5210
        val s = WifiMath.span(5180, 5210, 80)
        assertEquals(5170 to 5250, s)
        assertTrue(WifiMath.overlaps(s, WifiMath.span(5240, 0, 20)))
        assertFalse(WifiMath.overlaps(s, WifiMath.span(5260, 0, 20)))
    }

    @Test fun security() {
        assertEquals("WPA2", WifiMath.securityOf("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA3", WifiMath.securityOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2/WPA3", WifiMath.securityOf("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("Open", WifiMath.securityOf("[ESS]"))
        assertEquals("WPA2 Enterprise", WifiMath.securityOf("[RSN-EAP-CCMP][ESS]"))
    }

    @Test fun vendorAndRandomised() {
        assertEquals("Ubiquiti", WifiMath.vendorOf("24:5a:4c:11:22:33"))
        assertTrue(WifiMath.vendorOf("02:11:22:33:44:55").startsWith("Locally administered"))
        assertEquals("Unknown", WifiMath.vendorOf("00:00:01:00:00:00"))
    }
}

class ThresholdTest {
    @Test fun levels() {
        val t = SignalThresholds()
        assertEquals(SignalLevel.EXCELLENT, t.levelOf(-45))
        assertEquals(SignalLevel.VERY_GOOD, t.levelOf(-55))
        assertEquals(SignalLevel.GOOD, t.levelOf(-67))
        assertEquals(SignalLevel.FAIR, t.levelOf(-69))
        assertEquals(SignalLevel.WEAK, t.levelOf(-75))
        assertEquals(SignalLevel.POOR, t.levelOf(-76))
        assertTrue(t.isValid())
        assertFalse(SignalThresholds(-60, -50).isValid())
    }

    @Test fun materialTableRoundTrip() {
        val t = MaterialTable().with(Material.BRICK, Band.B5, 15f)
        val d = MaterialTable.decode(t.encode())
        assertEquals(15f, d.loss(Material.BRICK, Band.B5), 0.001f)
        assertEquals(Material.BRICK.loss24, d.loss(Material.BRICK, Band.B24), 0.001f)
        assertEquals(Material.CONCRETE.loss5, d.loss(Material.CONCRETE, Band.B5), 0.001f)
    }
}

class GeometryTest {
    @Test fun intersection() {
        assertTrue(Geometry.segmentsIntersect(0f, 0f, 10f, 0f, 5f, -5f, 5f, 5f))
        assertFalse(Geometry.segmentsIntersect(0f, 0f, 4f, 0f, 5f, -5f, 5f, 5f))
        assertEquals(5f, Geometry.distToSegment(5f, 5f, 0f, 0f, 10f, 0f), 1e-4f)
    }

    @Test fun pathLossCountsWallsAndOpenings() {
        val table = MaterialTable()
        val walls = listOf(
            WallSeg(5f, -5f, 5f, 5f, Material.BRICK),
            WallSeg(8f, -5f, 8f, 5f, Material.PLASTERBOARD),
        )
        val loss = Geometry.pathLoss(0f, 0f, 10f, 0f, walls, Band.B5, table)
        assertEquals(Material.BRICK.loss5 + Material.PLASTERBOARD.loss5, loss, 1e-4f)
        // a door in the brick wall replaces its loss
        val withDoor = walls + WallSeg(5f, -0.5f, 5f, 0.5f, Material.WOOD_DOOR, opening = true)
        val l2 = Geometry.pathLoss(0f, 0f, 10f, 0f, withDoor, Band.B5, table)
        assertEquals(Material.WOOD_DOOR.loss5 + Material.PLASTERBOARD.loss5, l2, 1e-4f)
    }
}

class IdwTest {
    @Test fun exactAtSamplesAndBetweenValues() {
        val samples = listOf(Sample(1f, 1f, -40f), Sample(9f, 1f, -80f))
        val g = Idw.interpolate(samples, Grid(10, 2, 10f, 2f), IdwOptions(power = 2f, radiusM = 20f))
        // cell centred at (0.5,0.5) is near sample 1
        val near = g[0, 0]
        assertTrue(near > -50f)
        val mid = g.valueAt(5f, 1f)
        assertTrue("mid=$mid", mid < -45f && mid > -75f)
        for (v in g.values) assertTrue(v <= -40f + 1e-3f && v >= -80f - 1e-3f)
    }

    @Test fun symmetricMidpointIsAverage() {
        val samples = listOf(Sample(0f, 0.5f, -40f), Sample(10f, 0.5f, -80f))
        val g = Idw.interpolate(samples, Grid(10, 1, 10f, 1f), IdwOptions(radiusM = 50f))
        // cells 4 and 5 are symmetric about x=5
        assertEquals(-60f, (g[4, 0] + g[5, 0]) / 2f, 0.01f)
    }

    @Test fun noExtrapolationBeyondRadius() {
        val g = Idw.interpolate(listOf(Sample(1f, 1f, -50f)), Grid(20, 20, 20f, 20f), IdwOptions(radiusM = 3f))
        assertTrue(g.valueAt(1f, 1f) == -50f)
        assertTrue(g.valueAt(18f, 18f).isNaN())
        assertEquals(0f, g.confidence[g.idx(19, 19)], 0f)
        val ext = Idw.interpolate(listOf(Sample(1f, 1f, -50f)), Grid(20, 20, 20f, 20f), IdwOptions(radiusM = 3f, extrapolate = true))
        assertEquals(-50f, ext.valueAt(18f, 18f), 1e-4f)
    }

    @Test fun wallAwareReducesInfluenceAcrossWall() {
        val samples = listOf(Sample(2f, 5f, -40f), Sample(8f, 5f, -80f))
        val wall = listOf(WallSeg(6f, 0f, 6f, 10f, Material.BRICK))
        val plain = Idw.interpolate(samples, Grid(10, 10, 10f, 10f), IdwOptions(radiusM = 20f))
        val aware = Idw.interpolate(samples, Grid(10, 10, 10f, 10f), IdwOptions(radiusM = 20f, walls = wall))
        // cell at x=6.5 is behind the wall from the strong sample → should be weaker with wall-awareness
        assertTrue(aware.valueAt(6.5f, 5f) < plain.valueAt(6.5f, 5f))
    }

    @Test fun statsFraction() {
        val g = Grid(2, 1, 2f, 1f)
        g.values[0] = -60f; g.values[1] = -80f
        val s = g.stats(-67f)
        assertEquals(0.5f, s.fractionAbove, 1e-6f)
        assertEquals(-70f, s.mean, 1e-4f)
    }
}

private fun pm(id: String, x: Float, y: Float, obs: List<ObsLite>, conn: String? = null, connRssi: Int? = null, ssid: String? = "Home", t: Long = 0) =
    PointMeasurement(id, "s", x, y, x, y, t, "MANUAL", 0.5f, ssid, conn, connRssi, null, null, null, true, obs)

class MeasuresTest {
    private val obs = listOf(
        ObsLite("aa", "Home", -50, 5180, 36, 80, 5210),
        ObsLite("bb", "Home", -65, 2437, 6, 20),
        ObsLite("cc", "Neighbour", -70, 5200, 40, 20),
        ObsLite("dd", "Neighbour", -60, 2412, 1, 20),
    )

    @Test fun bestAndFilters() {
        val p = pm("p", 0f, 0f, obs)
        assertEquals(-50f, Measures.bestRssi(p, SignalFilter()))
        assertEquals(-65f, Measures.bestRssi(p, SignalFilter(band = Band.B24, ssid = "Home")))
        assertEquals(Measures.NOT_DETECTED, Measures.bestRssi(p, SignalFilter(bssid = "zz")))
        assertNull(Measures.bestRssi(pm("e", 0f, 0f, emptyList()), SignalFilter()))
    }

    @Test fun coChannel() {
        val p = pm("p", 0f, 0f, obs)
        // Best Home 5 GHz AP spans 5170-5250; cc on ch40 overlaps
        assertEquals(1f, Measures.coChannelCount(p, SignalFilter(ssid = "Home"), -82))
    }

    @Test fun heatmapBuilderSignal() {
        val pts = listOf(pm("1", 1f, 1f, listOf(ObsLite("aa", "Home", -45, 5180, 36, 80))), pm("2", 9f, 9f, listOf(ObsLite("aa", "Home", -85, 5180, 36, 80))))
        val r = HeatmapBuilder.build(HeatmapType.SIGNAL, pts, SignalFilter(), 10f, 10f, IdwOptions(radiusM = 30f), 20)
        assertNotNull(r.grid)
        assertEquals(2, r.sampleCount)
        val r2 = HeatmapBuilder.build(HeatmapType.LATENCY, pts, SignalFilter(), 10f, 10f, IdwOptions(), 20)
        assertNotNull(r2.message)
    }

    @Test fun roamingMarkers() {
        val pts = listOf(
            pm("1", 0f, 0f, emptyList(), conn = "aa", t = 1),
            pm("2", 5f, 0f, emptyList(), conn = "aa", t = 2),
            pm("3", 9f, 0f, emptyList(), conn = "bb", t = 3),
        )
        val r = HeatmapBuilder.build(HeatmapType.ROAMING, pts, SignalFilter(), 10f, 2f, IdwOptions(radiusM = 20f), 10)
        assertEquals(1, r.roamMarkers.size)
        assertEquals(9f to 0f, r.roamMarkers.first())
        assertEquals(2, r.categoryLabels.size)
    }
}

class PropagationTest {
    private val floor0 = FloorGeom(0, 0f, emptyList(), Material.TIMBER_FLOOR, 20f, 20f)
    private val floors = mapOf(0 to floor0)
    private val params = PropagationParams(exponent = 2.0f)
    private val ap = PlannedAp("a", 0f, 0f, 0, 1.0f, 20f, 0f, Band.B5, 100)

    @Test fun freeSpaceMatchesFormula() {
        // receiver at same height, 10 m away, n = 2 → FSPL(1m) + 20 dB
        val f = Propagation.freqOf(ap)
        val expected = 20f - (Propagation.fspl1m(f) + 20f)
        assertEquals(expected, Propagation.rssi(ap, 10f, 0f, floor0, floors, params), 0.01f)
    }

    @Test fun decreasesWithDistanceAndWalls() {
        val near = Propagation.rssi(ap, 3f, 0f, floor0, floors, params)
        val far = Propagation.rssi(ap, 12f, 0f, floor0, floors, params)
        assertTrue(near > far)
        val walled = floor0.copy(walls = listOf(WallSeg(5f, -5f, 5f, 5f, Material.BRICK)))
        val withWall = Propagation.rssi(ap, 12f, 0f, walled, mapOf(0 to walled), params)
        assertEquals(far - Material.BRICK.loss5, withWall, 0.01f)
    }

    @Test fun floorLossApplied() {
        val upstairs = FloorGeom(1, 2.8f, emptyList(), Material.CONCRETE_SLAB, 20f, 20f)
        val fl = mapOf(0 to floor0, 1 to upstairs)
        val same = Propagation.rssi(ap, 5f, 0f, floor0, fl, params)
        val above = Propagation.rssi(ap, 5f, 0f, upstairs, fl, params)
        assertTrue(same - above >= Material.CONCRETE_SLAB.loss5)
    }

    @Test fun twoFourTravelsFurther() {
        val ap24 = ap.copy(band = Band.B24, channel = 6)
        assertTrue(Propagation.rssi(ap24, 10f, 0f, floor0, floors, params) > Propagation.rssi(ap, 10f, 0f, floor0, floors, params))
    }

    @Test fun directionalAntenna() {
        val d = ap.copy(directional = true, azimuthDeg = 0f, beamwidthDeg = 60f, gainDbi = 8f)
        assertTrue(Propagation.rssi(d, 10f, 0f, floor0, floors, params) > Propagation.rssi(d, -10f, 0f, floor0, floors, params))
    }

    @Test fun predictGridBestServer() {
        val ap2 = ap.copy(id = "b", xM = 20f, yM = 20f, channel = 36)
        val pg = Propagation.predict(listOf(ap, ap2), floor0, floors, 20f, 20f, 20, params)
        assertEquals(0, pg.bestAp[pg.best.idx(0, 0)])
        assertEquals(1, pg.bestAp[pg.best.idx(19, 19)])
    }
}

class OptimizerTest {
    @Test fun twoApsCoverLongBuildingBetterThanOne() {
        val geom = FloorGeom(0, 0f, emptyList(), Material.TIMBER_FLOOR, 40f, 8f)
        val f = OptFloor(geom, { _, _ -> 1f }, { _, _ -> true })
        val params = PropagationParams()
        val one = Optimizer.optimise(listOf(f), emptyList(), params, OptimiseRequest(apCount = 1, minRssi = -55f, cols = 30))
        val two = Optimizer.optimise(listOf(f), emptyList(), params, OptimiseRequest(apCount = 2, minRssi = -55f, cols = 30))
        assertEquals(2, two.suggestions.size)
        assertTrue(two.coverageAfter >= one.coverageAfter)
        // the two APs should be spread along the long axis
        val xs = two.suggestions.map { it.xM }.sorted()
        assertTrue(xs[1] - xs[0] > 10f)
        assertEquals(0f, two.coverageBefore, 0f)
    }

    @Test fun respectsNoApZones() {
        val geom = FloorGeom(0, 0f, emptyList(), Material.TIMBER_FLOOR, 20f, 10f)
        val f = OptFloor(geom, { _, _ -> 1f }, { x, _ -> x > 10f })
        val r = Optimizer.optimise(listOf(f), emptyList(), PropagationParams(), OptimiseRequest(apCount = 1, cols = 20))
        assertTrue(r.suggestions.all { it.xM > 10f })
    }
}

class CalibrationTest {
    @Test fun recoversExponentAndOffset() {
        val dists = listOf(2f, 4f, 6f, 9f, 13f, 18f)
        fun model(n: Float, d: Float) = 20f - 47f - 10f * n * kotlin.math.log10(d)
        val measured = dists.map { model(3.0f, it) - 4f }
        val r = Calibration.fit(measured, 2.0f, 0f) { n -> dists.map { model(n, it) } }!!
        assertEquals(3.0f, r.exponent, 0.06f)
        assertEquals(-4f, r.offsetDb, 0.5f)
        assertTrue(r.rmseAfter < r.rmseBefore)
        assertTrue(r.rmseAfter < 0.5f)
    }

    @Test fun needsEnoughPoints() {
        assertNull(Calibration.fit(listOf(-50f, -60f), 2f, 0f) { listOf(-50f, -60f) })
    }
}

class ComparisonAndInsightTest {
    @Test fun matchesOnlyNearbyPoints() {
        val a = listOf(pm("a1", 0f, 0f, listOf(ObsLite("x", "Home", -70, 5180, 36, 80))), pm("a2", 10f, 0f, listOf(ObsLite("x", "Home", -80, 5180, 36, 80))))
        val b = listOf(pm("b1", 0.4f, 0f, listOf(ObsLite("y", "Home", -60, 5180, 36, 80))), pm("b2", 30f, 0f, listOf(ObsLite("y", "Home", -50, 5180, 36, 80))))
        val c = Comparison.compare(a, b, SignalFilter(ssid = "Home"), SignalThresholds())
        assertEquals(1, c.matched.size)
        assertEquals(10f, c.deltas.single(), 1e-4f)
        assertEquals(1, c.unmatchedA)
        assertTrue(c.warnings.any { it.contains("no equivalent location") })
    }

    @Test fun insightsFlagWeakAndSticky() {
        val pts = listOf(
            pm("1", 0f, 0f, listOf(ObsLite("aa", "Home", -82, 5180, 36, 80)), conn = "aa", connRssi = -82),
            pm("2", 5f, 0f, listOf(ObsLite("aa", "Home", -74, 5180, 36, 80), ObsLite("bb", "Home", -55, 5500, 100, 80)), conn = "aa", connRssi = -74),
        )
        val ins = Insights.forSurvey(pts, emptyList(), SignalThresholds(), "Home")
        assertTrue(ins.any { it.severity == Severity.PROBLEM && it.title.startsWith("Poor coverage") })
        assertTrue(ins.any { it.title == "Potential roaming issue" })
    }
}

class ChannelAnalysisTest {
    private fun ap(b: String, ch: Int, rssi: Int, band: Band = Band.B24) = ApObservation(
        b, "n$b", rssi, WifiMath.freqOf(ch, band), ch, band, 20, 0, "", "WPA2", "", "", 0,
    )

    @Test fun recommendsQuietStandardChannel() {
        val res = listOf(ap("1", 1, -50), ap("2", 1, -55), ap("3", 6, -60), ap("4", 3, -70))
        val r = ChannelAnalysis.analyse(res, Band.B24)
        assertEquals(11, r.recommended.first())
        assertTrue(r.findings.any { it.contains("non-standard") })
    }
}

class PingParserTest {
    private val out = """
        PING 192.168.1.1 (192.168.1.1) 56(84) bytes of data.
        64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.42 ms
        64 bytes from 192.168.1.1: icmp_seq=2 ttl=64 time=5.10 ms
        64 bytes from 192.168.1.1: icmp_seq=4 ttl=64 time=4.00 ms

        --- 192.168.1.1 ping statistics ---
        4 packets transmitted, 3 received, 25% packet loss, time 3004ms
    """.trimIndent()

    @Test fun parses() {
        assertEquals(listOf(3.42f, 5.10f, 4.00f), PingParser.rtts(out))
        assertEquals(25f, PingParser.loss(out))
        assertEquals(4 to 3, PingParser.counts(out))
        val s = LatencyStats("gw", "ICMP", 4, 3, PingParser.rtts(out))
        assertEquals(25f, s.lossPct, 1e-4f)
        assertEquals((1.68f + 1.10f) / 2f, s.jitter!!, 1e-3f)
    }
}

class WalkPositionerTest {
    @Test fun interpolatesBetweenCheckpoints() {
        val route = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f))
        val times = listOf(0L, 10_000L, 20_000L)
        val r = WalkPositioner.assign(route, times, listOf(5_000L, 15_000L, 25_000L), 0.05f)
        assertEquals(50f, r[0]!!.first.x, 1e-3f)
        assertEquals(100f, r[1]!!.first.x, 1e-3f)
        assertEquals(50f, r[1]!!.first.y, 1e-3f)
        assertNull(r[2]) // after last confirmed checkpoint: not positioned
        assertTrue(abs(r[0]!!.second) <= 5f)
    }
}
