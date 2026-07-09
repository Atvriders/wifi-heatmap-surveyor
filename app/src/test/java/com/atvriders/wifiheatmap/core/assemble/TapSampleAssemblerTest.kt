package com.atvriders.wifiheatmap.core.assemble

import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSampleAssemblerTest {

    private fun snapshot(timestampMs: Long, rssiDbm: Int = -55) = SignalSnapshot(
        timestampMs = timestampMs,
        readings = listOf(
            WifiReading(
                bssid = "aa:bb:cc:dd:ee:ff",
                ssid = "TestNet",
                rssiDbm = rssiDbm,
                frequencyMhz = 2437,
                band = Band.GHZ_2_4,
                isConnected = true,
            ),
        ),
    )

    @Test
    fun snapshotsBeforeFirstTapAreDiscarded() {
        val assembler = TapSampleAssembler()

        assembler.onSnapshot(snapshot(10))
        assembler.onSnapshot(snapshot(20))
        assertEquals(0, assembler.bufferedCount)
        assertNull(assembler.currentAnchor)

        assertTrue(assembler.onTap(Vec2(0.0, 0.0), 0).isEmpty())
        // Nothing was buffered before the first tap, so the closing tap yields no samples.
        assertTrue(assembler.onTap(Vec2(100.0, 0.0), 100).isEmpty())
    }

    @Test
    fun firstTapAnchorsWithoutFinalizing() {
        val assembler = TapSampleAssembler()

        val result = assembler.onTap(Vec2(3.0, 4.0), 1_000)

        assertTrue(result.isEmpty())
        assertEquals(Vec2(3.0, 4.0), assembler.currentAnchor)
        assertEquals(0, assembler.nextSegmentIndex)
        assertEquals(0, assembler.bufferedCount)
    }

    @Test
    fun lerpFractionsAreExact() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)
        assembler.onSnapshot(snapshot(25, rssiDbm = -40))
        assembler.onSnapshot(snapshot(50, rssiDbm = -50))
        assembler.onSnapshot(snapshot(75, rssiDbm = -60))
        assertEquals(3, assembler.bufferedCount)

        val samples = assembler.onTap(Vec2(100.0, 200.0), 100)

        assertEquals(3, samples.size)
        // t0=0, t1=100 → snapshot at 25 sits 25% along both axes.
        assertEquals(25.0, samples[0].x, 0.0)
        assertEquals(50.0, samples[0].y, 0.0)
        assertEquals(50.0, samples[1].x, 0.0)
        assertEquals(100.0, samples[1].y, 0.0)
        assertEquals(75.0, samples[2].x, 0.0)
        assertEquals(150.0, samples[2].y, 0.0)
        // Snapshot fields carried through; position metadata is TAP-mode shaped.
        assertEquals(25L, samples[0].timestampMs)
        assertEquals(-40, samples[0].readings.single().rssiDbm)
        assertEquals(0, samples[0].segmentIndex)
        assertNull(samples[0].latitude)
        assertNull(samples[0].longitude)
        assertNull(samples[0].accuracyM)
        // Buffer cleared, anchor advanced, next segment index bumped.
        assertEquals(0, assembler.bufferedCount)
        assertEquals(Vec2(100.0, 200.0), assembler.currentAnchor)
        assertEquals(1, assembler.nextSegmentIndex)
    }

    @Test
    fun zeroDurationSegmentPlacesAllSamplesAtClosingTap() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 100)
        assembler.onSnapshot(snapshot(100))

        val samples = assembler.onTap(Vec2(7.0, 9.0), 100)

        assertEquals(1, samples.size)
        assertEquals(7.0, samples[0].x, 0.0)
        assertEquals(9.0, samples[0].y, 0.0)
    }

    @Test
    fun snapshotTimestampsOutsideSegmentWindowAreClamped() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(10.0, 20.0), 100)
        assembler.onSnapshot(snapshot(50)) // before t0 → clamps to p0
        assembler.onSnapshot(snapshot(250)) // after t1 → clamps to p1

        val samples = assembler.onTap(Vec2(30.0, 40.0), 200)

        assertEquals(2, samples.size)
        assertEquals(10.0, samples[0].x, 0.0)
        assertEquals(20.0, samples[0].y, 0.0)
        assertEquals(30.0, samples[1].x, 0.0)
        assertEquals(40.0, samples[1].y, 0.0)
        // Original snapshot timestamps are preserved even when clamped for interpolation.
        assertEquals(50L, samples[0].timestampMs)
        assertEquals(250L, samples[1].timestampMs)
    }

    @Test
    fun consecutiveSegmentsGetIncrementingIndices() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)

        assembler.onSnapshot(snapshot(50))
        val seg0 = assembler.onTap(Vec2(10.0, 0.0), 100)
        assembler.onSnapshot(snapshot(150))
        val seg1 = assembler.onTap(Vec2(20.0, 0.0), 200)
        assembler.onSnapshot(snapshot(250))
        val seg2 = assembler.onTap(Vec2(30.0, 0.0), 300)

        assertEquals(0, seg0.single().segmentIndex)
        assertEquals(1, seg1.single().segmentIndex)
        assertEquals(2, seg2.single().segmentIndex)
        assertEquals(3, assembler.nextSegmentIndex)
    }

    @Test
    fun undoMidSurveyRemovesLastSegmentAndRestoresAnchor() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)
        assembler.onSnapshot(snapshot(50))
        assembler.onTap(Vec2(10.0, 0.0), 100) // finalizes segment 0
        assembler.onSnapshot(snapshot(150)) // buffered against the new anchor
        assertEquals(1, assembler.bufferedCount)

        val undo = assembler.undoLastTap()

        assertNotNull(undo)
        assertEquals(0, undo!!.removedSegmentIndex)
        assertEquals(Vec2(0.0, 0.0), undo.restoredAnchor)
        assertEquals(Vec2(0.0, 0.0), assembler.currentAnchor)
        assertEquals(0, assembler.nextSegmentIndex)
        assertEquals(0, assembler.bufferedCount)

        // Survey continues from the restored anchor and t0: segment index 0 is reused
        // and interpolation runs from the restored tap time.
        assembler.onSnapshot(snapshot(50))
        val redo = assembler.onTap(Vec2(20.0, 0.0), 200)
        assertEquals(1, redo.size)
        assertEquals(0, redo[0].segmentIndex)
        assertEquals(5.0, redo[0].x, 0.0) // t=50 of [0..200] → 25% of 20
        assertEquals(0.0, redo[0].y, 0.0)
        assertEquals(1, assembler.nextSegmentIndex)
    }

    @Test
    fun undoPastFirstTapClearsAnchor() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(5.0, 5.0), 0)

        val undo = assembler.undoLastTap()

        assertNotNull(undo)
        assertNull(undo!!.removedSegmentIndex)
        assertNull(undo.restoredAnchor)
        assertNull(assembler.currentAnchor)
        assertEquals(0, assembler.nextSegmentIndex)

        // Back to pre-first-tap state: snapshots are discarded again.
        assembler.onSnapshot(snapshot(10))
        assertEquals(0, assembler.bufferedCount)
    }

    @Test
    fun undoUnwindsSegmentsThenFirstTapThenReturnsNull() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)
        assembler.onSnapshot(snapshot(50))
        assembler.onTap(Vec2(10.0, 0.0), 100)

        val first = assembler.undoLastTap()
        assertEquals(UndoResult(0, Vec2(0.0, 0.0)), first)

        val second = assembler.undoLastTap()
        assertEquals(UndoResult(null, null), second)
        assertNull(assembler.currentAnchor)

        assertNull(assembler.undoLastTap())
    }

    @Test
    fun undoOnEmptyAssemblerReturnsNull() {
        assertNull(TapSampleAssembler().undoLastTap())
    }

    @Test
    fun pauseDiscardsBufferAndBlocksSnapshots() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)
        assembler.onSnapshot(snapshot(10))
        assertEquals(1, assembler.bufferedCount)

        assembler.pause()

        assertTrue(assembler.isPaused)
        assertEquals(0, assembler.bufferedCount)
        assembler.onSnapshot(snapshot(20))
        assertEquals(0, assembler.bufferedCount)
        // Anchor is kept for display while paused.
        assertEquals(Vec2(0.0, 0.0), assembler.currentAnchor)
    }

    @Test
    fun resumeRequiresReanchorTapThatFinalizesNothing() {
        val assembler = TapSampleAssembler()
        assembler.onTap(Vec2(0.0, 0.0), 0)
        assembler.onSnapshot(snapshot(10))
        assembler.pause()
        assembler.resume()
        assertFalse(assembler.isPaused)
        assertEquals(Vec2(0.0, 0.0), assembler.currentAnchor)
        assertEquals(0, assembler.bufferedCount)

        // The first tap after resume only re-anchors: no samples, no segment consumed.
        val reanchor = assembler.onTap(Vec2(50.0, 0.0), 1_000)
        assertTrue(reanchor.isEmpty())
        assertEquals(0, assembler.nextSegmentIndex)
        assertEquals(Vec2(50.0, 0.0), assembler.currentAnchor)
        assertEquals(0, assembler.bufferedCount)

        // The next tap finalizes normally, interpolating from the re-anchor tap's time —
        // the pause gap does not smear into the segment.
        assembler.onSnapshot(snapshot(1_500))
        val samples = assembler.onTap(Vec2(150.0, 0.0), 2_000)
        assertEquals(1, samples.size)
        assertEquals(0, samples[0].segmentIndex)
        assertEquals(100.0, samples[0].x, 0.0) // t=1500 of [1000..2000] → halfway 50→150
        assertEquals(0.0, samples[0].y, 0.0)
    }

    @Test
    fun undoOfReanchorThenFinalizedSegmentStillFinalizesNextSegment() {
        // Regression: pause/resume/re-anchor then two undos must NOT leave the assembler
        // stuck awaiting a re-anchor, which would silently discard the next segment.
        val a = TapSampleAssembler()
        a.onTap(Vec2(0.0, 0.0), 0)          // first tap: anchor A
        a.onSnapshot(snapshot(50))
        a.onTap(Vec2(100.0, 0.0), 100)      // finalizes segment 0, anchor B
        a.pause()
        a.resume()
        a.onTap(Vec2(200.0, 0.0), 200)      // re-anchor to C (no segment)

        a.undoLastTap()                     // undo C: back to awaiting re-anchor at B
        a.undoLastTap()                     // undo B (finalized seg 0): back to anchor A

        // The next segment must finalize, not be swallowed by a stuck re-anchor state.
        a.onSnapshot(snapshot(250))
        val samples = a.onTap(Vec2(300.0, 0.0), 300)
        assertEquals(1, samples.size)
        assertEquals(0, samples[0].segmentIndex)
    }
}
