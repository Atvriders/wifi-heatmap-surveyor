package com.atvriders.wifiheatmap.core.heatmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegendModelTest {

    @Test
    fun ticks_fractionsSpanMinToMax() {
        val legend = LegendModel(minDbm = -90f, maxDbm = -30f)
        assertEquals(listOf(-85, -75, -67, -60, -50), legend.ticks.map { it.dbm })

        val byDbm = legend.ticks.associateBy { it.dbm }
        assertEquals(5f / 60f, byDbm.getValue(-85).fraction, 1e-6f)
        assertEquals(0.5f, byDbm.getValue(-60).fraction, 1e-6f)
        assertEquals(40f / 60f, byDbm.getValue(-50).fraction, 1e-6f)
    }

    @Test
    fun ticks_excludeBreakpointsOutsideRange() {
        val legend = LegendModel(minDbm = -80f, maxDbm = -55f)
        assertEquals(listOf(-75, -67, -60), legend.ticks.map { it.dbm })
        assertEquals(0.2f, legend.ticks.first().fraction, 1e-6f) // (-75 - -80) / 25
    }

    @Test
    fun ticks_rangeEndpointsAreInclusive() {
        val legend = LegendModel(minDbm = -85f, maxDbm = -50f)
        assertEquals(listOf(-85, -75, -67, -60, -50), legend.ticks.map { it.dbm })
        assertEquals(0f, legend.ticks.first().fraction, 1e-6f)
        assertEquals(1f, legend.ticks.last().fraction, 1e-6f)
    }

    @Test
    fun labels_arePlainDbmNumbers() {
        val legend = LegendModel(minDbm = -90f, maxDbm = -30f)
        assertEquals("-67", legend.ticks.first { it.dbm == -67 }.label)
        assertTrue(legend.ticks.all { it.label == it.dbm.toString() })
    }

    @Test
    fun customBreakpoints_areSortedAscending() {
        val legend = LegendModel(minDbm = -100f, maxDbm = -20f, breakpoints = listOf(-30, -90, -55))
        assertEquals(listOf(-90, -55, -30), legend.ticks.map { it.dbm })
    }
}
