package com.atvriders.wifiheatmap.core.assemble

import com.atvriders.wifiheatmap.core.geo.GeoProjection
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.PositionFix
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSampleAssemblerTest {

    private fun snapshot(timestampMs: Long) = SignalSnapshot(
        timestampMs = timestampMs,
        readings = listOf(
            WifiReading(
                bssid = "aa:bb:cc:dd:ee:ff",
                ssid = "TestNet",
                rssiDbm = -60,
                frequencyMhz = 5180,
                band = Band.GHZ_5,
                isConnected = true,
            ),
        ),
    )

    @Test
    fun snapshotBeforeAnyFixIsNullAndWaiting() {
        val assembler = GpsSampleAssembler()

        assertTrue(assembler.waitingForFix)
        assertNull(assembler.onSnapshot(snapshot(1_000)))
        assertTrue(assembler.waitingForFix)
        assertNull(assembler.originLatLon)
    }

    @Test
    fun inaccurateFixesAreRejectedAndStillWaiting() {
        val assembler = GpsSampleAssembler(accuracyGateM = 15f)

        assembler.onFix(PositionFix(1_000, 44.0, -79.5, accuracyM = 30f))
        assembler.onFix(PositionFix(1_500, 44.0, -79.5, accuracyM = 15.1f))

        assertTrue(assembler.waitingForFix)
        assertNull(assembler.originLatLon)
        assertNull(assembler.onSnapshot(snapshot(2_000)))
    }

    @Test
    fun acceptedFixPositionsSnapshotWithProjectedCoordinatesAndCopiedFields() {
        val assembler = GpsSampleAssembler(accuracyGateM = 15f)
        val originLat = 44.0
        val originLon = -79.5
        assembler.onFix(PositionFix(1_000, originLat, originLon, accuracyM = 5f))
        assertFalse(assembler.waitingForFix)

        // A second accepted fix away from the origin, then a snapshot bound to it.
        val fixLat = 44.0005
        val fixLon = -79.4992
        assembler.onFix(PositionFix(2_000, fixLat, fixLon, accuracyM = 8f))
        val sample = assembler.onSnapshot(snapshot(2_500))

        assertNotNull(sample)
        val expected = GeoProjection(originLat, originLon).toLocalMeters(fixLat, fixLon)
        assertEquals(expected.x, sample!!.x, 1e-9)
        assertEquals(expected.y, sample.y, 1e-9)
        assertEquals(2_500L, sample.timestampMs)
        assertEquals(-1, sample.segmentIndex)
        assertEquals(fixLat, sample.latitude!!, 0.0)
        assertEquals(fixLon, sample.longitude!!, 0.0)
        assertEquals(8f, sample.accuracyM!!, 0f)
        assertEquals("TestNet", sample.readings.single().ssid)
        assertFalse(assembler.waitingForFix)
    }

    @Test
    fun staleFixYieldsNullUntilFreshFixArrives() {
        val assembler = GpsSampleAssembler(fixStaleMs = 5_000)
        assembler.onFix(PositionFix(1_000, 44.0, -79.5, accuracyM = 5f))

        // Age of exactly fixStaleMs is still acceptable.
        assertNotNull(assembler.onSnapshot(snapshot(6_000)))
        assertFalse(assembler.waitingForFix)

        // Snapshot 6 s after the fix (> 5 s gate) → dropped, back to waiting.
        assertNull(assembler.onSnapshot(snapshot(7_000)))
        assertTrue(assembler.waitingForFix)

        // A fresh accepted fix recovers.
        assembler.onFix(PositionFix(7_000, 44.0001, -79.5001, accuracyM = 5f))
        assertFalse(assembler.waitingForFix)
        assertNotNull(assembler.onSnapshot(snapshot(7_500)))
    }

    @Test
    fun originIsFirstAcceptedFixAndLaterFixesProjectRelativeToIt() {
        val assembler = GpsSampleAssembler(accuracyGateM = 15f)

        // A rejected fix must not set the origin.
        assembler.onFix(PositionFix(500, 10.0, 10.0, accuracyM = 99f))
        assertNull(assembler.originLatLon)

        val originLat = 51.5
        val originLon = -0.12
        assembler.onFix(PositionFix(1_000, originLat, originLon, accuracyM = 4f))
        assertEquals(originLat to originLon, assembler.originLatLon)

        // A later accepted fix does not move the origin; it projects relative to it.
        val laterLat = 51.5008
        val laterLon = -0.1188
        assembler.onFix(PositionFix(2_000, laterLat, laterLon, accuracyM = 6f))
        assertEquals(originLat to originLon, assembler.originLatLon)

        val sample = assembler.onSnapshot(snapshot(2_100))
        assertNotNull(sample)
        val expected = GeoProjection(originLat, originLon).toLocalMeters(laterLat, laterLon)
        assertEquals(expected.x, sample!!.x, 1e-9)
        assertEquals(expected.y, sample.y, 1e-9)
    }
}
