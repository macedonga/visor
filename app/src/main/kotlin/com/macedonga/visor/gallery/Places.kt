package com.macedonga.visor.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.macedonga.visor.core.connection.CAMERA_PHOTO_STARTED
import com.macedonga.visor.core.connection.CAMERA_VIDEO_STARTED
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.Place
import com.macedonga.visor.core.media.PlaceLog
import com.macedonga.visor.settings.Need
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.settings.rememberOnResume
import com.macedonga.visor.ui.allows
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether the phone may say where it is, now and from the background, where the glasses' captures happen. */
fun placesAllowed(context: Context): Boolean =
    context.allows(Manifest.permission.ACCESS_FINE_LOCATION) && context.allows(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

/** Whether places are allowed, and the ask: location first, then background; refused for good, Visor's page in Android's settings. */
@Composable
fun rememberPlacesAsk(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var allowed by rememberOnResume { placesAllowed(context) }
    fun refusedForGood(permissions: Collection<String>) = permissions.all { activity?.shouldShowRequestPermissionRationale(it) == false }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = placesAllowed(context)
        if (!granted && refusedForGood(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))) openAppPage(context)
    }
    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        allowed = placesAllowed(context)
        // The background permission is a second question, and only after the first was granted.
        if (!allowed && context.allows(Manifest.permission.ACCESS_FINE_LOCATION)) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else if (answers.values.none { it } && refusedForGood(answers.keys)) openAppPage(context)
    }
    return allowed to {
        if (context.allows(Manifest.permission.ACCESS_FINE_LOCATION)) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else askFine.launch(Need.LOCATION.ask(context))
    }
}

/** Logs the phone's position each time the glasses start a capture. */
fun notePlaces(context: Context, scope: CoroutineScope, connection: GlassesConnection, log: PlaceLog) {
    scope.launch {
        connection.state.map { it.device?.camera }.distinctUntilChanged().collect { camera ->
            if (camera != CAMERA_PHOTO_STARTED && camera != CAMERA_VIDEO_STARTED) return@collect
            val at = System.currentTimeMillis()
            here(context)?.let { log.record(Place(at, it.latitude, it.longitude, it.accuracy)) }
        }
    }
}

/** The phone's position: the last known one if it is fresh, otherwise a new fix, within 20 seconds. */
@SuppressLint("MissingPermission")
private suspend fun here(context: Context): Location? {
    if (!context.allows(Manifest.permission.ACCESS_FINE_LOCATION) && !context.allows(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = when {
        manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.NETWORK_PROVIDER
    }
    val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    if (last != null && System.currentTimeMillis() - last.time < FRESH_MS && last.accuracy < 100f) return last
    return withTimeoutOrNull(FIX_TIMEOUT_MS) {
        suspendCancellableCoroutine { done ->
            val cancel = CancellationSignal()
            done.invokeOnCancellation { cancel.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location -> if (done.isActive) done.resume(location) }
            }.onFailure { if (done.isActive) done.resume(null) }
        }
    } ?: last
}

/** Place names from the platform's geocoder, kept for as long as the app runs. */
object PlaceNames {
    private val names = ConcurrentHashMap<Long, String>()

    private fun key(lat: Double, lon: Double) = ((lat * 1000).toLong() shl 32) xor (lon * 1000).toLong()

    fun cached(lat: Double, lon: Double): String? = names[key(lat, lon)]

    /** The town, or the nearest named thing to it. */
    suspend fun of(context: Context, lat: Double, lon: Double): String? {
        cached(lat, lon)?.let { return it }
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { done ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (done.isActive) done.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (done.isActive) done.resume(null) }
                    })
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull() }
        }
        val name = address?.run { locality ?: subAdminArea ?: adminArea ?: featureName } ?: return null
        names[key(lat, lon)] = name
        return name
    }
}

private const val FRESH_MS = 60_000L
private const val FIX_TIMEOUT_MS = 20_000L
