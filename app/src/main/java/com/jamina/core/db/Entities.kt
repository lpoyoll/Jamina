package com.jamina.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val notes: String = "",
)

/**
 * A building level. Plan coordinates are in "plan units" (image pixels for imported plans,
 * or an arbitrary drawing grid for blueprints). metersPerUnit converts to metres.
 * offsetXm/offsetYm align floors vertically in a shared building frame.
 */
@Serializable
@Entity(tableName = "floors", indices = [Index("projectId")])
data class FloorEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val level: Int,
    val ceilingHeightM: Float = 2.5f,
    val floorMaterial: String = "TIMBER_FLOOR",
    val imagePath: String? = null,
    val widthUnits: Float = 1200f,
    val heightUnits: Float = 800f,
    val metersPerUnit: Float = 0.025f,
    val calibrated: Boolean = false,
    val offsetXm: Float = 0f,
    val offsetYm: Float = 0f,
) {
    val widthM: Float get() = widthUnits * metersPerUnit
    val heightM: Float get() = heightUnits * metersPerUnit
}

@Serializable
@Entity(tableName = "walls", indices = [Index("floorId")])
data class WallEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val material: String,
    val type: String = "WALL",
    val exterior: Boolean = false,
)

@Serializable
@Entity(tableName = "rooms", indices = [Index("floorId")])
data class RoomEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val name: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val kind: String = "ROOM",
    val noApZone: Boolean = false,
    val priority: Boolean = false,
) {
    fun contains(px: Float, py: Float): Boolean {
        val l = minOf(x, x + w); val r = maxOf(x, x + w)
        val t = minOf(y, y + h); val b = maxOf(y, y + h)
        return px in l..r && py in t..b
    }
}

/** A planned (virtual) access point used by the predictive planner. */
@Serializable
@Entity(tableName = "virtual_aps", indices = [Index("floorId")])
data class VirtualApEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val scenario: String = "Default",
    val name: String,
    val x: Float,
    val y: Float,
    val mountHeightM: Float = 2.4f,
    val vendor: String = "Generic",
    val model: String = "Generic router",
    val standard: String = "Wi-Fi 6",
    val band: String = "B5",
    val channel: Int = 36,
    val widthMhz: Int = 80,
    val txPowerDbm: Float = 20f,
    val antennaGainDbi: Float = 3f,
    val directional: Boolean = false,
    val azimuthDeg: Float = 0f,
    val beamwidthDeg: Float = 90f,
    val enabled: Boolean = true,
)

@Serializable
@Entity(tableName = "survey_sessions", indices = [Index("floorId")])
data class SurveySessionEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val name: String,
    val mode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val isBaseline: Boolean = false,
    val deviceModel: String = "",
    val androidVersion: String = "",
    val notes: String = "",
)

@Serializable
@Entity(tableName = "survey_points", indices = [Index("sessionId"), Index("scanBatchId")])
data class SurveyPointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val positionSource: String = "MANUAL",
    val accuracyM: Float = 0.5f,
    val connectedSsid: String? = null,
    val connectedBssid: String? = null,
    val connectedRssi: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequency: Int? = null,
    val latencyMs: Float? = null,
    val packetLossPct: Float? = null,
    val jitterMs: Float? = null,
    val throughputMbps: Float? = null,
    val scanBatchId: String? = null,
    val scanFresh: Boolean = true,
    val scanAgeMs: Long = 0,
)

@Serializable
@Entity(tableName = "scan_batches")
data class ScanBatchEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val fresh: Boolean,
    val source: String = "SCANNER",
)

@Serializable
@Entity(tableName = "scan_observations", indices = [Index("batchId"), Index("bssid")])
data class ScanObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val timestamp: Long,
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val widthMhz: Int,
    val centerFreq0: Int = 0,
    val standard: String = "",
    val capabilities: String = "",
    val ageMs: Long = 0,
)

@Serializable
@Entity(tableName = "diagnostic_results")
data class DiagnosticResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val target: String,
    val valueMs: Float? = null,
    val lossPct: Float? = null,
    val jitterMs: Float? = null,
    val mbps: Float? = null,
    val success: Boolean = true,
    val method: String = "",
    val details: String = "",
)

@Serializable
@Entity(tableName = "roaming_events")
data class RoamingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val ssid: String?,
    val fromBssid: String?,
    val toBssid: String?,
    val rssiBefore: Int?,
    val rssiAfter: Int?,
    val freqBefore: Int?,
    val freqAfter: Int?,
    val interruptionMs: Long?,
    val note: String = "",
)

@Serializable
@Entity(tableName = "connection_samples")
data class ConnectionSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tag: String,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val frequency: Int?,
    val linkSpeedMbps: Int?,
    val latencyMs: Float?,
    val lossPct: Float?,
)
