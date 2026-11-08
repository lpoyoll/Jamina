package com.jamina.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jamina.core.model.MaterialTable
import com.jamina.core.model.SignalThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jamina_settings")

data class AppSettings(
    val thresholds: SignalThresholds = SignalThresholds(),
    val idwPower: Float = 2f,
    val idwRadiusM: Float = 6f,
    val wallAware: Boolean = true,
    val requireFreshScan: Boolean = true,
    val latencyAtPoints: Boolean = false,
    val throughputAtPoints: Boolean = false,
    val pathLossExponent: Float = 2.2f,
    val modelOffsetDb: Float = 0f,
    val receiverHeightM: Float = 1.0f,
    val materialOverrides: String = "",
    val internetHost: String = "1.1.1.1",
    val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=50000000",
    val uploadUrl: String = "https://speed.cloudflare.com/__up",
    val lanUrl: String = "",
    val apAliases: String = "",
    val advancedMode: Boolean = false,
) {
    val materialTable: MaterialTable get() = MaterialTable.decode(materialOverrides)

    val aliases: Map<String, String>
        get() = apAliases.split("\n").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).lowercase() to it.substring(i + 1)
        }.toMap()

    fun aliasOf(bssid: String?): String? = bssid?.let { aliases[it.lowercase()] }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val tEx = intPreferencesKey("t_ex")
        val tVg = intPreferencesKey("t_vg")
        val tGood = intPreferencesKey("t_good")
        val tFair = intPreferencesKey("t_fair")
        val tWeak = intPreferencesKey("t_weak")
        val idwPower = floatPreferencesKey("idw_power")
        val idwRadius = floatPreferencesKey("idw_radius")
        val wallAware = booleanPreferencesKey("wall_aware")
        val fresh = booleanPreferencesKey("require_fresh")
        val latPts = booleanPreferencesKey("lat_points")
        val tputPts = booleanPreferencesKey("tput_points")
        val ple = floatPreferencesKey("ple")
        val offset = floatPreferencesKey("model_offset")
        val rxH = floatPreferencesKey("rx_height")
        val materials = stringPreferencesKey("materials")
        val host = stringPreferencesKey("internet_host")
        val dl = stringPreferencesKey("dl_url")
        val ul = stringPreferencesKey("ul_url")
        val lan = stringPreferencesKey("lan_url")
        val aliases = stringPreferencesKey("aliases")
        val advanced = booleanPreferencesKey("advanced")
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            thresholds = SignalThresholds(
                p[K.tEx] ?: d.thresholds.excellent,
                p[K.tVg] ?: d.thresholds.veryGood,
                p[K.tGood] ?: d.thresholds.good,
                p[K.tFair] ?: d.thresholds.fair,
                p[K.tWeak] ?: d.thresholds.weak,
            ),
            idwPower = p[K.idwPower] ?: d.idwPower,
            idwRadiusM = p[K.idwRadius] ?: d.idwRadiusM,
            wallAware = p[K.wallAware] ?: d.wallAware,
            requireFreshScan = p[K.fresh] ?: d.requireFreshScan,
            latencyAtPoints = p[K.latPts] ?: d.latencyAtPoints,
            throughputAtPoints = p[K.tputPts] ?: d.throughputAtPoints,
            pathLossExponent = p[K.ple] ?: d.pathLossExponent,
            modelOffsetDb = p[K.offset] ?: d.modelOffsetDb,
            receiverHeightM = p[K.rxH] ?: d.receiverHeightM,
            materialOverrides = p[K.materials] ?: d.materialOverrides,
            internetHost = p[K.host] ?: d.internetHost,
            downloadUrl = p[K.dl] ?: d.downloadUrl,
            uploadUrl = p[K.ul] ?: d.uploadUrl,
            lanUrl = p[K.lan] ?: d.lanUrl,
            apAliases = p[K.aliases] ?: d.apAliases,
            advancedMode = p[K.advanced] ?: d.advancedMode,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(settings.value)
            p[K.tEx] = s.thresholds.excellent
            p[K.tVg] = s.thresholds.veryGood
            p[K.tGood] = s.thresholds.good
            p[K.tFair] = s.thresholds.fair
            p[K.tWeak] = s.thresholds.weak
            p[K.idwPower] = s.idwPower
            p[K.idwRadius] = s.idwRadiusM
            p[K.wallAware] = s.wallAware
            p[K.fresh] = s.requireFreshScan
            p[K.latPts] = s.latencyAtPoints
            p[K.tputPts] = s.throughputAtPoints
            p[K.ple] = s.pathLossExponent
            p[K.offset] = s.modelOffsetDb
            p[K.rxH] = s.receiverHeightM
            p[K.materials] = s.materialOverrides
            p[K.host] = s.internetHost
            p[K.dl] = s.downloadUrl
            p[K.ul] = s.uploadUrl
            p[K.lan] = s.lanUrl
            p[K.aliases] = s.apAliases
            p[K.advanced] = s.advancedMode
        }
    }

    suspend fun setAlias(bssid: String, name: String) = update { s ->
        val map = s.aliases.toMutableMap()
        if (name.isBlank()) map.remove(bssid.lowercase()) else map[bssid.lowercase()] = name.trim()
        s.copy(apAliases = map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
}
