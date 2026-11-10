package com.macedonga.visor.band

import com.macedonga.visor.core.identity.keyHintMatches
import com.macedonga.visor.core.protocol.NotifyLease
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** svc150, conn_device_lease: OK when the Display names a band set up here by its key hint, BadRequest otherwise. */
@Singleton
class DeviceLease @Inject constructor(
    private val bands: Bands,
    private val log: EventLog,
) : Responder {
    private val _holder = MutableStateFlow<String?>(null)

    /** The serial of the band the Display holds a lease on, null while it holds none. */
    val holder: StateFlow<String?> = _holder.asStateFlow()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val info = message.info
        val reply = DataXConnection.mirror(info.channelId)
        if (info.flags.isClosed && message.payload.isEmpty()) {
            if (_holder.value != null) log.add("Band lease: the Display let go")
            _holder.value = null
            return
        }
        if (info.messageType != NotifyLease.MESSAGE_TYPE) {
            connection.sendStatus(reply, NotifyLease.UNKNOWN_TYPE)
            return
        }
        val lease = runCatching { NotifyLease.decode(message.payload) }.getOrNull()
        val band = lease?.let { l -> bands.all.value.firstOrNull { b -> b.publicKey?.let { keyHintMatches(l.keyHint, it) } == true } }
        if (lease == null || band == null) {
            val hint = lease?.keyHint?.toHexString()
            log.add("Band lease: no band here for hint $hint (${bands.all.value.size} known)")
            connection.sendStatus(reply, NotifyLease.BAD_REQUEST)
            return
        }
        connection.sendStatus(reply, NotifyLease.OK)
        log.add("Band lease: the Display holds ${band.serial} (type ${lease.type})")
        if (!lease.relay) _holder.value = band.serial
    }
}
