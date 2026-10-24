package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
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
 * svc87 (persistent store) and svc11004 (constellation): accepted and never answered, since their schemas are native
 * and undecoded. The first message of each is logged once.
 */
@Singleton
class MapsQuiet @Inject constructor(private val log: Provider<EventLog>) {
    private val logged = ConcurrentHashMap.newKeySet<Int>()

    fun responder(svc: Int): Responder = Responder { _, _ ->
        if (logged.add(svc)) log.get().add("Maps: service $svc opened, not served")
    }

    companion object {
        const val SVC_PERSISTENT_STORE = 87
        const val SVC_CONSTELLATION = 11004
    }
}
