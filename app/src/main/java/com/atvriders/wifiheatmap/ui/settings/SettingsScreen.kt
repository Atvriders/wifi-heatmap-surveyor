package com.atvriders.wifiheatmap.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atvriders.wifiheatmap.data.AppSettings
import com.atvriders.wifiheatmap.data.DistanceUnit
import com.atvriders.wifiheatmap.data.SignalUnit
import com.atvriders.wifiheatmap.di.AppContainer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsState(initial = AppSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedRow(
                title = "Poll interval",
                options = listOf("0.5 s", "1 s", "2 s"),
                selectedIndex = when (settings.pollIntervalMs) {
                    500L -> 0
                    2000L -> 2
                    else -> 1
                },
                onSelect = { vm.setPollIntervalMs(listOf(500L, 1000L, 2000L)[it]) },
            )

            SectionDivider()

            SegmentedRow(
                title = "Signal units",
                caption = "% is a derived approximation",
                options = listOf("dBm", "%"),
                selectedIndex = if (settings.signalUnit == SignalUnit.PERCENT) 1 else 0,
                onSelect = {
                    vm.setSignalUnit(if (it == 1) SignalUnit.PERCENT else SignalUnit.DBM)
                },
            )

            SectionDivider()

            SegmentedRow(
                title = "Distance units",
                options = listOf("Meters", "Feet"),
                selectedIndex = if (settings.distanceUnit == DistanceUnit.FEET) 1 else 0,
                onSelect = {
                    vm.setDistanceUnit(if (it == 1) DistanceUnit.FEET else DistanceUnit.METERS)
                },
            )

            SectionDivider()

            ThresholdRow(
                thresholdDbm = settings.thresholdDbm,
                onCommit = { vm.setThresholdDbm(it) },
            )

            SectionDivider()

            SliderRow(
                title = "Indoor IDW radius",
                value = settings.idwRadiusIndoorM.toFloat(),
                valueRange = 3f..15f,
                valueLabel = { "${it.roundToInt()} m" },
                onCommit = { vm.setIdwRadiusIndoorM(it.roundToInt().toDouble()) },
            )

            SectionDivider()

            SliderRow(
                title = "Outdoor IDW radius",
                value = settings.idwRadiusOutdoorM.toFloat(),
                valueRange = 8f..40f,
                valueLabel = { "${it.roundToInt()} m" },
                onCommit = { vm.setIdwRadiusOutdoorM(it.roundToInt().toDouble()) },
            )

            SectionDivider()

            SwitchRow(
                title = "Keep screen on",
                caption = "While a survey is running",
                checked = settings.keepScreenOn,
                onCheckedChange = { vm.setKeepScreenOn(it) },
            )

            SectionDivider()

            SwitchRow(
                title = "Colorblind-safe heatmap palette",
                caption = "Perceptually uniform blue-to-yellow ramp",
                checked = settings.colorblindScale,
                onCheckedChange = { vm.setColorblindScale(it) },
            )

            SectionDivider()

            SegmentedRow(
                title = "Theme",
                options = listOf("System", "Light", "Dark"),
                selectedIndex = when (settings.themeMode) {
                    "LIGHT" -> 1
                    "DARK" -> 2
                    else -> 0
                },
                onSelect = { vm.setThemeMode(listOf("SYSTEM", "LIGHT", "DARK")[it]) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    caption: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun ThresholdRow(thresholdDbm: Int, onCommit: (Int) -> Unit) {
    var value by remember(thresholdDbm) { mutableFloatStateOf(thresholdDbm.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Default threshold", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pass/fail cutoff for coverage views",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${value.roundToInt()} dBm",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value.roundToInt()) },
            valueRange = -85f..-50f,
        )
        TextButton(onClick = {
            value = -67f
            onCommit(-67)
        }) {
            Text("Reset to -67 dBm")
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel(local),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = valueRange,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
