package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiSamplerTest {

    @Test
    fun ageIsMinusOneBeforeFirstReading() {
        val sampler = RssiSampler()
        assertEquals(-1L, sampler.ageMs(nowMs = 5_000))
        assertFalse(sampler.isStale(nowMs = 5_000))
    }

    @Test
    fun firstReadingIsFresh() {
        val sampler = RssiSampler()
        assertTrue(sampler.onReading(nowMs = 1_000, rssiDbm = -50))
        assertEquals(500L, sampler.ageMs(nowMs = 1_500))
    }

    @Test
    fun changedValueIsFresh() {
        val sampler = RssiSampler()
        sampler.onReading(nowMs = 1_000, rssiDbm = -50)

        assertTrue(sampler.onReading(nowMs = 2_000, rssiDbm = -52))
        assertEquals(0L, sampler.ageMs(nowMs = 2_000))
    }

    @Test
    fun repeatWithinWindowIsStale() {
        val sampler = RssiSampler() // repeatIsStaleAfterMs = 3_500
        sampler.onReading(nowMs = 1_000, rssiDbm = -50)

        assertFalse(sampler.onReading(nowMs = 2_000, rssiDbm = -50))
    }

    @Test
    fun repeatOlderThanWindowIsStillStaleAndDoesNotRefreshAge() {
        val sampler = RssiSampler() // repeatIsStaleAfterMs = 3_500
        sampler.onReading(nowMs = 1_000, rssiDbm = -50)

        assertFalse(sampler.onReading(nowMs = 10_000, rssiDbm = -50))
        // Age still measures from the last CHANGE at t = 1_000.
        assertEquals(9_000L, sampler.ageMs(nowMs = 10_000))
    }

    @Test
    fun ageTracksLastChangeNotLastReading() {
        val sampler = RssiSampler()
        sampler.onReading(nowMs = 1_000, rssiDbm = -50)
        sampler.onReading(nowMs = 2_000, rssiDbm = -50) // stale repeat
        sampler.onReading(nowMs = 3_000, rssiDbm = -50) // stale repeat
        assertEquals(2_500L, sampler.ageMs(nowMs = 3_500))

        assertTrue(sampler.onReading(nowMs = 4_000, rssiDbm = -47))
        assertEquals(500L, sampler.ageMs(nowMs = 4_500))
    }

    @Test
    fun isStaleFlipsAfterThresholdWithoutChange() {
        val sampler = RssiSampler(repeatIsStaleAfterMs = 3_500)
        sampler.onReading(nowMs = 1_000, rssiDbm = -50)

        assertFalse(sampler.isStale(nowMs = 4_400)) // age 3_400 <= threshold
        assertTrue(sampler.isStale(nowMs = 4_600)) // age 3_600 > threshold
    }
}
