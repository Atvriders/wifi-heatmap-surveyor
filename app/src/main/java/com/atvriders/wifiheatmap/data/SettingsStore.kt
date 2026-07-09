package com.atvriders.wifiheatmap.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DistanceUnit { METERS, FEET }
enum class SignalUnit { DBM, PERCENT }

data class AppSettings(
    val pollIntervalMs: Long = 1_000,
    val signalUnit: SignalUnit = SignalUnit.DBM,
    val distanceUnit: DistanceUnit = DistanceUnit.METERS,
    val thresholdDbm: Int = -67,
    val idwRadiusIndoorM: Double = 8.0,
    val idwRadiusOutdoorM: Double = 20.0,
    val keepScreenOn: Boolean = true,
    val themeMode: String = "SYSTEM",          // SYSTEM | LIGHT | DARK
    val colorblindScale: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val poll = longPreferencesKey("poll_interval_ms")
        val signalUnit = stringPreferencesKey("signal_unit")
        val distanceUnit = stringPreferencesKey("distance_unit")
        val threshold = intPreferencesKey("threshold_dbm")
        val radiusIndoor = floatPreferencesKey("idw_radius_indoor_m")
        val radiusOutdoor = floatPreferencesKey("idw_radius_outdoor_m")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val themeMode = stringPreferencesKey("theme_mode")
        val colorblind = booleanPreferencesKey("colorblind_scale")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings(distanceUnit = defaultDistanceUnitForCountry(currentCountry()))
        AppSettings(
            pollIntervalMs = p[Keys.poll] ?: defaults.pollIntervalMs,
            signalUnit = p[Keys.signalUnit]?.let { runCatching { SignalUnit.valueOf(it) }.getOrNull() }
                ?: defaults.signalUnit,
            distanceUnit = p[Keys.distanceUnit]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: defaults.distanceUnit,
            thresholdDbm = p[Keys.threshold] ?: defaults.thresholdDbm,
            idwRadiusIndoorM = (p[Keys.radiusIndoor] ?: defaults.idwRadiusIndoorM.toFloat()).toDouble(),
            idwRadiusOutdoorM = (p[Keys.radiusOutdoor] ?: defaults.idwRadiusOutdoorM.toFloat()).toDouble(),
            keepScreenOn = p[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            themeMode = p[Keys.themeMode] ?: defaults.themeMode,
            colorblindScale = p[Keys.colorblind] ?: defaults.colorblindScale,
        )
    }

    private fun currentCountry(): String =
        context.resources.configuration.locales.get(0)?.country.orEmpty()

    suspend fun setPollIntervalMs(v: Long) = context.dataStore.edit { it[Keys.poll] = v }
    suspend fun setSignalUnit(v: SignalUnit) = context.dataStore.edit { it[Keys.signalUnit] = v.name }
    suspend fun setDistanceUnit(v: DistanceUnit) = context.dataStore.edit { it[Keys.distanceUnit] = v.name }
    suspend fun setThresholdDbm(v: Int) = context.dataStore.edit { it[Keys.threshold] = v }
    suspend fun setIdwRadiusIndoorM(v: Double) = context.dataStore.edit { it[Keys.radiusIndoor] = v.toFloat() }
    suspend fun setIdwRadiusOutdoorM(v: Double) = context.dataStore.edit { it[Keys.radiusOutdoor] = v.toFloat() }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[Keys.keepScreenOn] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.themeMode] = v }
    suspend fun setColorblindScale(v: Boolean) = context.dataStore.edit { it[Keys.colorblind] = v }

    companion object {
        /** US, Liberia and Myanmar are the only non-metric holdouts. Pure and testable. */
        fun defaultDistanceUnitForCountry(isoCountry: String): DistanceUnit =
            when (isoCountry.uppercase()) {
                "US", "LR", "MM" -> DistanceUnit.FEET
                else -> DistanceUnit.METERS
            }
    }
}
