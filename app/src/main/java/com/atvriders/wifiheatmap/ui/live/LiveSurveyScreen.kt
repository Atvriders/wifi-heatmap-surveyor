package com.atvriders.wifiheatmap.ui.live

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.heatmap.ColorScale
import com.atvriders.wifiheatmap.data.SignalUnit
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.ui.common.SurveyPreflightGate
import kotlinx.coroutines.delay

/** GPS start extent in plan units (meters): -50..50 on both axes. */
private const val GPS_START_EXTENT = 100.0

/**
 * The walk-and-watch screen: live heatmap over the floor plan (or GPS field), tap
 * positioning, HUD stats, throttle warnings and pause/undo/finish controls.
 * Everything sits behind [SurveyPreflightGate] so sources only start when scanning
 * can actually work.
 */
@Composable
fun LiveSurveyScreen(
    container: AppContainer,
    surveyId: Long,
    onFinish: (surveyId: Long) -> Unit,
    onBack: () -> Unit,
) {
    SurveyPreflightGate {
        LiveSurveyContent(
            container = container,
            surveyId = surveyId,
            onFinish = onFinish,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSurveyContent(
    container: AppContainer,
    surveyId: Long,
    onFinish: (surveyId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: LiveSurveyViewModel = viewModel { LiveSurveyViewModel(container, surveyId) }

    val surveyState by vm.survey.collectAsState()
    val floorPlanState by vm.floorPlan.collectAsState()
    val planBitmap by vm.planBitmap.collectAsState()
    val settingsState by vm.appSettings.collectAsState()
    val frame by vm.frames.collectAsState()
    val stats by vm.liveStats.collectAsState()
    val throttle by vm.throttle.collectAsState()
    val ssids by vm.ssidsSeen.collectAsState()
    val sessionSamples by vm.samples.collectAsState()
    val priorSamples by vm.priorSamples.collectAsState()
    val tapAnchors by vm.tapAnchors.collectAsState()
    val currentAnchor by vm.currentAnchor.collectAsState()
    val heatFilter by vm.heatFilter.collectAsState()
    val ready by vm.ready.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val finished by vm.finished.collectAsState()

    // Durable finish: viewModelScope drives the stop+drain, surviving rotation; when it
    // completes we navigate exactly once (LaunchedEffect fires on the single false->true).
    LaunchedEffect(finished) {
        if (finished) onFinish(surveyId)
    }

    if (loadError) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Survey not found",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }

    val survey = surveyState
    val settings = settingsState
    if (survey == null || settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val isGps = survey.positioningMode == "GPS"
    val isConnectedMode = survey.scanMode == "CONNECTED"
    val paused = stats.paused
    val colorScale =
        if (settings.colorblindScale) ColorScale.ColorblindSafe else ColorScale.Default
    val totalSampleCount = priorSamples.size + stats.sampleCount
    val allSamples = remember(priorSamples, sessionSamples) { priorSamples + sessionSamples }
    val gpsCurrent = if (isGps) {
        sessionSamples.lastOrNull()?.let { Vec2(it.x, it.y) to (it.accuracyM ?: 0f) }
    } else {
        null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.snackbarMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Keep the screen on while surveying (per settings).
    val view = LocalView.current
    val keepOn = settings.keepScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // Stale-tap nudge: recording but no tap for 45 s (one nudge per tap).
    LaunchedEffect(isGps, ready) {
        if (isGps || !ready) return@LaunchedEffect
        var nudgedForTapAt = -1L
        while (true) {
            delay(5_000)
            val lastTap = vm.lastTapAtMs.value
            val recording = !vm.liveStats.value.paused && vm.currentAnchor.value != null
            if (recording && lastTap > 0 && lastTap != nudgedForTapAt &&
                SystemClock.elapsedRealtime() - lastTap > 45_000
            ) {
                nudgedForTapAt = lastTap
                snackbarHostState.showSnackbar(
                    "Still walking? Tap your position - samples are piling up at your last point."
                )
            }
        }
    }

    // CONNECTED mode: no RSSI for > 3 s -> informational disconnect banner.
    var showDisconnected by remember { mutableStateOf(false) }
    val rssiMissing = isConnectedMode && ready && stats.currentRssiDbm == null
    LaunchedEffect(rssiMissing) {
        if (rssiMissing) {
            delay(3_000)
            showDisconnected = true
        } else {
            showDisconnected = false
        }
    }

    var showLeaveDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showSsidPicker by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    BackHandler { showLeaveDialog = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = survey.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isConnectedMode) {
                                "Connected - ${stats.currentSsid ?: "no network"}"
                            } else {
                                "Scan-all"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showLeaveDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSsidPicker = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Choose heatmap network")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ThrottleBanner(throttle)
            if (isGps && stats.waitingForFix) {
                val accuracySuffix = gpsCurrent?.second?.takeIf { it > 0f }
                    ?.let { " Last accuracy: %.0f m".format(it) } ?: ""
                InfoBanner(
                    text = "Waiting for GPS fix...$accuracySuffix",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (showDisconnected) {
                InfoBanner(
                    text = "Disconnected - sampling paused",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            SurveyCanvas(
                planBitmap = planBitmap,
                planWidthPx = if (isGps) {
                    GPS_START_EXTENT
                } else {
                    (floorPlanState?.widthPx ?: 500).toDouble()
                },
                planHeightPx = if (isGps) {
                    GPS_START_EXTENT
                } else {
                    (floorPlanState?.heightPx ?: 500).toDouble()
                },
                frame = frame,
                samples = allSamples,
                tapAnchors = tapAnchors,
                currentAnchor = currentAnchor,
                gpsCurrent = gpsCurrent,
                isGps = isGps,
                onTapPlanPoint = if (!isGps && !paused) {
                    { vm.onTap(it) }
                } else {
                    null
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (paused) 0.6f else 1f),
            )

            StatsBar(
                stats = stats,
                scale = colorScale,
                totalSampleCount = totalSampleCount,
                signalPercent = settings.signalUnit == SignalUnit.PERCENT,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { if (paused) vm.resume() else vm.pause() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "Resume" else "Pause")
                }
                if (!isGps) {
                    IconButton(
                        onClick = vm::undo,
                        enabled = sessionSamples.isNotEmpty() || currentAnchor != null,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo last point",
                        )
                    }
                }
                Button(
                    onClick = { showFinishDialog = true },
                    enabled = !finishing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Finish")
                }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("Leave survey?") },
            text = {
                Text(
                    "Recording pauses when you leave. The survey stays active and you " +
                        "can resume it from Home."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    vm.pause()
                    onBack()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text("Stay") }
            },
        )
    }

    if (showFinishDialog) {
        val isEmpty = totalSampleCount == 0
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text(if (isEmpty) "Finish empty survey?" else "Finish survey?") },
            text = {
                if (isEmpty) {
                    Text(
                        "You haven't recorded any samples yet — the survey will be saved " +
                            "with nothing to show."
                    )
                } else {
                    Column {
                        Text("Duration: ${formatElapsedMmSs(stats.elapsedMs)}")
                        if (!isGps) Text("Points: ${tapAnchors.size}")
                        Text("Samples: $totalSampleCount")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFinishDialog = false
                    finishing = true
                    vm.finishAsync()
                }) { Text("Finish") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("Keep surveying") }
            },
        )
    }

    if (showSsidPicker) {
        SsidPickerSheet(
            ssids = ssids,
            currentFilter = heatFilter,
            onSelect = vm::setFilter,
            onDismiss = { showSsidPicker = false },
        )
    }
}

@Composable
private fun InfoBanner(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
