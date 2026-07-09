package com.atvriders.wifiheatmap.sources

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.atvriders.wifiheatmap.core.engine.PositionSource
import com.atvriders.wifiheatmap.core.model.PositionFix
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * [PositionSource] backed by the platform [LocationManager]: high-accuracy fixes
 * at a 1 s interval, delivered on the main executor and mapped straight into
 * core [PositionFix]es stamped with `SystemClock.elapsedRealtime()` (the shared
 * survey timebase, NOT the fix's own wall-clock time).
 *
 * Provider choice: on API 31+ the fused provider when the device offers it
 * (checked via `allProviders`, which works on every API level), otherwise raw GPS.
 */
class GpsPositionSource(context: Context) : PositionSource {

    private val appContext: Context = context.applicationContext
    private val locationManager: LocationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fixes = MutableSharedFlow<PositionFix>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<PositionFix> = _fixes

    private var started = false

    private val listener = LocationListenerCompat { location ->
        _fixes.tryEmit(
            PositionFix(
                timestampMs = SystemClock.elapsedRealtime(),
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = location.accuracy,
            )
        )
    }

    override fun start() {
        if (started) return // idempotent
        started = true
        val request = LocationRequestCompat.Builder(1000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager,
                pickProvider(),
                request,
                ContextCompat.getMainExecutor(appContext),
                listener,
            )
        } catch (_: SecurityException) {
            // Location permission revoked mid-session; the source simply emits nothing.
        } catch (_: IllegalArgumentException) {
            // Provider disappeared between the pick and the request; emit nothing.
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        try {
            LocationManagerCompat.removeUpdates(locationManager, listener)
        } catch (_: RuntimeException) {
            // Never registered (start failed) or manager already torn down; nothing to release.
        }
    }

    private fun pickProvider(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
}
