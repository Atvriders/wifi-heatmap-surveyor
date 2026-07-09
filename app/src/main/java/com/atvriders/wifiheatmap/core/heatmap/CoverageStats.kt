package com.atvriders.wifiheatmap.core.heatmap

/**
 * Coverage summary over an interpolated heatmap field.
 *
 * @property surveyedCells cells with an interpolated value (non-NaN).
 * @property passingCells surveyed cells whose value is >= the pass threshold (dBm).
 * @property fractionPassing passingCells / surveyedCells, 0 when [surveyedCells] == 0.
 * @property surveyedAreaM2 surveyedCells * cellArea in square meters, or null when no
 *   cell area was supplied (TAP mode without a plan-scale calibration).
 */
data class Coverage(
    val surveyedCells: Int,
    val passingCells: Int,
    val fractionPassing: Float,
    val surveyedAreaM2: Double?,
)

/** Computes [Coverage] from heatmap value arrays. */
object CoverageStats {
    /**
     * Scans a row-major field of interpolated values (dBm; NaN = unsampled, as produced
     * by [IncrementalIdwGrid.snapshot]) and counts surveyed vs passing cells against
     * [thresholdDbm] (a cell exactly at the threshold passes).
     *
     * @param cellAreaM2 area of one grid cell in square meters (`cellSize^2` in GPS mode,
     *   or pixel-cell area times the calibrated meters-per-pixel squared in TAP mode);
     *   pass null when unknown to get a null [Coverage.surveyedAreaM2].
     */
    fun compute(values: FloatArray, thresholdDbm: Float, cellAreaM2: Double?): Coverage {
        var surveyed = 0
        var passing = 0
        for (v in values) {
            if (v.isNaN()) continue
            surveyed++
            if (v >= thresholdDbm) passing++
        }
        val fraction = if (surveyed == 0) 0f else passing.toFloat() / surveyed
        return Coverage(
            surveyedCells = surveyed,
            passingCells = passing,
            fractionPassing = fraction,
            surveyedAreaM2 = cellAreaM2?.let { surveyed * it },
        )
    }
}
