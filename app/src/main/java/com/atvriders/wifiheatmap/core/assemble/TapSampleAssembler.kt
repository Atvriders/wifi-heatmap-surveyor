package com.atvriders.wifiheatmap.core.assemble

import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.SignalSnapshot

/**
 * Result of [TapSampleAssembler.undoLastTap].
 *
 * @property removedSegmentIndex Index of the finalized segment whose samples must be removed
 *   from storage (e.g. via `SampleSink.deleteSegment`), or null when the undone tap had not
 *   finalized any segment (the first tap of a survey, or a post-resume re-anchor tap).
 * @property restoredAnchor The tap position (plan units: floor-plan pixels in TAP mode;
 *   x grows east/right, y grows downward) that is the anchor again after the undo, or null
 *   when the undo removed the only remaining tap and the assembler is back to its
 *   pre-first-tap state.
 */
data class UndoResult(
    val removedSegmentIndex: Int?,
    val restoredAnchor: Vec2?,
)

/**
 * Ekahau-style stop-and-go position assembly for TAP mode.
 *
 * The surveyor taps their current location on the floor plan, walks in a straight line while
 * [SignalSnapshot]s are buffered, then taps again. Every buffered snapshot is linearly
 * interpolated between the two tap points by its timestamp, producing one finalized *segment*
 * of [PositionedSample]s.
 *
 * Units and conventions:
 *  - Positions are in plan units (floor-plan pixels); x grows east/right, y grows DOWNWARD.
 *  - All times are milliseconds on the same monotonic timebase as
 *    [SignalSnapshot.timestampMs]; callers inject tap time via the `nowMs` parameter —
 *    this class never reads a clock itself.
 *
 * Not thread-safe: confine to a single thread/dispatcher.
 */
class TapSampleAssembler(
    /**
     * Index the first finalized segment receives. Resumed surveys pass
     * (max stored segmentIndex + 1) so undo can never delete a previous
     * session's rows, which are matched by (surveyId, segmentIndex).
     */
    firstSegmentIndex: Int = 0,
) {

    /** One tap the surveyor made; [finalizedSegmentIndex] is null for taps that only anchored. */
    private data class Tap(
        val point: Vec2,
        val timeMs: Long,
        val finalizedSegmentIndex: Int?,
    )

    private var anchor: Vec2? = null
    private var anchorTimeMs: Long = 0L
    private var segmentCounter: Int = firstSegmentIndex
    private var paused: Boolean = false
    private var awaitingReanchor: Boolean = false
    private val buffer = ArrayList<SignalSnapshot>()
    private val tapHistory = ArrayDeque<Tap>()

    /** Position (plan units) of the most recent tap, or null before the first tap (or after undoing it). */
    val currentAnchor: Vec2? get() = anchor

    /** 0-based index that the NEXT finalized segment will receive. */
    val nextSegmentIndex: Int get() = segmentCounter

    /** Number of snapshots buffered and waiting for the closing tap. */
    val bufferedCount: Int get() = buffer.size

    /** True while [pause] is in effect; snapshots are discarded until [resume] plus a re-anchor tap. */
    val isPaused: Boolean get() = paused

    /**
     * Offers a snapshot for the in-progress segment.
     *
     * Buffered only when an anchor exists ([currentAnchor] != null) and the assembler is not
     * paused; discarded otherwise. Snapshots buffered after [resume] but before the required
     * re-anchor tap never reach output: the re-anchor tap clears the buffer.
     */
    fun onSnapshot(s: SignalSnapshot) {
        if (anchor == null || paused) return
        buffer.add(s)
    }

    /**
     * Registers a tap at [point] (plan units) at time [nowMs] (ms, snapshot timebase).
     *
     * First tap (no anchor) or the first tap after [resume]: sets the anchor and its time,
     * clears the buffer, finalizes nothing, and returns an empty list.
     *
     * Any later tap finalizes the segment from the current anchor `p0` (tapped at `t0`) to
     * [point] `p1` (tapped at `t1` = [nowMs]): each buffered snapshot with timestamp `t`
     * receives position `Vec2.lerp(p0, p1, (t - t0) / (t1 - t0))` with `t` clamped into the
     * range `t0..t1`; when `t1 <= t0` (zero or negative span) all samples land at `p1`. Returned
     * samples keep the snapshot's [SignalSnapshot.timestampMs] and readings, carry the
     * finalized segment's index, and have null latitude/longitude/accuracy. The anchor then
     * advances to `p1`/`t1`, [nextSegmentIndex] increments, and the buffer is cleared.
     *
     * Taps while paused are ignored (no state change, empty list returned) — call [resume] first.
     */
    fun onTap(point: Vec2, nowMs: Long): List<PositionedSample> {
        if (paused) return emptyList()

        val p0 = anchor
        if (p0 == null || awaitingReanchor) {
            anchor = point
            anchorTimeMs = nowMs
            buffer.clear()
            awaitingReanchor = false
            tapHistory.addLast(Tap(point, nowMs, finalizedSegmentIndex = null))
            return emptyList()
        }

        val t0 = anchorTimeMs
        val t1 = nowMs
        val spanMs = t1 - t0
        val segmentIndex = segmentCounter
        val samples = buffer.map { snapshot ->
            val fraction = if (spanMs <= 0L) {
                1.0
            } else {
                (snapshot.timestampMs.coerceIn(t0, t1) - t0).toDouble() / spanMs.toDouble()
            }
            val position = Vec2.lerp(p0, point, fraction)
            PositionedSample(
                timestampMs = snapshot.timestampMs,
                x = position.x,
                y = position.y,
                readings = snapshot.readings,
                segmentIndex = segmentIndex,
            )
        }

        anchor = point
        anchorTimeMs = t1
        segmentCounter++
        buffer.clear()
        tapHistory.addLast(Tap(point, t1, finalizedSegmentIndex = segmentIndex))
        return samples
    }

    /**
     * Undoes the most recent tap. The current buffer is always discarded.
     *
     * - Last tap finalized a segment: the anchor and its time revert to the previous tap,
     *   [nextSegmentIndex] decrements, and the result names the segment whose stored samples
     *   the caller must delete.
     * - Last tap only anchored and earlier taps remain (a post-resume re-anchor tap): the
     *   anchor reverts to the previous tap but the assembler re-enters the awaiting-re-anchor
     *   state (a fresh tap is still required before the next segment); result is
     *   `UndoResult(null, previousTap)`.
     * - Last tap was the only tap: the anchor clears entirely; result is `UndoResult(null, null)`.
     * - No taps at all: returns null.
     */
    fun undoLastTap(): UndoResult? {
        val removed = tapHistory.removeLastOrNull() ?: return null
        buffer.clear()

        val previous = tapHistory.lastOrNull()
        if (previous == null) {
            anchor = null
            anchorTimeMs = 0L
            awaitingReanchor = false
            return UndoResult(removedSegmentIndex = null, restoredAnchor = null)
        }

        anchor = previous.point
        anchorTimeMs = previous.timeMs
        return if (removed.finalizedSegmentIndex != null) {
            segmentCounter--
            UndoResult(removedSegmentIndex = segmentCounter, restoredAnchor = previous.point)
        } else {
            awaitingReanchor = true
            UndoResult(removedSegmentIndex = null, restoredAnchor = previous.point)
        }
    }

    /**
     * Pauses the survey: discards the current buffer and stops accepting snapshots.
     * Idempotent. The anchor is kept for display, but see [resume] for how surveying restarts.
     */
    fun pause() {
        if (paused) return
        paused = true
        buffer.clear()
    }

    /**
     * Resumes after [pause]. Snapshot acceptance restarts, but so that the pause gap cannot
     * smear samples along a stale segment, the next [onTap] does NOT finalize a segment — it
     * only re-anchors (clearing the buffer and returning an empty list). No-op if not paused;
     * if no tap has ever been made, the next tap is an ordinary first tap.
     */
    fun resume() {
        if (!paused) return
        paused = false
        buffer.clear()
        awaitingReanchor = anchor != null
    }
}
