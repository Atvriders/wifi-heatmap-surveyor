package com.atvriders.wifiheatmap.core.wifi

/**
 * Order statistics over a batch of RSSI values (dBm), e.g. for the survey HUD
 * or per-cell heatmap summaries.
 */
object SignalStats {

    /**
     * Summary of a non-empty RSSI batch. All signal fields are in dBm.
     *
     * @property min Weakest (most negative) RSSI in the batch.
     * @property median Median RSSI; for even-sized batches this is the
     *   lower-middle element (no averaging, so the value is always one that was
     *   actually observed).
     * @property max Strongest RSSI in the batch.
     * @property count Number of values summarized.
     */
    data class Summary(val min: Int, val median: Int, val max: Int, val count: Int)

    /**
     * Summarizes [rssis] (RSSI values in dBm, any order), or returns null when
     * the list is empty. The input list is not modified.
     */
    fun summarize(rssis: List<Int>): Summary? {
        if (rssis.isEmpty()) return null
        val sorted = rssis.sorted()
        return Summary(
            min = sorted.first(),
            median = sorted[(sorted.size - 1) / 2],
            max = sorted.last(),
            count = sorted.size,
        )
    }
}
