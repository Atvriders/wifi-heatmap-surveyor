package com.atvriders.wifiheatmap.ui.live

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.engine.HeatmapFrame
import com.atvriders.wifiheatmap.core.geo.PlanTransform
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.PositionedSample
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** 1 m in blank-grid plan pixels (blank-grid convention: 50 px/m). */
private const val BLANK_GRID_METER_PX = 50.0

/**
 * The pan/zoom/tap survey map: floor plan (or blank grid / GPS backdrop), live heatmap
 * raster, walked path, tap anchors and GPS position, all placed through one shared
 * [PlanTransform].
 *
 * Units: "plan" is floor-plan pixels in TAP mode and local meters in GPS mode. In GPS
 * mode pass the initial viewport EXTENT as [planWidthPx]/[planHeightPx] (e.g. 100 x 100
 * for the -50..50 m start grid); the initial fit is centered on plan origin (0, 0).
 *
 * @param onTapPlanPoint invoked with the tapped PLAN-space point; pass null to disable
 *   tap-to-position (GPS mode, paused).
 */
@Composable
fun SurveyCanvas(
    planBitmap: ImageBitmap?,
    planWidthPx: Double,
    planHeightPx: Double,
    frame: HeatmapFrame?,
    samples: List<PositionedSample>,
    tapAnchors: List<Vec2>,
    currentAnchor: Vec2?,
    gpsCurrent: Pair<Vec2, Float>?,
    isGps: Boolean,
    onTapPlanPoint: ((Vec2) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Keyless so the state identity is stable across plan changes: the pointerInput
    // closures below capture this one holder and never go stale.
    var transform by remember { mutableStateOf<PlanTransform?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTapPlanPoint)

    // A plan identity change invalidates the current transform; the fit effect below
    // (the single re-fit path) then refits once the canvas size is known.
    LaunchedEffect(planWidthPx, planHeightPx, isGps) { transform = null }

    // Initial letterbox fit once the canvas size is known (and after a plan change).
    LaunchedEffect(viewSize, planWidthPx, planHeightPx, isGps) {
        if (transform == null && viewSize.width > 0 && viewSize.height > 0) {
            val base = PlanTransform.fit(
                planWidthPx, planHeightPx,
                viewSize.width.toDouble(), viewSize.height.toDouble(),
            )
            transform = if (isGps) {
                // GPS plan spans -w/2..w/2: shift so the origin-centered extent is fitted.
                base.panByView(
                    Vec2(planWidthPx / 2.0 * base.scale, planHeightPx / 2.0 * base.scale)
                )
            } else {
                base
            }
        }
    }

    // Heat raster bitmap, rebuilt only when a new frame generation lands.
    val heatBitmap = remember(frame?.generation) {
        frame?.let { f ->
            Bitmap.createBitmap(f.gridW, f.gridH, Bitmap.Config.ARGB_8888).apply {
                setPixels(f.pixels, 0, f.gridW, 0, 0, f.gridW, f.gridH)
            }.asImageBitmap()
        }
    }

    // Pulsing ring around the current anchor; the infinite animation exists only
    // while an anchor does (no per-frame invalidation otherwise).
    val pulse: AnchorPulse? = if (currentAnchor != null) rememberAnchorPulse() else null

    // Walked path cached in PLAN space, rebuilt only when the sample list changes;
    // pan/zoom just re-places it via the transform at draw time.
    val walkedPath = remember(samples) {
        if (samples.size < 2) null
        else Path().apply {
            samples.forEachIndexed { index, sample ->
                if (index == 0) {
                    moveTo(sample.x.toFloat(), sample.y.toFloat())
                } else {
                    lineTo(sample.x.toFloat(), sample.y.toFloat())
                }
            }
        }
    }

    val dimColor = MaterialTheme.colorScheme.surfaceVariant
    val planBgColor = MaterialTheme.colorScheme.surface
    val lineColor = MaterialTheme.colorScheme.onSurface
    val pathColor = MaterialTheme.colorScheme.primary
    val anchorColor = MaterialTheme.colorScheme.primary
    val gpsColor = MaterialTheme.colorScheme.tertiary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = lineColor)

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform = transform
                        ?.zoomBy(zoom.toDouble(), Vec2(centroid.x.toDouble(), centroid.y.toDouble()))
                        ?.panByView(Vec2(pan.x.toDouble(), pan.y.toDouble()))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val t = transform ?: return@detectTapGestures
                    currentOnTap?.invoke(
                        t.viewToPlan(Vec2(offset.x.toDouble(), offset.y.toDouble()))
                    )
                }
            },
    ) {
        val t = transform ?: return@Canvas

        // (a) Dim backdrop behind everything.
        drawRect(dimColor)

        // (b) Floor plan image / blank grid.
        val planOrigin = t.planToView(Vec2.ZERO)
        val planViewW = planWidthPx * t.scale
        val planViewH = planHeightPx * t.scale
        if (planBitmap != null) {
            if (planViewW >= 1.0 && planViewH >= 1.0) {
                drawImage(
                    image = planBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(planBitmap.width, planBitmap.height),
                    dstOffset = IntOffset(planOrigin.x.roundToInt(), planOrigin.y.roundToInt()),
                    dstSize = IntSize(planViewW.roundToInt(), planViewH.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        } else if (!isGps) {
            drawBlankGrid(t, planWidthPx, planHeightPx, planBgColor, lineColor)
        }

        // (c) Heatmap raster, placed via the frame's own (possibly auto-expanded) spec.
        if (frame != null && heatBitmap != null) {
            val o = t.planToView(Vec2(frame.spec.originX, frame.spec.originY))
            val w = frame.spec.gridW * frame.spec.cellSize * t.scale
            val h = frame.spec.gridH * frame.spec.cellSize * t.scale
            if (w >= 1.0 && h >= 1.0) {
                drawImage(
                    image = heatBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.gridW, frame.gridH),
                    dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        // (d) Walked path: cached plan-space path drawn under the current transform,
        // stroke width compensated so its visual thickness stays constant.
        if (walkedPath != null) {
            val s = t.scale.toFloat()
            withTransform({
                translate(t.offsetX.toFloat(), t.offsetY.toFloat())
                scale(scaleX = s, scaleY = s, pivot = Offset.Zero)
            }) {
                drawPath(
                    walkedPath,
                    color = pathColor.copy(alpha = 0.6f),
                    style = Stroke(width = 2.dp.toPx() / s),
                )
            }
        }

        // (e) Tap anchors (plain dots; index labels omitted for draw perf).
        val anchorRadius = 6.dp.toPx()
        for (anchor in tapAnchors) {
            val v = t.planToView(anchor)
            drawCircle(anchorColor, radius = anchorRadius, center = Offset(v.x.toFloat(), v.y.toFloat()))
        }
        currentAnchor?.let { anchor ->
            val v = t.planToView(anchor)
            val center = Offset(v.x.toFloat(), v.y.toFloat())
            if (pulse != null) {
                drawCircle(
                    anchorColor.copy(alpha = pulse.alpha.value),
                    radius = anchorRadius * pulse.scale.value,
                    center = center,
                )
            }
            drawCircle(anchorColor, radius = anchorRadius, center = center)
            drawCircle(
                planBgColor,
                radius = anchorRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // (f) GPS current position + accuracy circle (plan units ARE meters in GPS mode).
        gpsCurrent?.let { (pos, accuracyM) ->
            val v = t.planToView(pos)
            val center = Offset(v.x.toFloat(), v.y.toFloat())
            val accuracyRadius = (accuracyM * t.scale).toFloat()
            if (accuracyRadius > 0f) {
                drawCircle(gpsColor.copy(alpha = 0.12f), radius = accuracyRadius, center = center)
                drawCircle(
                    gpsColor.copy(alpha = 0.4f),
                    radius = accuracyRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(gpsColor, radius = 5.dp.toPx(), center = center)
            drawCircle(
                Color.White,
                radius = 5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // GPS HUD: scale bar bottom-left + north arrow top-right (drawn on top).
        if (isGps) {
            drawGpsScaleBar(t, lineColor, textMeasurer, labelStyle)
            drawNorthArrow(lineColor, textMeasurer, labelStyle)
        }
    }
}

/** Animated pulse values for the current-anchor ring; read inside the draw phase. */
private class AnchorPulse(val scale: State<Float>, val alpha: State<Float>)

/**
 * The pulsing-ring infinite animation. Call ONLY while a current anchor exists so the
 * animation (and its per-frame invalidations) is torn down when no anchor is shown.
 */
@Composable
private fun rememberAnchorPulse(): AnchorPulse {
    val pulse = rememberInfiniteTransition(label = "anchor-pulse")
    val scale = pulse.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "anchor-pulse-scale",
    )
    val alpha = pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "anchor-pulse-alpha",
    )
    return AnchorPulse(scale, alpha)
}

/** Blank grid: 1 m lines (50 plan px) at 10% alpha, every 5 m heavier. */
private fun DrawScope.drawBlankGrid(
    t: PlanTransform,
    planWidthPx: Double,
    planHeightPx: Double,
    planBgColor: Color,
    lineColor: Color,
) {
    val origin = t.planToView(Vec2.ZERO)
    drawRect(
        planBgColor,
        topLeft = Offset(origin.x.toFloat(), origin.y.toFloat()),
        size = Size((planWidthPx * t.scale).toFloat(), (planHeightPx * t.scale).toFloat()),
    )
    val thin = 1.dp.toPx()
    val heavy = 2.dp.toPx()

    var i = 0
    var x = 0.0
    while (x <= planWidthPx + 1e-6) {
        val isHeavy = i % 5 == 0
        val a = t.planToView(Vec2(x, 0.0))
        val b = t.planToView(Vec2(x, planHeightPx))
        drawLine(
            lineColor.copy(alpha = if (isHeavy) 0.25f else 0.10f),
            Offset(a.x.toFloat(), a.y.toFloat()),
            Offset(b.x.toFloat(), b.y.toFloat()),
            strokeWidth = if (isHeavy) heavy else thin,
        )
        x += BLANK_GRID_METER_PX
        i++
    }
    i = 0
    var y = 0.0
    while (y <= planHeightPx + 1e-6) {
        val isHeavy = i % 5 == 0
        val a = t.planToView(Vec2(0.0, y))
        val b = t.planToView(Vec2(planWidthPx, y))
        drawLine(
            lineColor.copy(alpha = if (isHeavy) 0.25f else 0.10f),
            Offset(a.x.toFloat(), a.y.toFloat()),
            Offset(b.x.toFloat(), b.y.toFloat()),
            strokeWidth = if (isHeavy) heavy else thin,
        )
        y += BLANK_GRID_METER_PX
        i++
    }
}

/** Scale bar spanning a "nice" round distance of roughly 1/5 of the view width. */
private fun DrawScope.drawGpsScaleBar(
    t: PlanTransform,
    lineColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val targetMeters = size.width / 5.0 / t.scale
    val niceMeters = niceRoundMeters(targetMeters)
    val barPx = (niceMeters * t.scale).toFloat()
    if (barPx < 8f || !barPx.isFinite()) return
    val margin = 16.dp.toPx()
    val y = size.height - margin
    val tick = 5.dp.toPx()
    val stroke = 2.dp.toPx()
    drawLine(lineColor, Offset(margin, y), Offset(margin + barPx, y), strokeWidth = stroke)
    drawLine(lineColor, Offset(margin, y - tick), Offset(margin, y + 1f), strokeWidth = stroke)
    drawLine(
        lineColor,
        Offset(margin + barPx, y - tick),
        Offset(margin + barPx, y + 1f),
        strokeWidth = stroke,
    )
    drawText(
        textMeasurer = textMeasurer,
        text = formatMeters(niceMeters),
        topLeft = Offset(margin, y - tick - 16.dp.toPx()),
        style = labelStyle,
    )
}

/** Small north indicator: arrowhead + shaft + "N", top-right corner. */
private fun DrawScope.drawNorthArrow(
    lineColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val x = size.width - 28.dp.toPx()
    val top = 16.dp.toPx()
    val headH = 10.dp.toPx()
    val halfW = 5.dp.toPx()
    val shaftBottom = top + 26.dp.toPx()
    drawLine(lineColor, Offset(x, top + headH), Offset(x, shaftBottom), strokeWidth = 2.dp.toPx())
    val head = Path().apply {
        moveTo(x, top)
        lineTo(x - halfW, top + headH)
        lineTo(x + halfW, top + headH)
        close()
    }
    drawPath(head, lineColor)
    drawText(
        textMeasurer = textMeasurer,
        text = "N",
        topLeft = Offset(x + 8.dp.toPx(), top + 2.dp.toPx()),
        style = labelStyle,
    )
}

/** Rounds to the 1-2-5 series (…, 1, 2, 5, 10, 20, 50, …). */
private fun niceRoundMeters(v: Double): Double {
    if (v <= 0.0 || !v.isFinite()) return 1.0
    val exp = floor(log10(v))
    val base = 10.0.pow(exp)
    val mantissa = v / base
    val nice = when {
        mantissa < 1.5 -> 1.0
        mantissa < 3.5 -> 2.0
        mantissa < 7.5 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun formatMeters(m: Double): String = when {
    m >= 1000.0 -> "%.0f km".format(m / 1000.0)
    m >= 1.0 -> "%.0f m".format(m)
    else -> "%.1f m".format(m)
}
