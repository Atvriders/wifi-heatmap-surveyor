package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanThrottleDetectorTest {

    @Test
    fun startsUnknownWithZeroEta() {
        val detector = ScanThrottleDetector()
        assertEquals(ThrottleState.Unknown, detector.state)
        assertEquals(0L, detector.etaMs(nowMs = 0))
    }

    @Test
    fun fourAcceptedScansWithFreshResultsStayUnthrottled() {
        val detector = ScanThrottleDetector()
        for (i in 0 until 4) {
            val requestMs = i * 10_000L
            detector.onScanRequested(nowMs = requestMs, accepted = true)
            detector.onResults(nowMs = requestMs + 1_000, resultsUpdated = true, newestResultAgeMs = 500)
            assertEquals(ThrottleState.Unthrottled, detector.state)
            assertEquals(0L, detector.etaMs(nowMs = requestMs + 1_000))
        }
    }

    @Test
    fun rejectedFifthScanIsThrottledWithEtaWhenOldestRequestAgesOut() {
        val detector = ScanThrottleDetector() // windowMs = 120_000, budget = 4
        for (i in 0 until 4) {
            val requestMs = i * 10_000L // 0, 10_000, 20_000, 30_000
            detector.onScanRequested(nowMs = requestMs, accepted = true)
            detector.onResults(nowMs = requestMs + 1_000, resultsUpdated = true, newestResultAgeMs = 500)
        }

        detector.onScanRequested(nowMs = 40_000, accepted = false)

        // Oldest budgeted request (t = 0) ages out of the 120 s window at t = 120_000.
        assertEquals(ThrottleState.Throttled(nextScanEtaMs = 120_000), detector.state)
        assertEquals(80_000L, detector.etaMs(nowMs = 40_000)) // oldest + window - now
        assertEquals(0L, detector.etaMs(nowMs = 130_000)) // clamped once the ETA has passed
    }

    @Test
    fun rejectedScanWithoutFullWindowUsesFallbackEta() {
        val detector = ScanThrottleDetector()
        detector.onScanRequested(nowMs = 5_000, accepted = true)

        detector.onScanRequested(nowMs = 6_000, accepted = false)

        // Fewer than budget requests recorded -> nowMs + 30 s fallback.
        assertEquals(ThrottleState.Throttled(nextScanEtaMs = 36_000), detector.state)
        assertEquals(30_000L, detector.etaMs(nowMs = 6_000))
    }

    @Test
    fun resultsNotUpdatedAfterOurRequestMeansThrottled() {
        val detector = ScanThrottleDetector()
        detector.onScanRequested(nowMs = 1_000, accepted = true)

        detector.onResults(nowMs = 2_000, resultsUpdated = false, newestResultAgeMs = 0)

        assertTrue(detector.state is ThrottleState.Throttled)
        assertEquals(30_000L, detector.etaMs(nowMs = 2_000)) // fallback: window not full
    }

    @Test
    fun unsolicitedNotUpdatedResultsAreIgnored() {
        val detector = ScanThrottleDetector()

        detector.onResults(nowMs = 1_000, resultsUpdated = false, newestResultAgeMs = 0)

        assertEquals(ThrottleState.Unknown, detector.state)
    }

    @Test
    fun updatedButStaleResultsMeanOemCached() {
        val detector = ScanThrottleDetector() // staleResultAgeMs = 15_000
        detector.onScanRequested(nowMs = 1_000, accepted = true)

        detector.onResults(nowMs = 2_000, resultsUpdated = true, newestResultAgeMs = 20_000)

        assertEquals(ThrottleState.OemCached, detector.state)
        assertEquals(0L, detector.etaMs(nowMs = 2_000)) // scans still accepted; no deadline
    }

    @Test
    fun freshResultAtStaleBoundaryIsStillUnthrottled() {
        val detector = ScanThrottleDetector()
        detector.onScanRequested(nowMs = 1_000, accepted = true)

        detector.onResults(nowMs = 2_000, resultsUpdated = true, newestResultAgeMs = 15_000)

        assertEquals(ThrottleState.Unthrottled, detector.state)
    }

    @Test
    fun recoversToUnthrottledAfterWindowPasses() {
        val detector = ScanThrottleDetector()
        for (i in 0 until 4) {
            detector.onScanRequested(nowMs = i * 10_000L, accepted = true)
            detector.onResults(nowMs = i * 10_000L + 1_000, resultsUpdated = true, newestResultAgeMs = 500)
        }
        detector.onScanRequested(nowMs = 40_000, accepted = false)
        assertTrue(detector.state is ThrottleState.Throttled)

        // Past the window: an accepted scan with fresh results clears the state.
        detector.onScanRequested(nowMs = 131_000, accepted = true)
        detector.onResults(nowMs = 132_000, resultsUpdated = true, newestResultAgeMs = 500)

        assertEquals(ThrottleState.Unthrottled, detector.state)
        assertEquals(0L, detector.etaMs(nowMs = 132_000))
    }

    @Test
    fun etaUsesBudgethOldestWhenMoreThanBudgetAcceptedInWindow() {
        // 6 accepted requests within the 120 s window, then a rejected 7th. The ETA must be
        // when the count drops below budget=4 — the (size-budget)=2nd-oldest ages out — not
        // when the very first (which would under-report the wait).
        val detector = ScanThrottleDetector() // windowMs = 120_000, budget = 4
        listOf(0L, 10_000, 20_000, 30_000, 40_000, 50_000).forEach {
            detector.onScanRequested(nowMs = it, accepted = true)
            detector.onResults(nowMs = it + 500, resultsUpdated = true, newestResultAgeMs = 100)
        }
        detector.onScanRequested(nowMs = 60_000, accepted = false)

        val state = detector.state
        assertTrue(state is ThrottleState.Throttled)
        // acceptedRequestsMs[6-4] = 20_000; eta = 20_000 + 120_000 = 140_000.
        assertEquals(140_000L, (state as ThrottleState.Throttled).nextScanEtaMs)
    }
}
