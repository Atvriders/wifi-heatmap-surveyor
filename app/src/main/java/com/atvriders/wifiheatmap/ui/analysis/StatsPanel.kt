package com.atvriders.wifiheatmap.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.heatmap.Coverage
import com.atvriders.wifiheatmap.core.wifi.SignalFormat
import com.atvriders.wifiheatmap.core.wifi.SignalStats
import com.atvriders.wifiheatmap.data.DistanceUnit
import com.atvriders.wifiheatmap.ui.common.LegendBar
import java.util.Locale
import kotlin.math.roundToInt

private const val SQUARE_FEET_PER_SQUARE_METER = 10.7639

/**
 * Legend + headline numbers for the analysis screen: sample/AP counts, RSSI order
 * stats, coverage at the threshold, and surveyed area (hidden when unknown).
 */
@Composable
fun StatsPanel(
    scale: ColorScale,
    sampleCount: Int,
    apCount: Int,
    summary: SignalStats.Summary?,
    signalPercent: Boolean = false,
    coverage: Coverage?,
    thresholdDbm: Int,
    distanceUnit: DistanceUnit,
    uncalibrated: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Signal",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (uncalibrated) {
                    SuggestionChip(onClick = {}, label = { Text("Uncalibrated") })
                }
            }

            LegendBar(
                scale = scale,
                minDbm = -90f,
                maxDbm = -35f,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(label = "Samples", value = "$sampleCount", modifier = Modifier.weight(1f))
                StatCell(label = "APs heard", value = "$apCount", modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    label = "Min",
                    value = summary?.let { SignalFormat.format(it.min, signalPercent) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Median",
                    value = summary?.let { SignalFormat.format(it.median, signalPercent) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Max",
                    value = summary?.let { SignalFormat.format(it.max, signalPercent) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }

            if (coverage != null) {
                Text(
                    text = "Coverage >= $thresholdDbm dBm: " +
                        "${(coverage.fractionPassing * 100).roundToInt()}% of surveyed area",
                    style = MaterialTheme.typography.bodyMedium,
                )
                coverage.surveyedAreaM2?.let { areaM2 ->
                    val text = when (distanceUnit) {
                        DistanceUnit.METERS ->
                            "Surveyed area: ${String.format(Locale.US, "%.0f", areaM2)} m²"
                        DistanceUnit.FEET ->
                            "Surveyed area: " +
                                String.format(Locale.US, "%.0f", areaM2 * SQUARE_FEET_PER_SQUARE_METER) +
                                " ft²"
                    }
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}
