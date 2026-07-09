package com.atvriders.wifiheatmap.core.wifi

/**
 * Freshness de-duplication for connected-network RSSI polling.
 *
 * The framework only refreshes the connected network's RSSI every ~3 s, so a
 * fast poll loop mostly re-reads the same cached value. This class tags each
 * reading as fresh or stale so the engine can set
 * [com.atvriders.wifiheatmap.core.model.SignalSnapshot.fresh] accordingly and
 * avoid flooding a survey with duplicate samples.
 *
 * A reading is FRESH only when its dBm value differs from the previous reading
 * (or it is the very first reading). Every repeat of the same value is stale —
 * even one arriving after [repeatIsStaleAfterMs] — because a repeat carries no
 * new information; a genuinely stable signal is still represented by the one
 * fresh reading that first reported the value.
 *
 * Pure and synchronous; all `nowMs` values must come from one caller-supplied
 * clock (never read here). Not thread-safe; confine to one dispatcher.
 *
 * @property repeatIsStaleAfterMs Threshold in ms used by [isStale] to judge the
 *   whole reading stream: once the value has not changed for longer than this,
 *   the connected-RSSI source is considered stale/stuck (default 3 500 ms,
 *   just over the framework's ~3 s refresh period).
 */
class RssiSampler(val repeatIsStaleAfterMs: Long = 3_500) {

    /** dBm value of the previous reading; null before the first reading. */
    private var lastValueDbm: Int? = null

    /** Timestamp (ms) when the value last changed; null before the first reading. */
    private var lastChangeMs: Long? = null

    /**
     * Records a reading of the connected network's RSSI ([rssiDbm], in dBm) at
     * [nowMs] (ms on the caller's clock).
     *
     * @return true when the reading is fresh (first reading, or the value
     *   changed since the previous reading); false when it repeats the previous
     *   value. Repeats never update the last-change time, regardless of age.
     */
    fun onReading(nowMs: Long, rssiDbm: Int): Boolean {
        if (lastValueDbm == rssiDbm) return false
        lastValueDbm = rssiDbm
        lastChangeMs = nowMs
        return true
    }

    /**
     * Milliseconds elapsed at [nowMs] since the RSSI value last CHANGED
     * (repeats do not reset it), or -1 before the first reading.
     */
    fun ageMs(nowMs: Long): Long = lastChangeMs?.let { nowMs - it } ?: -1L

    /**
     * True when at least one reading exists and the value has not changed for
     * longer than [repeatIsStaleAfterMs] at [nowMs] — i.e. the connected-RSSI
     * source is likely serving a stuck cached value. False before the first
     * reading (nothing to be stale yet).
     */
    fun isStale(nowMs: Long): Boolean {
        val age = ageMs(nowMs)
        return age >= 0 && age > repeatIsStaleAfterMs
    }
}
