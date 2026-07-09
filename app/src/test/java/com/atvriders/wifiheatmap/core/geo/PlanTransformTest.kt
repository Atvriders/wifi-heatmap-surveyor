package com.atvriders.wifiheatmap.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanTransformTest {

    private val eps = 1e-9

    private fun assertVecEquals(expected: Vec2, actual: Vec2, delta: Double = eps) {
        assertEquals("x", expected.x, actual.x, delta)
        assertEquals("y", expected.y, actual.y, delta)
    }

    @Test
    fun identityMapsPlanToSameViewCoords() {
        val t = PlanTransform()
        assertVecEquals(Vec2(12.5, -7.0), t.planToView(Vec2(12.5, -7.0)))
        assertVecEquals(Vec2(12.5, -7.0), t.viewToPlan(Vec2(12.5, -7.0)))
    }

    @Test
    fun planToViewAppliesScaleThenOffset() {
        val t = PlanTransform(scale = 2.0, offsetX = 10.0, offsetY = -5.0)
        assertVecEquals(Vec2(3.0 * 2.0 + 10.0, 4.0 * 2.0 - 5.0), t.planToView(Vec2(3.0, 4.0)))
    }

    @Test
    fun roundtripUnderArbitraryPanAndZoom() {
        val t = PlanTransform()
            .panByView(Vec2(37.5, -12.25))
            .zoomBy(2.7, Vec2(140.0, 260.0))
            .panByView(Vec2(-80.0, 55.5))
            .zoomBy(0.6, Vec2(15.0, 490.0))
        val points = listOf(
            Vec2(0.0, 0.0),
            Vec2(123.4, 567.8),
            Vec2(-42.0, 17.5),
            Vec2(9999.0, -321.0),
        )
        for (p in points) {
            assertVecEquals(p, t.viewToPlan(t.planToView(p)), 1e-8)
            assertVecEquals(p, t.planToView(t.viewToPlan(p)), 1e-8)
        }
    }

    @Test
    fun panByViewShiftsRenderedContentByThatViewDelta() {
        val t = PlanTransform(scale = 3.0, offsetX = 5.0, offsetY = 6.0)
        val p = Vec2(10.0, 20.0)
        val before = t.planToView(p)
        val after = t.panByView(Vec2(17.0, -4.0)).planToView(p)
        assertVecEquals(before + Vec2(17.0, -4.0), after)
    }

    @Test
    fun zoomByMultipliesScale() {
        val t = PlanTransform(scale = 1.5).zoomBy(2.0, Vec2(0.0, 0.0))
        assertEquals(3.0, t.scale, eps)
    }

    @Test
    fun zoomByKeepsFocalPlanPointFixedInViewSpace() {
        val t = PlanTransform(scale = 1.5, offsetX = 30.0, offsetY = -40.0)
        val focusView = Vec2(200.0, 120.0)
        val planUnderFocus = t.viewToPlan(focusView)
        val zoomedIn = t.zoomBy(2.0, focusView)
        val zoomedOut = t.zoomBy(0.25, focusView)
        assertVecEquals(focusView, zoomedIn.planToView(planUnderFocus))
        assertVecEquals(focusView, zoomedOut.planToView(planUnderFocus))
    }

    @Test
    fun zoomByClampsToDefaultMaxScale() {
        val t = PlanTransform(scale = 1.0).zoomBy(1000.0, Vec2(50.0, 50.0))
        assertEquals(PlanTransform.MAX_SCALE, t.scale, eps)
    }

    @Test
    fun zoomByClampsToDefaultMinScale() {
        val t = PlanTransform(scale = 1.0).zoomBy(1e-6, Vec2(50.0, 50.0))
        assertEquals(PlanTransform.MIN_SCALE, t.scale, eps)
    }

    @Test
    fun zoomByHonorsCustomClampBounds() {
        val base = PlanTransform(scale = 1.0)
        assertEquals(4.0, base.zoomBy(10.0, Vec2.ZERO, minScale = 0.5, maxScale = 4.0).scale, eps)
        assertEquals(0.5, base.zoomBy(0.01, Vec2.ZERO, minScale = 0.5, maxScale = 4.0).scale, eps)
    }

    @Test
    fun zoomByKeepsFocalInvariantEvenWhenClamped() {
        val t = PlanTransform(scale = 30.0, offsetX = -12.0, offsetY = 7.0)
        val focusView = Vec2(300.0, 450.0)
        val planUnderFocus = t.viewToPlan(focusView)
        val clamped = t.zoomBy(100.0, focusView) // hits MAX_SCALE = 40.0
        assertEquals(PlanTransform.MAX_SCALE, clamped.scale, eps)
        assertVecEquals(focusView, clamped.planToView(planUnderFocus), 1e-8)
    }

    @Test
    fun zoomByAtClampBoundIsANoOp() {
        val t = PlanTransform(scale = PlanTransform.MAX_SCALE, offsetX = 3.0, offsetY = 4.0)
        val zoomed = t.zoomBy(5.0, Vec2(100.0, 200.0))
        assertEquals(t.scale, zoomed.scale, eps)
        assertEquals(t.offsetX, zoomed.offsetX, eps)
        assertEquals(t.offsetY, zoomed.offsetY, eps)
    }

    @Test
    fun fitCentersWideePlanAndRespectsPadding() {
        // Plan 200x100 into view 1000x1000, padding 5% each side => usable 900x900,
        // scale = min(900/200, 900/100) = 4.5, scaled plan = 900x450.
        val t = PlanTransform.fit(200.0, 100.0, 1000.0, 1000.0)
        assertEquals(4.5, t.scale, eps)
        assertVecEquals(Vec2(50.0, 275.0), t.planToView(Vec2(0.0, 0.0)))
        assertVecEquals(Vec2(950.0, 725.0), t.planToView(Vec2(200.0, 100.0)))
        // Plan center maps to view center.
        assertVecEquals(Vec2(500.0, 500.0), t.planToView(Vec2(100.0, 50.0)))
    }

    @Test
    fun fitUsesLimitingDimensionWhenHeightConstrains() {
        // Plan 100x200 into view 1000x500 with no padding => scale = min(10, 2.5) = 2.5.
        val t = PlanTransform.fit(100.0, 200.0, 1000.0, 500.0, paddingFraction = 0.0)
        assertEquals(2.5, t.scale, eps)
        assertVecEquals(Vec2(500.0, 250.0), t.planToView(Vec2(50.0, 100.0)))
        assertVecEquals(Vec2(375.0, 0.0), t.planToView(Vec2(0.0, 0.0)))
        assertVecEquals(Vec2(625.0, 500.0), t.planToView(Vec2(100.0, 200.0)))
    }

    @Test
    fun fitWithDegenerateDimensionsReturnsIdentity() {
        val t = PlanTransform.fit(0.0, 100.0, 1000.0, 1000.0)
        assertEquals(1.0, t.scale, eps)
        assertEquals(0.0, t.offsetX, eps)
        assertEquals(0.0, t.offsetY, eps)
    }
}
