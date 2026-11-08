package com.jamina.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.RoomEntity
import com.jamina.core.db.VirtualApEntity
import com.jamina.core.db.WallEntity
import com.jamina.core.db.newId
import com.jamina.core.model.Band
import com.jamina.core.settings.AppSettings
import com.jamina.heatmap.HeatmapRenderer
import com.jamina.heatmap.Measures
import com.jamina.heatmap.SignalFilter
import com.jamina.heatmap.stats
import com.jamina.planner.ApCatalog
import com.jamina.planner.Calibration
import com.jamina.planner.CalibrationResult
import com.jamina.planner.FloorGeom
import com.jamina.planner.OptFloor
import com.jamina.planner.OptimiseRequest
import com.jamina.planner.OptimiseResult
import com.jamina.planner.Optimizer
import com.jamina.planner.PlannedAp
import com.jamina.planner.Propagation
import com.jamina.planner.PropagationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

enum class PredictView(val label: String) { SIGNAL("Predicted signal"), BEST_AP("Best AP"), CO_CHANNEL("Co-channel overlap") }

data class PlannerUi(
    val floorId: String? = null,
    val scenario: String = "Default",
    val view: PredictView = PredictView.SIGNAL,
    val band: Band = Band.B5,
    val addMode: Boolean = false,
    val selectedApId: String? = null,
    val message: String? = null,
)

data class PredictionOut(
    val computing: Boolean = false,
    val image: ImageBitmap? = null,
    val coverage: Float? = null,
    val minRssi: Float? = null,
    val apLabels: List<String> = emptyList(),
    val scenarioCoverage: Map<String, Float> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlannerViewModel(private val c: AppContainer, val projectId: String) : ViewModel() {
    private val dao = c.dao
    val settings = c.settings.settings
    val floors = dao.floors(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allWalls = dao.wallsForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allRooms = dao.roomsForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val dbAps = dao.apsForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions = dao.sessionsForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val movingAp = MutableStateFlow<VirtualApEntity?>(null)
    val aps: StateFlow<List<VirtualApEntity>> = combine(dbAps, movingAp) { a, m -> if (m == null) a else a.map { if (it.id == m.id) m else it } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ui = MutableStateFlow(PlannerUi())
    val out = MutableStateFlow(PredictionOut())
    val optimising = MutableStateFlow(false)
    val optResult = MutableStateFlow<Pair<OptimiseRequest, OptimiseResult>?>(null)
    val calResult = MutableStateFlow<CalibrationResult?>(null)

    val floor: StateFlow<FloorEntity?> = combine(floors, ui) { fl, u -> fl.firstOrNull { it.id == u.floorId } ?: fl.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val planImage: StateFlow<ImageBitmap?> = floor.map { it?.imagePath }.distinctUntilChanged().mapLatest { p ->
        withContext(Dispatchers.IO) { c.repo.loadPlanBitmap(p)?.asImageBitmap() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val scenarios: StateFlow<List<String>> = combine(dbAps, ui) { a, u -> (listOf("Default") + a.map { it.scenario } + u.scenario).distinct() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf("Default"))

    init {
        viewModelScope.launch {
            combine(floor, allWalls, aps, ui, settings) { f, w, a, u, s -> Inputs(f, w, a, u, s) }
                .debounce(80)
                .collectLatest { compute(it) }
        }
    }

    private data class Inputs(val f: FloorEntity?, val walls: List<WallEntity>, val aps: List<VirtualApEntity>, val ui: PlannerUi, val s: AppSettings)

    fun params(s: AppSettings = settings.value) = PropagationParams(s.pathLossExponent, s.modelOffsetDb, s.receiverHeightM, s.materialTable)

    private fun geoms(walls: List<WallEntity>): Map<Int, FloorGeom> = c.repo.floorGeoms(floors.value, walls.groupBy { it.floorId })

    private fun planned(aps: List<VirtualApEntity>, scenario: String): List<PlannedAp> {
        val byId = floors.value.associateBy { it.id }
        return aps.filter { it.scenario == scenario && it.enabled }.mapNotNull { a -> byId[a.floorId]?.let { c.repo.plannedAp(a, it) } }
    }

    /** Building-frame coverage weight for a floor (rooms if drawn, else wall bounds, else whole plan). */
    private fun weightFn(f: FloorEntity, rooms: List<RoomEntity>, walls: List<WallEntity>): (Float, Float) -> Float {
        val fr = rooms.filter { it.floorId == f.id && it.kind == "ROOM" }
        val fw = walls.filter { it.floorId == f.id }
        val mpu = f.metersPerUnit
        val bx0 = fw.minOfOrNull { minOf(it.x1, it.x2) }; val bx1 = fw.maxOfOrNull { maxOf(it.x1, it.x2) }
        val by0 = fw.minOfOrNull { minOf(it.y1, it.y2) }; val by1 = fw.maxOfOrNull { maxOf(it.y1, it.y2) }
        return { x, y ->
            val ux = (x - f.offsetXm) / mpu; val uy = (y - f.offsetYm) / mpu
            when {
                fr.isNotEmpty() -> fr.firstOrNull { it.contains(ux, uy) }?.let { if (it.priority) 2f else 1f } ?: 0f
                bx0 != null && bx1 != null && by0 != null && by1 != null && bx1 - bx0 > 1 -> if (ux in bx0..bx1 && uy in by0!!..by1!!) 1f else 0f
                else -> if (ux in 0f..f.widthUnits && uy in 0f..f.heightUnits) 1f else 0f
            }
        }
    }

    private suspend fun compute(i: Inputs) {
        val f = i.f ?: return
        out.update { it.copy(computing = true) }
        val res = withContext(Dispatchers.Default) {
            val g = geoms(i.walls)
            val rx = g[f.level] ?: return@withContext null
            val list = planned(i.aps, i.ui.scenario)
            val params = params(i.s)
            val pg = Propagation.predict(list, rx, g, f.widthM, f.heightM, 110, params, i.ui.band)
            val img = when (i.ui.view) {
                PredictView.SIGNAL -> HeatmapRenderer.renderSignalGrid(pg.best, i.s.thresholds)
                PredictView.BEST_AP -> HeatmapRenderer.renderCategories(pg.bestAp, pg.best.cols, pg.best.rows)
                PredictView.CO_CHANNEL -> HeatmapRenderer.renderCounts(pg.coChannel, pg.best.cols, pg.best.rows)
            }
            val w = weightFn(f, allRooms.value, i.walls)
            val st = pg.best.stats(i.s.thresholds.good.toFloat(), { x, y -> w(x + f.offsetXm, y + f.offsetYm) > 0f })
            // Scenario comparison over all floors (coarse)
            val scen = (listOf("Default") + i.aps.map { it.scenario } + i.ui.scenario).distinct().associateWith { sc ->
                val l = planned(i.aps, sc)
                var cov = 0; var tot = 0
                for (fl in floors.value) {
                    val geom = g[fl.level] ?: continue
                    val p = Propagation.predict(l, geom, g, fl.widthM, fl.heightM, 36, params, i.ui.band)
                    val wf = weightFn(fl, allRooms.value, i.walls)
                    for (r in 0 until p.best.rows) for (cc in 0 until p.best.cols) {
                        if (wf(fl.offsetXm + p.best.cx(cc), fl.offsetYm + p.best.cy(r)) <= 0f) continue
                        tot++
                        if (p.best[cc, r] >= i.s.thresholds.good) cov++
                    }
                }
                if (tot == 0) 0f else cov.toFloat() / tot
            }
            val labels = list.map { p -> i.aps.firstOrNull { it.id == p.id }?.name ?: p.id }
            PredictionOut(false, img.asImageBitmap(), if (list.isEmpty()) null else st.fractionAbove, if (list.isEmpty()) null else st.min, labels, scen)
        }
        out.value = res ?: PredictionOut()
    }

    // ---------------- UI actions
    fun selectFloor(id: String) = ui.update { it.copy(floorId = id, selectedApId = null) }
    fun setScenario(s: String) = ui.update { it.copy(scenario = s, selectedApId = null) }
    fun setView(v: PredictView) = ui.update { it.copy(view = v) }
    fun setBand(b: Band) = ui.update { it.copy(band = b) }
    fun setAddMode(on: Boolean) = ui.update { it.copy(addMode = on) }
    fun clearMessage() = ui.update { it.copy(message = null) }

    fun newScenario(name: String, copyCurrent: Boolean) {
        val n = name.ifBlank { "Scenario ${scenarios.value.size + 1}" }
        viewModelScope.launch {
            if (copyCurrent) dao.upsertAps(dbAps.value.filter { it.scenario == ui.value.scenario }.map { it.copy(id = newId(), scenario = n) })
            ui.update { it.copy(scenario = n, selectedApId = null) }
        }
    }

    fun deleteScenario() {
        val sc = ui.value.scenario
        viewModelScope.launch {
            dbAps.value.filter { it.scenario == sc }.forEach { dao.deleteAp(it.id) }
            ui.update { it.copy(scenario = "Default", selectedApId = null) }
        }
    }

    fun onTap(p: Offset) {
        val f = floor.value ?: return
        val u = ui.value
        val near = aps.value.filter { it.floorId == f.id && it.scenario == u.scenario }
            .minByOrNull { hypot(it.x - p.x, it.y - p.y) }
            ?.takeIf { hypot(it.x - p.x, it.y - p.y) * f.metersPerUnit < 0.8f }
        if (near != null) { ui.update { it.copy(selectedApId = near.id) }; return }
        if (u.addMode) {
            val prof = ApCatalog.profiles.first()
            val band = u.band
            val n = aps.value.count { it.scenario == u.scenario } + 1
            val ap = VirtualApEntity(
                newId(), f.id, u.scenario, "AP $n", p.x, p.y, band = band.name,
                channel = Optimizer.channelPlan(band, n - 1), widthMhz = if (band == Band.B24) 20 else 80,
                txPowerDbm = when (band) { Band.B24 -> prof.tx24; Band.B5 -> prof.tx5; Band.B6 -> prof.tx6 ?: prof.tx5 },
                antennaGainDbi = when (band) { Band.B24 -> prof.gain24; Band.B5 -> prof.gain5; Band.B6 -> prof.gain6 ?: prof.gain5 },
                vendor = prof.vendor, model = prof.model, standard = prof.standard,
            )
            viewModelScope.launch { dao.upsertAp(ap) }
            ui.update { it.copy(selectedApId = ap.id, addMode = false) }
        } else ui.update { it.copy(selectedApId = null) }
    }

    fun onDragStart(p: Offset): Boolean {
        val f = floor.value ?: return false
        val a = aps.value.filter { it.floorId == f.id && it.scenario == ui.value.scenario }
            .minByOrNull { hypot(it.x - p.x, it.y - p.y) }
            ?.takeIf { hypot(it.x - p.x, it.y - p.y) * f.metersPerUnit < 1.0f } ?: return false
        movingAp.value = a
        ui.update { it.copy(selectedApId = a.id) }
        return true
    }

    fun onDrag(p: Offset) { movingAp.value = movingAp.value?.copy(x = p.x, y = p.y) }
    fun onDragEnd() { val a = movingAp.value ?: return; viewModelScope.launch { dao.upsertAp(a); movingAp.value = null } }

    fun updateAp(a: VirtualApEntity) { viewModelScope.launch { dao.upsertAp(a) } }
    fun deleteAp(id: String) { viewModelScope.launch { dao.deleteAp(id) }; ui.update { it.copy(selectedApId = null) } }

    // ---------------- Optimisation
    fun optimise(req: OptimiseRequest, replaceExisting: Boolean) {
        if (optimising.value) return
        optimising.value = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                val g = geoms(allWalls.value)
                val ofl = floors.value.mapNotNull { f ->
                    val geom = g[f.level] ?: return@mapNotNull null
                    val w = weightFn(f, allRooms.value, allWalls.value)
                    val noAp = allRooms.value.filter { it.floorId == f.id && it.noApZone }
                    OptFloor(geom, w) { x, y ->
                        val ux = (x - f.offsetXm) / f.metersPerUnit; val uy = (y - f.offsetYm) / f.metersPerUnit
                        noAp.none { it.contains(ux, uy) }
                    }
                }
                val existing = if (replaceExisting) emptyList() else planned(aps.value, ui.value.scenario)
                Optimizer.optimise(ofl, existing, params(), req)
            }
            optimising.value = false
            optResult.value = req to r
        }
    }

    fun dismissOptimisation() { optResult.value = null }

    fun applyOptimisation(replaceExisting: Boolean) {
        val (req, r) = optResult.value ?: return
        val base = ui.value.scenario
        val name = "Optimised ${scenarios.value.count { it.startsWith("Optimised") } + 1}"
        viewModelScope.launch {
            if (!replaceExisting) dao.upsertAps(dbAps.value.filter { it.scenario == base }.map { it.copy(id = newId(), scenario = name) })
            val byLevel = floors.value.associateBy { it.level }
            r.suggestions.forEachIndexed { i, s ->
                val f = byLevel[s.level] ?: return@forEachIndexed
                dao.upsertAp(
                    VirtualApEntity(
                        newId(), f.id, name, "Suggested ${i + 1}", (s.xM - f.offsetXm) / f.metersPerUnit, (s.yM - f.offsetYm) / f.metersPerUnit,
                        mountHeightM = req.mountHeightM, band = req.band.name, channel = s.channel, widthMhz = req.widthMhz,
                        txPowerDbm = req.txPowerDbm, antennaGainDbi = req.gainDbi,
                    ),
                )
            }
            optResult.value = null
            ui.update { it.copy(scenario = name, message = "Applied as scenario \"$name\". Compare it with \"$base\" below; drag APs to adjust.") }
        }
    }

    fun locationName(level: Int, xM: Float, yM: Float): String {
        val f = floors.value.firstOrNull { it.level == level } ?: return "Level $level"
        val ux = (xM - f.offsetXm) / f.metersPerUnit; val uy = (yM - f.offsetYm) / f.metersPerUnit
        val room = allRooms.value.firstOrNull { it.floorId == f.id && it.contains(ux, uy) }
        return "${f.name}: ${room?.name ?: "(%.1f m, %.1f m)".format(xM - f.offsetXm, yM - f.offsetYm)}"
    }

    // ---------------- Calibration
    fun calibrate(sessionId: String, ssid: String?) {
        viewModelScope.launch {
            val session = sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            val f = floors.value.firstOrNull { it.id == session.floorId } ?: return@launch
            val pts = c.repo.loadMeasurements(listOf(sessionId), f)
            val band = ui.value.band
            val pairs = pts.mapNotNull { p ->
                val m = Measures.bestObs(p, SignalFilter(ssid = ssid, band = band))?.rssi ?: return@mapNotNull null
                p to m.toFloat()
            }
            val list = planned(aps.value, ui.value.scenario).filter { it.band == band }
            if (list.isEmpty() || pairs.size < 3) {
                ui.update { it.copy(message = "Calibration needs at least 3 survey points that heard ${ssid ?: "the network"} on ${band.label}, and planned APs on that band.") }
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                val g = geoms(allWalls.value)
                val rx = g[f.level] ?: return@withContext null
                val s = settings.value
                Calibration.fit(pairs.map { it.second }, s.pathLossExponent, s.modelOffsetDb) { n ->
                    val prm = params(s).copy(exponent = n, offsetDb = 0f)
                    pairs.map { (p, _) -> list.maxOf { ap -> Propagation.rssi(ap, p.xM + f.offsetXm, p.yM + f.offsetYm, rx, g, prm) } }
                }
            }
            calResult.value = result
        }
    }

    fun applyCalibration() {
        val r = calResult.value ?: return
        viewModelScope.launch { c.settings.update { it.copy(pathLossExponent = r.exponent, modelOffsetDb = r.offsetDb) } }
        calResult.value = null
        ui.update { it.copy(message = "Model calibrated: exponent ${"%.2f".format(r.exponent)}, offset ${"%.1f".format(r.offsetDb)} dB.") }
    }

    fun dismissCalibration() { calResult.value = null }
    fun resetModel() { viewModelScope.launch { c.settings.update { it.copy(pathLossExponent = 2.2f, modelOffsetDb = 0f) } } }
    fun setModel(exp: Float?, offset: Float?, rxH: Float?) {
        viewModelScope.launch { c.settings.update { s -> s.copy(pathLossExponent = exp ?: s.pathLossExponent, modelOffsetDb = offset ?: s.modelOffsetDb, receiverHeightM = rxH ?: s.receiverHeightM) } }
    }
}
