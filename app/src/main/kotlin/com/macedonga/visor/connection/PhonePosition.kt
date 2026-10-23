package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's position for navigation and place search, from the provider [LocationShare] uses. Updates run only
 * while guidance does; otherwise the system's last known fix answers.
 */
@Singleton
class PhonePosition @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { last = it }

    @Volatile
    private var last: Location? = null
    private var users = 0

    /** Asks for updates while guidance runs. Each call is paired with [release]. */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun use() {
        if (users++ == 0 && manager != null && granted()) {
            manager.requestLocationUpdates(provider(manager), INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        }
    }

    @Synchronized
    fun release() {
        if (users > 0 && --users == 0) manager?.removeUpdates(listener)
    }

    /** The latest fix while updates run, else the system's last known one. Null without the permission. */
    @SuppressLint("MissingPermission")
    fun current(): LatLng? {
        if (manager == null || !granted()) return null
        val fix = if (users > 0) last else null
        return (fix ?: manager.getLastKnownLocation(provider(manager)))?.let { LatLng(it.latitude, it.longitude) }
    }

    /** A fix taken now, for when none is known yet; null without one in [timeoutMs]. */
    @SuppressLint("MissingPermission")
    suspend fun fresh(timeoutMs: Long = FRESH_MS): LatLng? {
        if (manager == null || !granted() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider(manager), cancel, context.mainExecutor) { fix ->
                    cont.resume(fix?.let { LatLng(it.latitude, it.longitude) })
                }
            }
        }
    }

    private fun granted(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun provider(m: LocationManager): String = when {
        m.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.NETWORK_PROVIDER
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val FRESH_MS = 5_000L
    }
}
