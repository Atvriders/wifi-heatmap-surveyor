package com.atvriders.wifiheatmap.core.heatmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSpecTest {

    @Test
    fun forBounds_longestAxisGetsTargetCells_aspectPreserved() {
        val spec = GridSpec.forBounds(0.0, 0.0, 200.0, 100.0, targetLongestCells = 200)
        assertEquals(1.0, spec.cellSize, 1e-9)
        assertEquals(200, spec.gridW)
        assertEquals(100, spec.gridH)
        assertEquals(0.0, spec.originX, 1e-9)
        assertEquals(0.0, spec.originY, 1e-9)
    }

    @Test
    fun forBounds_longestAxisVertical() {
        val spec = GridSpec.forBounds(0.0, 0.0, 50.0, 100.0, targetLongestCells = 100)
        assertEquals(1.0, spec.cellSize, 1e-9)
        assertEquals(50, spec.gridW)
        assertEquals(100, spec.gridH)
    }

    @Test
    fun forBounds_nonAlignedAspect_shortAxisRoundsUp() {
        // 100 x 33 with 200 target -> cellSize 0.5, short axis ceil(66) = 66.
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 33.0, targetLongestCells = 200)
        assertEquals(0.5, spec.cellSize, 1e-9)
        assertEquals(200, spec.gridW)
        assertEquals(66, spec.gridH)
    }

    @Test
    fun forBounds_shortAxisClampedToMinCells() {
        val spec = GridSpec.forBounds(0.0, 0.0, 100.0, 1.0, targetLongestCells = 200)
        assertEquals(0.5, spec.cellSize, 1e-9)
        assertEquals(200, spec.gridW)
        assertEquals(GridSpec.MIN_CELLS_PER_AXIS, spec.gridH)
    }

    @Test
    fun forBounds_tinyTargetClampedToMinCells() {
        val spec = GridSpec.forBounds(0.0, 0.0, 10.0, 10.0, targetLongestCells = 4)
        assertEquals(GridSpec.MIN_CELLS_PER_AXIS, spec.gridW)
        assertEquals(GridSpec.MIN_CELLS_PER_AXIS, spec.gridH)
        assertEquals(10.0 / GridSpec.MIN_CELLS_PER_AXIS, spec.cellSize, 1e-9)
    }

    @Test
    fun forBounds_zeroSizeBoundsPaddedToOnePlanUnit() {
        val spec = GridSpec.forBounds(5.0, 7.0, 5.0, 7.0, targetLongestCells = 200)
        assertEquals(1.0 / 200, spec.cellSize, 1e-12)
        assertEquals(200, spec.gridW)
        assertEquals(200, spec.gridH)
        assertEquals(4.5, spec.originX, 1e-9)
        assertEquals(6.5, spec.originY, 1e-9)
        assertTrue(spec.contains(5.0, 7.0))
        assertNotNull(spec.cellIndex(5.0, 7.0))
    }

    @Test
    fun forBounds_zeroWidthOnlyPadsThatAxis() {
        val spec = GridSpec.forBounds(3.0, 0.0, 3.0, 10.0, targetLongestCells = 200)
        // Height 10 is the longest axis; width padded to 1.0 -> 1 / 0.05 = 20 cells.
        assertEquals(0.05, spec.cellSize, 1e-9)
        assertEquals(20, spec.gridW)
        assertEquals(200, spec.gridH)
        assertEquals(2.5, spec.originX, 1e-9)
    }

    @Test
    fun cellIndex_insideOutsideAndEdges() {
        val spec = GridSpec(10, 10, 1.0, 0.0, 0.0)
        assertEquals(0, spec.cellIndex(0.5, 0.5))
        assertEquals(9, spec.cellIndex(9.5, 0.5))
        assertEquals(10, spec.cellIndex(0.5, 1.5)) // row-major: iy * gridW + ix
        assertEquals(99, spec.cellIndex(9.5, 9.5))
        // Exact max edge is clamped into the last row/column, min edge into the first.
        assertEquals(99, spec.cellIndex(10.0, 10.0))
        assertEquals(0, spec.cellIndex(0.0, 0.0))
        assertNull(spec.cellIndex(-0.01, 5.0))
        assertNull(spec.cellIndex(5.0, -0.01))
        assertNull(spec.cellIndex(10.01, 5.0))
        assertNull(spec.cellIndex(5.0, 10.01))
    }

    @Test
    fun cellIndex_respectsNonZeroOrigin() {
        val spec = GridSpec(10, 10, 2.0, 100.0, 50.0)
        assertNull(spec.cellIndex(99.9, 51.0))
        assertEquals(0, spec.cellIndex(101.0, 51.0))
        assertEquals(11, spec.cellIndex(103.9, 53.9))
    }

    @Test
    fun cellCenters() {
        val spec = GridSpec(10, 10, 2.0, 100.0, 50.0)
        assertEquals(101.0, spec.cellCenterX(0), 1e-9)
        assertEquals(107.0, spec.cellCenterX(3), 1e-9)
        assertEquals(51.0, spec.cellCenterY(0), 1e-9)
        assertEquals(57.0, spec.cellCenterY(3), 1e-9)
    }

    @Test
    fun contains_edgesInclusive() {
        val spec = GridSpec(10, 10, 1.0, 0.0, 0.0)
        assertTrue(spec.contains(0.0, 0.0))
        assertTrue(spec.contains(10.0, 10.0))
        assertTrue(!spec.contains(10.0001, 5.0))
        assertTrue(!spec.contains(5.0, -0.0001))
    }
}
