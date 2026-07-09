package com.atvriders.wifiheatmap.data.db

import androidx.room.Embedded

/** Home-screen list row. */
data class SurveyWithCount(
    @Embedded val survey: SurveyEntity,
    val sampleCount: Int,
)

/** Filter sheet row: one SSID with aggregates. */
data class SsidSummaryRow(
    val ssid: String,
    val bssidCount: Int,
    val maxRssi: Int,
    val readingCount: Int,
)

/** Per-BSSID drill-down row. */
data class BssidRow(
    val bssid: String,
    val frequencyMhz: Int,
    val band: Int,
    val maxRssi: Int,
    val readingCount: Int,
)

/** Heatmap rehydration/analysis point: best matching RSSI at one sample position. */
data class HeatPointRow(
    val x: Double,
    val y: Double,
    val rssi: Int,
)

/** Joined flat row used for CSV export AND full survey rehydration (grouped by sampleId). */
data class JoinedReadingRow(
    val sampleId: Long,
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val segmentIndex: Int,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: Int,
    val isConnected: Boolean,
)

/** Sample-extent row for grid sizing on rehydrate. */
data class BoundsRow(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)
