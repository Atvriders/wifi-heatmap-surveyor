package com.atvriders.wifiheatmap.core.wifi

/**
 * Empirically detects whether the OS is throttling Wi-Fi scans, since the
 * developer-options throttle toggle is not reliably readable across devices.
 *
 * This is a pure, synchronous state machine: the Android layer reports scan
 * lifecycle events (with `nowMs` timestamps from a single monotonic-ish clock,
 * e.g. `SystemClock.elapsedRealtime()`), and [state] is updated immediately.
 * All timestamps passed to this class must come from the same clock. Not
 * thread-safe; confine to one dispatcher.
 *
 * Evidence rules:
 * - `startScan()` returning false while Wi-Fi is on ([onScanRequested] with
 *   `accepted = false`) means the platform budget (default 4 scans / 2 min for
 *   foreground apps) is exhausted -> [ThrottleState.Throttled].
 * - A results broadcast with `resultsUpdated = false` shortly after our own
 *   request means the scan was silently suppressed -> [ThrottleState.Throttled].
 * - Updated results whose newest entry is older than [staleResultAgeMs] mean
 *   the device "succeeded" but served a cached list (some OEMs, e.g. Samsung)
 *   -> [ThrottleState.OemCached].
 * - Updated, recent results -> [ThrottleState.Unthrottled].
 *
 * Accepted request timestamps are kept in a sliding window of [windowMs] so
 * that, when throttling is detected, the ETA of the next allowed scan can be
 * computed as the moment the oldest budgeted request ages out of the window.
 *
 * @property windowMs Length of the platform throttle window in milliseconds
 *   (default 120 000 ms = 2 minutes).
 * @property budget Number of scans allowed per window (default 4).
 * @property staleResultAgeMs Maximum age in milliseconds of the newest scan
 *   result for it to count as fresh; older updated results indicate an
 *   OEM-served cache (default 15 000 ms).
 */
class ScanThrottleDetector(
    val windowMs: Long = 120_000,
    val budget: Int = 4,
    val staleResultAgeMs: Long = 15_000,
) {

    /** Current detected throttle state. Starts as [ThrottleState.Unknown]. */
    var state: ThrottleState = ThrottleState.Unknown
        private set

    /** Timestamps (ms) of accepted scan requests still inside the sliding window. */
    private val acceptedRequestsMs = ArrayDeque<Long>()

    /** Timestamp (ms) of the most recent scan request (accepted or not); null before any. */
    private var lastRequestMs: Long? = null

    /**
     * Records that a scan was requested at [nowMs] (ms on the shared clock).
     *
     * [accepted] mirrors `WifiManager.startScan()`'s return value with Wi-Fi
     * enabled. Accepted requests consume budget and enter the sliding window;
     * a rejected request is direct evidence of throttling and flips [state] to
     * [ThrottleState.Throttled] with the ETA described in [throttledEtaMs].
     */
    fun onScanRequested(nowMs: Long, accepted: Boolean) {
        prune(nowMs)
        lastRequestMs = nowMs
        if (accepted) {
            acceptedRequestsMs.addLast(nowMs)
        } else {
            state = ThrottleState.Throttled(nextScanEtaMs = throttledEtaMs(nowMs))
        }
    }

    /**
     * Records the outcome of a scan-results broadcast at [nowMs] (ms on the
     * shared clock).
     *
     * @param resultsUpdated Whether the platform reported the result list as
     *   updated (`EXTRA_RESULTS_UPDATED`). False after our own request means the
     *   scan was suppressed -> [ThrottleState.Throttled]. A false broadcast seen
     *   before any request was ever made is ignored (unsolicited, no evidence).
     * @param newestResultAgeMs Age in milliseconds of the newest result entry
     *   (now minus its capture timestamp). Updated results older than
     *   [staleResultAgeMs] -> [ThrottleState.OemCached]; otherwise fresh ->
     *   [ThrottleState.Unthrottled].
     */
    fun onResults(nowMs: Long, resultsUpdated: Boolean, newestResultAgeMs: Long) {
        prune(nowMs)
        state = when {
            !resultsUpdated -> {
                if (lastRequestMs == null) return
                ThrottleState.Throttled(nextScanEtaMs = throttledEtaMs(nowMs))
            }
            newestResultAgeMs > staleResultAgeMs -> ThrottleState.OemCached
            else -> ThrottleState.Unthrottled
        }
    }

    /**
     * Milliseconds from [nowMs] until the next scan should be allowed.
     *
     * Returns 0 when [state] is [ThrottleState.Unthrottled] or
     * [ThrottleState.Unknown] (scan freely), and also for
     * [ThrottleState.OemCached] (scans are accepted there; the results are just
     * cached, so there is no deadline to wait for). When
     * [ThrottleState.Throttled], returns the remaining wait until the state's
     * absolute [ThrottleState.Throttled.nextScanEtaMs], clamped to >= 0.
     */
    fun etaMs(nowMs: Long): Long = when (val s = state) {
        is ThrottleState.Throttled -> (s.nextScanEtaMs - nowMs).coerceAtLeast(0L)
        else -> 0L
    }

    /**
     * Absolute timestamp (ms, same clock as `nowMs`) when the next scan should
     * be permitted: the moment the oldest accepted request ages out of the
     * sliding window when a full [budget] of requests is recorded, otherwise a
     * conservative `nowMs + 30 s` fallback (we know we are throttled but cannot
     * see the platform's own window).
     */
    private fun throttledEtaMs(nowMs: Long): Long =
        if (acceptedRequestsMs.size >= budget) {
            acceptedRequestsMs.first() + windowMs
        } else {
            nowMs + FALLBACK_BACKOFF_MS
        }

    /** Drops accepted-request timestamps that have aged out of the window at [nowMs]. */
    private fun prune(nowMs: Long) {
        while (acceptedRequestsMs.isNotEmpty() && acceptedRequestsMs.first() + windowMs <= nowMs) {
            acceptedRequestsMs.removeFirst()
        }
    }

    companion object {
        /** ETA fallback in ms when throttling is detected without a full window of requests. */
        const val FALLBACK_BACKOFF_MS: Long = 30_000
    }
}
