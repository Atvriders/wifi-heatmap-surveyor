package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSchedulerTest {

    @Test
    fun firstScanIsImmediateInBothModes() {
        val scheduler = ScanScheduler()
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 0, throttled = false))
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 0, throttled = true))
    }

    @Test
    fun unthrottledPacingRespectsInterval() {
        val scheduler = ScanScheduler() // unthrottledIntervalMs = 4_000
        scheduler.recordScan(nowMs = 0)

        assertEquals(3_000L, scheduler.nextScanDelayMs(nowMs = 1_000, throttled = false))
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 4_000, throttled = false))
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 10_000, throttled = false))
    }

    @Test
    fun throttledPacingRespectsLongInterval() {
        val scheduler = ScanScheduler() // throttledIntervalMs = 30_000
        scheduler.recordScan(nowMs = 0)

        assertEquals(20_000L, scheduler.nextScanDelayMs(nowMs = 10_000, throttled = true))
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 30_000, throttled = true))
    }

    @Test
    fun fourQuickScansExhaustTokensUntilOldestAgesOut() {
        val scheduler = ScanScheduler() // budget = 4, windowMs = 120_000
        scheduler.recordScan(nowMs = 0)
        scheduler.recordScan(nowMs = 5_000)
        scheduler.recordScan(nowMs = 10_000)
        scheduler.recordScan(nowMs = 15_000)

        // Token constraint (oldest at t=0 frees at t=120_000) dominates the
        // 30 s pacing interval (which alone would allow t=45_000).
        assertEquals(104_000L, scheduler.nextScanDelayMs(nowMs = 16_000, throttled = true))

        // Exactly when the oldest scan ages out, a token frees and pacing is long satisfied.
        assertEquals(0L, scheduler.nextScanDelayMs(nowMs = 120_000, throttled = true))
    }

    @Test
    fun pacingIntervalDominatesWhenTokensAreFree() {
        val scheduler = ScanScheduler()
        scheduler.recordScan(nowMs = 0)
        scheduler.recordScan(nowMs = 40_000)

        // Only 2 scans in the window: no token wait, just the 30 s pacing from the last scan.
        assertEquals(25_000L, scheduler.nextScanDelayMs(nowMs = 45_000, throttled = true))
    }

    @Test
    fun unthrottledModeIgnoresTokenBucket() {
        val scheduler = ScanScheduler()
        scheduler.recordScan(nowMs = 0)
        scheduler.recordScan(nowMs = 1_000)
        scheduler.recordScan(nowMs = 2_000)
        scheduler.recordScan(nowMs = 3_000)

        // Budget is exhausted, but unthrottled pacing only waits out the 4 s interval.
        assertEquals(3_000L, scheduler.nextScanDelayMs(nowMs = 4_000, throttled = false))
    }
}
