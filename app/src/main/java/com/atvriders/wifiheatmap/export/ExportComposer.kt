package com.atvriders.wifiheatmap.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.atvriders.wifiheatmap.core.engine.HeatmapFrame
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.LegendModel
import java.util.Locale

/**
 * Text metadata rendered into the footer of a PNG report.
 *
 * @property scaleBarText optional plan-scale note (e.g. "Scale: 0.020 m/px"); omitted when null.
 * @property uncalibrated draws a diagonal "UNCALIBRATED" watermark over the plan area.
 * @property appVersion from BuildConfig.VERSION_NAME at the call site.
 */
data class ReportMeta(
    val surveyName: String,
    val dateText: String,
    val filterText: String,
    val thresholdText: String,
    val coverageText: String,
    val sampleCountText: String,
    val scaleBarText: String?,
    val uncalibrated: Boolean,
    val appVersion: String,
)

/**
 * Offscreen renderer for the shareable PNG report: floor plan + heatmap raster +
 * metadata footer with a legend strip. Pure android.graphics — no Compose — so it can
 * run on any background dispatcher. The caller compresses the returned bitmap to PNG.
 */
class ExportComposer {

    /**
     * Renders the report at [TARGET_WIDTH] px wide. The content area keeps the plan's
     * aspect ratio (TAP: [planWidthPx] x [planHeightPx]; GPS: [frame].spec bounds).
     * [pixelsOverride], when non-null, replaces [frame].pixels (e.g. pass/fail recolor).
     */
    fun renderReport(
        plan: Bitmap?,
        planWidthPx: Double,
        planHeightPx: Double,
        frame: HeatmapFrame,
        pixelsOverride: IntArray?,
        scale: ColorScale,
        isGps: Boolean,
        meta: ReportMeta,
    ): Bitmap {
        // Plan-space rectangle the content area maps 1:1 onto.
        val originX: Double
        val originY: Double
        val planW: Double
        val planH: Double
        if (isGps || planWidthPx <= 0.0 || planHeightPx <= 0.0) {
            originX = frame.spec.originX
            originY = frame.spec.originY
            planW = frame.spec.planWidth
            planH = frame.spec.planHeight
        } else {
            originX = 0.0
            originY = 0.0
            planW = planWidthPx
            planH = planHeightPx
        }

        val contentW = TARGET_WIDTH
        val contentH = (contentW * planH / planW).toInt().coerceIn(1, MAX_CONTENT_HEIGHT)
        val viewScale = contentW / planW

        val out = Bitmap.createBitmap(contentW, contentH + FOOTER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Floor plan, mapped from its plan-space rect (0,0)-(planWidthPx,planHeightPx).
        if (plan != null && planWidthPx > 0.0 && planHeightPx > 0.0) {
            val dest = RectF(
                ((0.0 - originX) * viewScale).toFloat(),
                ((0.0 - originY) * viewScale).toFloat(),
                ((planWidthPx - originX) * viewScale).toFloat(),
                ((planHeightPx - originY) * viewScale).toFloat(),
            )
            canvas.drawBitmap(plan, null, dest, bitmapPaint)
        }

        // Heatmap raster positioned via the frame's own spec (auto-expand safe).
        val pixels = pixelsOverride ?: frame.pixels
        val heat = Bitmap.createBitmap(frame.gridW, frame.gridH, Bitmap.Config.ARGB_8888)
        heat.setPixels(pixels, 0, frame.gridW, 0, 0, frame.gridW, frame.gridH)
        val heatDest = RectF(
            ((frame.spec.originX - originX) * viewScale).toFloat(),
            ((frame.spec.originY - originY) * viewScale).toFloat(),
            ((frame.spec.originX + frame.spec.planWidth - originX) * viewScale).toFloat(),
            ((frame.spec.originY + frame.spec.planHeight - originY) * viewScale).toFloat(),
        )
        canvas.drawBitmap(heat, null, heatDest, bitmapPaint)
        heat.recycle()

        if (meta.uncalibrated) {
            drawWatermark(canvas, contentW, contentH)
        }

        drawFooter(canvas, contentW, contentH, scale, meta)
        return out
    }

    /** Tiled diagonal "UNCALIBRATED" text, 48 px gray at 25% alpha, clipped to the plan area. */
    private fun drawWatermark(canvas: Canvas, contentW: Int, contentH: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 120, 120, 120)
            textSize = 48f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.save()
        canvas.clipRect(0, 0, contentW, contentH)
        canvas.rotate(-30f, contentW / 2f, contentH / 2f)
        val stepX = 560f
        val stepY = 240f
        var y = -contentH.toFloat()
        var row = 0
        while (y < contentH * 2f) {
            var x = -contentW.toFloat() + (row % 2) * (stepX / 2f)
            while (x < contentW * 2f) {
                canvas.drawText("UNCALIBRATED", x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restore()
    }

    private fun drawFooter(
        canvas: Canvas,
        width: Int,
        top: Int,
        scale: ColorScale,
        meta: ReportMeta,
    ) {
        val topF = top.toFloat()
        canvas.drawRect(
            0f, topF, width.toFloat(), topF + FOOTER_HEIGHT,
            Paint().apply { color = FOOTER_BG },
        )

        // Legend strip, right-aligned in the footer.
        val legendWidth = 560f
        val legendHeight = 36f
        val legendRight = width - MARGIN
        val legendLeft = legendRight - legendWidth
        val legendTop = topF + 56f
        drawLegend(canvas, scale, legendLeft, legendTop, legendWidth, legendHeight)

        // Three metadata lines, ellipsized so they never run under the legend.
        val maxTextWidth = legendLeft - MARGIN - 32f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = 30f
        }
        drawEllipsized(
            canvas, "${meta.surveyName} — ${meta.dateText}",
            MARGIN, topF + 64f, maxTextWidth, titlePaint,
        )
        drawEllipsized(
            canvas,
            listOf(meta.filterText, meta.thresholdText, meta.coverageText)
                .filter { it.isNotBlank() }
                .joinToString("  ·  "),
            MARGIN, topF + 122f, maxTextWidth, bodyPaint,
        )
        drawEllipsized(
            canvas,
            listOfNotNull(
                meta.sampleCountText,
                meta.scaleBarText,
                "v${meta.appVersion}",
                "WiFi Heatmap Surveyor",
            ).joinToString("  ·  "),
            MARGIN, topF + 172f, maxTextWidth, bodyPaint,
        )
    }

    /** Gradient bar sampled from [scale] across -90..-35 dBm plus LegendModel tick labels. */
    private fun drawLegend(
        canvas: Canvas,
        scale: ColorScale,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val legend = LegendModel(minDbm = LEGEND_MIN_DBM, maxDbm = LEGEND_MAX_DBM)
        val cols = width.toInt().coerceAtLeast(2)
        val strip = IntArray(cols)
        for (i in 0 until cols) {
            val dbm = LEGEND_MIN_DBM + (LEGEND_MAX_DBM - LEGEND_MIN_DBM) * i / (cols - 1)
            // Legend swatches are opaque regardless of the scale's overlay alpha.
            strip[i] = Color.BLACK or (scale.colorFor(dbm) and 0xFFFFFF)
        }
        val stripBitmap = Bitmap.createBitmap(strip, cols, 1, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(
            stripBitmap, null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        stripBitmap.recycle()

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = 24f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("dBm", left + width, top - 12f, captionPaint)

        val tickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        for (tick in legend.ticks) {
            val x = left + tick.fraction * width
            canvas.drawLine(x, top + height, x, top + height + 8f, tickLinePaint)
            canvas.drawText(tick.label, x, top + height + 36f, tickTextPaint)
        }
    }

    private fun drawEllipsized(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        if (maxWidth <= 0f) return
        var t = text
        if (paint.measureText(t) > maxWidth) {
            while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) {
                t = t.dropLast(1)
            }
            t = "$t…"
        }
        canvas.drawText(t, x, baselineY, paint)
    }

    companion object {
        /** Output bitmap width in pixels. */
        const val TARGET_WIDTH = 2048

        /** Footer band height in pixels. */
        const val FOOTER_HEIGHT = 200

        /** Cap on the plan content height so extreme aspect ratios can't OOM. */
        const val MAX_CONTENT_HEIGHT = 8192

        private const val LEGEND_MIN_DBM = -90f
        private const val LEGEND_MAX_DBM = -35f
        private const val MARGIN = 48f
        private val FOOTER_BG = Color.rgb(0x20, 0x21, 0x24)
    }
}

/** Formats a Double with [digits] decimals using a stable US locale (report text only). */
internal fun formatFixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)
