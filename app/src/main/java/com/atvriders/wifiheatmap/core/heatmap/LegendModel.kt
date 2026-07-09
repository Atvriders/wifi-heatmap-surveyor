package com.atvriders.wifiheatmap.core.heatmap

/**
 * View-agnostic legend for a heatmap color ramp spanning [minDbm]..[maxDbm] (dBm).
 *
 * The UI draws the gradient bar however it likes and places each [Tick] at
 * [Tick.fraction] along it (0 = [minDbm] end, 1 = [maxDbm] end).
 *
 * @property minDbm value at the weak end of the ramp, in dBm (must be < [maxDbm]).
 * @property maxDbm value at the strong end of the ramp, in dBm.
 * @property breakpoints candidate tick values in dBm; only those inside
 *   `[minDbm, maxDbm]` (inclusive) appear in [ticks]. Defaults to the common
 *   Wi-Fi quality thresholds.
 */
class LegendModel(
    val minDbm: Float,
    val maxDbm: Float,
    val breakpoints: List<Int> = listOf(-85, -75, -67, -60, -50),
) {
    init {
        require(maxDbm > minDbm) { "maxDbm ($maxDbm) must exceed minDbm ($minDbm)" }
    }

    /**
     * One legend tick.
     *
     * @property dbm tick value in dBm.
     * @property label display text, e.g. "-67".
     * @property fraction normalized position along the ramp, 0..1 = (dbm - min) / (max - min).
     */
    data class Tick(val dbm: Int, val label: String, val fraction: Float)

    /** In-range breakpoints as ticks, ascending by dBm (weakest first). */
    val ticks: List<Tick> = breakpoints
        .filter { it >= minDbm && it <= maxDbm }
        .sorted()
        .map { Tick(it, it.toString(), (it - minDbm) / (maxDbm - minDbm)) }
}
