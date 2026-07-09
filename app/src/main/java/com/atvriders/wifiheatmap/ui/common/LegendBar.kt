package com.atvriders.wifiheatmap.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.LegendModel

/**
 * Horizontal color-ramp legend: gradient bar sampled from [scale] across
 * [minDbm]..[maxDbm], with [LegendModel] breakpoint ticks labeled in dBm below.
 */
@Composable
fun LegendBar(
    scale: ColorScale,
    minDbm: Float,
    maxDbm: Float,
    modifier: Modifier = Modifier,
) {
    val legend = remember(minDbm, maxDbm) { LegendModel(minDbm, maxDbm) }
    val steps = 64
    val colors = remember(scale, minDbm, maxDbm) {
        List(steps) { i ->
            val dbm = minDbm + (i + 0.5f) / steps * (maxDbm - minDbm)
            // Force full opacity: the scale's alpha is tuned for the heat overlay.
            Color(0xFF000000.toInt() or (scale.colorFor(dbm) and 0xFFFFFF))
        }
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            val stepW = size.width / steps
            for (i in 0 until steps) {
                drawRect(
                    color = colors[i],
                    topLeft = Offset(i * stepW, 0f),
                    // Overdraw slightly to avoid hairline seams between steps.
                    size = Size(stepW + 1f, size.height),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth()) {
            for (tick in legend.ticks) {
                Text(
                    tick.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(
                        BiasAlignment(horizontalBias = tick.fraction * 2f - 1f, verticalBias = 0f),
                    ),
                )
            }
        }
    }
}
