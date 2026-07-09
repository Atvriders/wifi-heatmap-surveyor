package com.atvriders.wifiheatmap.ui.live

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.wifi.ThrottleState

private val AmberContainer = Color(0xFFFFE082)
private val AmberContent = Color(0xFF4E342E)

/**
 * Amber warning strip shown while the OS throttles Wi-Fi scans (or an OEM serves
 * cached results), with a "Fix" bottom sheet walking the user to Developer Options.
 * Renders nothing for [ThrottleState.Unknown]/[ThrottleState.Unthrottled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrottleBanner(throttle: ThrottleState, modifier: Modifier = Modifier) {
    val message = when (throttle) {
        is ThrottleState.Throttled -> "Android is throttling Wi-Fi scans (~1 per 30 s)"
        ThrottleState.OemCached -> "This device serves cached scan results"
        else -> return
    }
    var showSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(color = AmberContainer, contentColor = AmberContent, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSheet = true }) {
                Text("Fix", color = AmberContent, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Turn off Wi-Fi scan throttling",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Android limits apps to 4 Wi-Fi scans per 2 minutes. For a smooth " +
                        "survey, turn the limit off:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "1. Open Developer Options\n" +
                        "2. Scroll to the Networking section\n" +
                        "3. Turn OFF \"Wi-Fi scan throttling\"",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "If Developer Options is hidden: Settings -> About phone -> tap " +
                        "Build number 7 times.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        } catch (e: Exception) {
                            // Developer Options hidden or no handler: land on Settings.
                            try {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            } catch (_: Exception) {
                                // No settings activity at all; nothing further to do.
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Developer Options")
                }
            }
        }
    }
}
