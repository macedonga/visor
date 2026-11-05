package com.macedonga.visor.core.identity.provision

import com.macedonga.visor.core.identity.rawPublicKey
import com.macedonga.visor.core.identity.text
import com.macedonga.visor.core.identity.token.SessionEnded
import com.macedonga.visor.core.identity.token.WebLoginSource
import com.macedonga.visor.core.session.ChannelRegistry
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.SecureLink
import com.macedonga.visor.core.session.withLinkDeadline
import com.macedonga.visor.core.transport.Transport
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What a change of owner leaves: the pair's serial, the key and the device's keys. */
class ProvisioningResult(
    val serial: String,
    val keyPem: ByteArray,
    val reusedKey: Boolean,
    val deviceCert: ByteArray,
    /** additional_data.device_ec_public_key in Meta's final receipt, as the Meta app stores it; lease hints name it. */
    val deviceEcPublicKey: ByteArray?,
)

/** Run change owner over [transport] and return the key it installed. */
suspend fun provision(
    transport: Transport,
    source: WebLoginSource,
    existingKeyPem: ByteArray? = null,
    onStep: (ProvisionStep) -> Unit = {},
): ProvisioningResult {
    val key = if (existingKeyPem != null) ownerKeyFromPem(existingKeyPem) else generateOwnerKey()
    onStep(ProvisionStep.TOKEN)
    val token = withContext(Dispatchers.IO) { source.get() }
    val backend = PairingBackend(token.value)
    try {
        onStep(ProvisionStep.CONNECT)
        val changed = try {
            transport.connect()
            coroutineScope {
                onStep(ProvisionStep.SECURE_LINK)
                val link = SecureLink(transport)
                withLinkDeadline(
                    transport,
                    GlassesSession.LINK_SETUP_READ_TIMEOUT_MILLIS,
                    "the glasses stopped answering during link setup",
                ) { link.open() }
                val connection = DataXConnection(link, ChannelRegistry())
                val reader = connection.startReader(this)
                try {
                    ProvisioningFlow(connection, backend, key.publicKey64, onStep).run()
                } finally {
                    // Close first: a read blocked on the socket does not answer cancellation, and
                    // the reader would hold this scope open until it did.
                    transport.close()
                    reader.cancel()
                }
            }
        } finally {
            transport.close()
        }

        val info = changed.receipt.info
        return ProvisioningResult(
            serial = info.text("serial").orEmpty().ifEmpty { changed.identity.serial },
            keyPem = key.pem,
            reusedKey = existingKeyPem != null,
            deviceCert = changed.identity.certDer,
            deviceEcPublicKey = runCatching {
                // The Meta app reads it with getString, which takes a string or an object.
                val extra = (info["additional_data"] as? JsonObject)
                    ?: Json.parseToJsonElement(info.text("additional_data").orEmpty()).jsonObject
                rawPublicKey(Base64.getDecoder().decode(extra.text("device_ec_public_key").orEmpty()))
            }.getOrNull(),
        )
    } catch (ended: SessionEnded) {
        // The next setup logs in for a fresh token.
        source.forget(token)
        throw ended
    }
}
