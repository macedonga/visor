package com.macedonga.visor.core.transport

import java.io.IOException

/** The byte pipe under AirShield. */
interface Transport {

    /** Open the link. Throws [TransportConnectException] with a reason if it will not open. */
    suspend fun connect()

    /** Send one SDU. */
    suspend fun send(frame: ByteArray)

    /** Return exactly one SDU. Blocks until a whole one arrives. */
    suspend fun receive(): ByteArray

    /** Close the link, and end any read waiting on it. Safe to call twice. */
    fun close()

    /** The handshake is done: a leading bit 7 now starts a COMMAND frame, not a bare DataX message. */
    fun handshakeDone() {}
}

/** The peer closed the link, or this end did. */
class TransportClosedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** A leading byte that belongs to neither framing on this link. */
class TransportUnframeableException(val leadingByte: Int) :
    IOException("unframeable leading byte 0x%02x".format(leadingByte))

/** Why a link would not open. */
enum class ConnectFailure {
    /** No Bluetooth on this phone at all. */
    BLUETOOTH_UNSUPPORTED,

    /** There is a radio and it is turned off. */
    BLUETOOTH_OFF,

    /** The app has not been given the Bluetooth permission. */
    PERMISSION_REFUSED,

    /** The address is not an address. A bug or a bad config, never the user's doing. */
    BAD_ADDRESS,

    /** The glasses are not bonded to this phone, so RFCOMM will not open. */
    NOT_PAIRED,

    /** Everything was in place and nothing answered. Asleep, in the case, out of range. */
    NO_ANSWER,
}

/** The link would not open, and [failure] says what a person could do about it. */
class TransportConnectException(
    val failure: ConnectFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
