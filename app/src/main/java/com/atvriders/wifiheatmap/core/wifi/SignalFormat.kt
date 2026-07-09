package com.atvriders.wifiheatmap.core.wifi

/** Pure formatting of an RSSI value for display in dBm or as a derived percentage. */
object SignalFormat {

    /**
     * Maps an RSSI in dBm to a 0..100 signal quality percentage. This is the same clamped
     * linear approximation vendors use for signal bars — it is NOT a physical measurement,
     * which is why professionals work in dBm; the percentage is offered only as a convenience.
     * -100 dBm or worse → 0, -50 dBm or better → 100, linear between.
     */
    fun toPercent(rssiDbm: Int): Int =
        (2 * (rssiDbm + 100)).coerceIn(0, 100)

    /** "-63 dBm" or "74%" depending on [percent]. */
    fun format(rssiDbm: Int, percent: Boolean): String =
        if (percent) "${toPercent(rssiDbm)}%" else "$rssiDbm dBm"
}
