@file:OptIn(ExperimentalMaterial3Api::class)

package com.atvriders.wifiheatmap.ui.wizard

import android.content.Context
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atvriders.wifiheatmap.core.geo.PlanTransform
import com.atvriders.wifiheatmap.core.geo.Vec2
import com.atvriders.wifiheatmap.core.model.PositioningMode
import com.atvriders.wifiheatmap.core.model.ScanMode
import com.atvriders.wifiheatmap.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * New Survey wizard: Basics -> Floor plan -> Calibration -> Capture mode.
 * GPS surveys skip steps 2-3; blank-grid surveys skip step 3.
 */
@Composable
fun WizardScreen(
    container: AppContainer,
    onSurveyCreated: (surveyId: Long) -> Unit,
    onCancel: () -> Unit,
) {
    val vm: WizardViewModel = viewModel { WizardViewModel(container) }
    val state by vm.state.collectAsState()
    val createdId by vm.createdSurveyId.collectAsState()

    LaunchedEffect(createdId) { createdId?.let(onSurveyCreated) }

    var step by rememberSaveable { mutableIntStateOf(1) }
    var scanDefaultApplied by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val cancelWizard = {
        vm.discardDraft()
        onCancel()
    }
    val goBack = {
        if (step > 1) step = prevStepFrom(step, vm.state.value) else cancelWizard()
    }

    BackHandler(onBack = goBack)

    // Default the capture mode the first time step 4 is shown: CONNECTED when the
    // device is on a Wi-Fi network, SCAN_ALL otherwise.
    LaunchedEffect(step) {
        if (step == 4 && !scanDefaultApplied) {
            scanDefaultApplied = true
            if (!readWifiConnection(context).connected) vm.setScanMode(ScanMode.SCAN_ALL)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("New survey")
                            Text(
                                "Step $step of 4",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        if (step > 1) {
                            IconButton(onClick = goBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = cancelWizard) { Text("Cancel") }
                    },
                )
                LinearProgressIndicator(
                    progress = { step / 4f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                1 -> StepBasics(
                    state = state,
                    vm = vm,
                    onNext = { step = nextStepFrom(1, vm.state.value) },
                )
                2 -> StepFloorPlan(
                    container = container,
                    state = state,
                    vm = vm,
                    onNext = { step = nextStepFrom(2, vm.state.value) },
                )
                3 -> StepCalibration(
                    container = container,
                    state = state,
                    vm = vm,
                    onNext = { step = 4 },
                )
                else -> StepCaptureMode(
                    state = state,
                    vm = vm,
                    onStart = { vm.createSurvey(System.currentTimeMillis()) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step routing
// ---------------------------------------------------------------------------

private fun nextStepFrom(step: Int, state: WizardViewModel.State): Int = when (step) {
    1 -> if (state.mode == PositioningMode.GPS) 4 else 2
    2 -> if (state.planChoice == PlanChoice.IMPORT) 3 else 4
    else -> 4
}

private fun prevStepFrom(step: Int, state: WizardViewModel.State): Int = when (step) {
    4 -> when {
        state.mode == PositioningMode.GPS -> 1
        state.planChoice == PlanChoice.IMPORT -> 3
        else -> 2
    }
    3 -> 2
    else -> 1
}

// ---------------------------------------------------------------------------
// Step 1: Basics
// ---------------------------------------------------------------------------

@Composable
private fun StepBasics(
    state: WizardViewModel.State,
    vm: WizardViewModel,
    onNext: () -> Unit,
) {
    Text("Basics", style = MaterialTheme.typography.titleLarge)

    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text("Survey name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("How will you record your position?", style = MaterialTheme.typography.titleMedium)

    OptionCard(
        selected = state.mode == PositioningMode.TAP,
        onClick = { vm.setMode(PositioningMode.TAP) },
        icon = Icons.Filled.TouchApp,
        title = "Indoor",
        description = "Tap your position on a floor plan.",
        badge = "Recommended",
    )

    OptionCard(
        selected = state.mode == PositioningMode.GPS,
        onClick = { vm.setMode(PositioningMode.GPS) },
        icon = Icons.Filled.GpsFixed,
        title = "Outdoor",
        description = "GPS positioning.",
    )

    Button(
        onClick = onNext,
        enabled = state.name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Next") }
}

// ---------------------------------------------------------------------------
// Step 2: Floor plan (indoor only)
// ---------------------------------------------------------------------------

@Composable
private fun StepFloorPlan(
    container: AppContainer,
    state: WizardViewModel.State,
    vm: WizardViewModel,
    onNext: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importImage) }

    Text("Floor plan", style = MaterialTheme.typography.titleLarge)
    Text(
        "Pick a floor-plan image of the area, or survey on a blank grid.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OptionCard(
        selected = state.planChoice == PlanChoice.IMPORT,
        onClick = { launcher.launch(arrayOf("image/*")) },
        icon = Icons.Filled.Image,
        title = "Import image",
        description = "A photo, screenshot, or drawing of the floor plan. Tap again to pick a different image.",
    ) {
        if (state.importing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Importing…", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.importError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val imported = state.importedPlan
        if (imported != null && !state.importing) {
            val preview = rememberPlanBitmap(container, imported.path)
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "Floor plan preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                "Imported ${imported.widthPx} x ${imported.heightPx} px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OptionCard(
        selected = state.planChoice == PlanChoice.BLANK_GRID,
        onClick = vm::selectBlankGrid,
        icon = Icons.Filled.Grid4x4,
        title = "Blank grid",
        description = "No image needed — walk a plain grid sized to the area.",
    ) {
        if (state.planChoice == PlanChoice.BLANK_GRID) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.gridWidthText,
                    onValueChange = vm::setGridWidthText,
                    label = { Text("Width (m)") },
                    singleLine = true,
                    isError = state.gridWidthM == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.gridHeightText,
                    onValueChange = vm::setGridHeightText,
                    label = { Text("Depth (m)") },
                    singleLine = true,
                    isError = state.gridHeightM == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.gridWidthM == null || state.gridHeightM == null) {
                Text(
                    "Enter sizes in meters, greater than zero.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    val nextEnabled = when (state.planChoice) {
        PlanChoice.IMPORT -> state.importedPlan != null && !state.importing
        PlanChoice.BLANK_GRID -> state.gridWidthM != null && state.gridHeightM != null
        null -> false
    }
    Button(
        onClick = onNext,
        enabled = nextEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Next") }
}

// ---------------------------------------------------------------------------
// Step 3: Calibration (imported image only)
// ---------------------------------------------------------------------------

@Composable
private fun StepCalibration(
    container: AppContainer,
    state: WizardViewModel.State,
    vm: WizardViewModel,
    onNext: () -> Unit,
) {
    val plan = state.importedPlan
    if (plan == null) {
        Text("Import a floor plan first.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text("Set the scale", style = MaterialTheme.typography.titleLarge)
    Text(
        "Tap two points whose real-world distance you know (a doorway, a wall), " +
            "then enter that distance. Pinch to zoom, two-finger drag to pan.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    CalibrationCanvas(container = container, plan = plan, state = state, vm = vm)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = vm::clearCalibrationPins,
            enabled = state.calA != null || state.calB != null,
        ) { Text("Clear pins") }
    }

    OutlinedTextField(
        value = state.distanceText,
        onValueChange = vm::setDistanceText,
        label = { Text("Real distance between A and B (meters)") },
        singleLine = true,
        enabled = state.calA != null && state.calB != null,
        isError = state.distanceText.isNotEmpty() && state.realDistanceM == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        supportingText = {
            when {
                state.calA == null || state.calB == null ->
                    Text("Place both pins on the plan first.")
                state.distanceText.isNotEmpty() && state.realDistanceM == null ->
                    Text("Enter a distance in meters, greater than zero.")
                else -> {}
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    val mpp = state.metersPerPixel
    if (mpp != null) {
        Text(
            String.format(Locale.US, "Plan is ~%.1f m wide", plan.widthPx * mpp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }

    var showSkipDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { showSkipDialog = true }) { Text("Skip calibration") }
        Button(
            onClick = onNext,
            enabled = mpp != null,
            modifier = Modifier.weight(1f),
        ) { Text("Next") }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("Skip calibration?") },
            text = {
                Text(
                    "Without a scale, area statistics are unavailable, the heatmap " +
                        "smoothing radius falls back to a pixel-based estimate, and " +
                        "exports carry an \"Uncalibrated\" watermark. You can duplicate " +
                        "the survey later to recalibrate."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    vm.skipCalibration()
                    onNext()
                }) { Text("Skip anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) { Text("Keep calibrating") }
            },
        )
    }
}

/** Pan/zoom floor-plan canvas; taps place calibration pins A and B. */
@Composable
private fun CalibrationCanvas(
    container: AppContainer,
    plan: com.atvriders.wifiheatmap.data.FloorPlanImageStore.ImportedPlan,
    state: WizardViewModel.State,
    vm: WizardViewModel,
) {
    val bitmap = rememberPlanBitmap(container, plan.path)
    var transform by remember(plan.path) { mutableStateOf<PlanTransform?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val pinAColor = MaterialTheme.colorScheme.primary
    val pinBColor = MaterialTheme.colorScheme.tertiary
    val pinHaloColor = MaterialTheme.colorScheme.surface
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clipToBounds()
            .onSizeChanged { size ->
                if (transform == null && size.width > 0 && size.height > 0) {
                    transform = PlanTransform.fit(
                        planW = plan.widthPx.toDouble(),
                        planH = plan.heightPx.toDouble(),
                        viewW = size.width.toDouble(),
                        viewH = size.height.toDouble(),
                    )
                }
            }
            .pointerInput(plan.path) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform = transform
                        ?.zoomBy(zoom.toDouble(), Vec2(centroid.x.toDouble(), centroid.y.toDouble()))
                        ?.panByView(Vec2(pan.x.toDouble(), pan.y.toDouble()))
                }
            }
            .pointerInput(plan.path) {
                detectTapGestures { position ->
                    val t = transform ?: return@detectTapGestures
                    vm.placeCalibrationPin(
                        t.viewToPlan(Vec2(position.x.toDouble(), position.y.toDouble()))
                    )
                }
            },
    ) {
        drawRect(color = backgroundColor)
        val t = transform ?: return@Canvas

        if (bitmap != null) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(t.offsetX.roundToInt(), t.offsetY.roundToInt()),
                dstSize = IntSize(
                    (plan.widthPx * t.scale).roundToInt().coerceAtLeast(1),
                    (plan.heightPx * t.scale).roundToInt().coerceAtLeast(1),
                ),
            )
        }

        val aView = state.calA?.let(t::planToView)
        val bView = state.calB?.let(t::planToView)

        if (aView != null && bView != null) {
            drawLine(
                color = lineColor,
                start = Offset(aView.x.toFloat(), aView.y.toFloat()),
                end = Offset(bView.x.toFloat(), bView.y.toFloat()),
                strokeWidth = 3.dp.toPx(),
            )
        }

        fun drawPin(view: Vec2, label: String, color: androidx.compose.ui.graphics.Color) {
            val center = Offset(view.x.toFloat(), view.y.toFloat())
            drawCircle(color = pinHaloColor, radius = 12.dp.toPx() / 2f + 3.dp.toPx() / 2f, center = center)
            drawCircle(color = color, radius = 12.dp.toPx() / 2f, center = center)
            drawText(
                textMeasurer = textMeasurer,
                text = label,
                topLeft = center + Offset(8.dp.toPx(), -(22.dp.toPx())),
                style = TextStyle(
                    color = color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        aView?.let { drawPin(it, "A", pinAColor) }
        bView?.let { drawPin(it, "B", pinBColor) }
    }
}

// ---------------------------------------------------------------------------
// Step 4: Capture mode
// ---------------------------------------------------------------------------

@Composable
private fun StepCaptureMode(
    state: WizardViewModel.State,
    vm: WizardViewModel,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val wifi = remember { readWifiConnection(context) }

    Text("Capture mode", style = MaterialTheme.typography.titleLarge)

    OptionCard(
        selected = state.scanMode == ScanMode.CONNECTED,
        onClick = { vm.setScanMode(ScanMode.CONNECTED) },
        icon = Icons.Filled.Wifi,
        title = "Connected network",
        description = "Fast ~1 s sampling of the Wi-Fi you're on; never throttled.",
        badge = "Recommended",
    ) {
        if (wifi.connected) {
            Text(
                "Current network: ${wifi.ssid ?: "(name hidden until location permission is granted)"}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Not connected — join a network first",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    OptionCard(
        selected = state.scanMode == ScanMode.SCAN_ALL,
        onClick = { vm.setScanMode(ScanMode.SCAN_ALL) },
        icon = Icons.Filled.WifiTethering,
        title = "Scan everything",
        description = "Records every AP on all bands. Android throttles scans to 4 per " +
            "2 minutes unless Wi-Fi scan throttling is disabled in Developer Options.",
    )

    if (state.mode == PositioningMode.TAP) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.DirectionsWalk,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Tap where you stand, walk straight, tap at every turn and stop.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    Button(
        onClick = onStart,
        enabled = !state.creating,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.creating) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text("Start walking")
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

/** Large selectable option card with icon, title, optional badge, and extra content. */
@Composable
private fun OptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    description: String,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/** Loads the imported plan bitmap off the main thread; null while loading. */
@Composable
private fun rememberPlanBitmap(container: AppContainer, path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) null else withContext(Dispatchers.IO) {
            container.floorPlans.loadBitmap(path)?.asImageBitmap()
        }
    }
    return bitmap
}

private data class WifiConnection(val connected: Boolean, val ssid: String?)

/** Best-effort read of the current Wi-Fi connection via the (deprecated) WifiManager API. */
private fun readWifiConnection(context: Context): WifiConnection {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return WifiConnection(connected = false, ssid = null)
    @Suppress("DEPRECATION")
    val info = wifiManager.connectionInfo
    val connected = info != null && info.networkId != -1
    val ssid = info?.ssid
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
    return WifiConnection(connected = connected, ssid = if (connected) ssid else null)
}
