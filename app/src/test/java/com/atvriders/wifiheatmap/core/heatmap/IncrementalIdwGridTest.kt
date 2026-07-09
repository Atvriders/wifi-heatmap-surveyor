package com.atvriders.wifiheatmap.core.heatmap

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalIdwGridTest {

    /** 20x20 grid, cellSize 1, origin (0,0): cell centers at 0.5, 1.5, ..., 19.5. */
    private fun spec20() = GridSpec(20, 20, 1.0, 0.0, 0.0)

    private fun assertSameField(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (e.isNaN()) {
                assertTrue("cell $i: expected NaN, got $a", a.isNaN())
            } else {
                assertFalse("cell $i: expected $e, got NaN", a.isNaN())
                assertEquals("cell $i", e, a, eps)
            }
        }
    }

    @Test
    fun singlePoint_maxAtNearestCell_andConstantInsideCutoff_nanOutside() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 3.0)
        grid.addPoint(10.5, 10.5, -50.0)
        val snap = grid.snapshot()

        // Nearest cell (dead-center hit) carries the sample value.
        assertEquals(-50f, snap[spec.cellIndex(10.5, 10.5)!!], 1e-4f)

        for (iy in 0 until spec.gridH) {
            for (ix in 0 until spec.gridW) {
                val d = hypot(spec.cellCenterX(ix) - 10.5, spec.cellCenterY(iy) - 10.5)
                val v = snap[iy * spec.gridW + ix]
                when {
                    // Single-point IDW is constant (sumWV/sumW == value) everywhere it reaches,
                    // so the nearest cell is a max and every reached cell equals the sample.
                    d < 3.0 - 1e-9 -> assertEquals("cell ($ix,$iy) d=$d", -50f, v, 1e-4f)
                    d > 3.0 + 1e-9 -> assertTrue("cell ($ix,$iy) d=$d should be NaN", v.isNaN())
                }
            }
        }
    }

    @Test
    fun singlePoint_cutoffDiskIsRadiallySymmetric() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 3.0)
        grid.addPoint(10.5, 10.5, -50.0)
        val snap = grid.snapshot()

        // Point sits on the center of cell (10,10); mirror cells across it must match
        // exactly in both NaN pattern and value.
        for (iy in 1 until spec.gridH) {
            for (ix in 1 until spec.gridW) {
                val mx = 20 - ix // mirror of center ix+0.5 across x=10.5
                val my = 20 - iy
                if (mx !in 0 until spec.gridW || my !in 0 until spec.gridH) continue
                val v = snap[iy * spec.gridW + ix]
                val m = snap[my * spec.gridW + mx]
                if (v.isNaN()) {
                    assertTrue("mirror of NaN cell ($ix,$iy) not NaN", m.isNaN())
                } else {
                    assertEquals("mirror of cell ($ix,$iy)", v, m, 1e-4f)
                }
            }
        }
    }

    @Test
    fun strongAndWeakPoint_valuesDecayMonotonicallyAlongRowBetweenThem() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 30.0)
        grid.addPoint(2.5, 10.5, -30.0) // strong, on center of cell (2,10)
        grid.addPoint(17.5, 10.5, -80.0) // weak, on center of cell (17,10)
        val snap = grid.snapshot()

        val row = FloatArray(20) { ix -> snap[10 * spec.gridW + ix] }
        assertEquals(-30f, row[2], 0.01f)
        assertEquals(-80f, row[17], 0.01f)
        for (ix in 2 until 17) {
            assertTrue(
                "row not strictly decreasing at ix=$ix: ${row[ix]} -> ${row[ix + 1]}",
                row[ix] > row[ix + 1],
            )
        }
    }

    @Test
    fun twoPointField_mirrorCellsAcrossPointRowEqual() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 30.0)
        grid.addPoint(2.5, 10.5, -30.0)
        grid.addPoint(17.5, 10.5, -80.0)
        val snap = grid.snapshot()

        // Both points lie on y=10.5, so the field is symmetric across that line:
        // row iy (center iy+0.5) mirrors to row 20-iy (center 20.5-iy).
        for (iy in 1 until spec.gridH) {
            val my = 20 - iy
            if (my !in 0 until spec.gridH) continue
            for (ix in 0 until spec.gridW) {
                val v = snap[iy * spec.gridW + ix]
                val m = snap[my * spec.gridW + ix]
                assertFalse("cell ($ix,$iy) unexpectedly NaN", v.isNaN() || m.isNaN())
                assertEquals("mirror rows $iy/$my at ix=$ix", v, m, 1e-4f)
            }
        }
    }

    @Test
    fun twoEqualValuePoints_midpointCellHasThatValue() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 10.0)
        grid.addPoint(8.5, 10.5, -60.0)
        grid.addPoint(12.5, 10.5, -60.0)
        val snap = grid.snapshot()
        assertEquals(-60f, snap[spec.cellIndex(10.5, 10.5)!!], 1e-4f)
    }

    @Test
    fun twoDifferentValuePoints_equidistantMidpointAverages() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 10.0)
        grid.addPoint(8.5, 10.5, -40.0)
        grid.addPoint(12.5, 10.5, -80.0)
        val snap = grid.snapshot()
        // Midpoint cell center is 2.0 plan units from each point: equal weights.
        assertEquals(-60f, snap[spec.cellIndex(10.5, 10.5)!!], 1e-3f)
    }

    @Test
    fun incrementalAddsEqualBatchBuild_evenInDifferentOrder() {
        val spec = spec20()
        val points = listOf(
            Triple(3.2, 4.1, -45.0),
            Triple(12.7, 6.3, -62.0),
            Triple(7.5, 15.2, -71.0),
            Triple(16.1, 16.9, -55.0),
            Triple(5.0, 9.5, -80.0),
        )

        val incremental = IncrementalIdwGrid(spec, cutoffRadius = 6.0)
        for ((x, y, v) in points) {
            incremental.addPoint(x, y, v)
            incremental.snapshot() // interleaved snapshots must not perturb state
        }

        val batch = IncrementalIdwGrid(spec, cutoffRadius = 6.0)
        for ((x, y, v) in points.reversed()) batch.addPoint(x, y, v)

        assertSameField(batch.snapshot(), incremental.snapshot())
    }

    @Test
    fun removePoint_undoesAdd_includingNanPattern() {
        val spec = spec20()
        // Overlapping cutoff disks so shared cells exercise partial subtraction.
        val withUndo = IncrementalIdwGrid(spec, cutoffRadius = 4.0)
        withUndo.addPoint(3.2, 4.1, -45.0)
        withUndo.addPoint(6.0, 5.0, -62.0)
        withUndo.removePoint(6.0, 5.0, -62.0)

        val neverAdded = IncrementalIdwGrid(spec, cutoffRadius = 4.0)
        neverAdded.addPoint(3.2, 4.1, -45.0)

        assertSameField(neverAdded.snapshot(), withUndo.snapshot())
    }

    @Test
    fun removeOnlyPoint_returnsToAllNan() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 5.0)
        grid.addPoint(10.0, 10.0, -55.0)
        grid.removePoint(10.0, 10.0, -55.0)
        val snap = grid.snapshot()
        for (i in snap.indices) assertTrue("cell $i should be NaN", snap[i].isNaN())
    }

    @Test
    fun reset_clearsEverything_andGridRemainsUsable() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 5.0)
        grid.addPoint(10.5, 10.5, -50.0)
        grid.addPoint(4.5, 4.5, -70.0)
        grid.reset()

        val cleared = grid.snapshot()
        for (i in cleared.indices) assertTrue("cell $i should be NaN", cleared[i].isNaN())

        grid.addPoint(10.5, 10.5, -40.0)
        assertEquals(-40f, grid.snapshot()[spec.cellIndex(10.5, 10.5)!!], 1e-4f)
    }

    @Test
    fun snapshot_reusesProvidedBuffer() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 3.0)
        grid.addPoint(10.5, 10.5, -50.0)
        val buf = FloatArray(spec.cellCount)
        val returned = grid.snapshot(buf)
        assertTrue(returned === buf)
        assertEquals(-50f, buf[spec.cellIndex(10.5, 10.5)!!], 1e-4f)
    }

    @Test
    fun pointOutsideGrid_stillHeatsNearbyInGridCells() {
        val spec = spec20()
        val grid = IncrementalIdwGrid(spec, cutoffRadius = 3.0)
        grid.addPoint(-1.0, 10.5, -50.0)
        val snap = grid.snapshot()
        assertEquals(-50f, snap[spec.cellIndex(0.5, 10.5)!!], 1e-4f)
        assertTrue(snap[spec.cellIndex(10.5, 10.5)!!].isNaN())
    }
}
