package com.atvriders.wifiheatmap.ui.wizard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atvriders.wifiheatmap.core.geo.ScaleCalibration
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.PositioningMode
import com.atvriders.wifiheatmap.core.model.ScanMode
import com.atvriders.wifiheatmap.data.FloorPlanImageStore
import com.atvriders.wifiheatmap.data.db.FloorPlanEntity
import com.atvriders.wifiheatmap.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Which floor-plan source the user picked on the wizard's floor-plan step. */
enum class PlanChoice { IMPORT, BLANK_GRID }

/**
 * Holds all state entered across the New Survey wizard steps and turns it into a
 * persisted survey. Survives step navigation and configuration changes.
 */
class WizardViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val name: String = defaultSurveyName(),
        val mode: PositioningMode = PositioningMode.TAP,
        val scanMode: ScanMode = ScanMode.CONNECTED,
        val planChoice: PlanChoice? = null,
        val importedPlan: FloorPlanImageStore.ImportedPlan? = null,
        val importing: Boolean = false,
        val importError: String? = null,
        val gridWidthText: String = "20.0",
        val gridHeightText: String = "15.0",
        /** Calibration pin A in floor-plan pixel space. */
        val calA: Vec2? = null,
        /** Calibration pin B in floor-plan pixel space. */
        val calB: Vec2? = null,
        val distanceText: String = "",
        val calibrationSkipped: Boolean = false,
        val creating: Boolean = false,
    ) {
        val gridWidthM: Double? get() = gridWidthText.toDoubleOrNull()?.takeIf { it > 0.0 }
        val gridHeightM: Double? get() = gridHeightText.toDoubleOrNull()?.takeIf { it > 0.0 }
        val realDistanceM: Double? get() = distanceText.toDoubleOrNull()?.takeIf { it > 0.0 }

        /** Computed scale, or null while calibration inputs are incomplete/degenerate. */
        val metersPerPixel: Double?
            get() {
                val a = calA ?: return null
                val b = calB ?: return null
                val d = realDistanceM ?: return null
                return ScaleCalibration.metersPerPixel(a, b, d)
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Non-null once the survey row is persisted; the screen navigates on it. */
    private val _createdSurveyId = MutableStateFlow<Long?>(null)
    val createdSurveyId: StateFlow<Long?> = _createdSurveyId.asStateFlow()

    private inline fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun setName(value: String) = update { it.copy(name = value) }

    fun setMode(value: PositioningMode) = update { it.copy(mode = value) }

    fun setScanMode(value: ScanMode) = update { it.copy(scanMode = value) }

    fun selectBlankGrid() = update { it.copy(planChoice = PlanChoice.BLANK_GRID) }

    fun setGridWidthText(value: String) = update { it.copy(gridWidthText = value) }

    fun setGridHeightText(value: String) = update { it.copy(gridHeightText = value) }

    fun setDistanceText(value: String) =
        update { it.copy(distanceText = value, calibrationSkipped = false) }

    /**
     * Places a calibration pin at [p] (floor-plan pixels): first tap sets A, second
     * sets B, later taps move whichever existing pin is nearer.
     */
    fun placeCalibrationPin(p: Vec2) = update { s ->
        when {
            s.calA == null -> s.copy(calA = p, calibrationSkipped = false)
            s.calB == null -> s.copy(calB = p, calibrationSkipped = false)
            p.distanceTo(s.calA) <= p.distanceTo(s.calB) ->
                s.copy(calA = p, calibrationSkipped = false)
            else -> s.copy(calB = p, calibrationSkipped = false)
        }
    }

    fun clearCalibrationPins() =
        update { it.copy(calA = null, calB = null, calibrationSkipped = false) }

    fun skipCalibration() = update { it.copy(calibrationSkipped = true) }

    /** Copies the picked document into app storage; previous import (if any) is deleted. */
    fun importImage(uri: Uri) {
        val previousPath = _state.value.importedPlan?.path
        update { it.copy(planChoice = PlanChoice.IMPORT, importing = true, importError = null) }
        viewModelScope.launch {
            container.floorPlans.importImage(uri).fold(
                onSuccess = { plan ->
                    if (previousPath != null && previousPath != plan.path) {
                        withContext(Dispatchers.IO) { container.floorPlans.delete(previousPath) }
                    }
                    update {
                        it.copy(
                            importing = false,
                            importedPlan = plan,
                            importError = null,
                            // New geometry invalidates any pins placed on the old image.
                            calA = null,
                            calB = null,
                            distanceText = "",
                            calibrationSkipped = false,
                        )
                    }
                },
                onFailure = { e ->
                    update {
                        it.copy(
                            importing = false,
                            importError = e.message ?: "Could not import that image",
                        )
                    }
                },
            )
        }
    }

    /**
     * Deletes the imported image when the wizard is abandoned before creation.
     * No-op while creation is in flight or once a survey was created: the persisted
     * survey owns the image then.
     */
    fun discardDraft() {
        if (_state.value.creating || _createdSurveyId.value != null) return
        val path = _state.value.importedPlan?.path ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            container.floorPlans.delete(path)
        }
    }

    /**
     * Persists the survey (and its floor plan, if any) and emits the new id on
     * [createdSurveyId]. [nowMs] is wall-clock (Room createdAtMs).
     */
    fun createSurvey(nowMs: Long) {
        val s = _state.value
        if (s.creating || _createdSurveyId.value != null) return
        update { it.copy(creating = true) }
        viewModelScope.launch {
            val plan: FloorPlanEntity? = when {
                s.mode == PositioningMode.GPS -> null

                s.planChoice == PlanChoice.IMPORT && s.importedPlan != null -> {
                    val imported = s.importedPlan
                    // The survey is calibrated IFF a valid scale is computable from the two
                    // pins + real distance (ScaleCalibration.metersPerPixel returns null for
                    // degenerate/absent input). The sticky calibrationSkipped flag drives UI
                    // only and must NOT drop a computable calibration.
                    val mpp = s.metersPerPixel
                    FloorPlanEntity(
                        imagePath = imported.path,
                        widthPx = imported.widthPx,
                        heightPx = imported.heightPx,
                        metersPerPixel = mpp,
                        calAx = if (mpp != null) s.calA?.x else null,
                        calAy = if (mpp != null) s.calA?.y else null,
                        calBx = if (mpp != null) s.calB?.x else null,
                        calBy = if (mpp != null) s.calB?.y else null,
                        calDistanceM = if (mpp != null) s.realDistanceM else null,
                    )
                }

                else -> {
                    // Blank-grid convention: W x H meters at 50 px/m => 0.02 m/px.
                    val widthM = s.gridWidthM ?: 20.0
                    val heightM = s.gridHeightM ?: 15.0
                    FloorPlanEntity(
                        imagePath = null,
                        widthPx = (widthM * 50).toInt(),
                        heightPx = (heightM * 50).toInt(),
                        metersPerPixel = 0.02,
                        calAx = null, calAy = null,
                        calBx = null, calBy = null,
                        calDistanceM = null,
                    )
                }
            }
            // NonCancellable: a mid-persist cancellation must not leave a half-created
            // survey or an orphaned floor-plan image.
            withContext(NonCancellable) {
                val id = container.repository.createSurvey(
                    name = s.name.trim().ifEmpty { defaultSurveyName() },
                    positioningMode = s.mode.name,
                    scanMode = s.scanMode.name,
                    floorPlan = plan,
                    nowMs = nowMs,
                )
                _createdSurveyId.value = id
            }
        }
    }

    companion object {
        fun defaultSurveyName(): String =
            "Survey " + DateFormat.getDateInstance().format(Date())
    }
}
