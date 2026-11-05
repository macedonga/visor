package com.macedonga.visor.core.session

import com.macedonga.visor.core.transport.ConnectFailure

/** What the session tells the rest of the app. */
sealed interface SessionEvent

/** Where the link is. */
enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    LINK_SETUP,
    TRUST,
    READY,
    LOST,
}

/** The link moved. [reason] is filled in on [LinkState.LOST] and empty otherwise. */
data class LinkStateChanged(val state: LinkState, val reason: String = "") : SessionEvent

/** One message from a service, undecoded. */
class ServiceData(
    val serviceId: Int,
    val messageType: Int,
    val payload: ByteArray,
) : SessionEvent {

    override fun toString(): String =
        "ServiceData(service=$serviceId, type=0x%04x, %d bytes)".format(messageType, payload.size)
}

/** Something went wrong and the link did not necessarily end. */
data class ProtocolError(val detail: String) : SessionEvent

/** A DataX status frame other than svc36's 0x1000: [errorId] from [serviceId] on [channelId]. All but OK (0) are also worded as a [ProtocolError] first. */
data class DataXError(val serviceId: Int, val channelId: Int, val errorId: Int) : SessionEvent

/** A connect attempt failed, and [failure] says what a person could do about it. */
data class ConnectFailed(val failure: ConnectFailure) : SessionEvent
