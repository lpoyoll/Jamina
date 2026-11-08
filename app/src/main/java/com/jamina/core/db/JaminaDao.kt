package com.jamina.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JaminaDao {
    // Projects
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun projects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun projectFlow(id: String): Flow<ProjectEntity?>

    @Upsert
    suspend fun upsertProject(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectRow(id: String)

    // Floors
    @Query("SELECT * FROM floors WHERE projectId = :projectId ORDER BY level")
    fun floors(projectId: String): Flow<List<FloorEntity>>

    @Query("SELECT * FROM floors WHERE projectId = :projectId ORDER BY level")
    suspend fun floorsList(projectId: String): List<FloorEntity>

    @Query("SELECT * FROM floors WHERE id = :id")
    suspend fun floor(id: String): FloorEntity?

    @Query("SELECT * FROM floors WHERE id = :id")
    fun floorFlow(id: String): Flow<FloorEntity?>

    @Upsert
    suspend fun upsertFloor(f: FloorEntity)

    @Query("DELETE FROM floors WHERE id = :id")
    suspend fun deleteFloorRow(id: String)

    // Walls
    @Query("SELECT * FROM walls WHERE floorId = :floorId")
    fun walls(floorId: String): Flow<List<WallEntity>>

    @Query("SELECT * FROM walls WHERE floorId = :floorId")
    suspend fun wallsList(floorId: String): List<WallEntity>

    @Query("SELECT w.* FROM walls w INNER JOIN floors f ON w.floorId = f.id WHERE f.projectId = :projectId")
    fun wallsForProject(projectId: String): Flow<List<WallEntity>>

    @Upsert
    suspend fun upsertWall(w: WallEntity)

    @Upsert
    suspend fun upsertWalls(w: List<WallEntity>)

    @Query("DELETE FROM walls WHERE id = :id")
    suspend fun deleteWall(id: String)

    @Query("DELETE FROM walls WHERE floorId = :floorId")
    suspend fun deleteWallsForFloor(floorId: String)

    // Rooms
    @Query("SELECT * FROM rooms WHERE floorId = :floorId")
    fun rooms(floorId: String): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE floorId = :floorId")
    suspend fun roomsList(floorId: String): List<RoomEntity>

    @Query("SELECT r.* FROM rooms r INNER JOIN floors f ON r.floorId = f.id WHERE f.projectId = :projectId")
    fun roomsForProject(projectId: String): Flow<List<RoomEntity>>

    @Upsert
    suspend fun upsertRoom(r: RoomEntity)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun deleteRoom(id: String)

    @Query("DELETE FROM rooms WHERE floorId = :floorId")
    suspend fun deleteRoomsForFloor(floorId: String)

    // Virtual APs
    @Query("SELECT a.* FROM virtual_aps a INNER JOIN floors f ON a.floorId = f.id WHERE f.projectId = :projectId")
    fun apsForProject(projectId: String): Flow<List<VirtualApEntity>>

    @Query("SELECT a.* FROM virtual_aps a INNER JOIN floors f ON a.floorId = f.id WHERE f.projectId = :projectId")
    suspend fun apsForProjectList(projectId: String): List<VirtualApEntity>

    @Query("SELECT * FROM virtual_aps WHERE floorId = :floorId")
    suspend fun apsForFloor(floorId: String): List<VirtualApEntity>

    @Upsert
    suspend fun upsertAp(a: VirtualApEntity)

    @Upsert
    suspend fun upsertAps(a: List<VirtualApEntity>)

    @Query("DELETE FROM virtual_aps WHERE id = :id")
    suspend fun deleteAp(id: String)

    @Query("DELETE FROM virtual_aps WHERE floorId = :floorId")
    suspend fun deleteApsForFloor(floorId: String)

    // Sessions
    @Query("SELECT * FROM survey_sessions WHERE floorId = :floorId ORDER BY startedAt DESC")
    fun sessions(floorId: String): Flow<List<SurveySessionEntity>>

    @Query("SELECT * FROM survey_sessions WHERE floorId = :floorId ORDER BY startedAt DESC")
    suspend fun sessionsList(floorId: String): List<SurveySessionEntity>

    @Query("SELECT s.* FROM survey_sessions s INNER JOIN floors f ON s.floorId = f.id WHERE f.projectId = :projectId ORDER BY s.startedAt DESC")
    fun sessionsForProject(projectId: String): Flow<List<SurveySessionEntity>>

    @Query("SELECT * FROM survey_sessions WHERE id = :id")
    suspend fun session(id: String): SurveySessionEntity?

    @Upsert
    suspend fun upsertSession(s: SurveySessionEntity)

    @Query("DELETE FROM survey_sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: String)

    // Points
    @Query("SELECT * FROM survey_points WHERE sessionId = :sessionId ORDER BY timestamp")
    fun points(sessionId: String): Flow<List<SurveyPointEntity>>

    @Query("SELECT * FROM survey_points WHERE sessionId IN (:sessionIds) ORDER BY timestamp")
    fun pointsForSessions(sessionIds: List<String>): Flow<List<SurveyPointEntity>>

    @Query("SELECT * FROM survey_points WHERE sessionId IN (:sessionIds) ORDER BY timestamp")
    suspend fun pointsForSessionsList(sessionIds: List<String>): List<SurveyPointEntity>

    @Upsert
    suspend fun upsertPoint(p: SurveyPointEntity)

    @Upsert
    suspend fun upsertPoints(p: List<SurveyPointEntity>)

    @Query("DELETE FROM survey_points WHERE id = :id")
    suspend fun deletePoint(id: String)

    @Query("DELETE FROM survey_points WHERE sessionId = :sessionId")
    suspend fun deletePointsForSession(sessionId: String)

    // Scans
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(b: ScanBatchEntity): Long

    @Insert
    suspend fun insertObservations(o: List<ScanObservationEntity>)

    @Query("SELECT o.* FROM scan_observations o WHERE o.batchId IN (SELECT DISTINCT p.scanBatchId FROM survey_points p WHERE p.sessionId IN (:sessionIds))")
    suspend fun observationsForSessions(sessionIds: List<String>): List<ScanObservationEntity>

    @Query("SELECT * FROM scan_observations WHERE batchId IN (:batchIds)")
    suspend fun observationsForBatches(batchIds: List<String>): List<ScanObservationEntity>

    @Query("SELECT * FROM scan_observations WHERE bssid = :bssid ORDER BY timestamp DESC LIMIT :limit")
    suspend fun historyForBssid(bssid: String, limit: Int = 500): List<ScanObservationEntity>

    @Query("SELECT MIN(timestamp) FROM scan_observations WHERE bssid = :bssid")
    suspend fun firstSeen(bssid: String): Long?

    @Query("SELECT COUNT(*) FROM scan_batches")
    fun batchCount(): Flow<Int>

    @Query("DELETE FROM scan_observations WHERE batchId NOT IN (SELECT scanBatchId FROM survey_points WHERE scanBatchId IS NOT NULL)")
    suspend fun pruneUnreferencedObservations()

    @Query("DELETE FROM scan_batches WHERE id NOT IN (SELECT scanBatchId FROM survey_points WHERE scanBatchId IS NOT NULL)")
    suspend fun pruneUnreferencedBatches()

    @Query("SELECT * FROM scan_batches WHERE id IN (:ids)")
    suspend fun batches(ids: List<String>): List<ScanBatchEntity>

    // Diagnostics
    @Insert
    suspend fun insertDiagnostic(d: DiagnosticResultEntity): Long

    @Query("SELECT * FROM diagnostic_results ORDER BY timestamp DESC LIMIT :limit")
    fun recentDiagnostics(limit: Int = 200): Flow<List<DiagnosticResultEntity>>

    @Query("SELECT * FROM diagnostic_results ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentDiagnosticsList(limit: Int = 200): List<DiagnosticResultEntity>

    @Query("DELETE FROM diagnostic_results")
    suspend fun clearDiagnostics()

    // Roaming
    @Insert
    suspend fun insertRoaming(r: RoamingEventEntity): Long

    @Query("SELECT * FROM roaming_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentRoaming(limit: Int = 200): Flow<List<RoamingEventEntity>>

    @Query("SELECT * FROM roaming_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentRoamingList(limit: Int = 200): List<RoamingEventEntity>

    @Query("DELETE FROM roaming_events")
    suspend fun clearRoaming()

    // Connection samples
    @Insert
    suspend fun insertSample(s: ConnectionSampleEntity): Long

    @Query("SELECT * FROM connection_samples WHERE tag = :tag ORDER BY timestamp")
    suspend fun samplesForTag(tag: String): List<ConnectionSampleEntity>
}

@Database(
    entities = [
        ProjectEntity::class, FloorEntity::class, WallEntity::class, RoomEntity::class,
        VirtualApEntity::class, SurveySessionEntity::class, SurveyPointEntity::class,
        ScanBatchEntity::class, ScanObservationEntity::class, DiagnosticResultEntity::class,
        RoamingEventEntity::class, ConnectionSampleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): JaminaDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jamina.db").build()
    }
}
