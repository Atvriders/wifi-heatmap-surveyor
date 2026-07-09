package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalStatsTest {

    @Test
    fun emptyListYieldsNull() {
        assertNull(SignalStats.summarize(emptyList()))
    }

    @Test
    fun singleValueIsItsOwnSummary() {
        assertEquals(
            SignalStats.Summary(min = -60, median = -60, max = -60, count = 1),
            SignalStats.summarize(listOf(-60)),
        )
    }

    @Test
    fun oddCountUsesTrueMedian() {
        assertEquals(
            SignalStats.Summary(min = -80, median = -60, max = -40, count = 3),
            SignalStats.summarize(listOf(-40, -80, -60)),
        )
    }

    @Test
    fun evenCountUsesLowerMiddleMedian() {
        // Sorted: -90, -70, -50, -30 -> lower-middle is -70.
        assertEquals(
            SignalStats.Summary(min = -90, median = -70, max = -30, count = 4),
            SignalStats.summarize(listOf(-50, -90, -30, -70)),
        )
    }

    @Test
    fun inputOrderDoesNotMatterAndInputIsUntouched() {
        val input = listOf(-30, -100, -55)
        val summary = SignalStats.summarize(input)
        assertEquals(SignalStats.Summary(min = -100, median = -55, max = -30, count = 3), summary)
        assertEquals(listOf(-30, -100, -55), input)
    }
}
