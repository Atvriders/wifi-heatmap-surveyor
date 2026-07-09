package com.atvriders.wifiheatmap.core.wifi

import com.atvriders.wifiheatmap.core.model.Band

/**
 * Classifies a Wi-Fi channel center frequency into its [Band].
 *
 * Frequencies are in MHz, as reported by the platform (e.g. `ScanResult.frequency`,
 * `WifiInfo.frequency`). Ranges follow the platform's own band bucketing:
 *
 * - 2400..2500 MHz  -> [Band.GHZ_2_4]
 * - 4900..5924 MHz  -> [Band.GHZ_5] (includes the 4.9 GHz public-safety extension)
 * - 5925..7125 MHz  -> [Band.GHZ_6]
 *
 * Anything outside those ranges (including invalid/unknown sentinel frequencies)
 * yields null.
 */
object BandClassifier {

    /**
     * Returns the [Band] for [frequencyMhz] (channel center frequency in MHz),
     * or null when the frequency does not belong to a supported Wi-Fi band.
     */
    fun bandFor(frequencyMhz: Int): Band? = when (frequencyMhz) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900 until 5925 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> null
    }
}
