package com.macedonga.visor.core.session

import com.macedonga.visor.core.transport.ConnectFailure

/** What the session tells the rest of the app. */
sealed interface SessionEvent

/** Where the link is. Ported from `events.py`'s `LinkState`. */
enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    LINK_SETUP,
    TRUST,
    READY,
    LOST,
}

/** The link moved. [reason] is filled in on [LinkState.LOST] and empty otherwise. */
class LinkStateChanged(val state: LinkState, val reason: String = "") : SessionEvent {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkStateChanged) return false
        return state == other.state && reason == other.reason
    }

    override fun hashCode(): Int = 31 * state.hashCode() + reason.hashCode()

    override fun toString(): String = "LinkStateChanged($state, '$reason')"
}

/** One message from a service, undecoded. */
class ServiceData(
    val serviceId: Int,
    val messageType: Int,
    val payload: ByteArray,
) : SessionEvent {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServiceData) return false
        return serviceId == other.serviceId &&
            messageType == other.messageType &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = serviceId
        result = 31 * result + messageType
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ServiceData(service=$serviceId, type=0x%04x, %d bytes)".format(messageType, payload.size)
}

/** Something went wrong and the link did not necessarily end. */
class ProtocolError(val detail: String, val frame: ByteArray = ByteArray(0)) : SessionEvent {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolError) return false
        return detail == other.detail && frame.contentEquals(other.frame)
    }

    override fun hashCode(): Int = 31 * detail.hashCode() + frame.contentHashCode()

    override fun toString(): String = "ProtocolError($detail)"
}

/** A DataX error frame, published after the [ProtocolError] that words it: [errorId] from [serviceId] on [channelId]. */
data class DataXError(val serviceId: Int, val channelId: Int, val errorId: Int) : SessionEvent

/** One SDU, as it went over the wire. */
class WireFrame(val outbound: Boolean, val data: ByteArray) : SessionEvent {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return outbound == other.outbound && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * outbound.hashCode() + data.contentHashCode()

    override fun toString(): String =
        "WireFrame(${if (outbound) "tx" else "rx"}, ${data.size} bytes)"
}

/** A connect attempt failed, and [failure] says what a person could do about it. */
class ConnectFailed(val failure: ConnectFailure) : SessionEvent {

    override fun equals(other: Any?): Boolean =
        this === other || (other is ConnectFailed && failure == other.failure)

    override fun hashCode(): Int = failure.hashCode()

    override fun toString(): String = "ConnectFailed($failure)"
}
