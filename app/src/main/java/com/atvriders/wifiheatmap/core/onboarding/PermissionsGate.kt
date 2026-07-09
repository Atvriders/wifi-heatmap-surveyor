package com.atvriders.wifiheatmap.core.onboarding

/**
 * The first unmet precondition blocking a Wi-Fi survey, in the order the user
 * must resolve them. [PermissionsGate.evaluate] returns exactly one step; the UI
 * shows one prompt at a time and re-evaluates after every state change.
 */
enum class OnboardingStep {
    /**
     * Neither fine nor coarse location permission is granted.
     *
     * Consequence: Wi-Fi scan results are entirely unavailable — the platform
     * returns an empty list and the connected network's SSID/BSSID read as
     * redacted placeholders, so no survey data can be captured at all.
     *
     * Fix: request the runtime location permission dialog; if permanently
     * denied, send the user to the app's detail page in system Settings
     * (App info -> Permissions -> Location).
     */
    NEED_LOCATION_PERMISSION,

    /**
     * Running on API 29+ with only coarse location granted (on API 31+ this is
     * the "Approximate" choice in the permission dialog).
     *
     * Consequence: scan results are still empty on API 29+ — coarse location is
     * not sufficient to receive Wi-Fi scan results, so the survey silently
     * records nothing.
     *
     * Fix: re-request fine location; on API 31+ the user must pick "Precise" in
     * the dialog, or flip the "Use precise location" toggle on the app's
     * Location permission page in system Settings.
     */
    NEED_PRECISE_LOCATION,

    /**
     * The device-wide location service (the Location quick-settings toggle) is
     * off.
     *
     * Consequence: on every relevant API level, Wi-Fi scans silently return an
     * empty list and the connected SSID is redacted even though the app holds
     * the permission — this looks like "no networks anywhere" to the user.
     *
     * Fix: open the system Location settings screen
     * (Settings -> Location, i.e. the ACTION_LOCATION_SOURCE_SETTINGS intent)
     * and turn location on.
     */
    NEED_LOCATION_SERVICES,

    /**
     * Wi-Fi is turned off.
     *
     * Consequence: no scans can run and there is no connected network to read
     * RSSI from, so both CONNECTED and SCAN_ALL survey modes produce nothing.
     *
     * Fix: open the system Wi-Fi settings screen
     * (Settings -> Network & internet -> Internet, i.e. the ACTION_WIFI_SETTINGS
     * intent — on API 29+ apps cannot toggle Wi-Fi programmatically).
     */
    NEED_WIFI_ON,

    /** All preconditions met — surveying can start. */
    READY,
}

/**
 * Snapshot of the device preconditions relevant to Wi-Fi scanning, gathered by
 * the Android layer and evaluated here in pure JVM code.
 *
 * @property sdkInt the platform API level (android.os.Build.VERSION.SDK_INT).
 * @property fineGranted true when ACCESS_FINE_LOCATION is granted.
 * @property coarseGranted true when ACCESS_COARSE_LOCATION is granted.
 * @property locationServicesOn true when the device-wide location toggle is on.
 * @property wifiOn true when the Wi-Fi radio is enabled.
 */
data class DeviceState(
    val sdkInt: Int,
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val locationServicesOn: Boolean,
    val wifiOn: Boolean,
)

/**
 * Pure decision table mapping a [DeviceState] to the single [OnboardingStep]
 * the user must resolve next. Deterministic and side-effect free; the Android
 * layer re-invokes it after every permission result, settings return, or
 * broadcast.
 */
object PermissionsGate {

    /** Minimum API level on which coarse-only location stops yielding scan results. */
    private const val FINE_REQUIRED_SDK = 29

    /**
     * Returns the first failing precondition, checked in this order:
     * 1. no location permission at all -> [OnboardingStep.NEED_LOCATION_PERMISSION]
     * 2. API >= 29 without fine location (coarse-only) -> [OnboardingStep.NEED_PRECISE_LOCATION]
     *    (on API 26..28 coarse alone is sufficient and this check does not apply)
     * 3. location services off -> [OnboardingStep.NEED_LOCATION_SERVICES]
     * 4. Wi-Fi off -> [OnboardingStep.NEED_WIFI_ON]
     * 5. otherwise [OnboardingStep.READY]
     */
    fun evaluate(s: DeviceState): OnboardingStep = when {
        !s.fineGranted && !s.coarseGranted -> OnboardingStep.NEED_LOCATION_PERMISSION
        s.sdkInt >= FINE_REQUIRED_SDK && !s.fineGranted -> OnboardingStep.NEED_PRECISE_LOCATION
        !s.locationServicesOn -> OnboardingStep.NEED_LOCATION_SERVICES
        !s.wifiOn -> OnboardingStep.NEED_WIFI_ON
        else -> OnboardingStep.READY
    }
}
