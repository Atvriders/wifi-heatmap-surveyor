package com.atvriders.wifiheatmap.core.engine

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class SurveyEngineTest {

    /** Deterministic injected clock; same timebase as snapshot/fix timestamps. */
    private var now = 0L

    private class FakeSignalSource : SignalSource {
        private val flow = MutableSharedFlow<SignalSnapshot>(extraBufferCapacity = 64)
        val throttleState = MutableStateFlow<ThrottleState>(ThrottleState.Unknown)
        var started = 0
        var stopped = 0

        override val snapshots: Flow<SignalSnapshot> get() = flow
        override val throttle: StateFlow<ThrottleState> get() = throttleState
        override fun start() { started++ }
        override fun stop() { stopped++ }

        suspend fun emit(s: SignalSnapshot) { flow.emit(s) }
    }

    private class FakePositionSource : PositionSource {
        private val flow = MutableSharedFlow<PositionFix>(extraBufferCapacity = 64)
        var started = 0
        var stopped = 0

        override val fixes: Flow<PositionFix> get() = flow
        override fun start() { started++ }
        override fun stop() { stopped++ }

        suspend fun emit(fix: PositionFix) { flow.emit(fix) }
    }

    private class RecordingSink : SampleSink {
        val appended = mutableListOf<Pair<Long, List<PositionedSample>>>()
        val deleted = mutableListOf<Pair<Long, Int>>()

        override suspend fun appendSamples(surveyId: Long, batch: List<PositionedSample>) {
            appended += surveyId to batch
        }

        override suspend fun deleteSegment(surveyId: Long, segmentIndex: Int) {
            deleted += surveyId to segmentIndex
        }
    }

    private fun reading(
        bssid: String,
        ssid: String,
        rssi: Int,
        connected: Boolean = false,
        band: Band = Band.GHZ_5,
    ) = WifiReading(
        bssid = bssid,
        ssid = ssid,
        rssiDbm = rssi,
        frequencyMhz = if (band == Band.GHZ_2_4) 2437 else 5180,
        band = band,
        isConnected = connected,
    )

    private fun snap(t: Long, vararg readings: WifiReading, fresh: Boolean = true) =
        SignalSnapshot(timestampMs = t, readings = readings.toList(), fresh = fresh)

    private fun tapEngine(
        scope: CoroutineScope,
        signal: FakeSignalSource,
        sink: RecordingSink,
        filter: HeatFilter = HeatFilter(),
    ) = SurveyEngine(
        surveyId = 7L,
        mode = PositioningMode.TAP,
        signalSource = signal,
        positionSource = null,
        sink = sink,
        scope = scope,
        clock = { now },
        filterForStats = filter,
    )

    private fun TestScope.collectEvents(engine: SurveyEngine): MutableList<SurveyEvent> {
        val events = mutableListOf<SurveyEvent>()
        backgroundScope.launch { engine.events.collect { events += it } }
        return events
    }

    @Test
    fun tapFlowEndToEnd() = runTest {
        val signal = FakeSignalSource()
        val sink = RecordingSink()
        val engine = tapEngine(backgroundScope, signal, sink)
        val events = collectEvents(engine)

        engine.start()
        runCurrent()
        assertEquals(1, signal.started)

        now = 1_000
        assertTrue(engine.onTap(Vec2(0.0, 0.0)).isEmpty()) // anchor tap
        assertEquals(Vec2(0.0, 0.0), engine.currentAnchor)

        now = 1_250
        signal.emit(snap(1_250, reading("aa", "net", -50, connected = true)))
        runCurrent()
        now = 1_500
        signal.emit(snap(1_500, reading("aa", "net", -60, connected = true)))
        runCurrent()

        now = 2_000
        val batch = engine.onTap(Vec2(100.0, 40.0))

        assertEquals(2, batch.size)
        // Lerped positions: t=1250 is 25% along 1000..2000, t=1500 is 50%.
        assertEquals(25.0, batch[0].x, 1e-9)
        assertEquals(10.0, batch[0].y, 1e-9)
        assertEquals(50.0, batch[1].x, 1e-9)
        assertEquals(20.0, batch[1].y, 1e-9)
        assertEquals(0, batch[0].segmentIndex)
        assertEquals(0, batch[1].segmentIndex)
        assertEquals(batch, engine.samples.value)
        assertEquals(2, engine.liveStats.value.sampleCount)

        runCurrent()
        assertEquals(listOf(7L to batch), sink.appended) // ONE batch
        assertEquals(listOf<SurveyEvent>(SurveyEvent.SamplesAdded(batch)), events)
    }

    @Test
    fun undoRemovesSegmentEverywhere() = runTest {
        val signal = FakeSignalSource()
        val sink = RecordingSink()
        val engine = tapEngine(backgroundScope, signal, sink)
        val events = collectEvents(engine)

        engine.start()
        runCurrent()
        now = 1_000
        engine.onTap(Vec2(0.0, 0.0))
        signal.emit(snap(1_500, reading("aa", "net", -50)))
        runCurrent()
        now = 2_000
        val batch = engine.onTap(Vec2(10.0, 0.0))
        assertEquals(1, batch.size)
        runCurrent()

        val result = engine.undoLastSegment()
        assertEquals(0, result?.removedSegmentIndex)
        assertTrue(engine.samples.value.isEmpty())
        assertEquals(0, engine.liveStats.value.sampleCount)

        runCurrent()
        assertEquals(listOf(7L to 0), sink.deleted)
        assertTrue(events.contains(SurveyEvent.SegmentRemoved(0)))
        // Sink saw the append strictly before the delete.
        assertEquals(1, sink.appended.size)
    }

    @Test
    fun liveStatsTrackSnapshots() = runTest {
        val signal = FakeSignalSource()
        val engine = tapEngine(backgroundScope, signal, RecordingSink())
        engine.start() // started at now = 0
        runCurrent()

        now = 5_000
        signal.emit(
            snap(
                5_000,
                reading("aa", "alpha", -52),
                reading("bb", "beta", -45, band = Band.GHZ_2_4),
            ),
        )
        runCurrent()
        var stats = engine.liveStats.value
        assertEquals(-45, stats.currentRssiDbm) // best reading wins
        assertEquals("beta", stats.currentSsid)
        assertEquals(Band.GHZ_2_4, stats.currentBand)
        assertTrue(stats.fresh)
        assertEquals(2, stats.apCount)
        assertEquals(5_000L, stats.elapsedMs)
        assertEquals(0, stats.sampleCount)

        now = 8_000
        signal.emit(snap(8_000, reading("cc", "alpha", -70), fresh = false))
        runCurrent()
        stats = engine.liveStats.value
        assertEquals(-70, stats.currentRssiDbm)
        assertFalse(stats.fresh)
        assertEquals(3, stats.apCount) // distinct BSSIDs are cumulative
        assertEquals(8_000L, stats.elapsedMs)
    }

    @Test
    fun statsFilterNarrowsAndFallsBackToConnected() = runTest {
        val signal = FakeSignalSource()
        val engine = tapEngine(backgroundScope, signal, RecordingSink(), HeatFilter(ssid = "alpha"))
        engine.start()
        runCurrent()

        // "beta" is stronger but the stats filter pins "alpha".
        signal.emit(snap(1_000, reading("aa", "alpha", -52), reading("bb", "beta", -45)))
        runCurrent()
        assertEquals(-52, engine.liveStats.value.currentRssiDbm)
        assertEquals("alpha", engine.liveStats.value.currentSsid)

        // No filter match at all: fall back to the connected reading.
        signal.emit(snap(2_000, reading("bb", "beta", -45), reading("cc", "gamma", -80, connected = true)))
        runCurrent()
        assertEquals(-80, engine.liveStats.value.currentRssiDbm)
        assertEquals("gamma", engine.liveStats.value.currentSsid)
    }

    @Test
    fun ssidsSeenAggregatesAndSorts() = runTest {
        val signal = FakeSignalSource()
        val engine = tapEngine(backgroundScope, signal, RecordingSink())
        engine.start()
        runCurrent()

        signal.emit(snap(1_000, reading("a1", "alpha", -50), reading("b1", "beta", -70)))
        runCurrent()
        signal.emit(snap(2_000, reading("a1", "alpha", -40), reading("a2", "alpha", -55)))
        runCurrent()

        val seen = engine.ssidsSeen.value
        assertEquals(2, seen.size)
        assertEquals(SsidSummary("alpha", bssidCount = 2, maxRssi = -40, readingCount = 3), seen[0])
        assertEquals(SsidSummary("beta", bssidCount = 1, maxRssi = -70, readingCount = 1), seen[1])
    }

    @Test
    fun gpsFlowBatchesAndStopFlushes() = runTest {
        val signal = FakeSignalSource()
        val position = FakePositionSource()
        val sink = RecordingSink()
        val engine = SurveyEngine(
            surveyId = 9L,
            mode = PositioningMode.GPS,
            signalSource = signal,
            positionSource = position,
            sink = sink,
            scope = backgroundScope,
            clock = { now },
            gpsFlushEvery = 3,
            gpsFlushMs = 60_000,
        )
        val events = collectEvents(engine)

        engine.start()
        runCurrent()
        assertEquals(1, position.started)
        assertTrue(engine.liveStats.value.waitingForFix)

        now = 1_000
        position.emit(PositionFix(1_000, 45.0, -122.0, accuracyM = 5f))
        runCurrent()
        assertFalse(engine.liveStats.value.waitingForFix)

        now = 1_100
        signal.emit(snap(1_100, reading("aa", "net", -50)))
        runCurrent()
        now = 1_200
        signal.emit(snap(1_200, reading("aa", "net", -52)))
        runCurrent()
        assertEquals(2, engine.samples.value.size) // published immediately...
        assertTrue(sink.appended.isEmpty()) // ...but not yet sunk

        now = 1_300
        signal.emit(snap(1_300, reading("aa", "net", -54)))
        runCurrent()
        assertEquals(1, sink.appended.size) // gpsFlushEvery = 3 reached
        assertEquals(3, sink.appended[0].second.size)

        val first = engine.samples.value.first()
        assertEquals(0.0, first.x, 1e-9) // the origin fix projects to (0, 0)
        assertEquals(0.0, first.y, 1e-9)
        assertEquals(-1, first.segmentIndex)
        assertEquals(45.0, first.latitude!!, 1e-9)
        assertEquals(-122.0, first.longitude!!, 1e-9)

        now = 1_400
        signal.emit(snap(1_400, reading("aa", "net", -56)))
        runCurrent()
        assertEquals(4, engine.samples.value.size)
        assertEquals(1, sink.appended.size) // remainder still pending

        engine.stop()
        runCurrent()
        assertEquals(2, sink.appended.size) // stop() flushed the remainder
        assertEquals(1, sink.appended[1].second.size)
        assertEquals(1, signal.stopped)
        assertEquals(1, position.stopped)
        assertEquals(4, events.count { it is SurveyEvent.SamplesAdded }) // emitted per sample
    }

    @Test
    fun gpsTimeBasedFlush() = runTest {
        val signal = FakeSignalSource()
        val position = FakePositionSource()
        val sink = RecordingSink()
        val engine = SurveyEngine(
            surveyId = 9L,
            mode = PositioningMode.GPS,
            signalSource = signal,
            positionSource = position,
            sink = sink,
            scope = backgroundScope,
            clock = { now },
            gpsFlushEvery = 100,
            gpsFlushMs = 5_000,
        )
        engine.start()
        runCurrent()

        now = 1_000
        position.emit(PositionFix(1_000, 45.0, -122.0, 5f))
        runCurrent()
        now = 1_100
        signal.emit(snap(1_100, reading("aa", "net", -50)))
        runCurrent()
        assertTrue(sink.appended.isEmpty())

        now = 6_500
        position.emit(PositionFix(6_500, 45.0, -122.0, 5f)) // keep the fix non-stale
        runCurrent()
        now = 6_600
        signal.emit(snap(6_600, reading("aa", "net", -52)))
        runCurrent()

        // Oldest pending sample is 5 500 ms old (>= gpsFlushMs) -> time-based flush of both.
        assertEquals(1, sink.appended.size)
        assertEquals(2, sink.appended[0].second.size)
    }

    @Test
    fun pauseBlocksSampleProductionTap() = runTest {
        val signal = FakeSignalSource()
        val engine = tapEngine(backgroundScope, signal, RecordingSink())
        engine.start()
        runCurrent()

        now = 1_000
        engine.onTap(Vec2(0.0, 0.0))
        signal.emit(snap(1_100, reading("aa", "net", -50)))
        runCurrent()

        engine.pause()
        assertTrue(engine.liveStats.value.paused)
        signal.emit(snap(1_200, reading("aa", "net", -55))) // discarded
        runCurrent()
        now = 1_300
        assertTrue(engine.onTap(Vec2(10.0, 10.0)).isEmpty()) // taps ignored while paused
        assertTrue(engine.samples.value.isEmpty())

        engine.resume()
        assertFalse(engine.liveStats.value.paused)
        now = 1_400
        assertTrue(engine.onTap(Vec2(10.0, 10.0)).isEmpty()) // re-anchor only
        signal.emit(snap(1_500, reading("aa", "net", -60)))
        runCurrent()
        now = 1_600
        val batch = engine.onTap(Vec2(20.0, 10.0))

        assertEquals(1, batch.size) // only the post-re-anchor snapshot
        assertEquals(0, batch[0].segmentIndex)
        assertEquals(15.0, batch[0].x, 1e-9) // t=1500 is halfway along 1400..1600
        assertEquals(10.0, batch[0].y, 1e-9)
        assertEquals(batch, engine.samples.value)
    }

    @Test
    fun pauseBlocksSampleProductionGps() = runTest {
        val signal = FakeSignalSource()
        val position = FakePositionSource()
        val engine = SurveyEngine(
            surveyId = 9L,
            mode = PositioningMode.GPS,
            signalSource = signal,
            positionSource = position,
            sink = RecordingSink(),
            scope = backgroundScope,
            clock = { now },
        )
        engine.start()
        runCurrent()
        now = 1_000
        position.emit(PositionFix(1_000, 45.0, -122.0, 5f))
        runCurrent()
        now = 1_100
        signal.emit(snap(1_100, reading("aa", "net", -50)))
        runCurrent()
        assertEquals(1, engine.samples.value.size)

        engine.pause()
        now = 1_200
        signal.emit(snap(1_200, reading("aa", "net", -52)))
        runCurrent()
        assertEquals(1, engine.samples.value.size) // blocked
        assertEquals(-52, engine.liveStats.value.currentRssiDbm) // HUD still live

        engine.resume()
        now = 1_300
        signal.emit(snap(1_300, reading("aa", "net", -54)))
        runCurrent()
        assertEquals(2, engine.samples.value.size)
    }

    @Test
    fun throttlePassesThroughAndUndoOnEmptyIsNull() = runTest {
        val signal = FakeSignalSource()
        val engine = tapEngine(backgroundScope, signal, RecordingSink())

        assertEquals(ThrottleState.Unknown, engine.throttle.value)
        signal.throttleState.value = ThrottleState.Throttled(nextScanEtaMs = 123L)
        assertEquals(ThrottleState.Throttled(123L), engine.throttle.value)

        assertNull(engine.undoLastSegment()) // nothing to undo yet
    }
}
