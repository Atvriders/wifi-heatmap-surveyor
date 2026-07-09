package com.atvriders.wifiheatmap.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "surveys")
data class SurveyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
    val positioningMode: String,      // "TAP" | "GPS"
    val scanMode: String,             // "CONNECTED" | "SCAN_ALL"
    val floorPlanId: Long?,           // null for GPS surveys
    val status: String,               // "ACTIVE" | "COMPLETE"
)

@Entity(tableName = "floor_plans")
data class FloorPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String?,           // null => blank grid
    val widthPx: Int,
    val heightPx: Int,
    val metersPerPixel: Double?,      // null until calibrated; fixed by construction for grids
    // Raw two-point calibration inputs, kept so calibration stays re-editable.
    val calAx: Double?, val calAy: Double?,
    val calBx: Double?, val calBy: Double?,
    val calDistanceM: Double?,
)

/**
 * One positioned capture instant. x/y are floor-plan pixels (TAP) or local meters (GPS).
 * segmentIndex identifies the tap segment for undo/deletion; -1 in GPS mode.
 */
@Entity(
    tableName = "samples",
    indices = [Index("surveyId"), Index(value = ["surveyId", "segmentIndex"])],
    foreignKeys = [ForeignKey(
        entity = SurveyEntity::class,
        parentColumns = ["id"], childColumns = ["surveyId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surveyId: Long,
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val segmentIndex: Int,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
)

/**
 * One BSSID observation within a sample. surveyId and band are denormalized on
 * purpose: the hot filter queries hit this 100k+-row table without joins.
 */
@Entity(
    tableName = "readings",
    indices = [
        Index("sampleId"),
        Index(value = ["surveyId", "ssid"]),
        Index(value = ["surveyId", "bssid"]),
    ],
    foreignKeys = [ForeignKey(
        entity = SampleEntity::class,
        parentColumns = ["id"], childColumns = ["sampleId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    val surveyId: Long,
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: Int,                    // 2, 5, or 6 — precomputed from frequency
    val isConnected: Boolean,
)
