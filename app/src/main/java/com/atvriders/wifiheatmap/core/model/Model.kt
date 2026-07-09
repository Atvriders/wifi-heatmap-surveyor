package com.atvriders.wifiheatmap.core.model

/** How sample positions are produced during a survey. */
enum class PositioningMode { TAP, GPS }

/** Which signal source feeds the survey. */
enum class ScanMode { CONNECTED, SCAN_ALL }

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
}

/** One Wi-Fi observation of a single BSSID. */
data class WifiReading(
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: Band,
    val isConnected: Boolean,
)

/**
 * All readings captured at one instant, before a position is attached.
 * [fresh] is false when the platform served a repeat of a cached RSSI value
 * (the framework only refreshes the connected-network RSSI every ~3 s).
 */
data class SignalSnapshot(
    val timestampMs: Long,
    val readings: List<WifiReading>,
    val fresh: Boolean = true,
)

/**
 * A snapshot bound to a position. Coordinates are floor-plan pixels in TAP mode
 * and local meters (equirectangular projection around the survey origin) in GPS mode.
 * [segmentIndex] is the tap segment the sample was interpolated on (TAP mode; -1 for GPS).
 */
data class PositionedSample(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val readings: List<WifiReading>,
    val segmentIndex: Int = -1,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
)

/** A raw GPS fix; projection to local meters happens in the GPS sample assembler. */
data class PositionFix(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
)

/** What the heatmap visualizes. Null dimensions are unfiltered. */
data class HeatFilter(
    val ssid: String? = null,
    val band: Band? = null,
    val bssid: String? = null,
) {
    /** Best (max) RSSI among readings matching this filter, or null when none match. */
    fun bestRssi(readings: List<WifiReading>): Int? = readings
        .asSequence()
        .filter { ssid == null || it.ssid == ssid }
        .filter { band == null || it.band == band }
        .filter { bssid == null || it.bssid == bssid }
        .maxOfOrNull { it.rssiDbm }
}
