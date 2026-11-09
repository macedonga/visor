package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc51: where the phone is, once. The Display's browser asks it (USER_LOCATION_REQUEST, type 30, empty) before a web
 * app's geolocation, to learn whether the phone may share a location at all, and takes LOCATION_PERMISSIONS_DISABLED
 * or GOOGLE_LOCATION_ACCURACY_DISABLED as a refusal. The answer, type 1, is NavigationProto UserLocationResponse
 * {1 GeoCoordinate {1 lat, 2 lng, 3 bearing}, 2 time ms, 3 error, 4 accuracy m, 5 heading}.
 */
@Singleton
class UserLocation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: EventLog,
) : Responder {

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        if (message.info.messageType != MT_REQUEST) {
            log.add("User location: type ${message.info.messageType} not served")
            return
        }
        val reply = DataXConnection.mirror(message.info.channelId)
        runCatching { connection.send(reply, answer(), messageType = MT_RESPONSE) }
    }

    private suspend fun answer(): ByteArray {
        val manager = context.getSystemService(LocationManager::class.java)
        val allowed = context.allows(Manifest.permission.ACCESS_FINE_LOCATION) || context.allows(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (manager == null || !allowed) return error(LOCATION_PERMISSIONS_DISABLED)
        if (!manager.isLocationEnabled) return error(GOOGLE_LOCATION_ACCURACY_DISABLED)
        val fix = current(manager) ?: return error(OTHER_ERROR)
        var coordinate = Wire.fieldDouble(1, fix.latitude) + Wire.fieldDouble(2, fix.longitude)
        if (fix.hasBearing()) coordinate += Wire.fieldFloat(3, fix.bearing)
        var body = Wire.fieldBytes(1, coordinate) + Wire.fieldVarint(2, fix.time) + Wire.fieldVarint(3, USER_LOCATION_SUCCESS)
        if (fix.hasAccuracy()) body += Wire.fieldFloat(4, fix.accuracy)
        if (fix.hasBearing()) body += Wire.fieldFloat(5, fix.bearing)
        return body
    }

    // A fix no older than a minute, or a fresh one within a few seconds.
    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager): Location? {
        val providers = PROVIDERS.filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
        val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < FRESH_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (recent != null) return recent
        val provider = providers.firstOrNull() ?: return null
        return withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine { done ->
                val signal = CancellationSignal()
                done.invokeOnCancellation { signal.cancel() }
                runCatching { manager.getCurrentLocation(provider, signal, context.mainExecutor) { done.resume(it) } }
                    .onFailure { done.resume(null) }
            }
        }
    }

    private fun error(code: Long) = Wire.fieldVarint(3, code)

    companion object {
        const val SVC = 51
        private const val MT_REQUEST = 30
        private const val MT_RESPONSE = 1
        private const val USER_LOCATION_SUCCESS = 0L
        private const val LOCATION_PERMISSIONS_DISABLED = 1L
        private const val GOOGLE_LOCATION_ACCURACY_DISABLED = 2L
        private const val OTHER_ERROR = 3L
        private const val FRESH_MS = 60_000L
        private const val WAIT_MS = 5_000L
        private val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }
}
