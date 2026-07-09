package com.atvriders.wifiheatmap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atvriders.wifiheatmap.data.AppSettings
import com.atvriders.wifiheatmap.data.DistanceUnit
import com.atvriders.wifiheatmap.data.SignalUnit
import com.atvriders.wifiheatmap.di.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Thin write-through wrapper over [com.atvriders.wifiheatmap.data.SettingsStore]. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: Flow<AppSettings> = container.settings.settings

    fun setPollIntervalMs(v: Long) {
        viewModelScope.launch { container.settings.setPollIntervalMs(v) }
    }

    fun setSignalUnit(v: SignalUnit) {
        viewModelScope.launch { container.settings.setSignalUnit(v) }
    }

    fun setDistanceUnit(v: DistanceUnit) {
        viewModelScope.launch { container.settings.setDistanceUnit(v) }
    }

    fun setThresholdDbm(v: Int) {
        viewModelScope.launch { container.settings.setThresholdDbm(v) }
    }

    fun setIdwRadiusIndoorM(v: Double) {
        viewModelScope.launch { container.settings.setIdwRadiusIndoorM(v) }
    }

    fun setIdwRadiusOutdoorM(v: Double) {
        viewModelScope.launch { container.settings.setIdwRadiusOutdoorM(v) }
    }

    fun setKeepScreenOn(v: Boolean) {
        viewModelScope.launch { container.settings.setKeepScreenOn(v) }
    }

    fun setThemeMode(v: String) {
        viewModelScope.launch { container.settings.setThemeMode(v) }
    }

    fun setColorblindScale(v: Boolean) {
        viewModelScope.launch { container.settings.setColorblindScale(v) }
    }
}
