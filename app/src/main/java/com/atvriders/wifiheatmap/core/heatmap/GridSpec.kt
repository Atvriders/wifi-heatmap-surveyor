package com.atvriders.wifiheatmap.core.heatmap

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of a heatmap raster laid over the survey plane.
 *
 * Coordinate convention (project-wide): x grows east/right, y grows DOWNWARD
 * (screen convention). "Plan units" are floor-plan pixels in TAP mode and
 * meters in GPS mode.
 *
 * Cell (ix, iy) covers the half-open plan-space rectangle
 * `[originX + ix*cellSize, originX + (ix+1)*cellSize) x [originY + iy*cellSize, ...)`,
 * except that points exactly on the grid's max edge are clamped into the last
 * row/column so the full bounds passed to [forBounds] remain addressable.
 *
 * Flat cell arrays used throughout the heatmap package are row-major:
 * `index = iy * gridW + ix`.
 *
 * @property gridW number of cells along x (columns), >= 1.
 * @property gridH number of cells along y (rows), >= 1.
 * @property cellSize side length of one square cell, in plan units.
 * @property originX plan-space x of cell (0,0)'s min (west) corner.
 * @property originY plan-space y of cell (0,0)'s min (north/top) corner.
 */
data class GridSpec(
    val gridW: Int,
    val gridH: Int,
    val cellSize: Double,
    val originX: Double,
    val originY: Double,
) {
    init {
        require(gridW > 0 && gridH > 0) { "grid dimensions must be positive: ${gridW}x$gridH" }
        require(cellSize > 0.0) { "cellSize must be positive: $cellSize" }
    }

    /** Total cell count, the required size of every flat value/pixel array for this grid. */
    val cellCount: Int get() = gridW * gridH

    /** Plan-space width covered by the grid, in plan units (may exceed the source bounds). */
    val planWidth: Double get() = gridW * cellSize

    /** Plan-space height covered by the grid, in plan units (may exceed the source bounds). */
    val planHeight: Double get() = gridH * cellSize

    /**
     * Row-major flat index (`iy * gridW + ix`) of the cell containing plan point (x, y),
     * or null when the point lies outside the grid. Points exactly on the max edge are
     * clamped into the last row/column.
     */
    fun cellIndex(x: Double, y: Double): Int? {
        if (!contains(x, y)) return null
        val ix = min(gridW - 1, floor((x - originX) / cellSize).toInt())
        val iy = min(gridH - 1, floor((y - originY) / cellSize).toInt())
        return iy * gridW + ix
    }

    /** Plan-space x of the center of column [ix] (no bounds check), in plan units. */
    fun cellCenterX(ix: Int): Double = originX + (ix + 0.5) * cellSize

    /** Plan-space y of the center of row [iy] (no bounds check), in plan units. */
    fun cellCenterY(iy: Int): Double = originY + (iy + 0.5) * cellSize

    /** True when plan point (x, y) lies within the grid's covered rectangle (edges inclusive). */
    fun contains(x: Double, y: Double): Boolean =
        x >= originX && x <= originX + planWidth &&
            y >= originY && y <= originY + planHeight

    companion object {
        /** Smallest cell count allowed on either axis, and the floor for [forBounds]'s target. */
        const val MIN_CELLS_PER_AXIS: Int = 8

        /**
         * Builds an aspect-preserving grid over the given plan-space bounds (plan units).
         *
         * The longest bounds dimension is divided into [targetLongestCells] cells (floored
         * at [MIN_CELLS_PER_AXIS]); the other axis gets proportionally fewer cells of the
         * same [cellSize], but never fewer than [MIN_CELLS_PER_AXIS] — a clamped axis simply
         * extends past its bound (the extra cells stay unsampled). A zero-size dimension is
         * padded to 1.0 plan unit, centered on the degenerate bound.
         *
         * Min/max may be passed in either order; the grid origin is the padded min corner.
         */
        fun forBounds(
            minX: Double,
            minY: Double,
            maxX: Double,
            maxY: Double,
            targetLongestCells: Int = 200,
        ): GridSpec {
            var x0 = min(minX, maxX)
            var x1 = max(minX, maxX)
            var y0 = min(minY, maxY)
            var y1 = max(minY, maxY)
            if (x1 - x0 <= 0.0) {
                val cx = (x0 + x1) / 2.0
                x0 = cx - 0.5
                x1 = cx + 0.5
            }
            if (y1 - y0 <= 0.0) {
                val cy = (y0 + y1) / 2.0
                y0 = cy - 0.5
                y1 = cy + 0.5
            }
            val w = x1 - x0
            val h = y1 - y0
            val target = max(MIN_CELLS_PER_AXIS, targetLongestCells)
            val cellSize = max(w, h) / target
            // The -1e-9 slack keeps float noise in dim/cellSize from adding a spurious
            // extra row/column when the division is exact (the longest axis always is).
            val gridW = max(MIN_CELLS_PER_AXIS, ceil(w / cellSize - 1e-9).toInt())
            val gridH = max(MIN_CELLS_PER_AXIS, ceil(h / cellSize - 1e-9).toInt())
            return GridSpec(gridW, gridH, cellSize, x0, y0)
        }
    }
}
