package com.atvriders.wifiheatmap.core.wifi

import com.atvriders.wifiheatmap.core.model.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandClassifierTest {

    @Test
    fun classifies2GhzChannels() {
        assertEquals(Band.GHZ_2_4, BandClassifier.bandFor(2412)) // channel 1
        assertEquals(Band.GHZ_2_4, BandClassifier.bandFor(2484)) // channel 14
    }

    @Test
    fun classifies5GhzChannels() {
        assertEquals(Band.GHZ_5, BandClassifier.bandFor(5180)) // channel 36
        assertEquals(Band.GHZ_5, BandClassifier.bandFor(5825)) // channel 165
    }

    @Test
    fun frequencyJustBelow6GhzBoundaryIs5Ghz() {
        assertEquals(Band.GHZ_5, BandClassifier.bandFor(5924))
    }

    @Test
    fun classifies6GhzChannels() {
        assertEquals(Band.GHZ_6, BandClassifier.bandFor(5925)) // band start
        assertEquals(Band.GHZ_6, BandClassifier.bandFor(5955)) // channel 1 (6 GHz)
        assertEquals(Band.GHZ_6, BandClassifier.bandFor(6115)) // channel 33
        assertEquals(Band.GHZ_6, BandClassifier.bandFor(7115)) // channel 233
    }

    @Test
    fun bandEdgesAreInclusive() {
        assertEquals(Band.GHZ_2_4, BandClassifier.bandFor(2400))
        assertEquals(Band.GHZ_2_4, BandClassifier.bandFor(2500))
        assertEquals(Band.GHZ_5, BandClassifier.bandFor(4900))
        assertEquals(Band.GHZ_6, BandClassifier.bandFor(7125))
    }

    @Test
    fun outOfBandFrequenciesAreNull() {
        assertNull(BandClassifier.bandFor(1000))
        assertNull(BandClassifier.bandFor(8000))
        assertNull(BandClassifier.bandFor(2399))
        assertNull(BandClassifier.bandFor(2501))
        assertNull(BandClassifier.bandFor(7126))
        assertNull(BandClassifier.bandFor(0))
        assertNull(BandClassifier.bandFor(-1))
    }
}
