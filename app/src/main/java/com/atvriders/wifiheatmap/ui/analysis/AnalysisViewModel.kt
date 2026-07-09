package com.atvriders.wifiheatmap.ui.analysis

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atvriders.wifiheatmap.BuildConfig
import com.atvriders.wifiheatmap.core.engine.HeatmapController
import com.atvriders.wifiheatmap.core.engine.HeatmapFrame
import com.atvriders.wifiheatmap.core.export.CsvBuilder
import com.atvriders.wifiheatmap.core.export.CsvExportRow
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.Coverage
import com.atvriders.wifiheatmap.core.heatmap.CoverageStats
import com.atvriders.wifiheatmap.core.heatmap.GridSpec
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.HeatFilter
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.wifi.SignalStats
import com.atvriders.wifiheatmap.data.DistanceUnit
import com.atvriders.wifiheatmap.data.db.BssidRow
import com.atvriders.wifiheatmap.data.db.FloorPlanEntity
import com.atvriders.wifiheatmap.data.db.SsidSummaryRow
import com.atvriders.wifiheatmap.data.db.SurveyEntity
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.export.ExportComposer
import com.atvriders.wifiheatmap.export.ReportMeta
import com.atvriders.wifiheatmap.export.formatFixed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** How the analysis canvas colors the interpolated field. */
enum class DisplayMode { HEATMAP, PASS_FAIL }

/** Result of the most recent export; [id] is unique per export so effects re-fire. */
data class ExportEvent(val id: Long, val uri: Uri, val mimeType: String, val success: Boolean)

/**
 * Analysis-screen state holder. Rehydrates the survey from Room, drives its own
 * [HeatmapController] with the same grid configuration the live screen used, and
 * derives display pixels (pass/fail recolor + opacity) and coverage stats off-main.
 */
class AnalysisViewModel(
    private val container: AppContainer,
    private val surveyId: Long,
) : ViewModel() {

    private val repository = container.repository

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _survey = MutableStateFlow<SurveyEntity?>(null)
    val survey: StateFlow<SurveyEntity?> = _survey.asStateFlow()

    private val _floorPlan = MutableStateFlow<FloorPlanEntity?>(null)
    val floorPlan: StateFlow<FloorPlanEntity?> = _floorPlan.asStateFlow()

    private val _planBitmap = MutableStateFlow<Bitmap?>(null)
    val planBitmap: StateFlow<Bitmap?> = _planBitmap.asStateFlow()

    private val _samples = MutableStateFlow<List<PositionedSample>>(emptyList())
    val samples: StateFlow<List<PositionedSample>> = _samples.asStateFlow()

    /** Current heat filter; change via [setFilter] so the controller rebuilds too. */
    val filter = MutableStateFlow(HeatFilter())

    val displayMode = MutableStateFlow(DisplayMode.HEATMAP)

    /** Pass/fail + coverage threshold in dBm; initialized from settings on load. */
    val thresholdDbm = MutableStateFlow(-67)

    /** Heatmap layer opacity multiplier, applied onto the scale's own alpha. */
    val heatmapOpacity = MutableStateFlow(0.75f)

    val showDots = MutableStateFlow(true)
    val showPath = MutableStateFlow(false)

    val distanceUnit = MutableStateFlow(DistanceUnit.METERS)

    /** Color scale per the colorblind setting; fixed once settings load. */
    val colorScale = MutableStateFlow(ColorScale.Default)

    private val controller = HeatmapController(viewModelScope)

    /** Raw controller frames (heatmap colors, full scale alpha). */
    val frames: StateFlow<HeatmapFrame?> = controller.frames

    @Volatile
    private var isGps = false

    @Volatile
    private var metersPerPixel: Double? = null

    val ssidSummaries: StateFlow<List<SsidSummaryRow>> =
        repository.sampleDao.observeSsidSummaries(surveyId)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun bssidsFor(ssid: String): List<BssidRow> =
        repository.sampleDao.bssidsForSsid(surveyId, ssid)

    /** Display frames get their own generation sequence so recolors always invalidate. */
    private val displayGeneration = AtomicLong(1_000_000_000L)

    private data class DisplayArgs(
        val frame: HeatmapFrame?,
        val mode: DisplayMode,
        val thresholdDbm: Int,
        val opacity: Float,
    )

    /**
     * The frame the canvas should draw ([frame] pixels are recolored for pass/fail and
     * opacity-scaled; its generation is a fresh, strictly increasing display sequence)
     * plus the coverage computed over the same values and threshold.
     */
    data class DisplayState(val frame: HeatmapFrame?, val coverage: Coverage?)

    @OptIn(ExperimentalCoroutinesApi::class)
    val display: StateFlow<DisplayState> =
        combine(controller.frames, displayMode, thresholdDbm, heatmapOpacity) { frame, mode, threshold, opacity ->
            DisplayArgs(frame, mode, threshold, opacity)
        }
            .mapLatest { args -> withContext(Dispatchers.Default) { computeDisplay(args) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DisplayState(null, null))

    /** Order stats over the filtered per-sample best RSSIs; null when nothing matches. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val signalSummary: StateFlow<SignalStats.Summary?> =
        combine(filter, _samples) { f, all -> f to all }
            .mapLatest { (f, all) ->
                withContext(Dispatchers.Default) {
                    SignalStats.summarize(all.mapNotNull { f.bestRssi(it.readings) })
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _exportEvent = MutableStateFlow<ExportEvent?>(null)

    /** Most recent export result; stays set so the share affordance keeps working. */
    val exportEvent: StateFlow<ExportEvent?> = _exportEvent.asStateFlow()

    private val exportCounter = AtomicLong(0L)

    init {
        viewModelScope.launch {
            val survey = repository.surveyDao.getSurvey(surveyId)
            val plan = survey?.floorPlanId?.let { repository.surveyDao.getFloorPlan(it) }
            val settings = container.settings.settings.first()
            val all = repository.loadSamples(surveyId)

            thresholdDbm.value = settings.thresholdDbm
            distanceUnit.value = settings.distanceUnit
            colorScale.value =
                if (settings.colorblindScale) ColorScale.ColorblindSafe else ColorScale.Default
            isGps = survey?.positioningMode == "GPS"
            metersPerPixel = plan?.metersPerPixel

            _survey.value = survey
            _floorPlan.value = plan
            _samples.value = all

            plan?.imagePath?.let { path ->
                _planBitmap.value = withContext(Dispatchers.IO) {
                    container.floorPlans.loadBitmap(path)
                }
            }

            val spec: GridSpec
            val cutoffRadius: Double
            if (isGps) {
                val bounds = if (all.isEmpty()) null else repository.sampleDao.bounds(surveyId)
                spec = if (bounds != null) {
                    val padX = max(0.5, (bounds.maxX - bounds.minX) * 0.10)
                    val padY = max(0.5, (bounds.maxY - bounds.minY) * 0.10)
                    GridSpec.forBounds(
                        minX = bounds.minX - padX,
                        minY = bounds.minY - padY,
                        maxX = bounds.maxX + padX,
                        maxY = bounds.maxY + padY,
                    )
                } else {
                    GridSpec.forBounds(-50.0, -50.0, 50.0, 50.0)
                }
                cutoffRadius = settings.idwRadiusOutdoorM
            } else {
                val widthPx = (plan?.widthPx ?: 1000).toDouble()
                val heightPx = (plan?.heightPx ?: 1000).toDouble()
                spec = GridSpec.forBounds(0.0, 0.0, widthPx, heightPx)
                val mpp = plan?.metersPerPixel
                cutoffRadius =
                    if (mpp != null) settings.idwRadiusIndoorM / mpp
                    else max(widthPx, heightPx) / 12.0
            }
            controller.configure(spec, colorScale.value, cutoffRadius, filter.value)
            controller.setAll(all)
            _loading.value = false
        }
    }

    fun setFilter(newFilter: HeatFilter) {
        filter.value = newFilter
        controller.setFilter(newFilter, _samples.value)
    }

    private fun computeDisplay(args: DisplayArgs): DisplayState {
        val frame = args.frame ?: return DisplayState(null, null)
        val base = when (args.mode) {
            DisplayMode.HEATMAP -> frame.pixels
            DisplayMode.PASS_FAIL -> IntArray(frame.pixels.size).also {
                colorScale.value.passFailPixels(frame.values, args.thresholdDbm.toFloat(), it)
            }
        }
        val displayFrame = HeatmapFrame(
            values = frame.values,
            pixels = applyOpacity(base, args.opacity),
            gridW = frame.gridW,
            gridH = frame.gridH,
            generation = displayGeneration.incrementAndGet(),
            spec = frame.spec,
        )
        val coverage = CoverageStats.compute(
            values = frame.values,
            thresholdDbm = args.thresholdDbm.toFloat(),
            cellAreaM2 = cellAreaM2(frame.spec),
        )
        return DisplayState(displayFrame, coverage)
    }

    /** Scales every pixel's alpha byte by [opacity]; input arrays are never mutated. */
    private fun applyOpacity(pixels: IntArray, opacity: Float): IntArray {
        val factor = opacity.coerceIn(0f, 1f)
        if (factor >= 1f) return pixels
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a != 0) {
                out[i] = ((a * factor).roundToInt().coerceIn(0, 255) shl 24) or (p and 0xFFFFFF)
            }
        }
        return out
    }

    private fun cellAreaM2(spec: GridSpec): Double? {
        val mpp = metersPerPixel
        return when {
            isGps -> spec.cellSize * spec.cellSize
            mpp != null -> (spec.cellSize * mpp).let { it * it }
            else -> null
        }
    }

    /** "<name-sanitized>_yyyyMMdd-HHmm.<extension>" for the create-document dialogs. */
    fun suggestedFileName(extension: String): String {
        val raw = _survey.value?.name ?: "survey"
        val sanitized = raw
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifEmpty { "survey" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "${sanitized}_$stamp.$extension"
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            val success = runCatching {
                // NonCancellable: leaving the screen must not truncate the SAF document.
                withContext(NonCancellable) {
                    val survey = _survey.value ?: error("Survey not loaded")
                    val csv = withContext(Dispatchers.Default) {
                        buildCsv(survey, _floorPlan.value, _samples.value)
                    }
                    withContext(Dispatchers.IO) {
                        container.appContext.contentResolver.openOutputStream(uri)
                            ?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                            ?: error("Could not open output stream")
                    }
                }
            }.isSuccess
            _exportEvent.value =
                ExportEvent(exportCounter.incrementAndGet(), uri, "text/csv", success)
        }
    }

    private fun buildCsv(
        survey: SurveyEntity,
        plan: FloorPlanEntity?,
        all: List<PositionedSample>,
    ): String {
        val gps = survey.positioningMode == "GPS"
        val mpp = plan?.metersPerPixel
        val posSource = when {
            gps -> "gps"
            mpp != null -> "tap"
            else -> "tap_uncalibrated_px"
        }
        // Sample timestamps are persisted as wall-clock milliseconds; export them as-is.
        val rows = ArrayList<CsvExportRow>()
        for (sample in all) {
            val x = if (!gps && mpp != null) sample.x * mpp else sample.x
            val y = if (!gps && mpp != null) sample.y * mpp else sample.y
            for (reading in sample.readings) {
                rows.add(
                    CsvExportRow(
                        timestampMs = sample.timestampMs,
                        posSource = posSource,
                        segmentIndex = if (gps) null else sample.segmentIndex,
                        x = x,
                        y = y,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        accuracyM = sample.accuracyM,
                        ssid = reading.ssid,
                        bssid = reading.bssid,
                        band = when (reading.band) {
                            Band.GHZ_2_4 -> "2.4"
                            Band.GHZ_5 -> "5"
                            Band.GHZ_6 -> "6"
                        },
                        frequencyMhz = reading.frequencyMhz,
                        rssiDbm = reading.rssiDbm,
                        connected = reading.isConnected,
                    )
                )
            }
        }
        return CsvBuilder.build(rows)
    }

    fun exportPng(uri: Uri) {
        viewModelScope.launch {
            val success = runCatching {
                // NonCancellable: leaving the screen must not truncate the SAF document.
                withContext(NonCancellable) {
                    val survey = _survey.value ?: error("Survey not loaded")
                    val frame = controller.frames.value ?: error("Heatmap not rendered yet")
                    val plan = _floorPlan.value
                    val mode = displayMode.value
                    val threshold = thresholdDbm.value
                    val report = withContext(Dispatchers.Default) {
                        val pixelsOverride = if (mode == DisplayMode.PASS_FAIL) {
                            IntArray(frame.pixels.size).also {
                                colorScale.value.passFailPixels(frame.values, threshold.toFloat(), it)
                            }
                        } else {
                            null
                        }
                        val coverage = CoverageStats.compute(
                            values = frame.values,
                            thresholdDbm = threshold.toFloat(),
                            cellAreaM2 = cellAreaM2(frame.spec),
                        )
                        val meta = ReportMeta(
                            surveyName = survey.name,
                            dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                .format(Date(survey.createdAtMs)),
                            filterText = filterDescription(filter.value),
                            thresholdText = "Threshold >= $threshold dBm",
                            coverageText =
                                "Coverage ${(coverage.fractionPassing * 100).roundToInt()}% of surveyed area",
                            sampleCountText = "${_samples.value.size} samples",
                            scaleBarText = metersPerPixel?.let {
                                "Scale: ${formatFixed(it, 3)} m/px"
                            },
                            uncalibrated = !isGps && metersPerPixel == null,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                        ExportComposer().renderReport(
                            plan = _planBitmap.value,
                            planWidthPx = (plan?.widthPx ?: 0).toDouble(),
                            planHeightPx = (plan?.heightPx ?: 0).toDouble(),
                            frame = frame,
                            pixelsOverride = pixelsOverride,
                            scale = colorScale.value,
                            isGps = isGps,
                            meta = meta,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            container.appContext.contentResolver.openOutputStream(uri)
                                ?.use { report.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                ?: error("Could not open output stream")
                        } finally {
                            report.recycle()
                        }
                    }
                }
            }.isSuccess
            _exportEvent.value =
                ExportEvent(exportCounter.incrementAndGet(), uri, "image/png", success)
        }
    }

    private fun filterDescription(f: HeatFilter): String = buildString {
        append(f.ssid ?: "All networks")
        f.bssid?.let { append(" / ").append(it) }
        f.band?.let { append(" (").append(it.label).append(")") }
    }

    override fun onCleared() {
        controller.stop()
    }
}
