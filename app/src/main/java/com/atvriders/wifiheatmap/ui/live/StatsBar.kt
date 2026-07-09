package com.atvriders.wifiheatmap.ui.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.engine.LiveStats
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.core.wifi.SignalFormat

/** Amber used for the paused indicator. */
private val PausedAmber = Color(0xFFFFB300)

/**
 * Bottom status strip: big live dBm (colored by [scale], pulsing on fresh readings),
 * SSID + band chip, sample/AP counts, elapsed time and a paused indicator.
 *
 * @param totalSampleCount prior sessions' samples + this session's (the caller sums).
 */
@Composable
fun StatsBar(
    stats: LiveStats,
    scale: ColorScale,
    totalSampleCount: Int,
    signalPercent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rssi = stats.currentRssiDbm
    val rssiColor = if (rssi != null) {
        // Full-alpha override of the scale color (raster alpha is translucent).
        Color(0xFF000000.toInt() or (scale.colorFor(rssi.toFloat()) and 0xFFFFFF))
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Pulse on every fresh reading: elapsedMs advances once per snapshot.
    val flash = remember { Animatable(1f) }
    LaunchedEffect(stats.elapsedMs) {
        if (stats.fresh && stats.currentRssiDbm != null) {
            flash.snapTo(0.35f)
            flash.animateTo(1f, animationSpec = tween(durationMillis = 550))
        }
    }

    // Data-age: a 1 s ticker recomposes the "Xs ago" staleness label. nowMs starts at 0
    // and is guarded until the first tick so we never render a bogus age.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000)
        }
    }
    val lastFresh = stats.lastFreshAtMs
    val ageSec = if (lastFresh != null && nowMs != 0L) (nowMs - lastFresh) / 1000 else null

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (rssi != null) SignalFormat.format(rssi, signalPercent) else "--",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = rssiColor,
                modifier = Modifier.alpha(flash.value),
            )
            // Staleness hint: only once the reading is >= 2 s old (fresh readings show nothing).
            if (ageSec != null && ageSec >= 2) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${ageSec}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = buildString {
                            append(stats.currentSsid?.ifEmpty { "(hidden)" } ?: "No network")
                            stats.currentBand?.let { append(" · ").append(it.label) }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = "$totalSampleCount samples · ${stats.apCount} APs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatElapsedMmSs(stats.elapsedMs),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (stats.paused) {
                    Text(
                        text = "PAUSED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PausedAmber,
                    )
                }
            }
        }
    }
}

/** Milliseconds -> "m:ss" (or "h:mm:ss" past an hour). Shared with the finish dialog. */
internal fun formatElapsedMmSs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
