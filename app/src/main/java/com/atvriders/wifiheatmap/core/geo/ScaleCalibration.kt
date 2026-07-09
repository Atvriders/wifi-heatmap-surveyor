package com.atvriders.wifiheatmap.core.geo

/**
 * Converts a user-drawn calibration line on the floor plan into a plan scale.
 * Used in TAP mode to attach real-world distances to floor-plan pixels.
 */
object ScaleCalibration {

    /**
     * Scale in meters per floor-plan pixel from two calibration endpoints.
     *
     * @param a first endpoint in floor-plan pixels (x right, y down).
     * @param b second endpoint in floor-plan pixels (x right, y down).
     * @param realDistanceM the real-world distance between the two points, in meters.
     * @return meters per pixel, or null when degenerate: the endpoints are less than
     *   1.0 px apart, or [realDistanceM] is not strictly positive.
     */
    fun metersPerPixel(a: Vec2, b: Vec2, realDistanceM: Double): Double? {
        val pixelDistance = a.distanceTo(b)
        if (pixelDistance < 1.0 || realDistanceM <= 0.0) return null
        return realDistanceM / pixelDistance
    }
}
