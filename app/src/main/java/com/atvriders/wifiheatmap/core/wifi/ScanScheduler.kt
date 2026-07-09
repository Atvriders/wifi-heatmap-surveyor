package com.atvriders.wifiheatmap.core.wifi

/**
 * Token-bucket pacing for Wi-Fi scan requests.
 *
 * Pure and synchronous: the caller records every scan it actually issues via
 * [recordScan] and asks [nextScanDelayMs] how long to wait before the next one.
 * All `nowMs` values must come from one shared clock (e.g.
 * `SystemClock.elapsedRealtime()`), injected by the caller — this class never
 * reads the system time. Not thread-safe; confine to one dispatcher.
 *
 * Two pacing regimes, selected by the caller (typically from
 * [ScanThrottleDetector.state]):
 * - Unthrottled: simple fixed interval of [unthrottledIntervalMs] between scans.
 * - Throttled: at least [throttledIntervalMs] between scans, AND never more than
 *   [budget] scans inside any sliding [windowMs] window (mirrors the platform's
 *   4-scans-per-2-minutes foreground budget so rejected scans are avoided).
 *
 * @property budget Maximum scans allowed per sliding window when throttled (default 4).
 * @property windowMs Sliding window length in milliseconds (default 120 000 ms = 2 min).
 * @property unthrottledIntervalMs Minimum ms between scans when unthrottled (default 4 000).
 * @property throttledIntervalMs Minimum ms between scans when throttled (default 30 000).
 */
class ScanScheduler(
    val budget: Int = 4,
    val windowMs: Long = 120_000,
    val unthrottledIntervalMs: Long = 4_000,
    val throttledIntervalMs: Long = 30_000,
) {

    /** Timestamps (ms) of issued scans still inside the sliding window. */
    private val scansMs = ArrayDeque<Long>()

    /** Timestamp (ms) of the most recently issued scan; null before any. */
    private var lastScanMs: Long? = null

    /**
     * Records that a scan was actually issued at [nowMs] (ms on the shared
     * clock). Call this only for scans that were really started (accepted by
     * the platform), so the token bucket mirrors the platform's budget.
     */
    fun recordScan(nowMs: Long) {
        prune(nowMs)
        scansMs.addLast(nowMs)
        lastScanMs = nowMs
    }

    /**
     * Milliseconds to wait from [nowMs] before issuing the next scan (0 = scan
     * now). Returns 0 when no scan has ever been recorded.
     *
     * When [throttled] is false: time remaining in the [unthrottledIntervalMs]
     * interval since the last scan.
     *
     * When [throttled] is true: the larger of (a) time remaining in the
     * [throttledIntervalMs] interval since the last scan and (b) time until a
     * token frees, i.e. until the oldest of the last [budget] scans ages out of
     * the sliding [windowMs] window.
     */
    fun nextScanDelayMs(nowMs: Long, throttled: Boolean): Long {
        prune(nowMs)
        val last = lastScanMs ?: return 0L
        if (!throttled) {
            return (last + unthrottledIntervalMs - nowMs).coerceAtLeast(0L)
        }
        val intervalDelay = (last + throttledIntervalMs - nowMs).coerceAtLeast(0L)
        val tokenDelay = if (scansMs.size >= budget) {
            (scansMs.first() + windowMs - nowMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(intervalDelay, tokenDelay)
    }

    /** Drops scan timestamps that have aged out of the window at [nowMs]. */
    private fun prune(nowMs: Long) {
        while (scansMs.isNotEmpty() && scansMs.first() + windowMs <= nowMs) {
            scansMs.removeFirst()
        }
    }
}
