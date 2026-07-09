package com.atvriders.wifiheatmap.core.engine

import com.atvriders.wifiheatmap.core.assemble.GpsSampleAssembler
import com.atvriders.wifiheatmap.core.assemble.TapSampleAssembler
import com.atvriders.wifiheatmap.core.assemble.UndoResult
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.HeatFilter
import com.atvriders.wifiheatmap.core.model.PositionFix
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.PositioningMode
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import com.atvriders.wifiheatmap.core.wifi.ThrottleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Live heads-up numbers for the survey HUD, refreshed on every [SignalSnapshot].
 *
 * @property currentRssiDbm RSSI in dBm of the reading currently shown in the HUD (the best
 *   reading matching the engine's stats filter, falling back to the connected network's
 *   reading), or null when the latest snapshot had no usable reading.
 * @property currentSsid SSID of the reading behind [currentRssiDbm], or null.
 * @property currentBand [Band] of the reading behind [currentRssiDbm], or null.
 * @property fresh Whether the latest snapshot carried a fresh (non-cached) value
 *   ([SignalSnapshot.fresh]).
 * @property sampleCount Number of finalized [PositionedSample]s currently held by the engine
 *   (already reduced after an undo).
 * @property apCount Cumulative count of distinct BSSIDs observed since [SurveyEngine.start]
 *   (never decreases).
 * @property elapsedMs Milliseconds elapsed between [SurveyEngine.start] and the latest
 *   snapshot, on the engine's injected clock.
 * @property waitingForFix GPS mode only: true before the first accepted fix and while the
 *   newest accepted fix is too stale to position snapshots; always false in TAP mode.
 * @property paused True between [SurveyEngine.pause] and [SurveyEngine.resume].
 */
data class LiveStats(
    val currentRssiDbm: Int? = null,
    val currentSsid: String? = null,
    val currentBand: Band? = null,
    val fresh: Boolean = false,
    val sampleCount: Int = 0,
    val apCount: Int = 0,
    val elapsedMs: Long = 0,
    val waitingForFix: Boolean = false,
    val paused: Boolean = false,
)

/**
 * Aggregate of every reading observed for one SSID since the survey started.
 *
 * @property ssid Network name as broadcast (may be empty for hidden networks).
 * @property bssidCount Number of distinct BSSIDs (APs/radios) seen advertising this SSID.
 * @property maxRssi Strongest RSSI in dBm ever observed for this SSID.
 * @property readingCount Total number of [WifiReading]s observed for this SSID (repeats count).
 */
data class SsidSummary(
    val ssid: String,
    val bssidCount: Int,
    val maxRssi: Int,
    val readingCount: Int,
)

/** Discrete survey-data mutations, for consumers that need deltas rather than full lists. */
sealed interface SurveyEvent {
    /** [samples] were appended to [SurveyEngine.samples] (positions in plan units). */
    data class SamplesAdded(val samples: List<PositionedSample>) : SurveyEvent

    /** All samples with [PositionedSample.segmentIndex] == [segmentIndex] were removed (undo). */
    data class SegmentRemoved(val segmentIndex: Int) : SurveyEvent
}

/**
 * Orchestrates one live survey: collects [SignalSnapshot]s (and GPS [PositionFix]es), turns
 * them into positioned samples via the mode's assembler, exposes them as flows for the UI,
 * and persists them through the [SampleSink].
 *
 * Units and conventions:
 *  - Sample positions are plan units — floor-plan pixels in TAP mode, local meters in GPS
 *    mode; x grows east/right, y grows DOWNWARD (screen convention).
 *  - [clock] supplies milliseconds on the SAME monotonic timebase as
 *    [SignalSnapshot.timestampMs] and [PositionFix.timestampMs] (on Android, inject
 *    `SystemClock.elapsedRealtime()`); the engine never reads a clock itself.
 *
 * Threading: NOT thread-safe. Confine [start], [stop], [onTap], [undoLastSegment], [pause],
 * [resume] and the [scope]'s dispatcher to one single-threaded dispatcher (e.g. Main).
 * Sink writes are launched on [scope] and chained so they reach the sink strictly in
 * submission order (append before a later undo's delete).
 *
 * @property surveyId Storage key passed through to every [SampleSink] call.
 * @property mode TAP (tap-to-position) or GPS (fix-to-position).
 * @param signalSource Snapshot provider; its [SignalSource.throttle] is re-exposed as [throttle].
 * @param positionSource Required in GPS mode, ignored (may be null) in TAP mode.
 * @param sink Persistence port; receives batches, never single-sample spam in GPS mode.
 * @param scope Scope the collectors and sink writes run on; cancelling it kills the engine.
 * @param clock Milliseconds provider (see conventions above).
 * @param filterForStats Which reading feeds [LiveStats.currentRssiDbm] in scan-all mode:
 *   the best (strongest) reading matching this filter wins, with the connected network's
 *   reading as fallback when nothing matches. Default matches everything.
 * @param gpsFlushEvery GPS mode: flush the pending sink batch after this many samples (>= 1).
 * @param gpsFlushMs GPS mode: flush when the oldest pending sample is at least this many
 *   milliseconds old (> 0), checked as each sample arrives (no wall timers).
 */
class SurveyEngine(
    val surveyId: Long,
    val mode: PositioningMode,
    private val signalSource: SignalSource,
    private val positionSource: PositionSource?,
    private val sink: SampleSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val filterForStats: HeatFilter = HeatFilter(),
    gpsFlushEvery: Int = 10,
    gpsFlushMs: Long = 5_000,
    /** TAP mode: first segment index for this session (resume passes max stored + 1). */
    firstSegmentIndex: Int = 0,
    /** GPS mode: pre-seeded projection origin for resumed surveys (see [GpsSampleAssembler]). */
    gpsOriginLatLon: Pair<Double, Double>? = null,
) {
    init {
        require(mode != PositioningMode.GPS || positionSource != null) {
            "GPS mode requires a PositionSource"
        }
        require(gpsFlushEvery >= 1) { "gpsFlushEvery must be >= 1: $gpsFlushEvery" }
        require(gpsFlushMs > 0) { "gpsFlushMs must be > 0: $gpsFlushMs" }
    }

    private val flushEvery: Int = gpsFlushEvery
    private val flushMs: Long = gpsFlushMs

    private val tapAssembler: TapSampleAssembler? =
        if (mode == PositioningMode.TAP) TapSampleAssembler(firstSegmentIndex) else null
    private val gpsAssembler: GpsSampleAssembler? =
        if (mode == PositioningMode.GPS) {
            GpsSampleAssembler(presetOriginLatLon = gpsOriginLatLon)
        } else {
            null
        }

    private val _samples = MutableStateFlow<List<PositionedSample>>(emptyList())

    /** All finalized samples so far: append-only immutable list snapshots (undo replaces the list). */
    val samples: StateFlow<List<PositionedSample>> = _samples.asStateFlow()

    private val _events = MutableSharedFlow<SurveyEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Delta events, replay 0. Emitted with `tryEmit` (never suspends); the 64-slot buffer
     * drops the OLDEST event if a collector falls that far behind — slow consumers should
     * treat [samples] as the source of truth.
     */
    val events: SharedFlow<SurveyEvent> = _events.asSharedFlow()

    private val _liveStats = MutableStateFlow(LiveStats())

    /** HUD numbers; see [LiveStats] for field semantics and units. */
    val liveStats: StateFlow<LiveStats> = _liveStats.asStateFlow()

    private val _ssidsSeen = MutableStateFlow<List<SsidSummary>>(emptyList())

    /**
     * Every SSID observed in any snapshot since [start], sorted by [SsidSummary.maxRssi]
     * descending. Maintained incrementally from a per-SSID aggregate map (never rescans
     * samples); undo does NOT retract observations.
     */
    val ssidsSeen: StateFlow<List<SsidSummary>> = _ssidsSeen.asStateFlow()

    /** Pass-through of the signal source's empirically detected scan-throttle state. */
    val throttle: StateFlow<ThrottleState> get() = signalSource.throttle

    /**
     * TAP mode: plan-unit position of the most recent tap (the open segment's start), or null
     * before the first tap / after undoing it. Always null in GPS mode.
     */
    val currentAnchor: Vec2? get() = tapAssembler?.currentAnchor

    private class SsidAgg {
        val bssids = mutableSetOf<String>()
        var maxRssi = Int.MIN_VALUE
        var readingCount = 0
    }

    private val ssidAggregates = LinkedHashMap<String, SsidAgg>()
    private val seenBssids = mutableSetOf<String>()

    private val jobs = mutableListOf<Job>()
    private var sinkChain: Job? = null
    private var started = false
    private var stopped = false
    private var startedAtMs = 0L
    private var gpsPaused = false

    /** GPS mode: samples already published to [samples] but not yet written to the sink. */
    private val pendingGps = mutableListOf<PositionedSample>()
    private var pendingSinceMs = 0L

    /**
     * Starts collecting: launches the snapshot collector (and, in GPS mode, the fix
     * collector) on [scope], then starts the sources. Idempotent; calling again after
     * [stop] does nothing.
     */
    fun start() {
        if (started) return
        started = true
        startedAtMs = clock()
        if (mode == PositioningMode.GPS) {
            _liveStats.value = _liveStats.value.copy(waitingForFix = true)
        }
        jobs += scope.launch { signalSource.snapshots.collect { onSnapshotInternal(it) } }
        if (mode == PositioningMode.GPS) {
            jobs += scope.launch { positionSource!!.fixes.collect { onFixInternal(it) } }
        }
        signalSource.start()
        if (mode == PositioningMode.GPS) positionSource!!.start()
    }

    /**
     * Stops the survey: cancels the collectors, stops the sources, and flushes any pending
     * unsunk samples to the sink (as a final chained write on [scope]). Idempotent.
     */
    fun stop() {
        if (!started || stopped) return
        stopped = true
        for (job in jobs) job.cancel()
        jobs.clear()
        signalSource.stop()
        if (mode == PositioningMode.GPS) positionSource!!.stop()
        flushPendingGps()
    }

    /**
     * Suspends until every sink write enqueued so far (including [stop]'s final flush) has
     * completed. Callers that navigate away or cancel [scope] after stopping MUST await this
     * first, or the tail batch can be cancelled mid-transaction and silently lost.
     */
    suspend fun drainSink() {
        sinkChain?.join()
    }

    /**
     * TAP mode: registers a surveyor tap at [point] (plan units: floor-plan pixels; x east,
     * y down) at the injected clock's current time. The first tap (and the first tap after
     * [resume]) only anchors and returns an empty list. A later tap finalizes the segment:
     * the returned batch is appended to [samples], emitted as [SurveyEvent.SamplesAdded],
     * and written to the sink as one batch. No-op returning an empty list in GPS mode and
     * while paused.
     */
    fun onTap(point: Vec2): List<PositionedSample> {
        val assembler = tapAssembler ?: return emptyList()
        val batch = assembler.onTap(point, clock())
        if (batch.isEmpty()) return batch
        _samples.value = _samples.value + batch
        _events.tryEmit(SurveyEvent.SamplesAdded(batch))
        _liveStats.value = _liveStats.value.copy(sampleCount = _samples.value.size)
        enqueueSinkWrite { sink.appendSamples(surveyId, batch) }
        return batch
    }

    /**
     * TAP mode: undoes the most recent tap via [TapSampleAssembler.undoLastTap]. When the
     * undone tap had finalized a segment, that segment's samples are removed from [samples],
     * [SurveyEvent.SegmentRemoved] is emitted, and [SampleSink.deleteSegment] is called
     * (chained after any earlier append). Anchor-only taps undo with no data change
     * (`removedSegmentIndex == null`). Returns null when there is nothing to undo, and
     * always null in GPS mode.
     */
    fun undoLastSegment(): UndoResult? {
        val assembler = tapAssembler ?: return null
        val result = assembler.undoLastTap() ?: return null
        val segmentIndex = result.removedSegmentIndex
        if (segmentIndex != null) {
            _samples.value = _samples.value.filterNot { it.segmentIndex == segmentIndex }
            _events.tryEmit(SurveyEvent.SegmentRemoved(segmentIndex))
            _liveStats.value = _liveStats.value.copy(sampleCount = _samples.value.size)
            enqueueSinkWrite { sink.deleteSegment(surveyId, segmentIndex) }
        }
        return result
    }

    /**
     * Pauses sample production ([LiveStats.paused] becomes true). TAP mode delegates to the
     * assembler (buffer discarded; the next tap after [resume] only re-anchors). GPS mode
     * gates assembly: snapshots still refresh [liveStats]/[ssidsSeen] but produce no samples.
     */
    fun pause() {
        when (mode) {
            PositioningMode.TAP -> tapAssembler!!.pause()
            PositioningMode.GPS -> gpsPaused = true
        }
        _liveStats.value = _liveStats.value.copy(paused = true)
    }

    /** Resumes after [pause]; see [pause] for the TAP-mode re-anchor requirement. */
    fun resume() {
        when (mode) {
            PositioningMode.TAP -> tapAssembler!!.resume()
            PositioningMode.GPS -> gpsPaused = false
        }
        _liveStats.value = _liveStats.value.copy(paused = false)
    }

    private fun onSnapshotInternal(s: SignalSnapshot) {
        for (r in s.readings) {
            seenBssids.add(r.bssid)
            val agg = ssidAggregates.getOrPut(r.ssid) { SsidAgg() }
            agg.bssids.add(r.bssid)
            if (r.rssiDbm > agg.maxRssi) agg.maxRssi = r.rssiDbm
            agg.readingCount++
        }
        _ssidsSeen.value = ssidAggregates.entries
            .map { (ssid, agg) -> SsidSummary(ssid, agg.bssids.size, agg.maxRssi, agg.readingCount) }
            .sortedByDescending { it.maxRssi }

        val statsReading = pickStatsReading(s.readings)
        _liveStats.value = _liveStats.value.copy(
            currentRssiDbm = statsReading?.rssiDbm,
            currentSsid = statsReading?.ssid,
            currentBand = statsReading?.band,
            fresh = s.fresh,
            apCount = seenBssids.size,
            elapsedMs = clock() - startedAtMs,
        )

        when (mode) {
            PositioningMode.TAP -> tapAssembler!!.onSnapshot(s)
            PositioningMode.GPS -> onGpsSnapshot(s)
        }
    }

    private fun onGpsSnapshot(s: SignalSnapshot) {
        if (gpsPaused) return
        val assembler = gpsAssembler!!
        val sample = assembler.onSnapshot(s)
        if (sample == null) {
            _liveStats.value = _liveStats.value.copy(waitingForFix = assembler.waitingForFix)
            return
        }
        _samples.value = _samples.value + sample
        _events.tryEmit(SurveyEvent.SamplesAdded(listOf(sample)))
        if (pendingGps.isEmpty()) pendingSinceMs = clock()
        pendingGps.add(sample)
        _liveStats.value = _liveStats.value.copy(
            sampleCount = _samples.value.size,
            waitingForFix = false,
        )
        if (pendingGps.size >= flushEvery || clock() - pendingSinceMs >= flushMs) {
            flushPendingGps()
        }
    }

    private fun onFixInternal(fix: PositionFix) {
        val assembler = gpsAssembler!!
        assembler.onFix(fix)
        _liveStats.value = _liveStats.value.copy(waitingForFix = assembler.waitingForFix)
    }

    private fun flushPendingGps() {
        if (pendingGps.isEmpty()) return
        val batch = pendingGps.toList()
        pendingGps.clear()
        enqueueSinkWrite { sink.appendSamples(surveyId, batch) }
    }

    /** Chains sink writes on [scope] so they always reach the sink in submission order. */
    private fun enqueueSinkWrite(block: suspend () -> Unit) {
        val previous = sinkChain
        sinkChain = scope.launch {
            previous?.join()
            block()
        }
    }

    /**
     * The reading feeding [LiveStats]: strongest reading matching [filterForStats], else the
     * connected network's reading, else null.
     */
    private fun pickStatsReading(readings: List<WifiReading>): WifiReading? =
        readings
            .filter { filterForStats.bestRssi(listOf(it)) != null }
            .maxByOrNull { it.rssiDbm }
            ?: readings.firstOrNull { it.isConnected }
}
