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

/**
 * svc11020, maps_support. The glasses ask for saved and recent places, survey and feature state, third-party routes
 * and Tessa events; each request gets an empty answer with result 0, so the Display never waits on silence. Location
 * signals (0) and trip feedback (1) are notes with no answer.
 */
@Singleton
class MapsSupport @Inject constructor() : Responder {
    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val reply = REPLIES[message.info.messageType] ?: return
        // Route details (4 -> 5) have no decoded reply, so theirs is empty.
        val payload = if (message.info.messageType == REQUEST_ROUTE_DETAILS) ByteArray(0) else EMPTY_RESULT
        connection.send(DataXConnection.mirror(message.info.channelId), payload, messageType = reply)
    }

    companion object {
        const val SVC = 11020
        private val EMPTY_RESULT = Wire.fieldVarint(1, 0)
        private const val REQUEST_ROUTE_DETAILS = 4

        /** Request type to the reply type the glasses wait for: the phone's table, reply = request + 1. */
        private val REPLIES = mapOf(
            2 to 3,
            4 to 5,
            6 to 7,
            8 to 9,
            10 to 11,
            12 to 13,
            14 to 15,
            16 to 17,
            18 to 19,
        )
    }
}

/**
 * svc87, the Display's maps settings, MapsPersistentStoreValues: each value an enum where 0 leaves it unchanged. Maps,
 * SystemUI and setup each ask with an empty type 0 and get every value back. A type 1 sets values; Visor keeps mute,
 * the map view and the nav2 flag, and answers with mute, units, map view and nav2. Units and the clock follow the phone.
 */
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
            1L -> edit.putBoolean(KEY_MUTED, true)
            2L -> edit.putBoolean(KEY_MUTED, false)
        }
        v.varint(MAP_MODE).takeIf { it == PERSPECTIVE || it == NORTH_UP }?.let { edit.putLong(KEY_MAP_MODE, it) }
        if (v.has(FEATURES)) edit.putBoolean(KEY_NAV2, Wire.parse(v.bytes(FEATURES)).varint(1) != 0L)
        edit.apply()
    }

    // In field order. Routing is always REAL: SIMULATED would replay a fake trip.
    private fun values(all: Boolean): ByteArray {
        val metric = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.SI
        var b = Wire.fieldVarint(MUTED, if (prefs.getBoolean(KEY_MUTED, false)) 1L else 2L) +
            Wire.fieldVarint(UNITS, if (metric) 2L else 1L) +
            Wire.fieldVarint(MAP_MODE, prefs.getLong(KEY_MAP_MODE, PERSPECTIVE))
        if (all) {
            b += Wire.fieldVarint(HOME_MAP_MODE, PERSPECTIVE) +
                Wire.fieldVarint(ROUTING_MODE, ROUTING_REAL) +
                Wire.fieldVarint(TIME_FORMAT, if (DateFormat.is24HourFormat(context)) 2L else 1L)
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
        private const val PERSPECTIVE = 1L
        private const val NORTH_UP = 2L
        private const val ROUTING_REAL = 1L
        private const val ENABLED = 2L
        private const val KEY_MUTED = "muted"
        private const val KEY_MAP_MODE = "map_mode"
        private const val KEY_NAV2 = "nav2"
    }
}
