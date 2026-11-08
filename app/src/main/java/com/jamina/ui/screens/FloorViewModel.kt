package com.jamina.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jamina.AppContainer
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.RoomEntity
import com.jamina.core.db.SurveyPointEntity
import com.jamina.core.db.SurveySessionEntity
import com.jamina.core.db.WallEntity
import com.jamina.core.db.newId
import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.core.model.SurveyMode
import com.jamina.diagnostics.NetProbe
import com.jamina.diagnostics.SpeedTest
import com.jamina.floorplan.Geometry
import com.jamina.heatmap.GridStats
import com.jamina.heatmap.HeatColors
import com.jamina.heatmap.HeatmapBuilder
import com.jamina.heatmap.HeatmapRenderer
import com.jamina.heatmap.HeatmapResult
import com.jamina.heatmap.HeatmapType
import com.jamina.heatmap.IdwOptions
import com.jamina.heatmap.MarkerSpec
import com.jamina.heatmap.Measures
import com.jamina.heatmap.PlanRenderer
import com.jamina.heatmap.PointMeasurement
import com.jamina.heatmap.SignalFilter
import com.jamina.heatmap.stats
import com.jamina.wifi.ConnectionInfo
import com.jamina.wifi.ScanBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class FloorMode(val label: String) { BLUEPRINT("Blueprint"), SURVEY("Survey"), HEATMAP("Heatmap") }

enum class Tool(val label: String) {
    SELECT("Select"), WALL("Wall"), ROOM("Room"), DOOR("Door"), WINDOW("Window"),
    STAIRS("Stairs"), ERASE("Erase"), CALIBRATE("Calibrate")
}

data class EditorUi(
    val tool: Tool = Tool.WALL,
    val material: Material = Material.BRICK,
    val snap: Boolean = true,
    val selectedId: String? = null,
    val draftA: Offset? = null,
    val draftB: Offset? = null,
    val calA: Offset? = null,
    val calB: Offset? = null,
    val askCalibration: Boolean = false,
    val canUndo: Boolean = false,
    val renameRoomId: String? = null,
    val message: String? = null,
)

data class SurveyUi(
    val activeSessionId: String? = null,
    val collecting: Boolean = false,
    val pendingPoint: Offset? = null,
    val status: String? = null,
    val lastResult: String? = null,
    val route: List<Offset> = emptyList(),
    val running: Boolean = false,
    val checkpointTimes: List<Long> = emptyList(),
    val pin: Offset? = null,
    val pinAt: Long = 0,
    val runBatches: Int = 0,
    val selectedPointId: String? = null,
)

data class HeatUi(
    val type: HeatmapType = HeatmapType.SIGNAL,
    val sessionIds: Set<String> = emptySet(),
    val ssid: String? = null,
    val bssid: String? = null,
    val band: Band? = null,
    val showPoints: Boolean = true,
    val extrapolate: Boolean = false,
)

data class HeatOutput(
    val computing: Boolean = false,
    val image: ImageBitmap? = null,
    val result: HeatmapResult? = null,
    val stats: GridStats? = null,
    val points: List<PointMeasurement> = emptyList(),
    val ssids: List<String> = emptyList(),
    val bssids: List<Pair<String, String>> = emptyList(),
)

private sealed class Op {
    data class AddedWalls(val ids: List<String>) : Op()
    data class AddedRoom(val id: String, val wallIds: List<String>) : Op()
    data class Deleted(val walls: List<WallEntity>, val rooms: List<RoomEntity>) : Op()
    data class ChangedWall(val old: WallEntity) : Op()
    data class ChangedRoom(val old: RoomEntity) : Op()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FloorViewModel(private val c: AppContainer, val floorId: String) : ViewModel() {
    private val dao = c.dao
    val settings = c.settings.settings
    val scanState = c.scanner.state

    val floor: StateFlow<FloorEntity?> = dao.floorFlow(floorId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val dbWalls = dao.walls(floorId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val dbRooms = dao.rooms(floorId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions = dao.sessions(floorId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Item being dragged (shown instead of its stored version until the drag ends). */
    private val movingWall = MutableStateFlow<WallEntity?>(null)
    private val movingRoom = MutableStateFlow<RoomEntity?>(null)

    val walls: StateFlow<List<WallEntity>> = combine(dbWalls, movingWall) { w, m ->
        if (m == null) w else w.map { if (it.id == m.id) m else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rooms: StateFlow<List<RoomEntity>> = combine(dbRooms, movingRoom) { r, m ->
        if (m == null) r else r.map { if (it.id == m.id) m else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val planBitmap: StateFlow<Bitmap?> = floor.map { it?.imagePath }.distinctUntilChanged().mapLatest { path ->
        withContext(Dispatchers.IO) { c.repo.loadPlanBitmap(path) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val planImage: StateFlow<ImageBitmap?> = planBitmap.map { it?.asImageBitmap() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val editor = MutableStateFlow(EditorUi())
    val survey = MutableStateFlow(SurveyUi())
    val heat = MutableStateFlow(HeatUi())
    val heatOut = MutableStateFlow(HeatOutput())

    private val undo = ArrayDeque<Op>()

    val activePoints: StateFlow<List<PointMeasurement>> = survey.map { it.activeSessionId }.distinctUntilChanged().flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList()) else dao.points(sid)
    }.combine(floor.filterNotNull()) { pts, f -> pts to f }.mapLatest { (pts, f) ->
        if (pts.isEmpty()) emptyList() else {
            val obs = dao.observationsForBatches(pts.mapNotNull { it.scanBatchId }.distinct()).groupBy { it.batchId }
            com.jamina.core.Repository.toMeasurements(pts, obs, f.metersPerUnit)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var runJob: Job? = null

    init {
        // Default the active and heatmap sessions to the newest one.
        viewModelScope.launch {
            sessions.collect { list ->
                if (survey.value.activeSessionId == null || list.none { it.id == survey.value.activeSessionId }) {
                    survey.update { it.copy(activeSessionId = list.firstOrNull()?.id) }
                }
                val valid = heat.value.sessionIds.filter { id -> list.any { it.id == id } }.toSet()
                if (valid.isEmpty() && list.isNotEmpty()) heat.update { it.copy(sessionIds = setOf(list.first().id)) }
                else if (valid != heat.value.sessionIds) heat.update { it.copy(sessionIds = valid) }
            }
        }
        // Heatmap computation pipeline
        viewModelScope.launch {
            combine(heat, floor.filterNotNull(), dbWalls, settings, sessions) { h, f, w, s, _ -> listOf(h, f, w, s) }
                .debounce(150)
                .collectLatest { (hAny, fAny, wAny, sAny) ->
                    @Suppress("UNCHECKED_CAST")
                    computeHeat(hAny as HeatUi, fAny as FloorEntity, wAny as List<WallEntity>, sAny as com.jamina.core.settings.AppSettings)
                }
        }
    }

    // ------------------------------------------------------------------ Floor/plan
    fun importPlan(uri: Uri) {
        viewModelScope.launch {
            val f = floor.value ?: return@launch
            try {
                val (path, w, h) = c.repo.importPlan(floorId, uri)
                val hadWalls = dbWalls.value.isNotEmpty()
                dao.upsertFloor(f.copy(imagePath = path, widthUnits = w.toFloat(), heightUnits = h.toFloat(), metersPerUnit = 15f / w, calibrated = false))
                editor.update { it.copy(tool = Tool.CALIBRATE, calA = null, calB = null, message = "Plan imported. Calibrate: tap both ends of a wall whose length you know." + if (hadWalls) " Existing drawn walls kept their plan coordinates." else "") }
            } catch (e: Exception) {
                editor.update { it.copy(message = "Import failed: ${e.message}") }
            }
        }
    }

    fun updateFloor(transform: (FloorEntity) -> FloorEntity) {
        viewModelScope.launch { floor.value?.let { dao.upsertFloor(transform(it)) } }
    }

    fun applyCalibration(meters: Float) {
        val e = editor.value
        val a = e.calA ?: return; val b = e.calB ?: return
        val units = hypot(b.x - a.x, b.y - a.y)
        if (units < 1f || meters <= 0f) return
        updateFloor { it.copy(metersPerUnit = meters / units, calibrated = true) }
        editor.update { it.copy(askCalibration = false, calA = null, calB = null, tool = Tool.SELECT, message = "Scale set: ${"%.3f".format(meters / units * 100)} cm per unit. Next: switch to Survey.") }
    }

    fun cancelCalibration() = editor.update { it.copy(askCalibration = false, calA = null, calB = null) }

    // ------------------------------------------------------------------ Editor
    fun setTool(t: Tool) = editor.update { it.copy(tool = t, selectedId = null, draftA = null, draftB = null, calA = null, calB = null) }
    fun setMaterial(m: Material) {
        editor.update { it.copy(material = m) }
        val sel = selectedWall() ?: return
        changeWall(sel.copy(material = m.name))
    }
    fun setSnap(on: Boolean) = editor.update { it.copy(snap = on) }
    fun clearMessage() = editor.update { it.copy(message = null) }

    private fun mpu() = floor.value?.metersPerUnit ?: 0.025f
    private fun tolUnits(m: Float) = m / mpu()

    fun selectedWall(): WallEntity? = walls.value.firstOrNull { it.id == editor.value.selectedId }
    fun selectedRoom(): RoomEntity? = rooms.value.firstOrNull { it.id == editor.value.selectedId }

    private fun snapPoint(p: Offset, anchor: Offset? = null): Offset {
        if (!editor.value.snap) return p
        // endpoint snap
        val tol = tolUnits(0.3f)
        var best: Offset? = null; var bestD = tol
        for (w in dbWalls.value) {
            for (q in listOf(Offset(w.x1, w.y1), Offset(w.x2, w.y2))) {
                val d = (q - p).getDistance()
                if (d < bestD) { bestD = d; best = q }
            }
        }
        if (best != null) return best
        var out = p
        if (anchor != null) {
            val ang = Math.toDegrees(atan2((p.y - anchor.y).toDouble(), (p.x - anchor.x).toDouble())).toFloat()
            val norm = ((ang % 90f) + 90f) % 90f
            if (norm < 8f || norm > 82f) {
                out = if (abs(p.x - anchor.x) > abs(p.y - anchor.y)) Offset(p.x, anchor.y) else Offset(anchor.x, p.y)
            }
        }
        val g = tolUnits(0.25f)
        return Offset((out.x / g).roundToInt() * g, (out.y / g).roundToInt() * g).let {
            if (anchor != null && out.y == anchor.y) Offset(it.x, anchor.y) else if (anchor != null && out.x == anchor.x) Offset(anchor.x, it.y) else it
        }
    }

    private fun hitWall(p: Offset): WallEntity? {
        val tol = tolUnits(0.35f)
        return walls.value.map { it to Geometry.distToSegment(p.x, p.y, it.x1, it.y1, it.x2, it.y2) }
            .filter { it.second <= tol }.minByOrNull { it.second }?.first
    }

    private fun hitRoom(p: Offset): RoomEntity? = rooms.value.filter { it.contains(p.x, p.y) }.minByOrNull { abs(it.w * it.h) }

    private fun push(op: Op) { undo.addLast(op); if (undo.size > 100) undo.removeFirst(); editor.update { it.copy(canUndo = true) } }

    fun undo() {
        val op = undo.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (op) {
                is Op.AddedWalls -> op.ids.forEach { dao.deleteWall(it) }
                is Op.AddedRoom -> { dao.deleteRoom(op.id); op.wallIds.forEach { dao.deleteWall(it) } }
                is Op.Deleted -> { dao.upsertWalls(op.walls); op.rooms.forEach { dao.upsertRoom(it) } }
                is Op.ChangedWall -> dao.upsertWall(op.old)
                is Op.ChangedRoom -> dao.upsertRoom(op.old)
            }
            editor.update { it.copy(canUndo = undo.isNotEmpty(), selectedId = null) }
        }
    }

    fun changeWall(w: WallEntity) {
        val old = dbWalls.value.firstOrNull { it.id == w.id } ?: return
        push(Op.ChangedWall(old))
        viewModelScope.launch { dao.upsertWall(w) }
    }

    fun changeRoom(r: RoomEntity) {
        val old = dbRooms.value.firstOrNull { it.id == r.id } ?: return
        push(Op.ChangedRoom(old))
        viewModelScope.launch { dao.upsertRoom(r) }
    }

    fun setWallLength(w: WallEntity, meters: Float) {
        val len = Geometry.length(w.x1, w.y1, w.x2, w.y2)
        if (len < 1e-3f || meters <= 0f) return
        val k = (meters / mpu()) / len
        changeWall(w.copy(x2 = w.x1 + (w.x2 - w.x1) * k, y2 = w.y1 + (w.y2 - w.y1) * k))
    }

    fun deleteSelected() {
        val w = selectedWall(); val r = selectedRoom()
        viewModelScope.launch {
            if (w != null) { push(Op.Deleted(listOf(w), emptyList())); dao.deleteWall(w.id) }
            if (r != null) { push(Op.Deleted(emptyList(), listOf(r))); dao.deleteRoom(r.id) }
            editor.update { it.copy(selectedId = null) }
        }
    }

    fun renameRoom(id: String, name: String) {
        rooms.value.firstOrNull { it.id == id }?.let { changeRoom(it.copy(name = name.ifBlank { it.name })) }
        editor.update { it.copy(renameRoomId = null) }
    }

    fun dismissRename() = editor.update { it.copy(renameRoomId = null) }

    private var dragKind = 0 // 1 = draft shape, 2 = move wall endpoint 1, 3 = endpoint 2, 4 = move wall, 5 = move room, 6 = calibrate point
    private var dragStart = Offset.Zero

    fun onEditorTap(p: Offset) {
        val e = editor.value
        when (e.tool) {
            Tool.SELECT -> editor.update { it.copy(selectedId = (hitWall(p)?.id ?: hitRoom(p)?.id)) }
            Tool.ERASE -> {
                val w = hitWall(p); val r = if (w == null) hitRoom(p) else null
                viewModelScope.launch {
                    if (w != null) { push(Op.Deleted(listOf(w), emptyList())); dao.deleteWall(w.id) }
                    if (r != null) { push(Op.Deleted(emptyList(), listOf(r))); dao.deleteRoom(r.id) }
                }
            }
            Tool.CALIBRATE -> {
                if (e.calA == null || e.calB != null) editor.update { it.copy(calA = p, calB = null) }
                else editor.update { it.copy(calB = p, askCalibration = true) }
            }
            else -> {
                hitRoom(p)?.let { r -> if (e.tool == Tool.ROOM) editor.update { it.copy(renameRoomId = r.id) } }
            }
        }
    }

    fun onEditorDragStart(p: Offset): Boolean {
        val e = editor.value
        dragStart = p
        return when (e.tool) {
            Tool.WALL, Tool.ROOM, Tool.STAIRS, Tool.DOOR, Tool.WINDOW -> {
                val a = if (e.tool == Tool.DOOR || e.tool == Tool.WINDOW) projectToWall(p) else snapPoint(p)
                editor.update { it.copy(draftA = a, draftB = a) }; dragKind = 1; true
            }
            Tool.SELECT -> {
                val w = selectedWall()
                val r = selectedRoom()
                val tol = tolUnits(0.4f)
                when {
                    w != null && (Offset(w.x1, w.y1) - p).getDistance() < tol -> { dragKind = 2; movingWall.value = w; true }
                    w != null && (Offset(w.x2, w.y2) - p).getDistance() < tol -> { dragKind = 3; movingWall.value = w; true }
                    w != null && Geometry.distToSegment(p.x, p.y, w.x1, w.y1, w.x2, w.y2) < tol -> { dragKind = 4; movingWall.value = w; true }
                    r != null && r.contains(p.x, p.y) -> { dragKind = 5; movingRoom.value = r; true }
                    else -> false
                }
            }
            Tool.CALIBRATE -> { editor.update { it.copy(calA = p, calB = p) }; dragKind = 6; true }
            Tool.ERASE -> false
        }
    }

    private fun projectToWall(p: Offset): Offset {
        val w = hitWall(p) ?: return snapPoint(p)
        val (x, y) = Geometry.projectOnSegment(p.x, p.y, w.x1, w.y1, w.x2, w.y2)
        return Offset(x, y)
    }

    fun onEditorDrag(p: Offset) {
        val e = editor.value
        when (dragKind) {
            1 -> {
                val b = when (e.tool) {
                    Tool.WALL -> snapPoint(p, e.draftA)
                    Tool.DOOR, Tool.WINDOW -> {
                        val a = e.draftA ?: p
                        val w = hitWall(a)
                        if (w != null) { val (x, y) = Geometry.projectOnSegment(p.x, p.y, w.x1, w.y1, w.x2, w.y2); Offset(x, y) } else snapPoint(p, a)
                    }
                    else -> snapPoint(p)
                }
                editor.update { it.copy(draftB = b) }
            }
            2 -> movingWall.value?.let { w -> val q = snapPoint(p, Offset(w.x2, w.y2)); movingWall.value = w.copy(x1 = q.x, y1 = q.y) }
            3 -> movingWall.value?.let { w -> val q = snapPoint(p, Offset(w.x1, w.y1)); movingWall.value = w.copy(x2 = q.x, y2 = q.y) }
            4 -> {
                val orig = dbWalls.value.firstOrNull { it.id == movingWall.value?.id } ?: return
                val d = p - dragStart
                movingWall.value = orig.copy(x1 = orig.x1 + d.x, y1 = orig.y1 + d.y, x2 = orig.x2 + d.x, y2 = orig.y2 + d.y)
            }
            5 -> {
                val orig = dbRooms.value.firstOrNull { it.id == movingRoom.value?.id } ?: return
                val d = p - dragStart
                movingRoom.value = orig.copy(x = orig.x + d.x, y = orig.y + d.y)
            }
            6 -> editor.update { it.copy(calB = p) }
        }
    }

    fun onEditorDragEnd() {
        val e = editor.value
        val f = floor.value ?: return
        when (dragKind) {
            1 -> {
                val a = e.draftA; val b = e.draftB
                editor.update { it.copy(draftA = null, draftB = null) }
                if (a == null || b == null) return
                val lenM = (b - a).getDistance() * f.metersPerUnit
                if (lenM < 0.2f) return
                viewModelScope.launch {
                    when (e.tool) {
                        Tool.WALL -> {
                            val w = WallEntity(newId(), floorId, a.x, a.y, b.x, b.y, e.material.name)
                            dao.upsertWall(w); push(Op.AddedWalls(listOf(w.id)))
                        }
                        Tool.DOOR, Tool.WINDOW -> {
                            val mat = if (e.tool == Tool.DOOR) Material.WOOD_DOOR else if (e.material == Material.COATED_GLASS) Material.COATED_GLASS else Material.GLASS
                            val w = WallEntity(newId(), floorId, a.x, a.y, b.x, b.y, mat.name, type = if (e.tool == Tool.DOOR) "DOOR" else "WINDOW")
                            dao.upsertWall(w); push(Op.AddedWalls(listOf(w.id)))
                        }
                        Tool.ROOM, Tool.STAIRS -> {
                            val x0 = minOf(a.x, b.x); val y0 = minOf(a.y, b.y); val x1 = maxOf(a.x, b.x); val y1 = maxOf(a.y, b.y)
                            if (e.tool == Tool.ROOM) {
                                val n = dbRooms.value.count { it.kind == "ROOM" } + 1
                                val walls = listOf(
                                    WallEntity(newId(), floorId, x0, y0, x1, y0, e.material.name),
                                    WallEntity(newId(), floorId, x1, y0, x1, y1, e.material.name),
                                    WallEntity(newId(), floorId, x1, y1, x0, y1, e.material.name),
                                    WallEntity(newId(), floorId, x0, y1, x0, y0, e.material.name),
                                ).filter { w -> dbWalls.value.none { sameSegment(it, w) } }
                                val room = RoomEntity(newId(), floorId, "Room $n", x0, y0, x1 - x0, y1 - y0)
                                dao.upsertWalls(walls); dao.upsertRoom(room)
                                push(Op.AddedRoom(room.id, walls.map { it.id }))
                                editor.update { it.copy(renameRoomId = room.id) }
                            } else {
                                val room = RoomEntity(newId(), floorId, "Stairs", x0, y0, x1 - x0, y1 - y0, kind = "STAIRS")
                                dao.upsertRoom(room); push(Op.AddedRoom(room.id, emptyList()))
                            }
                        }
                        else -> {}
                    }
                }
            }
            2, 3, 4 -> movingWall.value?.let { w -> movingWall.value = null; changeWall(w) }
            5 -> movingRoom.value?.let { r -> movingRoom.value = null; changeRoom(r) }
            6 -> if (e.calA != null && e.calB != null && (e.calB - e.calA).getDistance() > 2f) editor.update { it.copy(askCalibration = true) }
        }
        dragKind = 0
    }

    private fun sameSegment(a: WallEntity, b: WallEntity): Boolean {
        val tol = tolUnits(0.05f)
        fun eq(x1: Float, y1: Float, x2: Float, y2: Float) = abs(x1 - x2) < tol && abs(y1 - y2) < tol
        return (eq(a.x1, a.y1, b.x1, b.y1) && eq(a.x2, a.y2, b.x2, b.y2)) || (eq(a.x1, a.y1, b.x2, b.y2) && eq(a.x2, a.y2, b.x1, b.y1))
    }

    // ------------------------------------------------------------------ Survey
    fun createSession(name: String, mode: SurveyMode, baseline: Boolean) {
        viewModelScope.launch {
            val s = SurveySessionEntity(
                newId(), floorId, name.ifBlank { "Survey ${sessions.value.size + 1}" }, mode.name, System.currentTimeMillis(),
                isBaseline = baseline, deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}", androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            dao.upsertSession(s)
            floor.value?.let { c.repo.touchProject(it.projectId) }
            survey.update { SurveyUi(activeSessionId = s.id) }
        }
    }

    fun selectSession(id: String) { stopRun(save = false); survey.update { SurveyUi(activeSessionId = id) } }

    fun deleteSession(id: String) { viewModelScope.launch { c.repo.deleteSession(id) } }

    fun finishSession() {
        val sid = survey.value.activeSessionId ?: return
        viewModelScope.launch {
            dao.session(sid)?.let { dao.upsertSession(it.copy(endedAt = System.currentTimeMillis())) }
            heat.update { it.copy(sessionIds = setOf(sid)) }
        }
    }

    fun activeSession(): SurveySessionEntity? = sessions.value.firstOrNull { it.id == survey.value.activeSessionId }

    fun onSurveyTap(p: Offset) {
        val s = activeSession() ?: return
        val st = survey.value
        if (st.collecting) return
        val near = activePoints.value.minByOrNull { hypot(it.xU - p.x, it.yU - p.y) }
        if (near != null && hypot(near.xU - p.x, near.yU - p.y) < tolUnits(0.35f) && !st.running) {
            survey.update { it.copy(selectedPointId = near.id) }; return
        }
        when (SurveyMode.valueOf(s.mode)) {
            SurveyMode.POINT -> measureAt(p)
            SurveyMode.WALK -> if (!st.running) survey.update { it.copy(route = it.route + p) }
            SurveyMode.CONTINUOUS -> survey.update { it.copy(pin = p, pinAt = System.currentTimeMillis()) }
        }
    }

    fun dismissPoint() = survey.update { it.copy(selectedPointId = null) }
    fun deletePoint(id: String) { viewModelScope.launch { dao.deletePoint(id) }; dismissPoint() }
    fun remeasure(id: String) {
        val p = activePoints.value.firstOrNull { it.id == id } ?: return
        dismissPoint(); measureAt(Offset(p.xU, p.yU))
    }

    private suspend fun savePoint(
        sid: String, p: Offset, batch: ScanBatch, conn: ConnectionInfo, source: String, accuracy: Float,
        latency: Float? = null, loss: Float? = null, jitter: Float? = null, tput: Float? = null,
    ): SurveyPointEntity {
        c.repo.saveBatch(batch, "SURVEY")
        val pt = SurveyPointEntity(
            id = newId(), sessionId = sid, x = p.x, y = p.y, timestamp = batch.timestamp, positionSource = source, accuracyM = accuracy,
            connectedSsid = conn.ssid, connectedBssid = conn.bssid, connectedRssi = conn.rssi, linkSpeedMbps = conn.linkSpeedMbps,
            frequency = conn.frequency, latencyMs = latency, packetLossPct = loss, jitterMs = jitter, throughputMbps = tput,
            scanBatchId = batch.id, scanFresh = batch.fresh, scanAgeMs = batch.results.minOfOrNull { it.ageMs } ?: 0,
        )
        dao.upsertPoint(pt)
        return pt
    }

    fun measureAt(p: Offset) {
        val sid = survey.value.activeSessionId ?: return
        val s = settings.value
        survey.update { it.copy(collecting = true, pendingPoint = p, status = "Starting…") }
        viewModelScope.launch {
            try {
                val batch = c.scanner.acquire(s.requireFreshScan, { msg -> survey.update { it.copy(status = msg) } })
                val conn = c.connection.poll()
                var lat: Float? = null; var loss: Float? = null; var jit: Float? = null; var tput: Float? = null
                if (s.latencyAtPoints && conn.gateway != null) {
                    survey.update { it.copy(status = "Measuring latency…") }
                    val r = NetProbe.ping(conn.gateway, 10)
                    lat = r.avg; loss = r.lossPct; jit = r.jitter
                }
                if (s.throughputAtPoints) {
                    survey.update { it.copy(status = "Measuring throughput…") }
                    val url = s.lanUrl.ifBlank { s.downloadUrl }
                    val r = SpeedTest.download(url, maxSeconds = 4f)
                    if (r.error == null) tput = r.mbps
                }
                savePoint(sid, p, batch, conn, "MANUAL", 0.5f, lat, loss, jit, tput)
                val best = batch.results.filter { conn.ssid == null || it.ssid == conn.ssid }.maxOfOrNull { it.rssi }
                survey.update {
                    it.copy(
                        collecting = false, pendingPoint = null, status = null,
                        lastResult = "Saved: ${batch.results.size} APs heard${best?.let { b -> ", best ${conn.ssid ?: ""} $b dBm" } ?: ""}" +
                            (if (batch.fresh) "" else " (cached scan, ${(batch.results.minOfOrNull { r -> r.ageMs } ?: 0) / 1000}s old)") +
                            (lat?.let { l -> ", latency ${"%.0f".format(l)} ms" } ?: ""),
                    )
                }
            } catch (e: Exception) {
                survey.update { it.copy(collecting = false, pendingPoint = null, status = null, lastResult = "Measurement failed: ${e.message}") }
            }
        }
    }

    fun clearRoute() = survey.update { it.copy(route = emptyList(), checkpointTimes = emptyList()) }
    fun undoRoutePoint() = survey.update { it.copy(route = it.route.dropLast(1)) }

    private val runBatches = mutableListOf<Pair<ScanBatch, ConnectionInfo>>()

    fun startRun() {
        val s = activeSession() ?: return
        val mode = SurveyMode.valueOf(s.mode)
        if (mode == SurveyMode.WALK && survey.value.route.size < 2) {
            survey.update { it.copy(lastResult = "Tap at least two checkpoints to draw your route first.") }; return
        }
        runBatches.clear()
        val startSeq = c.scanner.state.value.latest?.seq ?: 0
        survey.update { it.copy(running = true, checkpointTimes = listOf(System.currentTimeMillis()), runBatches = 0, status = "Recording…") }
        c.connection.start()
        runJob = viewModelScope.launch {
            launch {
                c.scanner.state.map { it.latest }.filterNotNull().distinctUntilChangedBy { it.id }.collect { b ->
                    if (b.seq <= startSeq || b.results.isEmpty()) return@collect
                    val conn = c.connection.poll()
                    if (mode == SurveyMode.CONTINUOUS) {
                        val st = survey.value
                        val pin = st.pin
                        if (pin != null) {
                            val acc = if (System.currentTimeMillis() - st.pinAt < 10_000) 1.0f else 3.0f
                            savePoint(s.id, pin, b, conn, "PINNED", acc)
                            survey.update { it.copy(runBatches = it.runBatches + 1, lastResult = "Recorded scan at pinned position (${b.results.size} APs)") }
                        } else {
                            survey.update { it.copy(lastResult = "Scan received but no position set — tap where you are.") }
                        }
                    } else {
                        runBatches += b to conn
                        survey.update { it.copy(runBatches = runBatches.size) }
                    }
                }
            }
            while (isActive) {
                if (c.scanner.msUntilAllowed() == 0L) c.scanner.requestScan()
                val wait = c.scanner.msUntilAllowed()
                survey.update { it.copy(status = if (wait > 0) "Recording… next scan in ${(wait + 999) / 1000}s" else "Recording… scanning") }
                delay(1000)
            }
        }
    }

    fun reachedCheckpoint() {
        val st = survey.value
        val times = st.checkpointTimes + System.currentTimeMillis()
        survey.update { it.copy(checkpointTimes = times) }
        if (times.size >= st.route.size) stopRun(save = true)
    }

    fun stopRun(save: Boolean = true) {
        val job = runJob ?: return
        runJob = null
        job.cancel()
        c.connection.stop()
        val st = survey.value
        val s = activeSession()
        survey.update { it.copy(running = false, status = null) }
        if (!save || s == null || SurveyMode.valueOf(s.mode) != SurveyMode.WALK) return
        val batches = runBatches.toList()
        viewModelScope.launch {
            val positioned = WalkPositioner.assign(st.route, st.checkpointTimes, batches.map { it.first.timestamp }, mpu())
            var saved = 0
            batches.forEachIndexed { i, (b, conn) ->
                val pos = positioned[i] ?: return@forEachIndexed
                savePoint(s.id, pos.first, b, conn, "ESTIMATED", pos.second)
                saved++
            }
            survey.update { it.copy(lastResult = "Walk saved: $saved of ${batches.size} scans positioned between ${st.checkpointTimes.size} reached checkpoints (estimated positions).", checkpointTimes = emptyList()) }
        }
    }

    // ------------------------------------------------------------------ Heatmap
    fun setHeat(transform: (HeatUi) -> HeatUi) = heat.update(transform)

    private suspend fun computeHeat(h: HeatUi, f: FloorEntity, walls: List<WallEntity>, s: com.jamina.core.settings.AppSettings) {
        heatOut.update { it.copy(computing = true) }
        val pts = c.repo.loadMeasurements(h.sessionIds.toList(), f)
        val ssids = pts.flatMap { p -> p.observations.map { it.ssid } }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val bssids = pts.flatMap { p -> p.observations.filter { h.ssid == null || it.ssid == h.ssid } }.groupBy { it.bssid }
            .map { (b, l) -> b to "${s.aliasOf(b) ?: b} (${l.first().ssid.ifBlank { "hidden" }}, ch ${l.first().channel})" }
        val out = withContext(Dispatchers.Default) {
            val local = c.repo.wallSegs(walls, f.copy(offsetXm = 0f, offsetYm = 0f))
            val opt = IdwOptions(power = s.idwPower, radiusM = s.idwRadiusM, extrapolate = h.extrapolate, walls = if (s.wallAware) local else emptyList())
            val filter = SignalFilter(h.ssid, h.bssid, h.band)
            val res = HeatmapBuilder.build(h.type, pts, filter, f.widthM, f.heightM, opt, cols = 120)
            val bmp = HeatmapRenderer.render(res, s.thresholds)
            val stats = if (h.type == HeatmapType.SIGNAL) res.grid?.stats(s.thresholds.good.toFloat()) else null
            Triple(res, bmp, stats)
        }
        heatOut.value = HeatOutput(false, out.second?.asImageBitmap(), out.first, out.third, pts, ssids, bssids)
    }

    fun pointColor(p: PointMeasurement): Int {
        val conn = p.connectedSsid
        val v = Measures.bestRssi(p, SignalFilter(ssid = conn)) ?: return 0xFF9E9E9E.toInt()
        return HeatColors.signal(v, settings.value.thresholds)
    }

    suspend fun renderExport(): Bitmap? = withContext(Dispatchers.Default) {
        val f = floor.value ?: return@withContext null
        val o = heatOut.value
        val res = o.result ?: return@withContext null
        val h = heat.value
        val s = settings.value
        val heatBmp = HeatmapRenderer.render(res, s.thresholds)
        val markers = if (h.showPoints) o.points.map { MarkerSpec(it.xU, it.yU, pointColor(it), hollow = it.source != "MANUAL") } else emptyList()
        val legend = when (res.type) {
            HeatmapType.SIGNAL -> PlanRenderer.signalLegend(s.thresholds)
            HeatmapType.BEST_AP, HeatmapType.ROAMING -> res.categoryLabels.mapIndexed { i, b -> HeatColors.category(i) to (s.aliasOf(b) ?: b) }
            else -> listOf(HeatColors.ramp(0f) to "Better", HeatColors.ramp(0.5f) to "Middle", HeatColors.ramp(1f) to "Worse")
        }
        val filterTxt = listOfNotNull(h.ssid?.let { "SSID $it" }, h.bssid?.let { "BSSID $it" }, h.band?.label).joinToString(", ").ifBlank { "all networks" }
        PlanRenderer.composite(
            f, planBitmap.value, heatBmp, walls.value, rooms.value, markers,
            "MEASURED ${res.type.label} — ${f.name}",
            "Interpolated (IDW) from ${res.sampleCount} survey points • $filterTxt • ${com.jamina.ui.components.Fmt.dateTime(System.currentTimeMillis())}",
            legend,
        )
    }

    fun exportPng(uri: Uri, ctx: android.content.Context, done: (String) -> Unit) {
        viewModelScope.launch {
            val bmp = renderExport() ?: return@launch done("Nothing to export yet")
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            done("Heatmap exported")
        }
    }

    override fun onCleared() {
        if (runJob != null) { runJob?.cancel(); c.connection.stop() }
    }
}

/**
 * Positions scans taken during a walking survey by linear interpolation between the times the
 * user confirmed reaching each checkpoint. Scans outside the confirmed segments are discarded.
 * Returns, per scan, (position in plan units, accuracy estimate in metres) or null.
 */
object WalkPositioner {
    fun assign(route: List<Offset>, times: List<Long>, scanTimes: List<Long>, metersPerUnit: Float): List<Pair<Offset, Float>?> {
        val segs = minOf(route.size, times.size) - 1
        return scanTimes.map { t ->
            if (segs < 1) return@map null
            for (i in 0 until segs) {
                val t0 = times[i]; val t1 = times[i + 1]
                if (t in t0..t1 && t1 > t0) {
                    val frac = (t - t0).toFloat() / (t1 - t0)
                    val a = route[i]; val b = route[i + 1]
                    val pos = Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
                    val segLenM = (b - a).getDistance() * metersPerUnit
                    // uncertainty is largest mid-segment where pace variations accumulate
                    val acc = (0.5f + segLenM * 0.25f * (1f - abs(frac - 0.5f) * 2f)).coerceAtMost(5f)
                    return@map pos to acc
                }
            }
            null
        }
    }
}
