package com.atvriders.wifiheatmap.sources

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.atvriders.wifiheatmap.core.engine.SignalSource
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.model.WifiReading
import com.atvriders.wifiheatmap.core.wifi.BandClassifier
import com.atvriders.wifiheatmap.core.wifi.DbmHygiene
import com.atvriders.wifiheatmap.core.wifi.RssiSampler
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
 * [SignalSource] for CONNECTED mode: polls the RSSI of the currently-associated
 * network every [pollIntervalMs] and emits one single-reading [SignalSnapshot]
 * per poll. Freshness is de-duplicated through one [RssiSampler] (the framework
 * only refreshes the connected RSSI every ~3 s).
 *
 * On API 31+ a [ConnectivityManager.NetworkCallback] registered with
 * FLAG_INCLUDE_LOCATION_INFO supplies an unredacted [WifiInfo]; its ssid/bssid
 * identity is preferred over the legacy poll's, while the RSSI value itself
 * always comes from the poll (the callback only fires on capability changes).
 *
 * Connected polling never trips the platform scan throttle, so [throttle] is
 * permanently [ThrottleState.Unthrottled].
 */
class ConnectedRssiPoller(
    context: Context,
    private val pollIntervalMs: Long,
) : SignalSource {

    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _snapshots = MutableSharedFlow<SignalSnapshot>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val snapshots: Flow<SignalSnapshot> = _snapshots

    override val throttle: StateFlow<ThrottleState> =
        MutableStateFlow(ThrottleState.Unthrottled)

    /** Poll scope; non-null exactly while started. Main.immediate keeps [sampler] confined. */
    private var scope: CoroutineScope? = null

    /** Freshness de-duplication; recreated on every start so a new session re-arms. */
    private var sampler = RssiSampler()

    /** Last WifiInfo delivered by the API 31+ network callback; null when not on Wi-Fi. */
    @Volatile
    private var callbackWifiInfo: WifiInfo? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun start() {
        if (scope != null) return // idempotent
        if (wifiManager == null) return // no Wi-Fi service on this device; emit nothing
        sampler = RssiSampler()
        registerNetworkCallbackIfSupported()
        val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = pollScope
        pollScope.launch {
            while (isActive) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Already unregistered (or registration never completed); nothing to release.
            } catch (_: SecurityException) {
                // Permission revoked mid-run; nothing else to do.
            }
        }
        networkCallback = null
        callbackWifiInfo = null
    }

    // WHY @Suppress: WifiManager.connectionInfo (and WifiInfo.networkId) is deprecated since
    // API 31 but remains the only synchronous way to read the connected network's live RSSI
    // through API 36; the replacement callback only fires on capability changes, not per poll.
    @Suppress("DEPRECATION")
    private fun pollOnce() {
        val wifi = wifiManager ?: return
        val now = SystemClock.elapsedRealtime()
        val pollInfo = try {
            wifi.connectionInfo
        } catch (_: SecurityException) {
            null // permission revoked mid-run; skip this poll
        } ?: return

        // Not associated: no callback-confirmed Wi-Fi network and the legacy info
        // reports no configured network.
        if (callbackWifiInfo == null && pollInfo.networkId == -1) return

        // Prefer the callback-provided identity (unredacted on 31+); RSSI stays with the poll.
        val identity = callbackWifiInfo ?: pollInfo
        val bssid = identity.bssid?.lowercase() ?: return
        if (bssid == REDACTED_BSSID) return // location-redacted placeholder, useless as identity

        val rssi = DbmHygiene.sanitize(pollInfo.rssi) ?: return
        val frequency = pollInfo.frequency
        val band = BandClassifier.bandFor(frequency) ?: return
        val ssid = cleanSsid(identity.ssid)

        val fresh = sampler.onReading(now, rssi)
        _snapshots.tryEmit(
            SignalSnapshot(
                timestampMs = now,
                readings = listOf(
                    WifiReading(
                        bssid = bssid,
                        ssid = ssid,
                        rssiDbm = rssi,
                        frequencyMhz = frequency,
                        band = band,
                        isConnected = true,
                    )
                ),
                fresh = fresh,
            )
        )
    }

    private fun registerNetworkCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback(
            FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                callbackWifiInfo = networkCapabilities.transportInfo as? WifiInfo
            }

            override fun onLost(network: Network) {
                callbackWifiInfo = null
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
            networkCallback = callback
        } catch (_: SecurityException) {
            // Some OEMs gate callback registration; fall back to poll-provided identity.
        } catch (_: RuntimeException) {
            // TooManyRequestsException etc.; fall back to poll-provided identity.
        }
    }

    /** Strips the quoting WifiInfo applies to UTF-8 SSIDs; the unknown sentinel becomes "". */
    private fun cleanSsid(raw: String?): String {
        val ssid = raw ?: return ""
        if (ssid == UNKNOWN_SSID) return ""
        return ssid.removeSurrounding("\"")
    }

    private companion object {
        /** Placeholder BSSID the platform serves when location access is missing. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"

        /** Sentinel WifiInfo.getSSID() returns when the SSID cannot be disclosed. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
