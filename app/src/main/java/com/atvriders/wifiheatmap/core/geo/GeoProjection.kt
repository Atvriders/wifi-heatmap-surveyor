package com.atvriders.wifiheatmap.core.geo

import kotlin.math.cos

/**
 * Local tangent-plane equirectangular projection anchored at a survey origin.
 *
 * Maps WGS-84 latitude/longitude (decimal degrees) to local meters on a north-up canvas:
 * - x grows EAST in meters,
 * - y grows DOWNWARD/SOUTHWARD in meters (project screen convention: a point north of the
 *   origin has NEGATIVE y).
 *
 * Accuracy is adequate for survey-sized areas (hundreds of meters around the origin);
 * distortion grows with distance from the origin and toward the poles. Degenerate near the
 * poles (|originLat| close to 90 degrees) where the longitude scale factor approaches zero.
 *
 * @param originLat origin latitude in decimal degrees (projects to y = 0).
 * @param originLon origin longitude in decimal degrees (projects to x = 0).
 */
class GeoProjection(val originLat: Double, val originLon: Double) {

    /** Meters per degree of longitude at the origin latitude. */
    private val metersPerDegreeLon: Double =
        cos(Math.toRadians(originLat)) * METERS_PER_DEGREE_LON_EQUATOR

    /**
     * Projects [lat]/[lon] (decimal degrees) to local meters relative to the origin.
     * Result: x = meters east of the origin, y = meters SOUTH of the origin
     * (negated latitude delta so y grows downward on a north-up canvas).
     */
    fun toLocalMeters(lat: Double, lon: Double): Vec2 = Vec2(
        x = (lon - originLon) * metersPerDegreeLon,
        y = -(lat - originLat) * METERS_PER_DEGREE_LAT,
    )

    /**
     * Inverse of [toLocalMeters]: converts local meters [p] (x east, y downward/south)
     * back to (latitude, longitude) in decimal degrees, returned in that order.
     */
    fun toLatLon(p: Vec2): Pair<Double, Double> = Pair(
        originLat - p.y / METERS_PER_DEGREE_LAT,
        originLon + p.x / metersPerDegreeLon,
    )

    companion object {
        /** Approximate meters per degree of latitude (WGS-84 mean). */
        const val METERS_PER_DEGREE_LAT: Double = 110540.0

        /** Approximate meters per degree of longitude at the equator (WGS-84). */
        const val METERS_PER_DEGREE_LON_EQUATOR: Double = 111320.0

        /**
         * Recovers the survey origin (latitude, longitude) from a stored sample that carries
         * both its raw fix ([lat], [lon]) and its projected local-meter position ([x], [y]).
         * Exact inverse of the projection arithmetic; used to re-seed the projection when a
         * GPS survey is resumed in a later session, so new samples stay in the original frame.
         */
        fun originFromSample(lat: Double, lon: Double, x: Double, y: Double): Pair<Double, Double> {
            val originLat = lat + y / METERS_PER_DEGREE_LAT
            val originLon = lon - x / (cos(Math.toRadians(originLat)) * METERS_PER_DEGREE_LON_EQUATOR)
            return originLat to originLon
        }
    }
}
