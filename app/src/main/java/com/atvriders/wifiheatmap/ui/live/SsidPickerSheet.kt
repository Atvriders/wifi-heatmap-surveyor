package com.atvriders.wifiheatmap.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atvriders.wifiheatmap.core.engine.SsidSummary
import com.atvriders.wifiheatmap.core.model.HeatFilter

/**
 * Bottom sheet picking which network the live heatmap visualizes: "All networks"
 * (empty [HeatFilter]) or one SSID. The current selection is checkmarked.
 *
 * @param ssids every SSID seen so far, as the engine reports them (strongest first).
 * @param currentFilter the filter currently applied ([HeatFilter.ssid] drives the checkmark).
 * @param onSelect invoked with the new filter; the caller passes it to the view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SsidPickerSheet(
    ssids: List<SsidSummary>,
    currentFilter: HeatFilter,
    onSelect: (HeatFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            item(key = "header") {
                Text(
                    text = "Heatmap network",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item(key = "all") {
                PickerRow(
                    title = "All networks",
                    subtitle = "Strongest signal at each point",
                    selected = currentFilter.ssid == null,
                    onClick = {
                        onSelect(HeatFilter())
                        onDismiss()
                    },
                )
            }
            items(ssids, key = { it.ssid }) { summary ->
                val aps = if (summary.bssidCount == 1) "1 AP" else "${summary.bssidCount} APs"
                PickerRow(
                    title = summary.ssid.ifEmpty { "(hidden network)" },
                    subtitle = "$aps · max ${summary.maxRssi} dBm",
                    selected = currentFilter.ssid == summary.ssid,
                    onClick = {
                        onSelect(HeatFilter(ssid = summary.ssid))
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
