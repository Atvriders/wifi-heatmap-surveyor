package com.atvriders.wifiheatmap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.ui.theme.WifiHeatmapTheme

/** Root composable. Navigation host replaces the placeholder as screens land. */
@Composable
fun AppRoot(container: AppContainer) {
    WifiHeatmapTheme {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "WiFi Heatmap Surveyor",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
