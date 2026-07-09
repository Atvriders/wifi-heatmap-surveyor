package com.atvriders.wifiheatmap.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.atvriders.wifiheatmap.core.onboarding.DeviceState
import com.atvriders.wifiheatmap.core.onboarding.OnboardingStep
import com.atvriders.wifiheatmap.core.onboarding.PermissionsGate

/**
 * Self-contained preflight gate: evaluates [PermissionsGate] against live device
 * state and renders [content] only when every precondition for Wi-Fi surveying is
 * met. Otherwise shows a full-screen fix-it prompt for the first unmet step,
 * re-evaluating on ON_RESUME (returns from Settings) and after permission results.
 */
@Composable
fun SurveyPreflightGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var deviceState by remember { mutableStateOf(readDeviceState(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { deviceState = readDeviceState(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceState = readDeviceState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (PermissionsGate.evaluate(deviceState)) {
        OnboardingStep.READY -> content()

        OnboardingStep.NEED_LOCATION_PERMISSION -> FixItScreen(
            icon = Icons.Outlined.LocationOn,
            title = "Location permission needed",
            body = "Android requires precise location to read Wi-Fi scan data — " +
                "nothing leaves this device (the app has no Internet permission).",
            primaryLabel = "Grant permission",
            onPrimary = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onCheckAgain = { deviceState = readDeviceState(context) },
            tertiaryLabel = "Open app settings",
            onTertiary = { context.startActivity(appDetailsIntent(context)) },
        )

        OnboardingStep.NEED_PRECISE_LOCATION -> FixItScreen(
            icon = Icons.Outlined.MyLocation,
            title = "Precise location required",
            body = "Only approximate location is granted, so Android withholds Wi-Fi " +
                "scan results and the survey would record nothing. Turn on \"Use " +
                "precise location\" for this app in system settings.",
            primaryLabel = "Open app settings",
            onPrimary = { context.startActivity(appDetailsIntent(context)) },
            onCheckAgain = { deviceState = readDeviceState(context) },
        )

        OnboardingStep.NEED_LOCATION_SERVICES -> FixItScreen(
            icon = Icons.Outlined.LocationOff,
            title = "Turn on location",
            body = "Device location is off. Android silently returns empty Wi-Fi " +
                "scans while location is disabled — even with permission granted — " +
                "so the survey would see no networks at all.",
            primaryLabel = "Open location settings",
            onPrimary = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            onCheckAgain = { deviceState = readDeviceState(context) },
        )

        OnboardingStep.NEED_WIFI_ON -> FixItScreen(
            icon = Icons.Outlined.WifiOff,
            title = "Turn on Wi-Fi",
            body = "Wi-Fi is off, so there are no networks to measure and nothing " +
                "to survey. Turn Wi-Fi on to continue.",
            primaryLabel = "Turn on Wi-Fi",
            onPrimary = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            },
            onCheckAgain = { deviceState = readDeviceState(context) },
        )
    }
}

private fun readDeviceState(context: Context): DeviceState {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    return DeviceState(
        sdkInt = Build.VERSION.SDK_INT,
        fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED,
        coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED,
        locationServicesOn = locationManager != null &&
            LocationManagerCompat.isLocationEnabled(locationManager),
        wifiOn = wifiManager?.isWifiEnabled == true,
    )
}

private fun appDetailsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

@Composable
private fun FixItScreen(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onCheckAgain: () -> Unit,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPrimary) { Text(primaryLabel) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
        if (tertiaryLabel != null && onTertiary != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onTertiary) { Text(tertiaryLabel) }
        }
    }
}
