package com.jamina.core.model

import kotlin.math.roundToInt

/** Wi-Fi frequency band. */
enum class Band(val label: String, val refFreqMhz: Int) {
    B24("2.4 GHz", 2437),
    B5("5 GHz", 5500),
    B6("6 GHz", 6500);

    companion object {
        fun fromFreq(freqMhz: Int): Band? = when (freqMhz) {
            in 2400..2500 -> B24
            in 4900..5899 -> B5
            in 5925..7125 -> B6
            else -> null
        }

        fun of(name: String?): Band? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Building material with default frequency-dependent attenuation (dB per crossing).
 * Values are typical published figures and are only defaults; users can override them.
 */
enum class Material(
    val label: String,
    val loss24: Float,
    val loss5: Float,
    val loss6: Float,
    val colorArgb: Long,
    val thicknessM: Float,
) {
    PLASTERBOARD("Plasterboard", 3f, 4f, 5f, 0xFF9E9E9E, 0.10f),
    TIMBER("Timber stud", 4f, 6f, 7f, 0xFF8D6E63, 0.12f),
    BRICK("Brick", 8f, 12f, 14f, 0xFFB5523B, 0.22f),
    BLOCK("Breeze block", 9f, 14f, 16f, 0xFF78909C, 0.22f),
    CONCRETE("Concrete", 12f, 18f, 22f, 0xFF607D8B, 0.25f),
    REINFORCED_CONCRETE("Reinforced concrete", 18f, 25f, 30f, 0xFF37474F, 0.30f),
    GLASS("Glass", 2f, 3f, 4f, 0xFF4FC3F7, 0.06f),
    COATED_GLASS("Low-E coated glass", 10f, 15f, 18f, 0xFF0288D1, 0.06f),
    METAL("Metal", 26f, 32f, 35f, 0xFF212121, 0.06f),
    FOIL_BOARD("Insulated foil board", 20f, 26f, 30f, 0xFFFFB300, 0.10f),
    WOOD_DOOR("Wooden door", 3f, 4f, 5f, 0xFFA1887F, 0.06f),
    TIMBER_FLOOR("Timber floor / ceiling", 8f, 12f, 14f, 0xFF6D4C41, 0.25f),
    CONCRETE_SLAB("Concrete floor slab", 15f, 22f, 26f, 0xFF455A64, 0.25f);

    fun defaultLoss(band: Band): Float = when (band) {
        Band.B24 -> loss24
        Band.B5 -> loss5
        Band.B6 -> loss6
    }

    companion object {
        val wallMaterials = listOf(
            PLASTERBOARD, TIMBER, BRICK, BLOCK, CONCRETE, REINFORCED_CONCRETE,
            GLASS, COATED_GLASS, METAL, FOIL_BOARD, WOOD_DOOR,
        )
        val floorMaterials = listOf(TIMBER_FLOOR, CONCRETE_SLAB, REINFORCED_CONCRETE)
        fun of(name: String?): Material = entries.firstOrNull { it.name == name } ?: PLASTERBOARD
    }
}

/** Attenuation table with optional user overrides (serialised as "NAME:a,b,c;NAME:a,b,c"). */
class MaterialTable(val overrides: Map<Material, FloatArray> = emptyMap()) {
    fun loss(material: Material, band: Band): Float {
        val o = overrides[material] ?: return material.defaultLoss(band)
        return o[band.ordinal]
    }

    fun encode(): String = overrides.entries.joinToString(";") { (m, v) -> "${m.name}:${v.joinToString(",")}" }

    fun with(material: Material, band: Band, value: Float): MaterialTable {
        val current = overrides[material]?.copyOf()
            ?: floatArrayOf(material.loss24, material.loss5, material.loss6)
        current[band.ordinal] = value
        return MaterialTable(overrides + (material to current))
    }

    companion object {
        fun decode(s: String?): MaterialTable {
            if (s.isNullOrBlank()) return MaterialTable()
            val map = mutableMapOf<Material, FloatArray>()
            for (part in s.split(";")) {
                val kv = part.split(":")
                if (kv.size != 2) continue
                val m = Material.entries.firstOrNull { it.name == kv[0] } ?: continue
                val vals = kv[1].split(",").mapNotNull { it.toFloatOrNull() }
                if (vals.size == 3) map[m] = vals.toFloatArray()
            }
            return MaterialTable(map)
        }
    }
}

enum class SignalLevel(val label: String, val colorArgb: Long) {
    EXCELLENT("Excellent", 0xFF1B5E20),
    VERY_GOOD("Very good", 0xFF43A047),
    GOOD("Good", 0xFF9CCC65),
    FAIR("Fair", 0xFFFDD835),
    WEAK("Weak", 0xFFFB8C00),
    POOR("Poor", 0xFFE53935),
}

/** User-configurable RSSI thresholds (lower bounds, dBm). */
data class SignalThresholds(
    val excellent: Int = -50,
    val veryGood: Int = -60,
    val good: Int = -67,
    val fair: Int = -70,
    val weak: Int = -75,
) {
    fun levelOf(rssi: Float): SignalLevel = when {
        rssi >= excellent -> SignalLevel.EXCELLENT
        rssi >= veryGood -> SignalLevel.VERY_GOOD
        rssi >= good -> SignalLevel.GOOD
        rssi >= fair -> SignalLevel.FAIR
        rssi >= weak -> SignalLevel.WEAK
        else -> SignalLevel.POOR
    }

    fun levelOf(rssi: Int): SignalLevel = levelOf(rssi.toFloat())

    fun bandLabel(level: SignalLevel): String = when (level) {
        SignalLevel.EXCELLENT -> "≥ $excellent dBm"
        SignalLevel.VERY_GOOD -> "$veryGood to ${excellent - 1} dBm"
        SignalLevel.GOOD -> "$good to ${veryGood - 1} dBm"
        SignalLevel.FAIR -> "$fair to ${good - 1} dBm"
        SignalLevel.WEAK -> "$weak to ${fair - 1} dBm"
        SignalLevel.POOR -> "< $weak dBm"
    }

    fun isValid(): Boolean = excellent > veryGood && veryGood > good && good > fair && fair > weak
}

enum class SurveyMode(val label: String) { POINT("Point"), WALK("Walking"), CONTINUOUS("Continuous") }

enum class PositionSource(val label: String) {
    MANUAL("Tapped position"),
    ESTIMATED("Estimated from checkpoints"),
    PINNED("Pinned while recording"),
}

enum class WallType(val label: String) { WALL("Wall"), DOOR("Door"), WINDOW("Window") }

enum class RoomKind(val label: String) { ROOM("Room"), STAIRS("Stairs") }

fun Float.fmt(decimals: Int = 1): String = "%.${decimals}f".format(this)
fun Double.fmt(decimals: Int = 1): String = "%.${decimals}f".format(this)
fun Float.pct(): String = "${(this * 100).roundToInt()}%"
