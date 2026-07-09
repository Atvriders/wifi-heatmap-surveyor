package com.atvriders.wifiheatmap.core.wifi

/** Empirically-detected Wi-Fi scan throttling state (the OS setting is not reliably readable). */
sealed interface ThrottleState {
    /** Not enough evidence yet. */
    data object Unknown : ThrottleState

    /** Scans are returning fresh results at the requested pace. */
    data object Unthrottled : ThrottleState

    /** OS is enforcing the 4-scans-per-2-minutes budget. */
    data class Throttled(val nextScanEtaMs: Long) : ThrottleState

    /** Device reports success but serves cached results (some OEMs, e.g. Samsung). */
    data object OemCached : ThrottleState
}
