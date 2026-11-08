package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.AuthenticationException
import com.macedonga.visor.core.protocol.Ecdh
import com.macedonga.visor.core.protocol.EnableEncryption
import com.macedonga.visor.core.protocol.EndLinkSetup
import com.macedonga.visor.core.protocol.FrameCrypto
import com.macedonga.visor.core.protocol.FrameType
import com.macedonga.visor.core.protocol.Framing
import com.macedonga.visor.core.protocol.HandshakeBinding
import com.macedonga.visor.core.protocol.Header
import com.macedonga.visor.core.protocol.Kdf
import com.macedonga.visor.core.protocol.LinkSetupMsgType
import com.macedonga.visor.core.protocol.LinkUuid
import com.macedonga.visor.core.protocol.RequestEncryption
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.transport.Transport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** RequestEncryption and EndLinkSetup ride the secure variant of channel 1, bit 15 set. */
internal const val LINK_SETUP_CHANNEL = 0x8001

/** The handshake did not complete. */
class HandshakeException(message: String) : Exception(message)

/**
 * SecureLink: the AirShield handshake plus the per-direction frame cipher. The cipher belongs to the session, not to
 * the transport: after a link switch both sides go on with the first link's cipher on the other link.
 */
class SecureLink(private val transport: Transport) {

    private val random = SecureRandom()

    private val txMutex = Mutex()

    private var txCrypto: FrameCrypto? = null
    private var rxCrypto: FrameCrypto? = null

    /** DATA frames that arrived during the handshake, handled after [open] returns. */
    private val pending = ArrayDeque<ByteArray>()

    /** Channel binding material for the svc36 exchange. Null until [open] succeeds. */
    var binding: HandshakeBinding? = null
        private set

    /** The code of the last skipped COMMAND frame. */
    var lastCommand: Int? = null
        private set

    /** What the glasses sent in the handshake, one line. Empty until [open] succeeds. */
    var handshake: String = ""
        private set

    /** DATA frames [open] queued; [receive] unseals these first. */
    private var handshakeQueued = 0

    /** DATA frames unsealed so far, numbered from 0. */
    private var unsealed = 0

    /** Run the AirShield handshake and install the per-direction frame ciphers. */
    suspend fun open() {
        val keyPair = Ecdh.generateEphemeral()
        val myPublic = keyPair.publicKey64
        val myChallenge = bytes(16)
        val mySeed = bytes(32)
        val myIv = bytes(16)
        // The per-direction random preamble seq, unsigned 32-bit.
        val mySeq = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        sendLinkSetup(
            LINK_SETUP_CHANNEL,
            LinkSetupMsgType.REQUEST_ENCRYPTION,
            RequestEncryption(publicKey = myPublic, challenge = myChallenge, supportedParameters = OUR_PARAMETERS).encode(),
        )
        // Send our EnableEncryption IMMEDIATELY.
        sendLinkSetup(
            ENABLE_CHANNEL,
            LinkSetupMsgType.ENABLE_ENCRYPTION,
            EnableEncryption(publicKey = myPublic, seed = mySeed, iv = myIv, base = mySeq, parameters = OUR_PARAMETERS).encode(),
        )

        var peerRequest: RequestEncryption? = null
        var peerEnable: EnableEncryption? = null
        for (attempt in 0 until HANDSHAKE_READS) {
            val sdu = transport.receive()
            if (sdu.isNotEmpty() &&
                (sdu[0].toInt() and Framing.TYPE_MASK) == FrameType.DATA.value
            ) {
                // An early encrypted frame. Handle it after open() returns.
                pending.addLast(sdu)
                continue
            }
            val info = Header.parse(sdu)
            val payload = sdu.copyOfRange(info.headerLen, sdu.size)
            when (info.messageType) {
                LinkSetupMsgType.REQUEST_ENCRYPTION.value ->
                    peerRequest = RequestEncryption.decode(payload)

                LinkSetupMsgType.ENABLE_ENCRYPTION.value ->
                    peerEnable = EnableEncryption.decode(payload)
            }
            if (peerRequest != null && peerEnable != null) break
        }

        handshakeQueued = pending.size
        val request = peerRequest
        val enable = peerEnable
        if (request == null || enable == null) {
            throw HandshakeException(
                "handshake: missing RequestEncryption / EnableEncryption from peer",
            )
        }

        val secret = Ecdh.computeSharedSecret(keyPair.privateKey, enable.publicKey)

        binding = HandshakeBinding(
            glChallenge = request.challenge,
            mySeed = mySeed,
            glReqPub = request.publicKey,
            myPub = myPublic,
        )

        // What both ends support is what the link uses: the band offers 27, no Multikey.
        val multikey = (OUR_PARAMETERS and request.supportedParameters and enable.parameters and Kdf.MULTIKEY) != 0
        // Keep exactly as measured: TX takes the peer's RequestEncryption challenge as nonce and
        // our seed.
        // The quirks of each peer message pick its direction's HKDF info and MAC word, as libairshield does (#80).
        val rxKeys = Kdf.deriveDirectionKeys(secret, myChallenge, enable.seed, enable.iv, multikey = multikey, quirks = enable.quirks)
        val txKeys = Kdf.deriveDirectionKeys(secret, request.challenge, mySeed, myIv, multikey = multikey, quirks = request.quirks)
        rxCrypto = FrameCrypto(rxKeys, enable.base.toInt(), quirks = enable.quirks)
        txCrypto = FrameCrypto(txKeys, mySeq.toInt(), quirks = request.quirks)
        handshake = describe(request, enable, multikey)

        // EndLinkSetup is NOT sent here.
    }

    private fun describe(request: RequestEncryption, enable: EnableEncryption, multikey: Boolean): String {
        val requestParameters = if (request.hasSupportedParameters) request.supportedParameters.toString() else "absent"
        val enableParameters = if (enable.hasParameters) enable.parameters.toString() else "absent"
        return "peer RequestEncryption supportedParameters=$requestParameters ellipticCurve=${request.ellipticCurve} " +
            "quirks=${request.quirks} airshieldVersion=${request.airshieldVersion} keyHints=${request.keyHint.size}; " +
            "peer EnableEncryption parameters=$enableParameters quirks=${enable.quirks} base=${enable.base} " +
            "phasedLinkSetupSupported=${enable.phasedLinkSetupSupported} " +
            "supportedLinkSetupServices=${enable.supportedLinkSetupServices} " +
            "linkSwitchVersionSupported=${enable.linkSwitchVersionSupported}; " +
            "publicKeysEqual=${request.publicKey.contentEquals(enable.publicKey)} multikey=$multikey " +
            "queuedDuringHandshake=$handshakeQueued"
    }

    /** The link uuid this side's EndLinkSetup gave the link: a switch to it names it. */
    val linkUuid: ByteArray = LinkUuid.random()

    /** Send EndLinkSetup as the first encrypted DATA frame: as the main link, or as one kept ready for a switch. */
    suspend fun finishLinkSetup(main: Boolean = true) {
        val state = if (main) EndLinkSetup.MAIN else EndLinkSetup.READY
        val body = EndLinkSetup.encode(state = state, streamUuid = LinkUuid.random(), linkUuid = linkUuid)
        val sdu = Header.build(
            channelId = LINK_SETUP_CHANNEL,
            dataLen = body.size,
            serviceId = null,
            messageType = LinkSetupMsgType.END_LINK_SETUP.value,
        ) + body
        sealAndSend(sdu)
    }

    suspend fun send(plaintext: ByteArray) = sealAndSend(plaintext)

    // Where sealed frames go: this link's transport, or another link's after a switch.
    @Volatile
    private var txTransport: Transport = transport

    /** Send every later frame on [to], sealed under this link's cipher. */
    fun moveTx(to: Transport) {
        txTransport = to
    }

    /** The next frame on this link's own transport, those the handshake held back first. */
    suspend fun nextSdu(): ByteArray = pending.removeFirstOrNull() ?: transport.receive()

    /** The plaintext of [sdu] under this link's cipher, from whichever transport carried it; null for a COMMAND frame. */
    fun open(sdu: ByteArray): ByteArray? {
        val crypto = checkNotNull(rxCrypto) { "link not open" }
        val frame = Framing.unpack(sdu)
        if (frame.type != FrameType.DATA.value) {
            lastCommand = frame.body.firstOrNull()?.toInt()?.and(0xFF)
            return null
        }
        return unseal(crypto, frame.streamId, frame.body)
    }

    /** The next DATA frame's plaintext on this link's own transport. */
    suspend fun receive(): ByteArray {
        while (true) open(nextSdu())?.let { return it }
    }

    /** Unseal the next DATA frame; a failed check names the frame and whether the handshake queued it. */
    private fun unseal(crypto: FrameCrypto, stream: Int, body: ByteArray): ByteArray {
        val index = unsealed++
        try {
            return crypto.unseal(body)
        } catch (error: AuthenticationException) {
            val origin = if (index < handshakeQueued) "queued during the handshake" else "after the handshake"
            throw AuthenticationException("${error.message} (frame $index, stream $stream, $origin)").apply { initCause(error) }
        }
    }

    /** A link setup message: a DataX message sent directly as the SDU, no AirShield byte. */
    private suspend fun sendLinkSetup(
        channelId: Int,
        messageType: LinkSetupMsgType,
        protobuf: ByteArray,
    ) {
        // The serviceId tag goes on the first message of the exchange only, per the capture.
        val serviceId =
            if (messageType == LinkSetupMsgType.REQUEST_ENCRYPTION) ServiceId.LINK_SETUP.value
            else null
        val sdu = Header.build(
            channelId = channelId,
            dataLen = protobuf.size,
            serviceId = serviceId,
            messageType = messageType.value,
        ) + protobuf
        txMutex.withLock { transport.send(sdu) }
    }

    /**
     * Seal and send under one lock, and without a cancellation point between them. A message longer than one frame
     * runs on into the next frames, as the glasses send theirs; the lock keeps another message out of the middle.
     */
    private suspend fun sealAndSend(plaintext: ByteArray) {
        txMutex.withLock {
            val crypto = checkNotNull(txCrypto) { "link not open" }
            withContext(NonCancellable) {
                var at = 0
                do {
                    val end = minOf(plaintext.size, at + FRAME_PLAINTEXT)
                    // Framing.pack adds the 0x40 type byte: [0x40][8B tag][1B blockCount][ct].
                    txTransport.send(Framing.pack(FrameType.DATA, 0, crypto.seal(plaintext.copyOfRange(at, end))))
                    at = end
                } while (at < plaintext.size)
            }
        }
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private companion object {
        const val ENABLE_CHANNEL = 0x0001

        /** How many frames [open] will read while waiting for the peer's two messages. */
        const val HANDSHAKE_READS = 8

        /** The parameters this side's RequestEncryption and EnableEncryption announce. */
        const val OUR_PARAMETERS = 31

        /** The most one frame holds: 256 blocks, less the pad's one byte at least. */
        const val FRAME_PLAINTEXT = 256 * 16 - 1
    }
}