package com.macedonga.visor.core.identity

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the app knows about one identity. */
data class IdentityBundle(
    /** The owner key, PEM encoded. */
    val privateKeyPem: ByteArray,
    val glassesAddress: String,
    val serial: String,
    val createdAt: String,
    /** How this pair was set up: `change-owner` or `band`. */
    val provenance: String,
    val state: String,
    /**
     * The glasses' key as Meta's final receipt names it (`additional_data.device_ec_public_key`), 64 raw bytes: the
     * key their constellation manifest lists them by. Null for pairs set up before Visor kept it.
     */
    val devicePublicKey: ByteArray? = null,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityBundle) return false
        return privateKeyPem.contentEquals(other.privateKeyPem) &&
            glassesAddress == other.glassesAddress &&
            serial == other.serial &&
            createdAt == other.createdAt &&
            provenance == other.provenance &&
            state == other.state &&
            devicePublicKey.contentEquals(other.devicePublicKey)
    }

    override fun hashCode(): Int {
        var result = privateKeyPem.contentHashCode()
        result = 31 * result + glassesAddress.hashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + devicePublicKey.contentHashCode()
        return result
    }

    /** Deliberately says nothing about the key. */
    override fun toString(): String =
        "IdentityBundle(serial=$serial, address=$glassesAddress, state=$state, " +
            "provenance=$provenance, key=${privateKeyPem.size}B)"
}

/** The recorded state of a bundle, explicit rather than inferred. */
object BundleState {
    const val UNVERIFIED = "unverified"
    const val VERIFIED = "verified"
    const val EVICTED = "evicted"
}

/** UTC now, as yyyy-MM-ddTHH:mm:ssZ. */
fun nowIso(): String = ISO_SECONDS_Z.format(Instant.now().atOffset(ZoneOffset.UTC))

private val ISO_SECONDS_Z: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/** The JSON shape, which is a wire format rather than an internal detail. */
@Serializable
internal class BundleJson(
    @SerialName("private_key_pem") val privateKeyPem: String,
    @SerialName("glasses_address") val glassesAddress: String,
    @SerialName("serial") val serial: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("provenance") val provenance: String,
    @SerialName("state") val state: String,
    @SerialName("device_public_key") val devicePublicKey: String? = null,
)

internal val BundleFormat = Json { ignoreUnknownKeys = true }

internal fun IdentityBundle.toJsonModel(): BundleJson = BundleJson(
    privateKeyPem = Base64.getEncoder().encodeToString(privateKeyPem),
    glassesAddress = glassesAddress,
    serial = serial,
    createdAt = createdAt,
    provenance = provenance,
    state = state,
    devicePublicKey = devicePublicKey?.let { Base64.getEncoder().encodeToString(it) },
)

internal fun BundleJson.toBundle(): IdentityBundle = IdentityBundle(
    privateKeyPem = Base64.getDecoder().decode(privateKeyPem),
    glassesAddress = glassesAddress,
    serial = serial,
    createdAt = createdAt,
    provenance = provenance,
    state = state,
    devicePublicKey = devicePublicKey?.let { Base64.getDecoder().decode(it) },
)

/** The store's contents: a JSON array of bundles. */
internal fun bundlesToJson(bundles: List<IdentityBundle>): String =
    BundleFormat.encodeToString(ListSerializer(BundleJson.serializer()), bundles.map { it.toJsonModel() })

internal fun bundlesFromJson(text: String): List<IdentityBundle> =
    BundleFormat.decodeFromString(ListSerializer(BundleJson.serializer()), text).map { it.toBundle() }
