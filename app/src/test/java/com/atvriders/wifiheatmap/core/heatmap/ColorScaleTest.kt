package com.atvriders.wifiheatmap.core.heatmap

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorScaleTest {

    private val alpha = 180
    private val alphaBits = alpha shl 24

    /** Two anchors whose channel midpoints are exact, for clean interpolation math. */
    private val scale = ColorScale(listOf(-90f to 0x102030, -50f to 0x304050), alpha = alpha)

    @Test
    fun colorFor_atAnchorDbm_returnsAnchorRgbWithClassAlpha() {
        assertEquals(alphaBits or 0x102030, scale.colorFor(-90f))
        assertEquals(alphaBits or 0x304050, scale.colorFor(-50f))
        assertEquals(
            (180 shl 24) or 0xFFD500,
            ColorScale.Default.colorFor(-67f),
        )
    }

    @Test
    fun colorFor_clampsBelowAndAboveAnchorRange() {
        assertEquals(alphaBits or 0x102030, scale.colorFor(-120f))
        assertEquals(alphaBits or 0x304050, scale.colorFor(0f))
        assertEquals(
            ColorScale.Default.colorFor(-90f),
            ColorScale.Default.colorFor(-127f),
        )
        assertEquals(
            ColorScale.Default.colorFor(-35f),
            ColorScale.Default.colorFor(-10f),
        )
    }

    @Test
    fun colorFor_midpointInterpolatesEachChannel() {
        // Midpoint of -90..-50 is -70; each channel is the average: 0x20, 0x30, 0x40.
        assertEquals(alphaBits or 0x203040, scale.colorFor(-70f))
        // Quarter point (-80): channels at 0x10 + 0.25 * 0x20 = 0x18, etc.
        assertEquals(alphaBits or 0x182838, scale.colorFor(-80f))
    }

    @Test
    fun colorFor_customAlphaIsCarried() {
        val opaque = ColorScale(listOf(-90f to 0x102030, -50f to 0x304050), alpha = 255)
        assertEquals((255 shl 24) or 0x102030, opaque.colorFor(-90f))
    }

    @Test
    fun toPixels_mapsNanToFullyTransparent() {
        val values = floatArrayOf(Float.NaN, -90f, -50f, Float.NaN)
        val out = IntArray(values.size) { -1 }
        scale.toPixels(values, out)
        assertEquals(0x00000000, out[0])
        assertEquals(alphaBits or 0x102030, out[1])
        assertEquals(alphaBits or 0x304050, out[2])
        assertEquals(0x00000000, out[3])
    }

    @Test
    fun passFailPixels_thresholdBehavior() {
        val values = floatArrayOf(-60f, -67f, -67.01f, -90f, Float.NaN)
        val out = IntArray(values.size) { -1 }
        scale.passFailPixels(values, thresholdDbm = -67f, out = out)

        val pass = alphaBits or 0x2E7D32
        val fail = alphaBits or 0xC62828
        assertEquals("above threshold", pass, out[0])
        assertEquals("exactly at threshold passes", pass, out[1])
        assertEquals("just below threshold", fail, out[2])
        assertEquals("far below threshold", fail, out[3])
        assertEquals("NaN transparent", 0x00000000, out[4])
    }

    @Test
    fun builtInScales_endpointsMatchTheirAnchors() {
        val d = ColorScale.Default
        assertEquals((180 shl 24) or 0x8E2420, d.colorFor(-90f))
        assertEquals((180 shl 24) or 0x00753A, d.colorFor(-35f))

        val c = ColorScale.ColorblindSafe
        assertEquals((180 shl 24) or 0x440154, c.colorFor(-90f))
        assertEquals((180 shl 24) or 0xFDE725, c.colorFor(-35f))
    }
}
