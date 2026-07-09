package com.atvriders.wifiheatmap.core.wifi

/**
 * Validates raw RSSI values before they enter the survey pipeline.
 *
 * Real Wi-Fi RSSI readings live in roughly -100..-10 dBm. The platform sometimes
 * emits garbage outside that range: the -127 dBm "invalid" sentinel, 0/positive
 * values from broken drivers, or absurdly low readings. Those must never be
 * averaged into a heatmap, so they are dropped here.
 */
object DbmHygiene {

    /** Weakest plausible RSSI in dBm (inclusive). */
    const val MIN_VALID_DBM: Int = -100

    /** Strongest plausible RSSI in dBm (inclusive). */
    const val MAX_VALID_DBM: Int = -10

    /**
     * Returns [rssiDbm] unchanged when it is a plausible RSSI
     * ([MIN_VALID_DBM]..[MAX_VALID_DBM] dBm), or null when it is out of range
     * (e.g. the -127 sentinel, 0, or positive garbage) and must be discarded.
     */
    fun sanitize(rssiDbm: Int): Int? = rssiDbm.takeIf { it in MIN_VALID_DBM..MAX_VALID_DBM }
}
