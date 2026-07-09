package com.atvriders.wifiheatmap.core.heatmap

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Inverse-distance-weighted (IDW) interpolation grid with O(cutoff-disk) incremental
 * updates, so live surveys can add (and undo) samples without rebuilding the raster.
 *
 * All coordinates are plan units (floor-plan pixels in TAP mode, meters in GPS mode),
 * x growing east/right and y growing DOWNWARD. Values are typically RSSI in dBm but the
 * grid is unit-agnostic. Cell arrays are row-major (`iy * gridW + ix`), matching [GridSpec].
 *
 * For each added point, every cell whose CENTER lies within [cutoffRadius] of the point
 * accumulates weight `w = 1 / (d^power + 1e-6)` (d = point-to-cell-center distance in
 * plan units) into `sumW` and `w * value` into `sumWV`. [snapshot] reads `sumWV / sumW`,
 * with untouched cells reported as NaN. [removePoint] subtracts the exact same
 * contributions, so a point's add is bit-for-bit undoable while it is the sole
 * contributor to a cell, and undone to well below display precision otherwise.
 *
 * Not thread-safe: confine mutation and snapshotting to one thread/dispatcher.
 *
 * @property spec grid geometry the accumulators are laid out on.
 * @property power IDW distance exponent (dimensionless, > 0; 2.0 is the classic choice).
 * @property cutoffRadius influence radius around each sample, in plan units (> 0).
 *   Callers must pass it by name when relying on [power]'s default.
 */
class IncrementalIdwGrid(
    val spec: GridSpec,
    val power: Double = 2.0,
    val cutoffRadius: Double,
) {
    init {
        require(power > 0.0) { "power must be positive: $power" }
        require(cutoffRadius > 0.0) { "cutoffRadius must be positive: $cutoffRadius" }
    }

    private val sumW = FloatArray(spec.cellCount)
    private val sumWV = FloatArray(spec.cellCount)

    /**
     * Accumulates a sample at plan point (x, y) with the given [value] (e.g. RSSI dBm)
     * into every cell whose center is within [cutoffRadius] of the point. Points outside
     * the grid still contribute to any in-grid cells inside the radius.
     */
    fun addPoint(x: Double, y: Double, value: Double) {
        forEachCellInRadius(x, y) { index, w ->
            sumW[index] += w.toFloat()
            sumWV[index] += (w * value).toFloat()
        }
    }

    /**
     * Exactly undoes a prior [addPoint] with the identical (x, y, value): recomputes the
     * same per-cell weights and subtracts them. Removing a point that was never added
     * corrupts the accumulators — callers own that bookkeeping.
     */
    fun removePoint(x: Double, y: Double, value: Double) {
        forEachCellInRadius(x, y) { index, w ->
            sumW[index] -= w.toFloat()
            sumWV[index] -= (w * value).toFloat()
        }
    }

    /**
     * Writes the interpolated field into a row-major array of size [GridSpec.cellCount]:
     * `sumWV / sumW` per cell, or [Float.NaN] where no sample has reached the cell
     * (accumulated weight below 1e-9). Pass [out] to reuse a buffer (must be exactly
     * cellCount long); otherwise a new array is allocated. Does not mutate the grid.
     *
     * @return the array the field was written into ([out] when provided).
     */
    fun snapshot(out: FloatArray? = null): FloatArray {
        val n = spec.cellCount
        val dst = out ?: FloatArray(n)
        require(dst.size == n) { "out.size=${dst.size}, expected $n" }
        for (i in 0 until n) {
            val w = sumW[i]
            dst[i] = if (w < EMPTY_WEIGHT_EPS) Float.NaN else sumWV[i] / w
        }
        return dst
    }

    /** Clears all accumulated samples; the next [snapshot] is all-NaN. */
    fun reset() {
        sumW.fill(0f)
        sumWV.fill(0f)
    }

    /**
     * Invokes [action] with the flat index and IDW weight of every cell whose center is
     * within [cutoffRadius] of (x, y). Deterministic given identical inputs — the
     * add/remove symmetry depends on that.
     */
    private inline fun forEachCellInRadius(x: Double, y: Double, action: (index: Int, w: Double) -> Unit) {
        val s = spec
        val ixMin = floor((x - cutoffRadius - s.originX) / s.cellSize).toInt().coerceAtLeast(0)
        val ixMax = floor((x + cutoffRadius - s.originX) / s.cellSize).toInt().coerceAtMost(s.gridW - 1)
        val iyMin = floor((y - cutoffRadius - s.originY) / s.cellSize).toInt().coerceAtLeast(0)
        val iyMax = floor((y + cutoffRadius - s.originY) / s.cellSize).toInt().coerceAtMost(s.gridH - 1)
        if (ixMin > ixMax || iyMin > iyMax) return
        val r2 = cutoffRadius * cutoffRadius
        for (iy in iyMin..iyMax) {
            val dy = s.cellCenterY(iy) - y
            val rowBase = iy * s.gridW
            for (ix in ixMin..ixMax) {
                val dx = s.cellCenterX(ix) - x
                val d2 = dx * dx + dy * dy
                if (d2 <= r2) {
                    val d = sqrt(d2)
                    val w = 1.0 / (d.pow(power) + WEIGHT_SOFTENING)
                    action(rowBase + ix, w)
                }
            }
        }
    }

    private companion object {
        /** Added to d^power so a sample sitting exactly on a cell center has finite weight. */
        const val WEIGHT_SOFTENING = 1e-6

        /** sumW below this is treated as "never sampled" and snapshots as NaN. */
        const val EMPTY_WEIGHT_EPS = 1e-9f
    }
}
