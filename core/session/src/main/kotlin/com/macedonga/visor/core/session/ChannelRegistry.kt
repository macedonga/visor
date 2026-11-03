package com.macedonga.visor.core.session

/** Which DataX channel is talking to which service, right now. */
class ChannelRegistry {

    private val local = HashMap<Int, Int>()
    private val peer = HashMap<Int, Int>()

    // The counter is plain, and `lifecycle.py`'s `_settle_channels` docstring claims it is not: it
    // says allocation happens "above the highest peer channel seen so far".
    private var nextChannel = FIRST_LOCAL_CHANNEL

    /** Allocate the next channel id for a channel this side opens, with the originator bit. */
    fun nextLocalChannel(): Int = synchronized(this) {
        val channel = nextChannel
        nextChannel += 1
        ORIGINATOR_BIT or channel
    }

    /** Record the service of a channel this side opened. */
    fun bindLocal(channelId: Int, serviceId: Int) = synchronized(this) {
        local[channelId and 0x7FFF] = serviceId
        Unit
    }

    /** The service of [channelId], a channel this side opened, or 0 when it is not open. */
    fun localService(channelId: Int): Int = synchronized(this) {
        local[channelId and 0x7FFF] ?: 0
    }

    /** Record the service named on an inbound frame, whichever side opened the channel. */
    fun bindInbound(channelId: Int, serviceId: Int) = synchronized(this) {
        mapForInbound(channelId)[channelId and 0x7FFF] = serviceId
        Unit
    }

    /** The service on [channelId], or 0 when nothing has named one. */
    fun serviceForInbound(channelId: Int): Int = synchronized(this) {
        mapForInbound(channelId)[channelId and 0x7FFF] ?: 0
    }

    /** Forget [channelId], and return the service it had, or 0. */
    fun closeInbound(channelId: Int): Int = synchronized(this) {
        mapForInbound(channelId).remove(channelId and 0x7FFF) ?: 0
    }

    private fun mapForInbound(channelId: Int): HashMap<Int, Int> =
        if ((channelId and ORIGINATOR_BIT) != 0) peer else local

    private companion object {
        const val ORIGINATOR_BIT = 0x8000

        // The counter starts at 2, not 1. Channel 0x8001 is the link setup channel, so
        // starting at 1 would hand it out as the first service channel and break the link.
        const val FIRST_LOCAL_CHANNEL = 0x0002
    }
}
