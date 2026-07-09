package com.atvriders.wifiheatmap.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip check for [GeoProjection.originFromSample]: a stored sample carrying its raw
 * fix plus its projected local-meter position must recover the original survey origin.
 */
class OriginRecoveryTest {

    @Test
    fun `originFromSample recovers the projection origin from a projected point`() {
        val projection = GeoProjection(45.5, -122.6)
        val v = projection.toLocalMeters(45.5008, -122.5985)

        val (originLat, originLon) =
            GeoProjection.originFromSample(45.5008, -122.5985, v.x, v.y)

        assertEquals(45.5, originLat, 1e-9)
        assertEquals(-122.6, originLon, 1e-9)
    }
}
