package com.atvriders.wifiheatmap.core.assemble

import com.atvriders.wifiheatmap.core.geo.GeoProjection
import com.atvriders.wifiheatmap.core.model.PositionFix
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.SignalSnapshot

/**
 * Position assembly for GPS mode: binds each [SignalSnapshot] to the most recent accepted
 * [PositionFix], projected to local meters around the survey origin.
 *
 * Units and conventions:
 *  - Output x/y are local meters ("plan units" in GPS mode) via [GeoProjection]:
 *    x grows east/right, y grows DOWNWARD (screen convention, so north is negative y).
 *  - The origin is the FIRST accepted fix; all later fixes project relative to it.
 *  - [PositionFix.timestampMs] and [SignalSnapshot.timestampMs] must share a timebase
 *    (milliseconds); staleness is judged by their difference — no clock is read here.
 *
 * Not thread-safe: confine to a single thread/dispatcher.
 *
 * @property accuracyGateM Fixes with reported horizontal accuracy (meters, 68% radius) worse
 *   than this are rejected outright and never become the origin or the current fix.
 * @property fixStaleMs A snapshot is dropped when the newest accepted fix is more than this
 *   many milliseconds older than the snapshot (an age of exactly [fixStaleMs] still passes).
 */
class GpsSampleAssembler(
    val accuracyGateM: Float = 15f,
    val fixStaleMs: Long = 5_000,
    /**
     * Pre-seeded origin (latitude, longitude) for resumed surveys: when set, all fixes
     * project into this frame instead of anchoring a new origin at the first accepted fix,
     * keeping this session's samples aligned with previously stored ones.
     */
    presetOriginLatLon: Pair<Double, Double>? = null,
) {
    private var projection: GeoProjection? =
        presetOriginLatLon?.let { GeoProjection(it.first, it.second) }
    private var lastAcceptedFix: PositionFix? = null
    private var waiting: Boolean = true

    /**
     * True until the first accepted fix arrives, and again whenever the most recent snapshot
     * attempt found the newest accepted fix stale (older than [fixStaleMs] relative to that
     * snapshot). Cleared by the next accepted fix or the next successfully positioned snapshot.
     */
    val waitingForFix: Boolean get() = waiting

    /**
     * (latitude, longitude) in decimal degrees of the survey origin — the first accepted
     * fix — or null before any fix has been accepted.
     */
    val originLatLon: Pair<Double, Double>? get() = projection?.let { it.originLat to it.originLon }

    /**
     * Offers a raw GPS fix. Accepted only when `fix.accuracyM <= accuracyGateM`; rejected
     * fixes change nothing. The first accepted fix sets the [GeoProjection] origin for the
     * whole survey; every accepted fix becomes the current position for subsequent snapshots.
     */
    fun onFix(fix: PositionFix) {
        if (fix.accuracyM > accuracyGateM) return
        if (projection == null) projection = GeoProjection(fix.latitude, fix.longitude)
        lastAcceptedFix = fix
        waiting = false
    }

    /**
     * Binds a snapshot to the newest accepted fix.
     *
     * Returns null (and sets [waitingForFix]) when no fix has been accepted yet or the newest
     * accepted fix is older than [fixStaleMs] relative to [s]'s timestamp. Otherwise returns a
     * [PositionedSample] whose x/y are the fix projected to local meters, `segmentIndex = -1`
     * (GPS mode has no tap segments), and latitude/longitude/accuracyM copied from the fix;
     * timestamp and readings come from the snapshot.
     */
    fun onSnapshot(s: SignalSnapshot): PositionedSample? {
        val fix = lastAcceptedFix
        val proj = projection
        if (fix == null || proj == null) {
            waiting = true
            return null
        }
        if (s.timestampMs - fix.timestampMs > fixStaleMs) {
            waiting = true
            return null
        }
        waiting = false
        val local = proj.toLocalMeters(fix.latitude, fix.longitude)
        return PositionedSample(
            timestampMs = s.timestampMs,
            x = local.x,
            y = local.y,
            readings = s.readings,
            segmentIndex = -1,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = fix.accuracyM,
        )
    }
}
