package com.atvriders.wifiheatmap.ui.live

import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atvriders.wifiheatmap.core.engine.HeatmapController
import com.atvriders.wifiheatmap.core.engine.HeatmapFrame
import com.atvriders.wifiheatmap.core.engine.LiveStats
import com.atvriders.wifiheatmap.core.engine.PositionSource
import com.atvriders.wifiheatmap.core.engine.SampleSink
import com.atvriders.wifiheatmap.core.engine.SignalSource
import com.atvriders.wifiheatmap.core.engine.SsidSummary
import com.atvriders.wifiheatmap.core.engine.SurveyEngine
import com.atvriders.wifiheatmap.core.engine.SurveyEvent
import com.atvriders.wifiheatmap.core.geo.GeoProjection
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.GridSpec
import com.atvriders.wifiheatmap.core.model.HeatFilter
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.PositioningMode
import com.atvriders.wifiheatmap.core.wifi.ThrottleState
import com.atvriders.wifiheatmap.data.AppSettings
import com.atvriders.wifiheatmap.data.db.FloorPlanEntity
import com.atvriders.wifiheatmap.data.db.SurveyEntity
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.sources.ConnectedRssiPoller
import com.atvriders.wifiheatmap.sources.GpsPositionSource
import com.atvriders.wifiheatmap.sources.ScanAllSignalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Orchestrates one live survey run: loads the survey + floor plan + settings, wires
 * the mode-appropriate sources into a [SurveyEngine], feeds engine events into a
 * [HeatmapController], and exposes everything the screen needs as [StateFlow]s.
 *
 * TIMEBASE: every timestamp fed to core rides `SystemClock.elapsedRealtime()`.
 */
class LiveSurveyViewModel(
    private val container: AppContainer,
    val surveyId: Long,
) : ViewModel() {

    private val repository = container.repository

    private val _survey = MutableStateFlow<SurveyEntity?>(null)

    /** Loaded survey row; null until the initial load completes. */
    val survey: StateFlow<SurveyEntity?> = _survey.asStateFlow()

    private val _floorPlan = MutableStateFlow<FloorPlanEntity?>(null)

    /** Loaded floor plan row (null for GPS surveys, and until loaded). */
    val floorPlan: StateFlow<FloorPlanEntity?> = _floorPlan.asStateFlow()

    private val _planBitmap = MutableStateFlow<ImageBitmap?>(null)

    /** Decoded floor plan image; null for blank-grid plans and GPS surveys. */
    val planBitmap: StateFlow<ImageBitmap?> = _planBitmap.asStateFlow()

    private val _appSettings = MutableStateFlow<AppSettings?>(null)

    /** Settings snapshot taken once at load (poll rate, radii, colorblind, keep-screen-on). */
    val appSettings: StateFlow<AppSettings?> = _appSettings.asStateFlow()

    private val controller = HeatmapController(viewModelScope)

    /** Latest rendered heatmap raster; null until the first frame lands. */
    val frames: StateFlow<HeatmapFrame?> = controller.frames

    private val _liveStats = MutableStateFlow(LiveStats())

    /** Mirror of the engine's HUD stats. */
    val liveStats: StateFlow<LiveStats> = _liveStats.asStateFlow()

    private val _throttle = MutableStateFlow<ThrottleState>(ThrottleState.Unknown)

    /** Mirror of the signal source's empirically detected scan-throttle state. */
    val throttle: StateFlow<ThrottleState> = _throttle.asStateFlow()

    private val _ssidsSeen = MutableStateFlow<List<SsidSummary>>(emptyList())

    /** Every SSID seen this session, strongest first. */
    val ssidsSeen: StateFlow<List<SsidSummary>> = _ssidsSeen.asStateFlow()

    private val _samples = MutableStateFlow<List<PositionedSample>>(emptyList())

    /** THIS session's finalized samples (mirror of the engine's list). */
    val samples: StateFlow<List<PositionedSample>> = _samples.asStateFlow()

    private val _priorSamples = MutableStateFlow<List<PositionedSample>>(emptyList())

    /** Samples persisted by previous sessions of this survey (resume support). */
    val priorSamples: StateFlow<List<PositionedSample>> = _priorSamples.asStateFlow()

    /** Convenience count of resumed-in samples. */
    val priorSampleCount: Int get() = _priorSamples.value.size

    private val _tapAnchors = MutableStateFlow<List<Vec2>>(emptyList())

    /** Plan-space positions of this session's taps, in tap order (undo pops the last). */
    val tapAnchors: StateFlow<List<Vec2>> = _tapAnchors.asStateFlow()

    private val _currentAnchor = MutableStateFlow<Vec2?>(null)

    /** The open segment's start (last tap), or null before the first tap / after undoing it. */
    val currentAnchor: StateFlow<Vec2?> = _currentAnchor.asStateFlow()

    private val _heatFilter = MutableStateFlow(HeatFilter())

    /** What the live heatmap visualizes; change via [setFilter]. */
    val heatFilter: StateFlow<HeatFilter> = _heatFilter.asStateFlow()

    private val _lastTapAtMs = MutableStateFlow(0L)

    /** elapsedRealtime of the most recent tap; 0 before the first tap. Drives the stale-tap nudge. */
    val lastTapAtMs: StateFlow<Long> = _lastTapAtMs.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the engine is built and started. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _loadError = MutableStateFlow(false)

    /** True if the survey row could not be loaded (missing/deleted); terminal state. */
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /** True once [finishAsync] has completed the durable finish (engine stop + drain + status). */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-shot user-facing messages (undo confirmations etc.). */
    val snackbarMessages: SharedFlow<String> = _snackbar.asSharedFlow()

    private var engine: SurveyEngine? = null

    /**
     * Engine + sink writes live on this scope, NOT viewModelScope: clearing the ViewModel
     * cancels viewModelScope before onCleared() runs, which would kill the engine's final
     * sink flush and silently lose the tail batch. This scope survives until the flush drains.
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Persists wall-clock timestamps: the engine runs on elapsedRealtime (monotonic, shared
     * with sources), but rows must carry real time so multi-session/reboot surveys export
     * and order correctly. Conversion happens once, at the sink boundary.
     */
    private class WallClockSink(
        private val delegate: SampleSink,
        private val wallAnchorMs: Long,
        private val elapsedAnchorMs: Long,
    ) : SampleSink {
        override suspend fun appendSamples(surveyId: Long, batch: List<PositionedSample>) =
            delegate.appendSamples(
                surveyId,
                batch.map { it.copy(timestampMs = wallAnchorMs + (it.timestampMs - elapsedAnchorMs)) },
            )

        override suspend fun deleteSegment(surveyId: Long, segmentIndex: Int) =
            delegate.deleteSegment(surveyId, segmentIndex)
    }

    init {
        viewModelScope.launch {
            val s = repository.surveyDao.getSurvey(surveyId) ?: run {
                _loadError.value = true
                return@launch
            }
            val plan = s.floorPlanId?.let { repository.surveyDao.getFloorPlan(it) }
            // Publish the plan BEFORE the survey so the screen never sees a
            // loaded survey with a missing plan (wrong canvas fit).
            _floorPlan.value = plan
            _survey.value = s
            val imagePath = plan?.imagePath
            if (imagePath != null) {
                launch(Dispatchers.IO) {
                    container.floorPlans.loadBitmap(imagePath)?.let {
                        _planBitmap.value = it.asImageBitmap()
                    }
                }
            }

            val settings = container.settings.settings.first()
            _appSettings.value = settings

            // Resume support.
            val prior = repository.loadSamples(surveyId)
            _priorSamples.value = prior
            val firstSegmentIndex = ((repository.sampleDao.maxSegmentIndex(surveyId)) ?: -1) + 1

            val mode = if (s.positioningMode == "GPS") PositioningMode.GPS else PositioningMode.TAP
            val signalSource: SignalSource = if (s.scanMode == "SCAN_ALL") {
                ScanAllSignalSource(container.appContext)
            } else {
                ConnectedRssiPoller(container.appContext, settings.pollIntervalMs)
            }
            val positionSource: PositionSource? =
                if (mode == PositioningMode.GPS) GpsPositionSource(container.appContext) else null

            // Resumed GPS surveys must keep projecting into the original session's frame,
            // recovered from any stored sample that carries both its fix and projection.
            val gpsOrigin = if (mode == PositioningMode.GPS) {
                prior.firstNotNullOfOrNull { sample ->
                    val lat = sample.latitude
                    val lon = sample.longitude
                    if (lat != null && lon != null) {
                        GeoProjection.originFromSample(lat, lon, sample.x, sample.y)
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            val eng = SurveyEngine(
                surveyId = surveyId,
                mode = mode,
                signalSource = signalSource,
                positionSource = positionSource,
                sink = WallClockSink(
                    delegate = repository,
                    wallAnchorMs = System.currentTimeMillis(),
                    elapsedAnchorMs = SystemClock.elapsedRealtime(),
                ),
                scope = engineScope,
                clock = { SystemClock.elapsedRealtime() },
                firstSegmentIndex = firstSegmentIndex,
                gpsOriginLatLon = gpsOrigin,
            )
            engine = eng

            // Pinned live grid config.
            val colorScale =
                if (settings.colorblindScale) ColorScale.ColorblindSafe else ColorScale.Default
            if (mode == PositioningMode.TAP) {
                val planW = (plan?.widthPx ?: DEFAULT_PLAN_DIM).toDouble()
                val planH = (plan?.heightPx ?: DEFAULT_PLAN_DIM).toDouble()
                val metersPerPixel = plan?.metersPerPixel
                val cutoff = if (metersPerPixel != null && metersPerPixel > 0.0) {
                    settings.idwRadiusIndoorM / metersPerPixel
                } else {
                    max(planW, planH) / 12.0
                }
                controller.configure(
                    GridSpec.forBounds(0.0, 0.0, planW, planH),
                    colorScale, cutoff, _heatFilter.value,
                )
            } else {
                controller.configure(
                    GridSpec.forBounds(-50.0, -50.0, 50.0, 50.0),
                    colorScale, settings.idwRadiusOutdoorM, _heatFilter.value,
                )
            }
            controller.setAll(prior)

            // Mirror engine flows (Main.immediate: subscribed before start() below).
            launch { eng.liveStats.collect { _liveStats.value = it } }
            launch { eng.throttle.collect { _throttle.value = it } }
            launch { eng.ssidsSeen.collect { _ssidsSeen.value = it } }
            launch { eng.samples.collect { _samples.value = it } }
            launch {
                eng.events.collect { event ->
                    when (event) {
                        is SurveyEvent.SamplesAdded -> controller.submit(event.samples)
                        is SurveyEvent.SegmentRemoved ->
                            controller.removeSegment(event.segmentIndex, allSamples())
                    }
                }
            }

            eng.start()
            _ready.value = true
        }
    }

    private fun allSamples(): List<PositionedSample> =
        _priorSamples.value + (engine?.samples?.value ?: emptyList())

    /** TAP mode: registers a tap at [planPoint] (floor-plan pixels). No-op in GPS mode / paused. */
    fun onTap(planPoint: Vec2) {
        val eng = engine ?: return
        if (eng.mode != PositioningMode.TAP || _liveStats.value.paused) return
        eng.onTap(planPoint)
        _tapAnchors.value = _tapAnchors.value + planPoint
        _currentAnchor.value = eng.currentAnchor
        _lastTapAtMs.value = SystemClock.elapsedRealtime()
    }

    /** Undoes the most recent tap (removing its finalized segment, if any). */
    fun undo() {
        val eng = engine ?: return
        val before = eng.samples.value
        val result = eng.undoLastSegment() ?: return
        val removedSegment = result.removedSegmentIndex
        val message = if (removedSegment != null) {
            val n = before.count { it.segmentIndex == removedSegment }
            "Removed point + $n samples"
        } else {
            "Removed point"
        }
        _snackbar.tryEmit(message)
        if (_tapAnchors.value.isNotEmpty()) _tapAnchors.value = _tapAnchors.value.dropLast(1)
        _currentAnchor.value = eng.currentAnchor
    }

    /** Pauses sample production; paused state is reflected in [liveStats]. */
    fun pause() {
        engine?.pause()
    }

    /**
     * Resumes after [pause]. Assembler contract: in TAP mode the NEXT tap only
     * re-anchors — no samples are produced until the tap after it.
     */
    fun resume() {
        engine?.resume()
    }

    /** Swaps the heatmap filter and rebuilds the grid from all (prior + session) samples. */
    fun setFilter(filter: HeatFilter) {
        _heatFilter.value = filter
        controller.setFilter(filter, allSamples())
    }

    /**
     * Stops the engine, waits for every queued sample write to land, then marks the
     * survey COMPLETE. Only after this returns may the caller navigate away.
     */
    suspend fun finish() {
        engine?.let {
            it.stop()
            it.drainSink()
        }
        repository.surveyDao.setStatus(surveyId, "COMPLETE")
    }

    /**
     * Durable wrapper around [finish]: runs on viewModelScope so it survives config
     * changes (rotation). The drain completes even if the screen is recreated; when it
     * finishes, [finished] flips true so the screen can navigate away exactly once.
     */
    fun finishAsync() {
        viewModelScope.launch {
            finish()
            _finished.value = true
        }
    }

    override fun onCleared() {
        controller.stop()
        val eng = engine ?: run {
            engineScope.cancel()
            return
        }
        // stop() enqueues the final flush on engineScope (alive, independent of
        // viewModelScope); the scope is cancelled only after the flush drains.
        runCatching { eng.stop() }
        engineScope.launch {
            runCatching { eng.drainSink() }
            engineScope.cancel()
        }
    }

    private companion object {
        /** Fallback plan dimension when a TAP survey unexpectedly has no floor plan row. */
        const val DEFAULT_PLAN_DIM = 500
    }
}
