package com.atvriders.wifiheatmap.core.geo

import kotlin.math.sqrt

/**
 * Immutable 2D point/vector in "plan units" — floor-plan pixels in TAP mode, meters in
 * GPS mode (see [GeoProjection]). Project-wide coordinate convention: x grows east/right,
 * y grows DOWNWARD (screen convention).
 */
data class Vec2(val x: Double, val y: Double) {

    /** Component-wise sum, same units as the operands. */
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    /** Component-wise difference, same units as the operands. */
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    /** Uniform scaling by a unitless [scalar]; result keeps this vector's units. */
    operator fun times(scalar: Double): Vec2 = Vec2(x * scalar, y * scalar)

    /** Euclidean magnitude in the same units as the components (plan units). */
    val length: Double
        get() = sqrt(x * x + y * y)

    /** Euclidean distance to [other] in the same units as the components (plan units). */
    fun distanceTo(other: Vec2): Double = (this - other).length

    companion object {
        /** The origin (0, 0). */
        val ZERO: Vec2 = Vec2(0.0, 0.0)

        /**
         * Linear interpolation from [a] (at t = 0.0) to [b] (at t = 1.0).
         * [t] is unitless and is NOT clamped, so t outside [0, 1] extrapolates.
         */
        fun lerp(a: Vec2, b: Vec2, t: Double): Vec2 =
            Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}
