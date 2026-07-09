package com.atvriders.wifiheatmap.core.engine

import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.GridSpec
import com.atvriders.wifiheatmap.core.heatmap.IncrementalIdwGrid
import com.atvriders.wifiheatmap.core.model.HeatFilter
import com.atvriders.wifiheatmap.core.model.PositionedSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * One immutable rendered heatmap raster.
 *
 * Arrays are row-major (`index = iy * gridW + ix`) and freshly allocated per frame — never
 * mutated after emission, so consumers may hold them indefinitely. [values] are interpolated
 * RSSI in dBm ([Float.NaN] = unsampled cell); [pixels] are packed `0xAARRGGBB` Ints (NaN cells
 * are fully transparent 0), directly consumable by `Bitmap.setPixels` on the Android side.
 *
 * Equality and hash are on [generation] ONLY (strictly increasing per emitted frame), so a
 * `StateFlow<HeatmapFrame?>` never conflates distinct frames and array contents are never
 * compared.
 *
 * @property gridW cells along x (== [spec].gridW, kept for convenience).
 * @property gridH cells along y (== [spec].gridH).
 * @property generation 1-based, strictly increasing frame counter.
 * @property spec geometry the raster is laid out on, in plan units (floor-plan pixels in TAP
 *   mode, meters in GPS mode; x east/right, y DOWNWARD). Needed to place the raster over the
 *   plan — it can differ from the configured spec after an auto-expand.
 */
data class HeatmapFrame(
    val values: FloatArray,
    val pixels: IntArray,
    val gridW: Int,
    val gridH: Int,
    val generation: Long,
    val spec: GridSpec,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is HeatmapFrame && other.generation == generation)

    override fun hashCode(): Int = generation.hashCode()
}

/**
 * Owns the [IncrementalIdwGrid] for the live heatmap and turns sample mutations into
 * throttled, immutable [HeatmapFrame]s.
 *
 * All mutators ([configure], [setAll], [submit], [removeSegment], [setFilter]) are cheap and
 * thread-safe: they enqueue commands to a single worker coroutine on [computeDispatcher],
 * which owns all grid state. The worker coalesces every command queued since its last frame
 * into ONE recompute and emits at most one frame per [minIntervalMs] (rate-limited with
 * `delay`, so kotlinx-coroutines-test virtual time controls it).
 *
 * Per sample the contributed value is `filter.bestRssi(sample.readings)` in dBm; samples with
 * no matching reading contribute nothing. Coordinates are plan units (floor-plan pixels in
 * TAP mode, meters in GPS mode; x east/right, y DOWNWARD).
 *
 * AUTO-EXPAND: when a submitted contributing sample lies outside the current [GridSpec], the
 * worker recomputes a spec covering the union of the current spec's bounds and all
 * contributing samples plus a 10% per-axis margin (reusing the configured spec's longest-axis
 * cell count as `targetLongestCells`), then rebuilds the grid — expected rare (GPS mode).
 *
 * @param scope scope the worker runs in; cancelling it (or calling [stop]) ends the worker.
 * @param minIntervalMs minimum milliseconds between two emitted frames (>= 0).
 * @param computeDispatcher dispatcher the grid math runs on; inject a test dispatcher in tests.
 */
class HeatmapController(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 500,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0: $minIntervalMs" }
    }

    private sealed interface Command {
        data class Configure(
            val spec: GridSpec,
            val scale: ColorScale,
            val cutoffRadius: Double,
            val filter: HeatFilter,
        ) : Command

        data class SetAll(val all: List<PositionedSample>) : Command
        data class Submit(val new: List<PositionedSample>) : Command
        data class RemoveSegment(val segmentIndex: Int, val all: List<PositionedSample>) : Command
        data class SetFilter(val filter: HeatFilter, val all: List<PositionedSample>) : Command
    }

    /** Exactly what was fed to [IncrementalIdwGrid.addPoint], so removal replays it exactly. */
    private data class Contribution(
        val x: Double,
        val y: Double,
        val value: Double,
        val segmentIndex: Int,
    )

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _frames = MutableStateFlow<HeatmapFrame?>(null)

    /** Latest rendered frame, null until the first [configure]-triggered recompute lands. */
    val frames: StateFlow<HeatmapFrame?> = _frames.asStateFlow()

    // ---- Worker-confined state: touched ONLY by the worker coroutine. ----
    private var spec: GridSpec? = null
    private var scale: ColorScale? = null
    private var cutoffRadius = 0.0
    private var filter = HeatFilter()
    private var grid: IncrementalIdwGrid? = null
    private var targetLongestCells = 0
    private val contributions = ArrayList<Contribution>()
    private var generation = 0L

    private val worker: Job = scope.launch(computeDispatcher) {
        while (true) {
            val first = commands.receiveCatching().getOrNull() ?: break
            var mutated = apply(first)
            while (true) {
                val next = commands.tryReceive().getOrNull() ?: break
                mutated = apply(next) || mutated
            }
            if (mutated) {
                emitFrame()
                delay(minIntervalMs)
            }
        }
    }

    /**
     * (Re)creates the grid with the given geometry, color mapping, IDW [cutoffRadius] (plan
     * units, > 0) and [filter]. Re-adds NOTHING — the caller follows with [setAll]. Both
     * commands enqueued back-to-back are coalesced into a single frame.
     */
    fun configure(spec: GridSpec, scale: ColorScale, cutoffRadius: Double, filter: HeatFilter) {
        commands.trySend(Command.Configure(spec, scale, cutoffRadius, filter))
    }

    /** Full rebuild from scratch: [all] replaces every prior contribution under the current filter. */
    fun setAll(all: List<PositionedSample>) {
        commands.trySend(Command.SetAll(all))
    }

    /** Incremental add of newly finalized samples (queued; coalesced by the worker). */
    fun submit(new: List<PositionedSample>) {
        commands.trySend(Command.Submit(new))
    }

    /**
     * Removes segment [segmentIndex]'s contributions via [IncrementalIdwGrid.removePoint] —
     * an exact incremental undo replayed from the controller's own record of what it added,
     * so the values match the adds bit-for-bit even if callers recompute filters meanwhile.
     * [all] is the authoritative post-undo sample list; the current implementation services
     * the removal purely from its internal record and accepts [all] for future rebuild paths.
     */
    fun removeSegment(segmentIndex: Int, all: List<PositionedSample>) {
        commands.trySend(Command.RemoveSegment(segmentIndex, all))
    }

    /** Swaps the heat filter: resets the grid and fully re-adds [all] under [filter]. */
    fun setFilter(filter: HeatFilter, all: List<PositionedSample>) {
        commands.trySend(Command.SetFilter(filter, all))
    }

    /** Cancels the worker; all later mutator calls are silently ignored. Idempotent. */
    fun stop() {
        commands.close()
        worker.cancel()
    }

    /** Applies one command to the worker-confined state; true when a new frame is warranted. */
    private fun apply(cmd: Command): Boolean = when (cmd) {
        is Command.Configure -> {
            spec = cmd.spec
            scale = cmd.scale
            cutoffRadius = cmd.cutoffRadius
            filter = cmd.filter
            targetLongestCells = max(cmd.spec.gridW, cmd.spec.gridH)
            contributions.clear()
            grid = IncrementalIdwGrid(cmd.spec, cutoffRadius = cmd.cutoffRadius)
            true
        }

        is Command.SetAll -> {
            if (grid == null) {
                false
            } else {
                rebuildFrom(cmd.all)
                true
            }
        }

        is Command.Submit -> submitInternal(cmd.new)

        is Command.RemoveSegment -> removeInternal(cmd.segmentIndex)

        is Command.SetFilter -> {
            filter = cmd.filter
            if (grid == null) {
                false
            } else {
                rebuildFrom(cmd.all)
                true
            }
        }
    }

    private fun submitInternal(new: List<PositionedSample>): Boolean {
        val g = grid ?: return false
        val s = spec ?: return false
        val added = new.mapNotNull { toContribution(it) }
        if (added.isEmpty()) return false
        contributions.addAll(added)
        if (added.any { !s.contains(it.x, it.y) }) {
            rebuildGrid()
        } else {
            for (c in added) g.addPoint(c.x, c.y, c.value)
        }
        return true
    }

    private fun removeInternal(segmentIndex: Int): Boolean {
        val g = grid ?: return false
        var removedAny = false
        val iterator = contributions.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            if (c.segmentIndex == segmentIndex) {
                g.removePoint(c.x, c.y, c.value)
                iterator.remove()
                removedAny = true
            }
        }
        return removedAny
    }

    private fun rebuildFrom(all: List<PositionedSample>) {
        contributions.clear()
        for (sample in all) toContribution(sample)?.let { contributions.add(it) }
        rebuildGrid()
    }

    /** Fresh grid over the current (possibly auto-expanded) spec, re-adding every contribution. */
    private fun rebuildGrid() {
        val current = spec ?: return
        val next =
            if (contributions.all { current.contains(it.x, it.y) }) current
            else expandedSpec(current)
        spec = next
        val g = IncrementalIdwGrid(next, cutoffRadius = cutoffRadius)
        for (c in contributions) g.addPoint(c.x, c.y, c.value)
        grid = g
    }

    /**
     * Union of [current]'s bounds and all contributions' bounds plus a 10% per-axis margin
     * around the samples, gridded with the configured longest-axis cell count.
     */
    private fun expandedSpec(current: GridSpec): GridSpec {
        if (contributions.isEmpty()) return current
        var sMinX = Double.POSITIVE_INFINITY
        var sMaxX = Double.NEGATIVE_INFINITY
        var sMinY = Double.POSITIVE_INFINITY
        var sMaxY = Double.NEGATIVE_INFINITY
        for (c in contributions) {
            sMinX = min(sMinX, c.x)
            sMaxX = max(sMaxX, c.x)
            sMinY = min(sMinY, c.y)
            sMaxY = max(sMaxY, c.y)
        }
        val marginX = MARGIN_FRACTION * (sMaxX - sMinX)
        val marginY = MARGIN_FRACTION * (sMaxY - sMinY)
        return GridSpec.forBounds(
            minX = min(current.originX, sMinX - marginX),
            minY = min(current.originY, sMinY - marginY),
            maxX = max(current.originX + current.planWidth, sMaxX + marginX),
            maxY = max(current.originY + current.planHeight, sMaxY + marginY),
            targetLongestCells = targetLongestCells,
        )
    }

    private fun toContribution(sample: PositionedSample): Contribution? =
        filter.bestRssi(sample.readings)?.let { best ->
            Contribution(sample.x, sample.y, best.toDouble(), sample.segmentIndex)
        }

    private fun emitFrame() {
        val g = grid ?: return
        val sc = scale ?: return
        val s = spec ?: return
        val values = g.snapshot()
        val pixels = IntArray(values.size)
        sc.toPixels(values, pixels)
        generation += 1
        _frames.value = HeatmapFrame(values, pixels, s.gridW, s.gridH, generation, s)
    }

    private companion object {
        /** Auto-expand margin, as a fraction of the samples' span on each axis. */
        const val MARGIN_FRACTION = 0.10
    }
}
