package com.atvriders.wifiheatmap.data

import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.WifiReading
import com.atvriders.wifiheatmap.data.db.JoinedReadingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {

    @Test
    fun bandRoundtrip() {
        for (band in Band.entries) {
            assertEquals(band, bandFromDbInt(band.toDbInt()))
        }
        assertNull(bandFromDbInt(4))
        assertNull(bandFromDbInt(0))
    }

    @Test
    fun sampleToEntityCopiesEverything() {
        val sample = PositionedSample(
            timestampMs = 42L, x = 1.5, y = 2.5,
            readings = emptyList(), segmentIndex = 3,
            latitude = 45.0, longitude = -122.0, accuracyM = 5f,
        )
        val e = sample.toEntity(surveyId = 7)
        assertEquals(7L, e.surveyId)
        assertEquals(42L, e.timestampMs)
        assertEquals(1.5, e.x, 0.0)
        assertEquals(2.5, e.y, 0.0)
        assertEquals(3, e.segmentIndex)
        assertEquals(45.0, e.latitude!!, 0.0)
        assertEquals(-122.0, e.longitude!!, 0.0)
        assertEquals(5f, e.accuracyM!!, 0f)
    }

    @Test
    fun readingToEntityDenormalizesSurveyIdAndBand() {
        val r = WifiReading("aa:bb", "Net", -60, 5180, Band.GHZ_5, isConnected = true)
        val e = r.toEntity(surveyId = 9)
        assertEquals(9L, e.surveyId)
        assertEquals(5, e.band)
        assertEquals(0L, e.sampleId) // patched at insert time
    }

    @Test
    fun regroupJoinsRowsBySampleId() {
        fun row(sampleId: Long, bssid: String, ts: Long = sampleId * 100) = JoinedReadingRow(
            sampleId = sampleId, timestampMs = ts, x = sampleId.toDouble(), y = 0.0,
            segmentIndex = 0, latitude = null, longitude = null, accuracyM = null,
            ssid = "Net", bssid = bssid, rssiDbm = -60, frequencyMhz = 5180,
            band = 5, isConnected = false,
        )
        val samples = regroupJoinedRows(
            listOf(row(1, "a"), row(1, "b"), row(2, "c"))
        )
        assertEquals(2, samples.size)
        assertEquals(2, samples[0].readings.size)
        assertEquals(1, samples[1].readings.size)
        assertEquals(100L, samples[0].timestampMs)
        assertEquals("c", samples[1].readings[0].bssid)
    }

    @Test
    fun regroupDropsUnknownBandReadingsButKeepsSample() {
        val bad = JoinedReadingRow(
            sampleId = 1, timestampMs = 1, x = 0.0, y = 0.0, segmentIndex = -1,
            latitude = null, longitude = null, accuracyM = null,
            ssid = "X", bssid = "z", rssiDbm = -50, frequencyMhz = 900,
            band = 9, isConnected = false,
        )
        val samples = regroupJoinedRows(listOf(bad))
        assertEquals(1, samples.size)
        assertEquals(0, samples[0].readings.size)
    }

    @Test
    fun regroupEmptyIsEmpty() {
        assertEquals(0, regroupJoinedRows(emptyList()).size)
    }
}
