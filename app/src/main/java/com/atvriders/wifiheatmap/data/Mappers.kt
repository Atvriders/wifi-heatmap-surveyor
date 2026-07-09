package com.atvriders.wifiheatmap.data

import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.WifiReading
import com.atvriders.wifiheatmap.data.db.JoinedReadingRow
import com.atvriders.wifiheatmap.data.db.ReadingEntity
import com.atvriders.wifiheatmap.data.db.SampleEntity

fun Band.toDbInt(): Int = when (this) {
    Band.GHZ_2_4 -> 2
    Band.GHZ_5 -> 5
    Band.GHZ_6 -> 6
}

fun bandFromDbInt(v: Int): Band? = when (v) {
    2 -> Band.GHZ_2_4
    5 -> Band.GHZ_5
    6 -> Band.GHZ_6
    else -> null
}

fun PositionedSample.toEntity(surveyId: Long): SampleEntity = SampleEntity(
    surveyId = surveyId,
    timestampMs = timestampMs,
    x = x,
    y = y,
    segmentIndex = segmentIndex,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
)

fun WifiReading.toEntity(surveyId: Long): ReadingEntity = ReadingEntity(
    sampleId = 0,          // patched by SampleDao.insertBatch
    surveyId = surveyId,
    bssid = bssid,
    ssid = ssid,
    rssiDbm = rssiDbm,
    frequencyMhz = frequencyMhz,
    band = band.toDbInt(),
    isConnected = isConnected,
)

/**
 * Regroups the flat export/rehydration join back into [PositionedSample]s,
 * dropping readings whose stored band is unknown. Row order (by sample) is preserved.
 */
fun regroupJoinedRows(rows: List<JoinedReadingRow>): List<PositionedSample> {
    if (rows.isEmpty()) return emptyList()
    val out = ArrayList<PositionedSample>()
    var currentId = Long.MIN_VALUE
    var readings = ArrayList<WifiReading>()
    var head: JoinedReadingRow? = null

    fun flush() {
        val h = head ?: return
        out.add(
            PositionedSample(
                timestampMs = h.timestampMs,
                x = h.x,
                y = h.y,
                readings = readings,
                segmentIndex = h.segmentIndex,
                latitude = h.latitude,
                longitude = h.longitude,
                accuracyM = h.accuracyM,
            )
        )
    }

    for (row in rows) {
        if (row.sampleId != currentId) {
            flush()
            currentId = row.sampleId
            head = row
            readings = ArrayList()
        }
        val band = bandFromDbInt(row.band) ?: continue
        readings.add(
            WifiReading(
                bssid = row.bssid,
                ssid = row.ssid,
                rssiDbm = row.rssiDbm,
                frequencyMhz = row.frequencyMhz,
                band = band,
                isConnected = row.isConnected,
            )
        )
    }
    flush()
    return out
}
