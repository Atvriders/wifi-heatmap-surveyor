package com.atvriders.wifiheatmap.core.geo

import kotlin.math.min

/**
 * Immutable similarity transform (uniform scale + translation, no rotation) mapping
 * plan coordinates to view coordinates:
 *
 *     view = plan * scale + offset
 *
 * Both spaces use the project convention (x right, y DOWNWARD). "Plan" units are
 * floor-plan pixels in TAP mode or meters in GPS mode; "view" units are screen pixels.
 * All mutating-style methods return a NEW instance.
 *
 * @param scale view units per plan unit (e.g. screen px per floor-plan px). Must be > 0.
 * @param offsetX view-space x of the plan origin, in view units.
 * @param offsetY view-space y of the plan origin, in view units.
 */
data class PlanTransform(
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
) {

    /** Maps a plan-space point [p] to view space: p * scale + offset. */
    fun planToView(p: Vec2): Vec2 = Vec2(p.x * scale + offsetX, p.y * scale + offsetY)

    /** Maps a view-space point [p] back to plan space: (p - offset) / scale. */
    fun viewToPlan(p: Vec2): Vec2 = Vec2((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    /**
     * Pans by [d], a delta expressed in VIEW units (e.g. a touch drag in screen px).
     * Returns a new transform whose plan content appears shifted by [d] on screen.
     */
    fun panByView(d: Vec2): PlanTransform = copy(offsetX = offsetX + d.x, offsetY = offsetY + d.y)

    /**
     * Zooms by [factor] (unitless; > 1 zooms in) about [focusView], a VIEW-space focal
     * point (e.g. the pinch centroid in screen px). The plan point currently under
     * [focusView] stays under it after the zoom. The resulting scale is clamped to
     * [[minScale], [maxScale]]; when the clamp engages the effective factor shrinks
     * accordingly, so the focal invariant still holds.
     *
     * @return a new transform; equal to this one when the scale is already pinned
     *   at the relevant clamp bound.
     */
    fun zoomBy(
        factor: Double,
        focusView: Vec2,
        minScale: Double = MIN_SCALE,
        maxScale: Double = MAX_SCALE,
    ): PlanTransform {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val effectiveFactor = newScale / scale
        // Invariant: focusView = planFocus * newScale + newOffset, where
        // planFocus = (focusView - offset) / scale. Solving for newOffset:
        return PlanTransform(
            scale = newScale,
            offsetX = focusView.x - (focusView.x - offsetX) * effectiveFactor,
            offsetY = focusView.y - (focusView.y - offsetY) * effectiveFactor,
        )
    }

    companion object {
        /** Default minimum [scale] (view units per plan unit) enforced by [zoomBy]. */
        const val MIN_SCALE: Double = 0.05

        /** Default maximum [scale] (view units per plan unit) enforced by [zoomBy]. */
        const val MAX_SCALE: Double = 40.0

        /**
         * Transform that letterboxes a plan of size [planW] x [planH] (plan units)
         * centered inside a view of size [viewW] x [viewH] (view units), leaving at
         * least [paddingFraction] of each view dimension as margin on every side
         * (default 5%). Aspect ratio is preserved (uniform scale).
         *
         * Degenerate inputs (any non-positive dimension) return the identity transform.
         * [paddingFraction] is coerced into [0.0, 0.45]. The fitted scale is NOT
         * clamped to [MIN_SCALE]/[MAX_SCALE]; subsequent [zoomBy] calls clamp.
         */
        fun fit(
            planW: Double,
            planH: Double,
            viewW: Double,
            viewH: Double,
            paddingFraction: Double = 0.05,
        ): PlanTransform {
            if (planW <= 0.0 || planH <= 0.0 || viewW <= 0.0 || viewH <= 0.0) {
                return PlanTransform()
            }
            val padding = paddingFraction.coerceIn(0.0, 0.45)
            val usableW = viewW * (1.0 - 2.0 * padding)
            val usableH = viewH * (1.0 - 2.0 * padding)
            val scale = min(usableW / planW, usableH / planH)
            return PlanTransform(
                scale = scale,
                offsetX = (viewW - planW * scale) / 2.0,
                offsetY = (viewH - planH * scale) / 2.0,
            )
        }
    }
}
