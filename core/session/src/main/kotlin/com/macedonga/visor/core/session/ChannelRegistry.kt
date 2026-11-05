package com.macedonga.visor.core.session

/** Which DataX channel is talking to which service, right now. */
class ChannelRegistry {

    private val local = HashMap<Int, Int>()
    private val peer = HashMap<Int, Int>()
    private var nextChannel = FIRST_LOCAL_CHANNEL

    /** Allocate the next channel id for a channel this side opens, with the originator bit. */
    fun nextLocalChannel(): Int = synchronized(this) {
        val channel = nextChannel
        nextChannel += 1
        DataXConnection.ORIGINATOR_BIT or channel
    }

    /** Record the service of a channel this side opened. */
    fun bindLocal(channelId: Int, serviceId: Int) = synchronized(this) {
        local[key(channelId)] = serviceId
        Unit
    }

    /** The service of [channelId], a channel this side opened, or 0 when it is not open. */
    fun localService(channelId: Int): Int = synchronized(this) {
        local[key(channelId)] ?: 0
    }

    /** Record the service named on an inbound frame, whichever side opened the channel. */
    fun bindInbound(channelId: Int, serviceId: Int) = synchronized(this) {
        mapForInbound(channelId)[key(channelId)] = serviceId
        Unit
    }

    /** The service on [channelId], or 0 when nothing has named one. */
    fun serviceForInbound(channelId: Int): Int = synchronized(this) {
        mapForInbound(channelId)[key(channelId)] ?: 0
    }

    /** Forget [channelId], and return the service it had, or 0. */
    fun closeInbound(channelId: Int): Int = synchronized(this) {
        mapForInbound(channelId).remove(key(channelId)) ?: 0
    }

    private fun mapForInbound(channelId: Int): HashMap<Int, Int> =
        if (DataXConnection.hasOriginatorBit(channelId)) peer else local

    private fun key(channelId: Int) = channelId and (DataXConnection.ORIGINATOR_BIT - 1)

    private companion object {
        // Not 1: 0x8001 is the link setup channel.
        const val FIRST_LOCAL_CHANNEL = 0x0002
    }
}
