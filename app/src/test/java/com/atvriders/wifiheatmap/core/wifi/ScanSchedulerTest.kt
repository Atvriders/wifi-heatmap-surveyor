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

    @Test
    fun moreThanBudgetInWindowFreesTokenAtTheBudgethOldest() {
        // 6 scans inside the 120 s window (e.g. after an unthrottled run), then throttling
        // is detected. A token frees when the count drops below budget=4 — i.e. when the
        // (size-budget)=2nd-oldest ages out, NOT when the very first one does.
        val scheduler = ScanScheduler(budget = 4, windowMs = 120_000, throttledIntervalMs = 30_000)
        listOf(0L, 10_000, 20_000, 30_000, 40_000, 50_000).forEach { scheduler.recordScan(it) }

        // At t=60_000: 6 in window. Token frees when scansMs[6-4]=20_000 ages out → 140_000.
        // Old buggy code used first()=0 → 120_000 (says free at t=60_000, under-waits).
        // Interval: last(50_000)+30_000-60_000 = 20_000. max(20_000, 140_000-60_000=80_000) = 80_000.
        assertEquals(80_000L, scheduler.nextScanDelayMs(nowMs = 60_000, throttled = true))
    }
}
