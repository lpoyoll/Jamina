package com.jamina.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.room.withTransaction
import com.jamina.core.db.AppDatabase
import com.jamina.core.db.DiagnosticResultEntity
import com.jamina.core.db.FloorEntity
import com.jamina.core.db.ProjectEntity
import com.jamina.core.db.RoomEntity
import com.jamina.core.db.ScanBatchEntity
import com.jamina.core.db.ScanObservationEntity
import com.jamina.core.db.SurveyPointEntity
import com.jamina.core.db.SurveySessionEntity
import com.jamina.core.db.VirtualApEntity
import com.jamina.core.db.WallEntity
import com.jamina.core.db.newId
import com.jamina.core.model.Band
import com.jamina.core.model.Material
import com.jamina.floorplan.WallSeg
import com.jamina.heatmap.ObsLite
import com.jamina.heatmap.PointMeasurement
import com.jamina.planner.FloorGeom
import com.jamina.planner.PlannedAp
import com.jamina.wifi.ScanBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ProjectBackup(
    val format: String = "jamina-project-v1",
    val exportedAt: Long,
    val project: ProjectEntity,
    val floors: List<FloorEntity>,
    val walls: List<WallEntity>,
    val rooms: List<RoomEntity>,
    val aps: List<VirtualApEntity>,
    val sessions: List<SurveySessionEntity>,
    val points: List<SurveyPointEntity>,
    val batches: List<ScanBatchEntity>,
    val observations: List<ScanObservationEntity>,
    /** floorId -> base64 PNG/JPEG of the plan image */
    val images: Map<String, String> = emptyMap(),
)

class Repository(private val context: Context, private val db: AppDatabase) {
    val dao = db.dao()
    private val savedBatches = ConcurrentHashMap.newKeySet<String>()
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    // ---------- Scans ----------
    suspend fun saveBatch(batch: ScanBatch, source: String = "SCANNER") {
        if (!savedBatches.add(batch.id)) return
        val inserted = dao.insertBatch(ScanBatchEntity(batch.id, batch.timestamp, batch.fresh, source))
        if (inserted == -1L) return
        dao.insertObservations(batch.results.map {
            ScanObservationEntity(
                batchId = batch.id, timestamp = batch.timestamp - it.ageMs, bssid = it.bssid, ssid = it.ssid,
                rssi = it.rssi, frequency = it.frequency, channel = it.channel, widthMhz = it.widthMhz,
                centerFreq0 = it.centerFreq0, standard = it.standard, capabilities = it.capabilities, ageMs = it.ageMs,
            )
        })
    }

    // ---------- Projects ----------
    suspend fun createProject(name: String): ProjectEntity {
        val now = System.currentTimeMillis()
        val p = ProjectEntity(newId(), name.ifBlank { "Untitled building" }, now, now)
        dao.upsertProject(p)
        return p
    }

    suspend fun touchProject(id: String) {
        dao.project(id)?.let { dao.upsertProject(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun createFloor(projectId: String, name: String, level: Int, copyWallsFrom: FloorEntity? = null): FloorEntity {
        val f = FloorEntity(
            id = newId(), projectId = projectId, name = name, level = level,
            widthUnits = copyWallsFrom?.widthUnits ?: 1200f,
            heightUnits = copyWallsFrom?.heightUnits ?: 800f,
            metersPerUnit = copyWallsFrom?.metersPerUnit ?: 0.025f,
            calibrated = copyWallsFrom?.calibrated ?: false,
            offsetXm = copyWallsFrom?.offsetXm ?: 0f,
            offsetYm = copyWallsFrom?.offsetYm ?: 0f,
        )
        dao.upsertFloor(f)
        if (copyWallsFrom != null) {
            val walls = dao.wallsList(copyWallsFrom.id).filter { it.exterior }
            dao.upsertWalls(walls.map { it.copy(id = newId(), floorId = f.id) })
        }
        touchProject(projectId)
        return f
    }

    suspend fun deleteFloor(floorId: String) = db.withTransaction {
        for (s in dao.sessionsList(floorId)) deleteSessionInternal(s.id)
        dao.deleteWallsForFloor(floorId)
        dao.deleteRoomsForFloor(floorId)
        dao.deleteApsForFloor(floorId)
        dao.floor(floorId)?.imagePath?.let { File(it).delete() }
        dao.deleteFloorRow(floorId)
    }

    suspend fun deleteProject(projectId: String) {
        for (f in dao.floorsList(projectId)) deleteFloor(f.id)
        dao.deleteProjectRow(projectId)
    }

    private suspend fun deleteSessionInternal(sessionId: String) {
        dao.deletePointsForSession(sessionId)
        dao.deleteSessionRow(sessionId)
    }

    suspend fun deleteSession(sessionId: String) = db.withTransaction { deleteSessionInternal(sessionId) }

    // ---------- Floor plan import ----------
    /** Imports PNG/JPG/WEBP or the first page of a PDF; returns (path, width, height). */
    suspend fun importPlan(floorId: String, uri: Uri, pdfPage: Int = 0): Triple<String, Int, Int> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: ""
        val bmp: Bitmap = if (mime == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
            val pfd: ParcelFileDescriptor = cr.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(pdfPage.coerceIn(0, renderer.pageCount - 1)).use { page ->
                        val scale = (2400f / maxOf(page.width, page.height)).coerceAtLeast(1f)
                        val b = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("Unsupported image")
        }
        val dir = File(context.filesDir, "plans").apply { mkdirs() }
        val file = File(dir, "$floorId-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Triple(file.absolutePath, bmp.width, bmp.height)
    }

    fun loadPlanBitmap(path: String?): Bitmap? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(path)
    }

    // ---------- Measurements ----------
    suspend fun loadMeasurements(sessionIds: List<String>, floor: FloorEntity): List<PointMeasurement> = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext emptyList()
        val pts = dao.pointsForSessionsList(sessionIds)
        val obs = dao.observationsForSessions(sessionIds).groupBy { it.batchId }
        toMeasurements(pts, obs, floor.metersPerUnit)
    }

    // ---------- Geometry ----------
    fun wallSegs(walls: List<WallEntity>, floor: FloorEntity): List<WallSeg> = walls.map {
        val m = floor.metersPerUnit
        WallSeg(it.x1 * m + floor.offsetXm, it.y1 * m + floor.offsetYm, it.x2 * m + floor.offsetXm, it.y2 * m + floor.offsetYm, Material.of(it.material), it.type != "WALL")
    }

    /** Builds building-frame floor geometry; elevation is the cumulative ceiling height of lower levels. */
    fun floorGeoms(floors: List<FloorEntity>, walls: Map<String, List<WallEntity>>): Map<Int, FloorGeom> {
        val sorted = floors.sortedBy { it.level }
        val out = mutableMapOf<Int, FloorGeom>()
        val ground = sorted.firstOrNull { it.level == 0 }
        var elev = 0f
        // elevations for levels >= 0
        for (f in sorted.filter { it.level >= 0 }) {
            out[f.level] = FloorGeom(f.level, elev, wallSegs(walls[f.id] ?: emptyList(), f), Material.of(f.floorMaterial), f.widthM, f.heightM, f.offsetXm, f.offsetYm)
            elev += f.ceilingHeightM + 0.3f
        }
        elev = 0f
        for (f in sorted.filter { it.level < 0 }.sortedByDescending { it.level }) {
            elev -= f.ceilingHeightM + 0.3f
            out[f.level] = FloorGeom(f.level, elev, wallSegs(walls[f.id] ?: emptyList(), f), Material.of(f.floorMaterial), f.widthM, f.heightM, f.offsetXm, f.offsetYm)
        }
        if (ground == null && out.isEmpty()) return emptyMap()
        return out
    }

    fun plannedAp(a: VirtualApEntity, floor: FloorEntity): PlannedAp = PlannedAp(
        a.id, a.x * floor.metersPerUnit + floor.offsetXm, a.y * floor.metersPerUnit + floor.offsetYm, floor.level,
        a.mountHeightM, a.txPowerDbm, a.antennaGainDbi, Band.of(a.band) ?: Band.B5, a.channel, a.widthMhz,
        a.directional, a.azimuthDeg, a.beamwidthDeg,
    )

    // ---------- Backup ----------
    suspend fun exportProject(projectId: String): String = withContext(Dispatchers.IO) {
        val p = dao.project(projectId) ?: error("Project not found")
        val floors = dao.floorsList(projectId)
        val walls = floors.flatMap { dao.wallsList(it.id) }
        val rooms = floors.flatMap { dao.roomsList(it.id) }
        val aps = dao.apsForProjectList(projectId)
        val sessions = floors.flatMap { dao.sessionsList(it.id) }
        val sids = sessions.map { it.id }
        val points = if (sids.isEmpty()) emptyList() else dao.pointsForSessionsList(sids)
        val batchIds = points.mapNotNull { it.scanBatchId }.distinct()
        val batches = if (batchIds.isEmpty()) emptyList() else batchIds.chunked(500).flatMap { dao.batches(it) }
        val obs = if (batchIds.isEmpty()) emptyList() else batchIds.chunked(500).flatMap { dao.observationsForBatches(it) }
        val images = floors.mapNotNull { f ->
            f.imagePath?.let { path -> File(path).takeIf { it.exists() }?.let { f.id to Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }
        }.toMap()
        json.encodeToString(ProjectBackup.serializer(), ProjectBackup(exportedAt = System.currentTimeMillis(), project = p, floors = floors, walls = walls, rooms = rooms, aps = aps, sessions = sessions, points = points, batches = batches, observations = obs.map { it.copy(id = 0) }, images = images))
    }

    /** Restores a backup. Existing records with the same IDs are replaced. */
    suspend fun importProject(text: String): ProjectEntity = withContext(Dispatchers.IO) {
        val b = json.decodeFromString(ProjectBackup.serializer(), text)
        require(b.format.startsWith("jamina-project")) { "Not a Jamina project backup" }
        val dir = File(context.filesDir, "plans").apply { mkdirs() }
        db.withTransaction {
            dao.upsertProject(b.project.copy(updatedAt = System.currentTimeMillis()))
            for (f in b.floors) {
                val img = b.images[f.id]?.let { data ->
                    val file = File(dir, "${f.id}-restored-${System.currentTimeMillis()}.png")
                    file.writeBytes(Base64.decode(data, Base64.NO_WRAP))
                    file.absolutePath
                }
                dao.upsertFloor(f.copy(imagePath = img))
            }
            dao.upsertWalls(b.walls)
            b.rooms.forEach { dao.upsertRoom(it) }
            dao.upsertAps(b.aps)
            b.sessions.forEach { dao.upsertSession(it) }
            for (batch in b.batches) {
                if (dao.insertBatch(batch) != -1L) {
                    dao.insertObservations(b.observations.filter { it.batchId == batch.id }.map { it.copy(id = 0) })
                }
            }
            dao.upsertPoints(b.points)
        }
        b.project
    }

    suspend fun recordDiagnostic(d: DiagnosticResultEntity) = dao.insertDiagnostic(d)

    companion object {
        fun toMeasurements(
            pts: List<SurveyPointEntity>,
            obs: Map<String, List<ScanObservationEntity>>,
            metersPerUnit: Float,
        ): List<PointMeasurement> = pts.map { p ->
            PointMeasurement(
                id = p.id, sessionId = p.sessionId, xU = p.x, yU = p.y,
                xM = p.x * metersPerUnit, yM = p.y * metersPerUnit, timestamp = p.timestamp,
                source = p.positionSource, accuracyM = p.accuracyM, connectedSsid = p.connectedSsid,
                connectedBssid = p.connectedBssid, connectedRssi = p.connectedRssi, latencyMs = p.latencyMs,
                lossPct = p.packetLossPct, throughputMbps = p.throughputMbps, fresh = p.scanFresh,
                observations = (p.scanBatchId?.let { obs[it] } ?: emptyList()).map {
                    ObsLite(it.bssid, it.ssid, it.rssi, it.frequency, it.channel, it.widthMhz, it.centerFreq0)
                },
            )
        }
    }
}
