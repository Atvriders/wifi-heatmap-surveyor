package com.atvriders.wifiheatmap.core.export

import java.time.Instant

/**
 * One CSV output row: a single (sample, reading) pair, flat for Excel/pandas.
 * Position columns are meters when the survey is calibrated (or GPS); an
 * uncalibrated tap survey exports raw plan pixels and says so in [posSource].
 */
data class CsvExportRow(
    val timestampMs: Long,
    val posSource: String,      // "tap" | "tap_uncalibrated_px" | "gps"
    val segmentIndex: Int?,     // tap surveys only
    val x: Double?,
    val y: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val ssid: String,
    val bssid: String,
    val band: String,           // "2.4" | "5" | "6"
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val connected: Boolean,
)

/** RFC-4180 CSV assembly (pure string building; the Android layer only writes the file). */
object CsvBuilder {

    const val HEADER = "timestamp_iso,pos_source,segment_index,x_m,y_m,latitude,longitude," +
        "gps_accuracy_m,ssid,bssid,band_ghz,frequency_mhz,rssi_dbm,connected"

    fun build(rows: List<CsvExportRow>): String = buildString {
        append(HEADER).append("\r\n")
        for (r in rows) {
            append(Instant.ofEpochMilli(r.timestampMs).toString()).append(',')
            append(quote(r.posSource)).append(',')
            append(r.segmentIndex?.toString().orEmpty()).append(',')
            append(r.x?.toString().orEmpty()).append(',')
            append(r.y?.toString().orEmpty()).append(',')
            append(r.latitude?.toString().orEmpty()).append(',')
            append(r.longitude?.toString().orEmpty()).append(',')
            append(r.accuracyM?.toString().orEmpty()).append(',')
            append(quote(r.ssid)).append(',')
            append(quote(r.bssid)).append(',')
            append(r.band).append(',')
            append(r.frequencyMhz).append(',')
            append(r.rssiDbm).append(',')
            append(r.connected).append("\r\n")
        }
    }

    /**
     * RFC-4180: quote when the field contains a comma, quote, CR or LF; embedded
     * quotes double. SSIDs legitimately contain commas, emoji and quotes.
     */
    internal fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
