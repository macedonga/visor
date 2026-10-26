package com.macedonga.visor.core.identity.provision

import com.macedonga.visor.core.identity.BuildConfig
import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.publicKeyOf
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.readPrivateKeyPem
import com.macedonga.visor.core.protocol.IdentityMsgType
import com.macedonga.visor.core.protocol.IdentityStatus
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Change owner: make a key WE generated the glasses' owner, so the client is its own. */

// notes/39: graph.meta.com does not resolve at all. The hardware tier is what issues receipts.
const val GRAPH_BASE = "https://graph.facebook-hardware.com"
private const val PAIR_PROTOCOL_VERSION = "4"

open class ProvisioningError(message: String) : Exception(message)

/** 0x1042: the glasses already have an owner, and Meta's receipt names a different account. */
class WrongOwnerError : ProvisioningError("These glasses belong to another Meta account")

private fun hwAppToken(): String = BuildConfig.META_HW_TOKEN.ifEmpty {
    throw ProvisioningError("This build has no Meta app token. Add VISOR_META_HW_TOKEN to local.properties and build again.")
}

/** An ownership receipt as carried over svc36: {1: signature, 2: JSON body}. */
class Receipt(val signature: ByteArray, val bodyJson: ByteArray) {

    fun encode(): ByteArray = Wire.fieldBytes(1, signature) + Wire.fieldBytes(2, bodyJson)

    val info: JsonObject
        get() = try {
            Json.parseToJsonElement(String(bodyJson, Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            JsonObject(emptyMap())
        }

    companion object {
        fun decode(data: ByteArray): Receipt {
            val fields = Wire.parse(data)
            return Receipt(signature = fields.bytes(1), bodyJson = fields.bytes(2))
        }
    }
}

/** IdentityResponse {1: certificate, 2: serial, 3: registration challenge, 4: DRK present, 5: secondary certificate}. */
class DeviceIdentity(val certDer: ByteArray, val serial: String, val secondaryCert: ByteArray = ByteArray(0)) {
    companion object {
        fun decode(data: ByteArray): DeviceIdentity {
            val fields = Wire.parse(data)
            return DeviceIdentity(certDer = fields.bytes(1), serial = String(fields.bytes(2), Charsets.US_ASCII), secondaryCert = fields.bytes(5))
        }
    }
}

/** An owner key in the two forms the flow and the store need. */
class OwnerKey(val pem: ByteArray, val publicKey64: ByteArray)

/** A fresh EC P-256 owner key, PKCS#8 PEM as `save_owner_key` writes it. */
fun generateOwnerKey(): OwnerKey {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val pair = generator.generateKeyPair()
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
    val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII)
    return OwnerKey(pem, publicKeyOf(readPrivateKeyPem(pem)))
}

/** Load an existing owner key in the same shape [generateOwnerKey] returns. */
fun ownerKeyFromPem(pem: ByteArray): OwnerKey = OwnerKey(pem, publicKeyOf(readPrivateKeyPem(pem)))

/** pair_request then pair. */
class PairingBackend(
    private val token: String,
    private val universe: String = "ar",
    private val http: HttpPost = UrlConnectionPost,
) {

    private fun common(): LinkedHashMap<String, String> = linkedMapOf(
        "access_token" to hwAppToken(),
        "user_access_token" to token,
        "user_token_universe" to universe,
        "pair_protocol_version" to PAIR_PROTOCOL_VERSION,
    )

    private fun post(method: String, fields: Map<String, String>, receiptKey: String): Receipt {
        // DIVERGENCE: urllib sends its own "Python-urllib" user agent and HttpURLConnection
        // sends Android's. The reference sets none, so neither does this.
        val reply = http.post(
            "$GRAPH_BASE/$method",
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Content-Encoding" to "gzip",
            ),
            gzip(pyUrlencode(fields).toByteArray(Charsets.US_ASCII)),
        )
        if (reply.status >= 400) {
            val detail = String(reply.body, Charsets.UTF_8).take(300)
            throw ProvisioningError("$method: HTTP ${reply.status} $detail")
        }
        val payload = Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject
        payload["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
                ?: error.toString()
            throw ProvisioningError("$method: $message")
        }
        val value = payload[receiptKey] ?: throw ProvisioningError("$method: no $receiptKey in the response")
        val signature = (payload["receipt_signature"] as? JsonPrimitive)?.content
            ?: throw ProvisioningError("$method: no receipt_signature in the response")
        val body = if (value is JsonPrimitive && value.isString) value.content else pyJsonDumps(value)
        return Receipt(
            // b64decode without validate= discards characters outside the alphabet, and so
            // does the MIME decoder.
            signature = Base64.getMimeDecoder().decode(signature),
            bodyJson = body.toByteArray(Charsets.UTF_8),
        )
    }

    fun pairRequest(certDer: ByteArray, serial: String, deviceNonce: ByteArray, appPubkey: ByteArray, secondaryCert: ByteArray = ByteArray(0)): Receipt {
        val fields = common()
        fields["device_cert"] = Base64.getEncoder().encodeToString(certDer)
        fields["serial_number"] = serial
        fields["additional_data"] = pyJsonDumps(
            buildJsonObject {
                put("device_nonce", Base64.getEncoder().encodeToString(deviceNonce))
                put("app_pubkey", Base64.getEncoder().encodeToString(appPubkey))
                // IdentityResponse field 5, as the Meta app sends it; glasses have none.
                put("secondary_cert", if (secondaryCert.isEmpty()) "" else Base64.getEncoder().encodeToString(secondaryCert))
            },
        )
        return post("pair_request", fields, "pending_ownership_receipt")
    }

    fun pair(devicePendingReceipt: Receipt): Receipt {
        val fields = common()
        fields["device_pending_ownership_receipt"] = String(devicePendingReceipt.bodyJson, Charsets.UTF_8)
        fields["device_pending_ownership_receipt_signature"] =
            Base64.getEncoder().encodeToString(devicePendingReceipt.signature)
        // ephemeral_pairing_flag is OMITTED for a normal pairing: the Meta app passes the
        // boolean false and simply does not add the field (notes_old/62).
        return post("pair", fields, "final_ownership_receipt")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}

/** The steps a setup reports as it reaches each one, for the person watching it. */
enum class ProvisionStep {
    TOKEN,
    CONNECT,
    SECURE_LINK,
    IDENTITY,
    SKIP_CHALLENGE,
    PAIR_REQUEST,
    START_CHANGE_OWNER,
    PAIR,
    FINISH_CHANGE_OWNER,
}

/** The six svc36 steps, over an open DataX connection. */
class ProvisioningFlow(
    private val connection: DataXConnection,
    private val backend: PairingBackend,
    private val appPubkey: ByteArray,
    private val timeoutMillis: Long = 20_000,
    private val onStep: (ProvisionStep) -> Unit = {},
) {
    private val channel = connection.openChannel(ServiceId.IDENTITY.value)
    private var first = true

    /** The device's certificate from its IdentityResponse, once [run] has it. */
    var deviceCert: ByteArray? = null
        private set

    private suspend fun send(messageType: IdentityMsgType, payload: ByteArray = ByteArray(0)) {
        // A peer names a channel's service only on that channel's first message; later ones
        // carry service_id 0.
        connection.send(
            channel,
            payload,
            messageType = messageType.value,
            serviceId = if (first) ServiceId.IDENTITY.value else null,
        )
        first = false
    }

    /** Wait for one message type, bounded. */
    private suspend fun await(messageType: IdentityMsgType): ByteArray {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val mirror = DataXConnection.mirror(channel)
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            val message = withTimeoutOrNull(remaining) { connection.receive() } ?: continue
            if (message.info.messageType == messageType.value) return message.payload
            if (message.info.flags.hasError && message.info.channelId == mirror) {
                if (message.info.errorId == IdentityStatus.INVALID_USER_ID.value) throw WrongOwnerError()
                throw ProvisioningError(
                    "glasses answered status 0x%04x while awaiting %s".format(message.info.errorId, messageType.name),
                )
            }
        }
        throw ProvisioningError("timed out awaiting ${messageType.name}")
    }

    suspend fun run(): Receipt {
        onStep(ProvisionStep.IDENTITY)
        send(IdentityMsgType.IDENTITY_REQUEST)
        val identity = DeviceIdentity.decode(await(IdentityMsgType.IDENTITY_RESPONSE))
        deviceCert = identity.certDer

        onStep(ProvisionStep.SKIP_CHALLENGE)
        send(IdentityMsgType.SKIP_CHALLENGE)
        val nonce = Wire.parse(await(IdentityMsgType.SKIP_CHALLENGE_ACK)).bytes(1)

        onStep(ProvisionStep.PAIR_REQUEST)
        val pending = withContext(Dispatchers.IO) {
            backend.pairRequest(
                certDer = identity.certDer, serial = identity.serial, deviceNonce = nonce, appPubkey = appPubkey,
                secondaryCert = identity.secondaryCert,
            )
        }

        onStep(ProvisionStep.START_CHANGE_OWNER)
        send(IdentityMsgType.START_CHANGE_OWNER_REQ, pending.encode())
        val devicePending = Receipt.decode(await(IdentityMsgType.START_CHANGE_OWNER_RESP))

        onStep(ProvisionStep.PAIR)
        val final = withContext(Dispatchers.IO) { backend.pair(devicePendingReceipt = devicePending) }

        onStep(ProvisionStep.FINISH_CHANGE_OWNER)
        send(IdentityMsgType.FINISH_CHANGE_OWNER_REQ, final.encode())
        await(IdentityMsgType.FINISH_CHANGE_OWNER_RESP)
        return final
    }
}
