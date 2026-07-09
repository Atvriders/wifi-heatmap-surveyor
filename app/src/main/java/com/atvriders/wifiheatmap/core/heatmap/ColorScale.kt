package com.atvriders.wifiheatmap.core.heatmap

import kotlin.math.roundToInt

/**
 * Maps signal values (RSSI dBm) to ARGB colors by piecewise-linear interpolation
 * between anchor colors, plus batch value-array -> pixel-array conversion.
 *
 * Pure JVM: "pixels" are packed ARGB Ints (`0xAARRGGBB`), directly consumable by
 * `Bitmap.setPixels` on the Android side. Pixel arrays are row-major and index-aligned
 * with [GridSpec]/[IncrementalIdwGrid] value arrays.
 *
 * @property anchors (dbm, 0xRRGGBB) stops, strictly ascending by dBm (weakest first).
 *   Any bits above the low 24 in the color are ignored.
 * @property alpha alpha channel (0..255) applied to every emitted pixel; default 180
 *   keeps the floor plan visible under the heat layer.
 */
class ColorScale(
    val anchors: List<Pair<Float, Int>>,
    val alpha: Int = 180,
) {
    init {
        require(anchors.isNotEmpty()) { "anchors must not be empty" }
        require(alpha in 0..255) { "alpha must be in 0..255: $alpha" }
        for (i in 1 until anchors.size) {
            require(anchors[i - 1].first < anchors[i].first) {
                "anchors must be strictly ascending by dBm: ${anchors[i - 1].first} !< ${anchors[i].first}"
            }
        }
    }

    private val alphaBits = alpha shl 24

    /**
     * ARGB color for [dbm]: linear per-channel (R, G, B) interpolation between the
     * surrounding anchors, clamped to the first/last anchor color outside the anchor
     * range, always carrying the class [alpha]. NaN maps to fully transparent 0.
     */
    fun colorFor(dbm: Float): Int {
        if (dbm.isNaN()) return TRANSPARENT
        if (dbm <= anchors.first().first) return alphaBits or (anchors.first().second and RGB_MASK)
        if (dbm >= anchors.last().first) return alphaBits or (anchors.last().second and RGB_MASK)
        var hi = 1
        while (anchors[hi].first < dbm) hi++
        val (loDbm, loRgb) = anchors[hi - 1]
        val (hiDbm, hiRgb) = anchors[hi]
        val t = (dbm - loDbm) / (hiDbm - loDbm)
        val r = lerpChannel(loRgb shr 16, hiRgb shr 16, t)
        val g = lerpChannel(loRgb shr 8, hiRgb shr 8, t)
        val b = lerpChannel(loRgb, hiRgb, t)
        return alphaBits or (r shl 16) or (g shl 8) or b
    }

    /**
     * Converts a value array (dBm, NaN = unsampled) to ARGB pixels via [colorFor];
     * NaN becomes fully transparent 0x00000000. [out] must be at least as long as
     * [values]; indices map one-to-one.
     */
    fun toPixels(values: FloatArray, out: IntArray) {
        require(out.size >= values.size) { "out.size=${out.size} < values.size=${values.size}" }
        for (i in values.indices) {
            val v = values[i]
            out[i] = if (v.isNaN()) TRANSPARENT else colorFor(v)
        }
    }

    /**
     * Binary pass/fail rendering: values >= [thresholdDbm] become pass-green (0x2E7D32),
     * values below become fail-red (0xC62828), both with the class [alpha]; NaN stays
     * fully transparent. [out] must be at least as long as [values].
     */
    fun passFailPixels(values: FloatArray, thresholdDbm: Float, out: IntArray) {
        require(out.size >= values.size) { "out.size=${out.size} < values.size=${values.size}" }
        val pass = alphaBits or PASS_RGB
        val fail = alphaBits or FAIL_RGB
        for (i in values.indices) {
            val v = values[i]
            out[i] = when {
                v.isNaN() -> TRANSPARENT
                v >= thresholdDbm -> pass
                else -> fail
            }
        }
    }

    private fun lerpChannel(a: Int, b: Int, t: Float): Int {
        val a8 = a and 0xFF
        val b8 = b and 0xFF
        return (a8 + t * (b8 - a8)).roundToInt().coerceIn(0, 255)
    }

    companion object {
        private const val TRANSPARENT = 0x00000000
        private const val RGB_MASK = 0xFFFFFF

        /** Opaque RGB of the pass (>= threshold) color used by [passFailPixels]. */
        const val PASS_RGB = 0x2E7D32

        /** Opaque RGB of the fail (< threshold) color used by [passFailPixels]. */
        const val FAIL_RGB = 0xC62828

        /**
         * Industry-standard RSSI ramp, ascending dBm from weakest (-90, dark red)
         * to strongest (-35, deep green).
         */
        val Default = ColorScale(
            listOf(
                -90f to 0x8E2420,
                -85f to 0xE53935,
                -75f to 0xFF8A00,
                -67f to 0xFFD500,
                -60f to 0x7DC242,
                -50f to 0x00A651,
                -35f to 0x00753A,
            ),
        )

        /** Viridis-like ramp with monotonic lightness, safe for common color-vision deficiencies. */
        val ColorblindSafe = ColorScale(
            listOf(
                -90f to 0x440154,
                -80f to 0x3B528B,
                -70f to 0x21918C,
                -60f to 0x5EC962,
                -50f to 0xFDE725,
                -35f to 0xFDE725,
            ),
        )
    }
}
