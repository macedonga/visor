package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.Ecdh
import com.macedonga.visor.core.protocol.EnableEncryption
import com.macedonga.visor.core.protocol.EphemeralKeyPair
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
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.transport.Transport
import java.security.SecureRandom
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a handshake's random material comes from. Injectable so a test can pin it. */
fun interface RandomSource {

    fun nextBytes(count: Int): ByteArray

    companion object {

        fun secure(): RandomSource {
            val random = SecureRandom()
            return RandomSource { count -> ByteArray(count).also(random::nextBytes) }
        }
    }
}

/** The handshake did not complete. Ported from `handshake.py`'s `ProtocolError`. */
class HandshakeException(message: String) : Exception(message)

/** SecureLink: the AirShield handshake plus the per-direction frame cipher. */
class SecureLink(
    private val transport: Transport,
    private val random: RandomSource = RandomSource.secure(),
    // DIVERGENCE: the reference's `new_uuid4` stamps `os.urandom(16)` by hand.
    private val newUuid: () -> ByteArray = LinkUuid::random,
    private val ephemeral: () -> EphemeralKeyPair = Ecdh::generateEphemeral,
) : PlaintextLink {

    // DIVERGENCE: the reference has no lock.
    private val txMutex = Mutex()

    private var txCrypto: FrameCrypto? = null
    private var rxCrypto: FrameCrypto? = null

    /** DATA frames that arrived during the handshake, handled after [open] returns. */
    private val pending = ArrayDeque<ByteArray>()

    /** Channel binding material for a future re-sign. Null until [open] succeeds. */
    var binding: HandshakeBinding? = null
        private set

    /** The code of the last skipped COMMAND frame. */
    var lastCommand: Int? = null
        private set

    /** Run the AirShield handshake and install the per-direction frame ciphers. */
    suspend fun open() {
        val keyPair = ephemeral()
        val myPublic = keyPair.publicKey64
        val myChallenge = random.nextBytes(16)
        val mySeed = random.nextBytes(32)
        val myIv = random.nextBytes(16)
        // The per-direction random preamble seq, little endian, as the reference's
        // `int.from_bytes(os.urandom(4), "little")`.
        val mySeq = random.nextBytes(4).let { bytes ->
            (bytes[0].toLong() and 0xFF) or
                ((bytes[1].toLong() and 0xFF) shl 8) or
                ((bytes[2].toLong() and 0xFF) shl 16) or
                ((bytes[3].toLong() and 0xFF) shl 24)
        }

        sendLinkSetup(
            LINK_SETUP_CHANNEL,
            LinkSetupMsgType.REQUEST_ENCRYPTION,
            RequestEncryption(publicKey = myPublic, challenge = myChallenge).encode(),
        )
        // Send our EnableEncryption IMMEDIATELY.
        sendLinkSetup(
            ENABLE_CHANNEL,
            LinkSetupMsgType.ENABLE_ENCRYPTION,
            EnableEncryption(publicKey = myPublic, seed = mySeed, iv = myIv, base = mySeq).encode(),
        )

        var peerRequest: RequestEncryption? = null
        var peerEnable: EnableEncryption? = null
        // The reference's `for _ in range(8)` with its `break`.
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

        val request = peerRequest
        val enable = peerEnable
        if (request == null || enable == null) {
            throw HandshakeException(
                "handshake: missing RequestEncryption / EnableEncryption from peer",
            )
        }

        val secret = Ecdh.computeSharedSecret(keyPair.privateKey, enable.publicKey)

        binding = HandshakeBinding(
            myChallenge = myChallenge,
            glChallenge = request.challenge,
            mySeed = mySeed,
            glSeed = enable.seed,
            myPub = myPublic,
            glPub = enable.publicKey,
            glReqPub = request.publicKey,
            ecdhSecret = secret,
        )

        // What both ends support is what the link uses: the band offers 27, no Multikey.
        val multikey = (OUR_PARAMETERS and request.supportedParameters and enable.parameters and Kdf.MULTIKEY) != 0
        // Keep exactly as measured: TX takes the peer's RequestEncryption challenge as nonce and
        // our seed.
        val rxKeys = Kdf.deriveDirectionKeys(secret, myChallenge, enable.seed, enable.iv, multikey = multikey)
        val txKeys = Kdf.deriveDirectionKeys(secret, request.challenge, mySeed, myIv, multikey = multikey)
        rxCrypto = FrameCrypto(rxKeys, initRxSeq = enable.base.toInt())
        txCrypto = FrameCrypto(txKeys, initTxSeq = mySeq.toInt())

        // EndLinkSetup is NOT sent here.
    }

    /** Send EndLinkSetup as the first encrypted DATA frame. */
    suspend fun finishLinkSetup(asMain: Int = 1) {
        checkNotNull(txCrypto) { "link not open" }
        val scopeUuid = newUuid()
        val leaseUuid = newUuid()
        check(LinkUuid.isValid(scopeUuid) && LinkUuid.isValid(leaseUuid)) {
            "an EndLinkSetup uuid would fail the firmware's v4 check, and the failure is silent"
        }
        val body = Wire.fieldVarint(1, asMain.toLong()) +
            Wire.fieldBytes(2, scopeUuid) +
            Wire.fieldBytes(3, leaseUuid)
        val sdu = Header.build(
            channelId = LINK_SETUP_CHANNEL,
            dataLen = body.size,
            serviceId = null,
            messageType = LinkSetupMsgType.END_LINK_SETUP.value,
        ) + body
        sealAndSend(sdu)
    }

    override suspend fun send(plaintext: ByteArray) {
        checkNotNull(txCrypto) { "link not open" }
        sealAndSend(plaintext)
    }

    /** The next DATA frame's plaintext. */
    override suspend fun receive(): ByteArray {
        val crypto = checkNotNull(rxCrypto) { "link not open" }
        while (true) {
            val sdu = pending.removeFirstOrNull() ?: transport.receive()
            val frame = Framing.unpack(sdu)
            if (frame.type == FrameType.DATA.value) {
                // verifyUnpad = false: the plaintext is a DataX message, possibly block
                // aligned with no pad byte, and the DataX layer trims by the header length.
                return crypto.unseal(frame.body, verifyUnpad = false)
            }
            lastCommand = frame.body.firstOrNull()?.toInt()?.and(0xFF)
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
                    transport.send(Framing.pack(FrameType.DATA, 0, crypto.seal(plaintext.copyOfRange(at, end))))
                    at = end
                } while (at < plaintext.size)
            }
        }
    }

    private companion object {
        /** RequestEncryption and EndLinkSetup ride the secure variant of channel 1, bit 15 set. */
        const val LINK_SETUP_CHANNEL = 0x8001
        const val ENABLE_CHANNEL = 0x0001

        /** How many frames [open] will read while waiting for the peer's two messages. */
        const val HANDSHAKE_READS = 8

        /** The parameters this side's RequestEncryption and EnableEncryption announce. */
        const val OUR_PARAMETERS = 31

        /** The most one frame holds: 256 blocks, less the pad's one byte at least. */
        const val FRAME_PLAINTEXT = 256 * 16 - 1
    }
}