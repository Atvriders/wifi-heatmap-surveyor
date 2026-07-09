package com.atvriders.wifiheatmap.ui.analysis

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.Band
import com.atvriders.wifiheatmap.data.db.BssidRow
import com.atvriders.wifiheatmap.data.db.SsidSummaryRow
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.ui.live.SurveyCanvas
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Post-survey analysis: read-only heatmap canvas, SSID/band/BSSID filtering,
 * heatmap vs pass/fail display, stats, and CSV/PNG export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    container: AppContainer,
    surveyId: Long,
    onResumeSurvey: (surveyId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: AnalysisViewModel = viewModel { AnalysisViewModel(container, surveyId) }

    val loading by vm.loading.collectAsState()
    val survey by vm.survey.collectAsState()
    val floorPlan by vm.floorPlan.collectAsState()
    val planBitmap by vm.planBitmap.collectAsState()
    val samples by vm.samples.collectAsState()
    val filter by vm.filter.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val threshold by vm.thresholdDbm.collectAsState()
    val opacity by vm.heatmapOpacity.collectAsState()
    val showDots by vm.showDots.collectAsState()
    val showPath by vm.showPath.collectAsState()
    val display by vm.display.collectAsState()
    val summaries by vm.ssidSummaries.collectAsState()
    val signalSummary by vm.signalSummary.collectAsState()
    val distanceUnit by vm.distanceUnit.collectAsState()
    val colorScale by vm.colorScale.collectAsState()
    val exportEvent by vm.exportEvent.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSsidSheet by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) vm.exportCsv(uri) }
    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri -> if (uri != null) vm.exportPng(uri) }

    LaunchedEffect(exportEvent?.id) {
        val event = exportEvent ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(if (event.success) "Exported" else "Export failed")
    }

    val shareLastExport: () -> Unit = {
        exportEvent?.let { event ->
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = event.mimeType
                    putExtra(Intent.EXTRA_STREAM, event.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share export"))
            } catch (_: Exception) {
                // No activity able to handle the share; ignore.
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = survey?.name ?: "Analysis",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (survey?.status == "ACTIVE") {
                        TextButton(onClick = { onResumeSurvey(surveyId) }) {
                            Text("Resume survey")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val isGps = survey?.positioningMode == "GPS"
        val displayFrame = display.frame
        val planImage = remember(planBitmap) { planBitmap?.asImageBitmap() }

        // Plan extent for the canvas: TAP uses the floor plan's pixel size; GPS uses a
        // symmetric-around-origin extent covering the analysis grid (the canvas centers
        // its initial GPS fit on the origin).
        val planExtent: Pair<Double, Double> = if (isGps) {
            val spec = displayFrame?.spec
            if (spec != null) {
                val halfW = max(abs(spec.originX), abs(spec.originX + spec.planWidth))
                val halfH = max(abs(spec.originY), abs(spec.originY + spec.planHeight))
                (halfW * 2.0).coerceAtLeast(1.0) to (halfH * 2.0).coerceAtLeast(1.0)
            } else {
                100.0 to 100.0
            }
        } else {
            (floorPlan?.widthPx ?: 1000).toDouble() to (floorPlan?.heightPx ?: 1000).toDouble()
        }
        val sampleDots = remember(samples, showDots) {
            if (showDots) samples.map { Vec2(it.x, it.y) } else emptyList()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // (a) Read-only survey canvas showing the effective (recolored) frame.
            SurveyCanvas(
                planBitmap = planImage,
                planWidthPx = planExtent.first,
                planHeightPx = planExtent.second,
                frame = displayFrame,
                samples = if (showPath) samples else emptyList(),
                tapAnchors = sampleDots,
                currentAnchor = null,
                gpsCurrent = null,
                isGps = isGps,
                onTapPlanPoint = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // (b) Filter row: SSID/BSSID chip + band chips.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { showSsidSheet = true },
                        label = {
                            Text(
                                text = buildString {
                                    append(filter.ssid ?: "All networks")
                                    filter.bssid?.let { append(" · ").append(it) }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val bands = listOf<Pair<Band?, String>>(
                        null to "All",
                        Band.GHZ_2_4 to "2.4",
                        Band.GHZ_5 to "5",
                        Band.GHZ_6 to "6",
                    )
                    for ((band, label) in bands) {
                        FilterChip(
                            selected = filter.band == band,
                            onClick = { vm.setFilter(filter.copy(band = band)) },
                            label = { Text(label) },
                        )
                    }
                }

                // (c) Display controls.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = displayMode == DisplayMode.HEATMAP,
                        onClick = { vm.displayMode.value = DisplayMode.HEATMAP },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Heatmap") }
                    SegmentedButton(
                        selected = displayMode == DisplayMode.PASS_FAIL,
                        onClick = { vm.displayMode.value = DisplayMode.PASS_FAIL },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Pass-Fail") }
                }
                if (displayMode == DisplayMode.PASS_FAIL) {
                    Text(
                        text = "Threshold: $threshold dBm",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = threshold.toFloat(),
                        onValueChange = { vm.thresholdDbm.value = it.roundToInt() },
                        valueRange = -85f..-50f,
                        steps = 34,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (preset in listOf(-60, -65, -67, -70, -75)) {
                            TextButton(onClick = { vm.thresholdDbm.value = preset }) {
                                Text("$preset")
                            }
                        }
                    }
                }
                Text(text = "Heatmap opacity", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%")
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = opacity == value,
                            onClick = { vm.heatmapOpacity.value = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) { Text(label) }
                    }
                }

                // (d) Overlay toggles.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Sample dots",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showDots, onCheckedChange = { vm.showDots.value = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Walked path",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showPath, onCheckedChange = { vm.showPath.value = it })
                }

                // (e) Stats.
                val apCount = filter.ssid
                    ?.let { s -> summaries.firstOrNull { it.ssid == s }?.bssidCount ?: 0 }
                    ?: summaries.sumOf { it.bssidCount }
                StatsPanel(
                    scale = colorScale,
                    sampleCount = samples.size,
                    apCount = apCount,
                    summary = signalSummary,
                    coverage = display.coverage,
                    thresholdDbm = threshold,
                    distanceUnit = distanceUnit,
                    uncalibrated = survey?.positioningMode == "TAP" &&
                        floorPlan?.metersPerPixel == null,
                )

                // (f) Export row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { csvLauncher.launch(vm.suggestedFileName("csv")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV") }
                    Button(
                        onClick = { pngLauncher.launch(vm.suggestedFileName("png")) },
                        modifier = Modifier.weight(1f),
                        enabled = displayFrame != null,
                    ) { Text("Export PNG report") }
                    if (exportEvent?.success == true) {
                        IconButton(onClick = shareLastExport) {
                            Icon(Icons.Filled.Share, contentDescription = "Share last export")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showSsidSheet) {
        ModalBottomSheet(onDismissRequest = { showSsidSheet = false }) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                item {
                    ListItem(
                        headlineContent = { Text("All networks") },
                        leadingContent = {
                            if (filter.ssid == null) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            vm.setFilter(filter.copy(ssid = null, bssid = null))
                            showSsidSheet = false
                        },
                    )
                }
                items(summaries, key = { it.ssid }) { row ->
                    SsidSheetRow(
                        row = row,
                        selectedBssid = if (filter.ssid == row.ssid) filter.bssid else null,
                        ssidSelected = filter.ssid == row.ssid,
                        loadBssids = { vm.bssidsFor(row.ssid) },
                        onSelectSsid = {
                            vm.setFilter(filter.copy(ssid = row.ssid, bssid = null))
                            showSsidSheet = false
                        },
                        onSelectBssid = { bssid ->
                            vm.setFilter(filter.copy(ssid = row.ssid, bssid = bssid))
                            showSsidSheet = false
                        },
                    )
                }
            }
        }
    }
}

/** One SSID row in the filter sheet, expandable to its per-BSSID drill-down. */
@Composable
private fun SsidSheetRow(
    row: SsidSummaryRow,
    selectedBssid: String?,
    ssidSelected: Boolean,
    loadBssids: suspend () -> List<BssidRow>,
    onSelectSsid: () -> Unit,
    onSelectBssid: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var bssids by remember { mutableStateOf<List<BssidRow>?>(null) }
    LaunchedEffect(expanded) {
        if (expanded && bssids == null) bssids = loadBssids()
    }

    Column {
        ListItem(
            headlineContent = { Text(row.ssid) },
            supportingContent = { Text("${row.bssidCount} APs · max ${row.maxRssi} dBm") },
            leadingContent = {
                if (ssidSelected && selectedBssid == null) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected")
                }
            },
            trailingContent = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse BSSIDs" else "Expand BSSIDs",
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onSelectSsid),
        )
        if (expanded) {
            val list = bssids
            if (list == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                for (bssid in list) {
                    ListItem(
                        headlineContent = {
                            Text(bssid.bssid, style = MaterialTheme.typography.bodyMedium)
                        },
                        supportingContent = {
                            Text(
                                "${bandLabel(bssid.band)} · ${bssid.frequencyMhz} MHz · " +
                                    "max ${bssid.maxRssi} dBm"
                            )
                        },
                        leadingContent = {
                            if (bssid.bssid == selectedBssid) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .clickable { onSelectBssid(bssid.bssid) },
                    )
                }
            }
        }
    }
}

private fun bandLabel(dbBand: Int): String = when (dbBand) {
    2 -> "2.4 GHz"
    5 -> "5 GHz"
    6 -> "6 GHz"
    else -> "$dbBand GHz"
}
