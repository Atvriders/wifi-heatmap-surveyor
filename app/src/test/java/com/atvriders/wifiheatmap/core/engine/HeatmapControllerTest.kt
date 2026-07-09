package com.atvriders.wifiheatmap.core.engine

import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.GridSpec
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.HeatFilter
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.WifiReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent/advanceTimeBy/advanceUntilIdle
class HeatmapControllerTest {

    /**
     * Controller on the TestScope itself (NOT backgroundScope: `advanceUntilIdle` skips
     * background-only work, so the worker would never run). Every test must call `stop()`.
     */
    private fun TestScope.controller(minIntervalMs: Long = 500) = HeatmapController(
        scope = this,
        minIntervalMs = minIntervalMs,
        computeDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun sample(
        x: Double,
        y: Double,
        rssi: Int,
        segment: Int = 0,
        ssid: String = "net",
        band: Band = Band.GHZ_5,
    ) = PositionedSample(
        timestampMs = 0L,
        x = x,
        y = y,
        readings = listOf(
            WifiReading(
                bssid = "aa:$ssid",
                ssid = ssid,
                rssiDbm = rssi,
                frequencyMhz = if (band == Band.GHZ_2_4) 2437 else 5180,
                band = band,
                isConnected = false,
            ),
        ),
        segmentIndex = segment,
    )

    private fun assertValuesClose(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        assertEquals("array sizes differ", expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (e.isNaN()) {
                assertTrue("cell $i: expected NaN, got $a", a.isNaN())
            } else {
                assertEquals("cell $i", e, a, eps)
            }
        }
    }

    @Test
    fun configureAndSetAllProduceExpectedNanVsValuePattern() = runTest {
        val controller = controller()
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 100.0, targetLongestCells = 20)
        controller.configure(spec, ColorScale.Default, cutoffRadius = 12.0, filter = HeatFilter())
        controller.setAll(listOf(sample(50.0, 50.0, -50)))
        runCurrent()

        val frame = controller.frames.value
        assertNotNull(frame)
        frame!!
        assertEquals(20, frame.gridW)
        assertEquals(20, frame.gridH)
        assertEquals(1L, frame.generation) // configure + setAll coalesced into ONE frame
        assertEquals(spec.cellCount, frame.values.size)
        assertEquals(spec.cellCount, frame.pixels.size)

        // The cell containing the sample: single point -> the field equals the sample value.
        val hot = spec.cellIndex(50.0, 50.0)!!
        assertEquals(-50f, frame.values[hot], 1e-4f)
        assertTrue("hot cell must have a visible pixel", frame.pixels[hot] != 0)

        // A far corner cell (~67 plan units away, beyond cutoffRadius 12): NaN + transparent.
        val far = spec.cellIndex(2.0, 2.0)!!
        assertTrue(frame.values[far].isNaN())
        assertEquals(0, frame.pixels[far])
        controller.stop()
    }

    @Test
    fun rapidSubmitsWithinIntervalYieldOneRecompute() = runTest {
        val controller = controller(minIntervalMs = 500)
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 100.0, targetLongestCells = 20)
        controller.configure(spec, ColorScale.Default, cutoffRadius = 12.0, filter = HeatFilter())
        controller.setAll(listOf(sample(50.0, 50.0, -50)))
        runCurrent()
        val gen1 = controller.frames.value!!.generation
        assertEquals(1L, gen1)

        // Three rapid submits inside the min interval: no frame yet...
        // (Samples are farther apart than 2 * cutoffRadius, so their IDW disks never
        // overlap and each hot cell reads exactly its own sample's value.)
        controller.submit(listOf(sample(20.0, 20.0, -60)))
        controller.submit(listOf(sample(80.0, 20.0, -65)))
        controller.submit(listOf(sample(20.0, 80.0, -70)))
        runCurrent()
        assertEquals(gen1, controller.frames.value!!.generation)

        // ...then exactly ONE recompute carrying all three once the interval elapses.
        advanceTimeBy(500)
        runCurrent()
        val frame = controller.frames.value!!
        assertEquals(gen1 + 1, frame.generation)
        assertEquals(-60f, frame.values[spec.cellIndex(20.0, 20.0)!!], 1e-4f)
        assertEquals(-65f, frame.values[spec.cellIndex(80.0, 20.0)!!], 1e-4f)
        assertEquals(-70f, frame.values[spec.cellIndex(20.0, 80.0)!!], 1e-4f)

        // And nothing further without new submissions.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(gen1 + 1, controller.frames.value!!.generation)
        controller.stop()
    }

    @Test
    fun setFilterRecomputesUnderNewFilter() = runTest {
        val controller = controller()
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 100.0, targetLongestCells = 20)
        val s = PositionedSample(
            timestampMs = 0L,
            x = 50.0,
            y = 50.0,
            readings = listOf(
                WifiReading("aa", "A", -40, 5180, Band.GHZ_5, false),
                WifiReading("bb", "B", -70, 2437, Band.GHZ_2_4, false),
            ),
            segmentIndex = 0,
        )
        val all = listOf(s)
        controller.configure(spec, ColorScale.Default, cutoffRadius = 12.0, filter = HeatFilter())
        controller.setAll(all)
        advanceUntilIdle()
        val hot = spec.cellIndex(50.0, 50.0)!!
        assertEquals(-40f, controller.frames.value!!.values[hot], 1e-4f) // unfiltered: A wins

        controller.setFilter(HeatFilter(ssid = "B"), all)
        advanceUntilIdle()
        assertEquals(-70f, controller.frames.value!!.values[hot], 1e-4f) // A excluded

        controller.setFilter(HeatFilter(ssid = "C"), all)
        advanceUntilIdle()
        assertTrue(controller.frames.value!!.values[hot].isNaN()) // nothing matches
        controller.stop()
    }

    @Test
    fun removeSegmentReturnsGridToPreSegmentState() = runTest {
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 100.0, targetLongestCells = 20)
        val base = listOf(
            sample(20.0, 20.0, -45, segment = 0),
            sample(30.0, 40.0, -55, segment = 0),
        )
        // (45, 45) is close enough to (30, 40) that both segments' IDW disks share cells,
        // so this exercises the float-residual path of removePoint, not just exact NaNs.
        val segmentOne = listOf(
            sample(45.0, 45.0, -65, segment = 1),
            sample(70.0, 30.0, -75, segment = 1),
        )

        // Control: a grid that never saw segment 1.
        val control = controller()
        control.configure(spec, ColorScale.Default, cutoffRadius = 15.0, filter = HeatFilter())
        control.setAll(base)
        advanceUntilIdle()
        val expected = control.frames.value!!.values
        control.stop()

        // Test: add both segments, then incrementally remove segment 1.
        val controller = controller()
        controller.configure(spec, ColorScale.Default, cutoffRadius = 15.0, filter = HeatFilter())
        controller.setAll(base + segmentOne)
        advanceUntilIdle()
        controller.removeSegment(1, base)
        advanceUntilIdle()

        val frame = controller.frames.value!!
        assertEquals(2L, frame.generation)
        assertValuesClose(expected, frame.values, eps = 1e-4f)
        controller.stop()
    }

    @Test
    fun submitOutsideBoundsAutoExpandsGrid() = runTest {
        val controller = controller()
        val spec = GridSpec.forBounds(0.0, 0.0, 10.0, 10.0, targetLongestCells = 10)
        controller.configure(spec, ColorScale.Default, cutoffRadius = 8.0, filter = HeatFilter())
        controller.setAll(listOf(sample(5.0, 5.0, -50)))
        advanceUntilIdle()
        var frame = controller.frames.value!!
        assertEquals(10, frame.gridW)
        assertEquals(-50f, frame.values[spec.cellIndex(5.0, 5.0)!!], 1e-3f)

        controller.submit(listOf(sample(50.0, 50.0, -70))) // far outside the configured bounds
        advanceUntilIdle()
        frame = controller.frames.value!!

        // The active spec grew to cover both samples (plus margin)...
        assertTrue(frame.spec.contains(5.0, 5.0))
        assertTrue(frame.spec.contains(50.0, 50.0))
        assertTrue(frame.spec.planWidth > spec.planWidth)
        // ...while reusing the configured longest-axis cell count.
        assertEquals(10, frame.gridW)
        assertEquals(10, frame.gridH)
        // Both samples still heat their own (non-overlapping) neighborhoods exactly.
        assertEquals(-50f, frame.values[frame.spec.cellIndex(5.0, 5.0)!!], 1e-3f)
        assertEquals(-70f, frame.values[frame.spec.cellIndex(50.0, 50.0)!!], 1e-3f)
        controller.stop()
    }

    @Test
    fun generationIncrementsPerFrameAndStopHaltsEmission() = runTest {
        val controller = controller()
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 100.0, targetLongestCells = 20)
        controller.configure(spec, ColorScale.Default, cutoffRadius = 12.0, filter = HeatFilter())
        controller.setAll(listOf(sample(50.0, 50.0, -50)))
        advanceUntilIdle()
        assertEquals(1L, controller.frames.value!!.generation)

        controller.submit(listOf(sample(20.0, 20.0, -60)))
        advanceUntilIdle()
        assertEquals(2L, controller.frames.value!!.generation)

        controller.submit(listOf(sample(30.0, 30.0, -65)))
        advanceUntilIdle()
        assertEquals(3L, controller.frames.value!!.generation)

        controller.stop()
        controller.submit(listOf(sample(40.0, 40.0, -70)))
        advanceUntilIdle()
        assertEquals(3L, controller.frames.value!!.generation) // no frames after stop
    }
}
