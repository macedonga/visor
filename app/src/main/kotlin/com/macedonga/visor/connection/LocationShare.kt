package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** svc41102, locationservice: the phone's fixes (type 4) from the glasses' request (3) until stop (5), a close or the link's end. */
@Singleton
class LocationShare @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: RequestLog,
    private val glasses: Provider<GlassesConnection>,
    log: EventLog,
) : Responder {
    private val scope = responderScope(log, "Location")
    private val listening = ConcurrentHashMap<Int, Subscription>()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        if (message.info.flags.isClosed) {
            listening.remove(channel)?.stop()
            return
        }
        when (message.info.messageType) {
            MT_REQUEST -> {
                val display = isDisplayBuild(glasses.get().state.value.info?.build)
                // The Display asks again on the same channel when only the accuracy changes; the new request replaces the old.
                if (!display && listening.containsKey(channel)) return
                val precise = runCatching { Wire.parse(message.payload).varint(3) }.getOrDefault(0L) == ACCURACY_BEST
                val next = Subscription(connection, channel, precise, display)
                listening.put(channel, next)?.stop()
                next.start()
            }
            MT_STOP -> listening.remove(channel)?.stop()
            MT_REGISTER_PROVIDER, MT_KEEPALIVE -> Unit
        }
    }

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        listening.remove(channelId)?.stop()
    }

    private inner class Subscription(
        private val mux: DataXConnection,
        private val channel: Int,
        private val precise: Boolean,
        private val display: Boolean,
    ) : LocationListener {
        private val reply = DataXConnection.mirror(channel)
        private val manager = context.getSystemService(LocationManager::class.java)
        private val logId = requests.start("location://${if (precise) "precise" else "approximate"}", RequestSource.LOCATION)
        private var fixes = 0L
        private var linkWatch: Job? = null

        @Volatile
        private var finished = false

        @SuppressLint("MissingPermission")
        fun start() {
            // Channel ids repeat across links, so a subscription ends with the link it came on.
            linkWatch = scope.launch {
                mux.awaitGone()
                if (listening.remove(channel, this@Subscription)) stop()
            }
            val permission = if (precise) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
            if (manager == null || !context.allows(permission)) {
                fail(STATUS_NO_PERMISSION, "no location permission")
                return
            }
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .firstOrNull { p -> (p != LocationManager.GPS_PROVIDER || precise) && manager.allProviders.contains(p) && manager.isProviderEnabled(p) }
            if (provider == null) {
                fail(STATUS_OTHER_ERROR, "no location provider enabled")
                return
            }
            try {
                manager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
                manager.requestLocationUpdates(provider, INTERVAL_MS, 0f, this, Looper.getMainLooper())
            } catch (error: RuntimeException) {
                fail(STATUS_OTHER_ERROR, "location updates refused: ${error.message}")
            }
        }

        fun stop() {
            linkWatch?.cancel()
            manager?.removeUpdates(this)
            if (!finished) requests.finish(logId, "stopped after $fixes fixes", failed = false)
        }

        private fun fail(status: Long, why: String) {
            send(Wire.fieldVarint(1, status))
            requests.finish(logId, why, failed = true)
            finished = true
        }

        override fun onLocationChanged(location: Location) {
            fixes++
            send(Wire.fieldVarint(1, STATUS_OK) + Wire.fieldBytes(2, encode(location, display)))
        }

        private fun send(body: ByteArray) {
            scope.launch { runCatching { mux.send(reply, body, messageType = MT_RESULT) } }
        }
    }

    /** Location: 1 latitude, 2 longitude, 3 accuracy m, 5 time ms, 8 altitude, 9 its accuracy, 10 bearing, 11 its accuracy, 12 speed, 13 its accuracy. */
    private fun encode(l: Location, display: Boolean): ByteArray {
        var b = Wire.fieldDouble(1, l.latitude) + Wire.fieldDouble(2, l.longitude) + Wire.fieldFloat(3, l.accuracy) + Wire.fieldVarint(5, l.time)
        if (l.hasAltitude()) b += Wire.fieldDouble(8, l.altitude)
        if (l.hasVerticalAccuracy()) b += Wire.fieldFloat(9, l.verticalAccuracyMeters)
        // The Display takes an unknown bearing or speed as -1.
        fun motion(field: Int, known: Boolean, value: Float) {
            if (known) b += Wire.fieldFloat(field, value) else if (display) b += Wire.fieldFloat(field, UNKNOWN)
        }
        motion(10, l.hasBearing(), l.bearing)
        motion(11, l.hasBearingAccuracy(), l.bearingAccuracyDegrees)
        motion(12, l.hasSpeed(), l.speed)
        motion(13, l.hasSpeedAccuracy(), l.speedAccuracyMetersPerSecond)
        return b
    }

    companion object {
        const val SVC = 41102
        private const val MT_REQUEST = 3
        private const val MT_RESULT = 4
        private const val MT_STOP = 5
        private const val MT_KEEPALIVE = 18
        private const val MT_REGISTER_PROVIDER = 26
        private const val UNKNOWN = -1f
        private const val ACCURACY_BEST = 0L
        private const val STATUS_OK = 0L
        private const val STATUS_NO_PERMISSION = 1L
        // LocationResult ERROR_OTHER: no provider can answer.
        private const val STATUS_OTHER_ERROR = 2L
        private const val INTERVAL_MS = 2_000L
    }
}
