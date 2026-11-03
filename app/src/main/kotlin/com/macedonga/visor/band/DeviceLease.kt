package com.macedonga.visor.band

import com.macedonga.visor.core.identity.keyHintMatches
import com.macedonga.visor.core.protocol.NotifyLease
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * svc150, conn_device_lease, as Meta's DeviceLeaseService answers it: the Display names its band by key hint, the phone
 * says OK when it owns that band and steps back from it until the channel closes, BadRequest when it does not.
 */
@Singleton
class DeviceLease @Inject constructor(
    private val bands: Bands,
    private val link: BandLink,
    private val log: EventLog,
) : Responder {

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val info = message.info
        val reply = DataXConnection.mirror(info.channelId)
        if (info.flags.isClosed && message.payload.isEmpty()) {
            if (link.state.value.leased) log.add("Band lease: the Display let go")
            link.leased(false)
            return
        }
        if (info.messageType != NotifyLease.MESSAGE_TYPE) {
            connection.sendStatus(reply, NotifyLease.UNKNOWN_TYPE)
            return
        }
        val lease = runCatching { NotifyLease.decode(message.payload) }.getOrNull()
        val band = lease?.let { l -> bands.all.value.firstOrNull { b -> b.publicKey?.let { keyHintMatches(l.keyHint, it) } == true } }
        if (lease == null || band == null) {
            val hint = lease?.keyHint?.joinToString("") { "%02x".format(it) }
            log.add("Band lease: no band here for hint $hint (${bands.all.value.size} known)")
            connection.sendStatus(reply, NotifyLease.BAD_REQUEST)
            return
        }
        connection.sendStatus(reply, NotifyLease.OK)
        log.add("Band lease: the Display holds ${band.serial} (type ${lease.type})")
        if (!lease.relay) link.leased(true)
    }
}
