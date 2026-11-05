package com.macedonga.visor.connection

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.text.format.DateFormat
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** svc11020, maps_support: every request gets an empty answer with result 0, so the Display never waits. */
@Singleton
class MapsSupport @Inject constructor() : Responder {
    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val type = message.info.messageType
        // Requests are the even types 2..18, each answered on type + 1; 0 and 1 are notes.
        if (type !in 2..18 || type % 2 != 0) return
        // Route details (4 -> 5) have no decoded reply, so theirs is empty.
        val payload = if (type == REQUEST_ROUTE_DETAILS) ByteArray(0) else EMPTY_RESULT
        connection.send(DataXConnection.mirror(message.info.channelId), payload, messageType = type + 1)
    }

    companion object {
        const val SVC = 11020
        private val EMPTY_RESULT = Wire.fieldVarint(1, 0)
        private const val REQUEST_ROUTE_DETAILS = 4
    }
}

/** svc87, the Display's maps settings: Visor keeps mute, map view and nav2; units and the clock follow the phone. */
@Singleton
class MapsStore @Inject constructor(@ApplicationContext private val context: Context) : Responder {
    private val prefs = context.getSharedPreferences("maps_store", Context.MODE_PRIVATE)

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val type = message.info.messageType
        when (type) {
            REQUEST -> {}
            SET -> keep(message.payload)
            else -> return
        }
        connection.send(DataXConnection.mirror(message.info.channelId), values(all = type == REQUEST), messageType = type)
    }

    private fun keep(payload: ByteArray) {
        val v = runCatching { Wire.parse(Wire.parse(payload).bytes(1)) }.getOrNull() ?: return
        val edit = prefs.edit()
        when (v.varint(MUTED)) {
            MUTE_ON -> edit.putBoolean(KEY_MUTED, true)
            MUTE_OFF -> edit.putBoolean(KEY_MUTED, false)
        }
        v.varint(MAP_MODE).takeIf { it == PERSPECTIVE || it == NORTH_UP }?.let { edit.putLong(KEY_MAP_MODE, it) }
        if (v.has(FEATURES)) edit.putBoolean(KEY_NAV2, Wire.parse(v.bytes(FEATURES)).varint(1) != 0L)
        edit.apply()
    }

    // In field order. Routing is always REAL: SIMULATED would replay a fake trip.
    private fun values(all: Boolean): ByteArray {
        val metric = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.SI
        var b = Wire.fieldVarint(MUTED, if (prefs.getBoolean(KEY_MUTED, false)) MUTE_ON else MUTE_OFF) +
            Wire.fieldVarint(UNITS, if (metric) UNITS_METRIC else UNITS_IMPERIAL) +
            Wire.fieldVarint(MAP_MODE, prefs.getLong(KEY_MAP_MODE, PERSPECTIVE))
        if (all) {
            b += Wire.fieldVarint(HOME_MAP_MODE, PERSPECTIVE) +
                Wire.fieldVarint(ROUTING_MODE, ROUTING_REAL) +
                Wire.fieldVarint(TIME_FORMAT, if (DateFormat.is24HourFormat(context)) CLOCK_24H else CLOCK_12H)
        }
        b += Wire.fieldBytes(FEATURES, Wire.fieldVarint(1, if (prefs.getBoolean(KEY_NAV2, true)) 1L else 0L))
        if (all) b += Wire.fieldVarint(MANEUVER_DISPLAY, ENABLED)
        return b
    }

    companion object {
        const val SVC = 87
        private const val REQUEST = 0
        private const val SET = 1
        private const val MUTED = 1
        private const val UNITS = 2
        private const val MAP_MODE = 3
        private const val HOME_MAP_MODE = 4
        private const val ROUTING_MODE = 5
        private const val TIME_FORMAT = 6
        private const val FEATURES = 7
        private const val MANEUVER_DISPLAY = 8
        private const val MUTE_ON = 1L
        private const val MUTE_OFF = 2L
        private const val UNITS_IMPERIAL = 1L
        private const val UNITS_METRIC = 2L
        private const val CLOCK_12H = 1L
        private const val CLOCK_24H = 2L
        private const val PERSPECTIVE = 1L
        private const val NORTH_UP = 2L
        private const val ROUTING_REAL = 1L
        private const val ENABLED = 2L
        private const val KEY_MUTED = "muted"
        private const val KEY_MAP_MODE = "map_mode"
        private const val KEY_NAV2 = "nav2"
    }
}
