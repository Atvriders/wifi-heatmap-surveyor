package com.atvriders.wifiheatmap.core.heatmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageStatsTest {

    @Test
    fun compute_mixedNanPassFail() {
        val values = floatArrayOf(Float.NaN, -50f, -70f, -90f, Float.NaN, -60f)
        val coverage = CoverageStats.compute(values, thresholdDbm = -65f, cellAreaM2 = 0.25)

        assertEquals(4, coverage.surveyedCells)
        assertEquals(2, coverage.passingCells) // -50 and -60 pass
        assertEquals(0.5f, coverage.fractionPassing, 1e-6f)
        assertEquals(1.0, coverage.surveyedAreaM2!!, 1e-9)
    }

    @Test
    fun compute_valueExactlyAtThresholdPasses() {
        val values = floatArrayOf(-65f, -65.01f)
        val coverage = CoverageStats.compute(values, thresholdDbm = -65f, cellAreaM2 = null)
        assertEquals(2, coverage.surveyedCells)
        assertEquals(1, coverage.passingCells)
    }

    @Test
    fun compute_emptyArray_fractionZero() {
        val coverage = CoverageStats.compute(FloatArray(0), thresholdDbm = -67f, cellAreaM2 = 2.0)
        assertEquals(0, coverage.surveyedCells)
        assertEquals(0, coverage.passingCells)
        assertEquals(0f, coverage.fractionPassing, 0f)
        assertEquals(0.0, coverage.surveyedAreaM2!!, 1e-9)
    }

    @Test
    fun compute_allNan_fractionZero() {
        val values = FloatArray(16) { Float.NaN }
        val coverage = CoverageStats.compute(values, thresholdDbm = -67f, cellAreaM2 = 0.5)
        assertEquals(0, coverage.surveyedCells)
        assertEquals(0f, coverage.fractionPassing, 0f)
        assertEquals(0.0, coverage.surveyedAreaM2!!, 1e-9)
    }

    @Test
    fun compute_nullCellArea_nullSurveyedArea() {
        val values = floatArrayOf(-50f, -80f)
        val coverage = CoverageStats.compute(values, thresholdDbm = -67f, cellAreaM2 = null)
        assertEquals(2, coverage.surveyedCells)
        assertNull(coverage.surveyedAreaM2)
    }

    @Test
    fun compute_areaScalesWithSurveyedCellsOnly() {
        val values = floatArrayOf(-50f, Float.NaN, -80f, Float.NaN, -60f)
        val coverage = CoverageStats.compute(values, thresholdDbm = -67f, cellAreaM2 = 2.5)
        assertEquals(3, coverage.surveyedCells)
        assertEquals(7.5, coverage.surveyedAreaM2!!, 1e-9)
    }
}
