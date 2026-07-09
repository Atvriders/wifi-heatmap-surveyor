package com.atvriders.wifiheatmap.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoProjectionTest {

    @Test
    fun originProjectsToZero() {
        val projection = GeoProjection(originLat = 45.0, originLon = 7.5)
        val p = projection.toLocalMeters(45.0, 7.5)
        assertEquals(0.0, p.x, 1e-12)
        assertEquals(0.0, p.y, 1e-12)
    }

    @Test
    fun oneMillidegreeLonAtLat45IsAbout78point7MetersEast() {
        val projection = GeoProjection(originLat = 45.0, originLon = 0.0)
        val p = projection.toLocalMeters(45.0, 0.001)
        // 0.001 deg * cos(45 deg) * 111320 m/deg ~= 78.7 m; verify within 0.5%.
        val relativeError = abs(p.x - 78.7) / 78.7
        assertTrue("x = ${p.x}, relative error $relativeError", relativeError < 0.005)
        assertEquals("pure east displacement has y = 0", 0.0, p.y, 1e-9)
    }

    @Test
    fun oneMillidegreeLatNorthGivesNegativeYAbout110point5Meters() {
        val projection = GeoProjection(originLat = 45.0, originLon = 0.0)
        val p = projection.toLocalMeters(45.001, 0.0)
        // North of the origin must be NEGATIVE y (y grows downward/southward).
        assertTrue("y must be negative for a point north of origin, was ${p.y}", p.y < 0.0)
        val relativeError = abs(p.y - (-110.5)) / 110.5
        assertTrue("y = ${p.y}, relative error $relativeError", relativeError < 0.005)
        assertEquals("pure north displacement has x = 0", 0.0, p.x, 1e-9)
    }

    @Test
    fun southAndWestOfOriginGivePositiveYAndNegativeX() {
        val projection = GeoProjection(originLat = 45.0, originLon = 0.0)
        val p = projection.toLocalMeters(44.999, -0.001)
        assertTrue("south of origin must be positive y, was ${p.y}", p.y > 0.0)
        assertTrue("west of origin must be negative x, was ${p.x}", p.x < 0.0)
    }

    @Test
    fun roundtripRecoversLatLonWithinOneNanodegree() {
        val projection = GeoProjection(originLat = 45.0, originLon = -122.5)
        val points = listOf(
            45.0012345 to -122.4990123,
            44.9987654 to -122.5011111,
            45.0 to -122.5,
            45.0031415 to -122.5026535,
        )
        for ((lat, lon) in points) {
            val (latBack, lonBack) = projection.toLatLon(projection.toLocalMeters(lat, lon))
            assertEquals("lat roundtrip for ($lat, $lon)", lat, latBack, 1e-9)
            assertEquals("lon roundtrip for ($lat, $lon)", lon, lonBack, 1e-9)
        }
    }

    @Test
    fun reverseRoundtripRecoversLocalMeters() {
        val projection = GeoProjection(originLat = 37.25, originLon = 11.75)
        val p = Vec2(123.456, -78.9)
        val (lat, lon) = projection.toLatLon(p)
        val back = projection.toLocalMeters(lat, lon)
        assertEquals(p.x, back.x, 1e-6)
        assertEquals(p.y, back.y, 1e-6)
    }
}
