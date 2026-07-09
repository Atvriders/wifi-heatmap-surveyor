package com.atvriders.wifiheatmap.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class Vec2Test {

    private val eps = 1e-12

    @Test
    fun plusAddsComponentWise() {
        val result = Vec2(1.5, -2.0) + Vec2(0.5, 4.0)
        assertEquals(2.0, result.x, eps)
        assertEquals(2.0, result.y, eps)
    }

    @Test
    fun minusSubtractsComponentWise() {
        val result = Vec2(1.5, -2.0) - Vec2(0.5, 4.0)
        assertEquals(1.0, result.x, eps)
        assertEquals(-6.0, result.y, eps)
    }

    @Test
    fun timesScalesUniformly() {
        val result = Vec2(3.0, -4.0) * 2.5
        assertEquals(7.5, result.x, eps)
        assertEquals(-10.0, result.y, eps)
    }

    @Test
    fun timesByZeroYieldsZeroVector() {
        val result = Vec2(3.0, -4.0) * 0.0
        assertEquals(0.0, result.x, eps)
        assertEquals(0.0, result.y, eps)
    }

    @Test
    fun lengthIsEuclideanMagnitude() {
        assertEquals(5.0, Vec2(3.0, -4.0).length, eps)
        assertEquals(0.0, Vec2.ZERO.length, eps)
    }

    @Test
    fun distanceToIsEuclideanDistance() {
        assertEquals(5.0, Vec2(1.0, 1.0).distanceTo(Vec2(4.0, 5.0)), eps)
        assertEquals(0.0, Vec2(7.0, -3.0).distanceTo(Vec2(7.0, -3.0)), eps)
    }

    @Test
    fun lerpAtZeroReturnsA() {
        val a = Vec2(2.0, -3.0)
        val b = Vec2(10.0, 5.0)
        val result = Vec2.lerp(a, b, 0.0)
        assertEquals(a.x, result.x, eps)
        assertEquals(a.y, result.y, eps)
    }

    @Test
    fun lerpAtOneReturnsB() {
        val a = Vec2(2.0, -3.0)
        val b = Vec2(10.0, 5.0)
        val result = Vec2.lerp(a, b, 1.0)
        assertEquals(b.x, result.x, eps)
        assertEquals(b.y, result.y, eps)
    }

    @Test
    fun lerpAtHalfReturnsMidpoint() {
        val result = Vec2.lerp(Vec2(2.0, -3.0), Vec2(10.0, 5.0), 0.5)
        assertEquals(6.0, result.x, eps)
        assertEquals(1.0, result.y, eps)
    }
}
