package com.atvriders.wifiheatmap.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.atvriders.wifiheatmap.core.engine.SignalSource
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import com.atvriders.wifiheatmap.core.wifi.BandClassifier
import com.atvriders.wifiheatmap.core.wifi.DbmHygiene
import com.atvriders.wifiheatmap.core.wifi.ScanScheduler
import com.atvriders.wifiheatmap.core.wifi.ScanThrottleDetector
import com.atvriders.wifiheatmap.core.wifi.ThrottleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [SignalSource] for SCAN_ALL mode: paces `startScan()` requests through the core
 * [ScanScheduler], detects platform throttling / OEM result caching with the core
 * [ScanThrottleDetector], and emits one multi-reading [SignalSnapshot] per
 * SCAN_RESULTS_AVAILABLE broadcast.
 *
 * The results receiver stays registered for the entire started session (not just
 * around our own requests) so broadcasts triggered by other apps' scans are
 * harvested for free.
 *
 * Detector/scheduler are not thread-safe; both the scan loop (Main.immediate)
 * and the broadcast receiver (main thread) touch them on the main thread only.
 */
class ScanAllSignalSource(context: Context) : SignalSource {

    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val detector = ScanThrottleDetector()
    private val scheduler = ScanScheduler()

    private val _snapshots = MutableSharedFlow<SignalSnapshot>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val snapshots: Flow<SignalSnapshot> = _snapshots

    private val _throttle = MutableStateFlow<ThrottleState>(ThrottleState.Unknown)
    override val throttle: StateFlow<ThrottleState> = _throttle

    /** Scan-loop scope; non-null exactly while started. */
    private var scope: CoroutineScope? = null

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (scope == null) return // stopped between unregister race and delivery
            handleResultsBroadcast(intent)
        }
    }

    override fun start() {
        if (scope != null) return // idempotent
        if (wifiManager == null) return // no Wi-Fi service on this device; emit nothing
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = scanScope
        scanScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val accepted = requestScan()
                detector.onScanRequested(now, accepted)
                if (accepted) scheduler.recordScan(now)
                _throttle.value = detector.state
                delay(
                    scheduler
                        .nextScanDelayMs(now, detector.state is ThrottleState.Throttled)
                        .coerceAtLeast(500)
                )
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered; nothing to release.
            }
        }
    }

    // WHY @Suppress: WifiManager.startScan() is deprecated since API 28 yet remains the only
    // way for a foreground app to request an on-demand full scan through API 36.
    @Suppress("DEPRECATION")
    private fun requestScan(): Boolean = try {
        wifiManager?.startScan() ?: false
    } catch (_: SecurityException) {
        false // permission revoked mid-run; treat as a rejected request
    }

    private fun handleResultsBroadcast(intent: Intent?) {
        val now = SystemClock.elapsedRealtime()
        val resultsUpdated =
            intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true
        val results: List<ScanResult> = try {
            wifiManager?.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (results.isEmpty()) {
            // No entries to judge freshness by: report an over-threshold age so an
            // "updated" empty list never counts as fresh evidence, and emit nothing.
            detector.onResults(now, resultsUpdated, detector.staleResultAgeMs + 1)
            _throttle.value = detector.state
            return
        }

        // ScanResult.timestamp is MICROseconds since boot -> convert to ms BEFORE differencing.
        val newestAgeMs = now - results.maxOf { it.timestamp } / 1000
        detector.onResults(now, resultsUpdated, newestAgeMs)
        _throttle.value = detector.state

        val connectedBssid = connectedBssidOrNull()
        val readings = results.mapNotNull { toReading(it, connectedBssid) }
        if (readings.isNotEmpty()) {
            _snapshots.tryEmit(SignalSnapshot(now, readings, fresh = resultsUpdated))
        }
    }

    private fun toReading(result: ScanResult, connectedBssid: String?): WifiReading? {
        val bssid = result.BSSID?.lowercase() ?: return null
        val rssi = DbmHygiene.sanitize(result.level) ?: return null
        val frequency = result.frequency
        val band = BandClassifier.bandFor(frequency) ?: return null
        // WHY @Suppress: ScanResult.SSID is deprecated on API 33 (getWifiSsid replacement)
        // but the String field is still populated on every API level this app supports.
        @Suppress("DEPRECATION")
        val ssid = result.SSID ?: ""
        return WifiReading(
            bssid = bssid,
            ssid = ssid,
            rssiDbm = rssi,
            frequencyMhz = frequency,
            band = band,
            isConnected = connectedBssid != null && bssid == connectedBssid,
        )
    }

    // WHY @Suppress: WifiManager.connectionInfo is deprecated since API 31 but is the only
    // synchronous way to learn the currently-associated BSSID for tagging scan rows.
    @Suppress("DEPRECATION")
    private fun connectedBssidOrNull(): String? = try {
        wifiManager?.connectionInfo?.bssid?.lowercase()?.takeIf { it != REDACTED_BSSID }
    } catch (_: SecurityException) {
        null // best-effort tag only
    }

    private companion object {
        /** Placeholder BSSID the platform serves when location access is missing. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"
    }
}
