package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalFormatTest {

    @Test
    fun percentClampsAndScales() {
        assertEquals(0, SignalFormat.toPercent(-100))
        assertEquals(0, SignalFormat.toPercent(-120))
        assertEquals(100, SignalFormat.toPercent(-50))
        assertEquals(100, SignalFormat.toPercent(-30))
        assertEquals(66, SignalFormat.toPercent(-67))
    }

    @Test
    fun formatSwitchesUnit() {
        assertEquals("-63 dBm", SignalFormat.format(-63, percent = false))
        assertEquals("74%", SignalFormat.format(-63, percent = true))
    }
}
