package com.atvriders.wifiheatmap.core.assemble

import com.atvriders.wifiheatmap.core.geo.GeoProjection
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.core.model.PositionFix
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Resume path: a [GpsSampleAssembler] seeded with a preset origin must project fixes into
 * the ORIGINAL survey frame instead of re-anchoring a fresh origin at the first fix.
 */
class GpsResumeOriginTest {

    @Test
    fun `preset origin is used instead of re-anchoring at the first fix`() {
        val assembler = GpsSampleAssembler(presetOriginLatLon = 45.5 to -122.6)

        assembler.onFix(
            PositionFix(
                timestampMs = 1000,
                latitude = 45.5008,
                longitude = -122.5985,
                accuracyM = 5f,
            )
        )
        val sample = assembler.onSnapshot(
            SignalSnapshot(
                timestampMs = 1000,
                readings = listOf(
                    WifiReading(
                        bssid = "aa:bb:cc:dd:ee:ff",
                        ssid = "TestNet",
                        rssiDbm = -55,
                        frequencyMhz = 5180,
                        band = Band.GHZ_5,
                        isConnected = true,
                    )
                ),
            )
        )

        assertNotNull(sample)
        val expected = GeoProjection(45.5, -122.6).toLocalMeters(45.5008, -122.5985)
        // Exact equality: had the assembler re-anchored at the first fix, x/y would be ~0,0.
        assertEquals(expected.x, sample!!.x, 0.0)
        assertEquals(expected.y, sample.y, 0.0)
    }
}
