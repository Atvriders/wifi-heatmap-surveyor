package com.atvriders.wifiheatmap.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleCalibrationTest {

    @Test
    fun hundredPixelsFiveMetersGivesFiveCentimetersPerPixel() {
        val result = ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(100.0, 0.0), 5.0)
        assertNotNull(result)
        assertEquals(0.05, result!!, 1e-12)
    }

    @Test
    fun diagonalDistanceUsesEuclideanLength() {
        // (0,0) -> (60,80) is 100 px.
        val result = ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(60.0, 80.0), 5.0)
        assertNotNull(result)
        assertEquals(0.05, result!!, 1e-12)
    }

    @Test
    fun coincidentPointsAreDegenerate() {
        assertNull(ScaleCalibration.metersPerPixel(Vec2(10.0, 10.0), Vec2(10.0, 10.0), 5.0))
    }

    @Test
    fun subPixelSeparationIsDegenerate() {
        assertNull(ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(0.5, 0.5), 5.0))
    }

    @Test
    fun exactlyOnePixelSeparationIsAccepted() {
        val result = ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(1.0, 0.0), 2.0)
        assertNotNull(result)
        assertEquals(2.0, result!!, 1e-12)
    }

    @Test
    fun zeroRealDistanceIsDegenerate() {
        assertNull(ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(100.0, 0.0), 0.0))
    }

    @Test
    fun negativeRealDistanceIsDegenerate() {
        assertNull(ScaleCalibration.metersPerPixel(Vec2(0.0, 0.0), Vec2(100.0, 0.0), -3.0))
    }
}
