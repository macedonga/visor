package com.macedonga.visor.core.identity

import com.macedonga.visor.core.protocol.Ecdh
import com.macedonga.visor.core.protocol.Wire
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The glasses' constellation manifest: the peers they trust, signed by an authority key the phone installs. Built as
 * Meta's own ManifestBuilder writes it (its output from the DAT SDK 1.0.1 checked against this layout, 2026-11-08):
 * ConstellationSignedContent {1 signature, 2 0, 3 body}; body {1 version, 2 a 4-byte key salt, 3 the content key
 * wrapped for each device and then each app, 4 a public key, 5 the device list, sealed with a key derived from the
 * content key, 6 the app list, sealed with the content key itself}.
 *
 * The glasses open their wrap with ECDH of their own key and the authority key that SetManifestAuthorityKey installed
 * (research notes/20), so every wrap is made with the authority key, never a fresh one. Field 4 carries the authority's
 * public key too. A wrap the glasses cannot open fails their loadDevices, and svc79 then refuses every app with
 * status 1.
 */
object ConstellationManifest {
    /** svc10, the link manager, carries the install. */
    const val SERVICE = 10
    const val GET_VERSION = 4144
    const val VERSION_INFO = 4145
    const val SET_AUTHORITY_KEY = 4127
    const val FILE_DATA = 4122
    const val FILE_COMPLETE = 4126
    private const val CHUNK = 1024

    /** A device: its receipt key (64 raw bytes), vendor data (see [vendorData]) and Bluetooth addresses. */
    class Device(val publicKey: ByteArray, val vendorData: ByteArray, val btcAddress: String, val bleAddress: String)

    /** An app: its key and its package, which the glasses name its link's App scope by. */
    class App(val publicKey: ByteArray, val bundleId: String)

    /** The manifest the glasses hold: GET_VERSION's answer {1 version, 2 key salt}. */
    class Version(val version: Long, val salt: ByteArray)

    fun version(payload: ByteArray): Version = Wire.parse(payload).let { Version(it.varint(1), it.bytes(2)) }

    /** Vendor data as the Meta app writes it: 0x01 0xAB, then the product id, big endian. */
    fun vendorData(product: Int): ByteArray = byteArrayOf(1, 0xAB.toByte(), (product shr 8).toByte(), product.toByte())

    /** SHA256(SHA256(key) ‖ salt): a peer's tag. A key wrap keeps 10 bytes of it, an EnableTrust 8. */
    fun keyTag(publicKey: ByteArray, salt: ByteArray): ByteArray = sha(sha(publicKey), salt)

    /** The signed manifest for [devices] and [apps], at [version], with a fresh salt and content key. */
    fun build(authority: PrivateKey, authorityPublic: ByteArray, devices: List<Device>, apps: List<App>, version: Long, groupId: ByteArray): ByteArray {
        require(devices.isNotEmpty()) { "a manifest needs the glasses among its devices" }
        val random = SecureRandom()
        val salt = ByteArray(4).also(random::nextBytes)
        val contentKey = ByteArray(32).also(random::nextBytes)
        val listKey = hkdf(contentKey, sha(contentKey, sha(contentKey)))

        fun wrap(publicKey: ByteArray): ByteArray {
            val key = hkdf(Ecdh.computeSharedSecret(authority, publicKey), sha(salt))
            val iv = ByteArray(16).also(random::nextBytes)
            return Wire.fieldBytes(1, iv) + Wire.fieldBytes(2, aes(key, iv, contentKey)) +
                Wire.fieldBytes(3, keyTag(publicKey, salt).copyOf(10))
        }

        fun sealed(key: ByteArray, list: ByteArray): ByteArray {
            val iv = ByteArray(16).also(random::nextBytes)
            return Wire.fieldBytes(1, iv) + Wire.fieldBytes(2, aes(key, iv, pad(list)))
        }

        val deviceList = devices.joinBytes { d ->
            Wire.fieldBytes(
                1,
                Wire.fieldBytes(1, publicKeyMessage(d.publicKey)) + Wire.fieldBytes(2, d.vendorData) +
                    (if (d.btcAddress.isNotEmpty()) Wire.fieldString(4, d.btcAddress) else ByteArray(0)) +
                    Wire.fieldString(5, d.bleAddress),
            )
        } + Wire.fieldBytes(2, groupId)
        val appList = apps.joinBytes { a -> Wire.fieldBytes(1, Wire.fieldBytes(1, publicKeyMessage(a.publicKey)) + Wire.fieldString(3, a.bundleId)) }

        val body = Wire.fieldVarint(1, version) + Wire.fieldBytes(2, salt) +
            (devices.map { it.publicKey } + apps.map { it.publicKey }).joinBytes { Wire.fieldBytes(3, wrap(it)) } +
            Wire.fieldBytes(4, publicKeyMessage(authorityPublic)) + Wire.fieldBytes(5, sealed(listKey, deviceList)) +
            (if (apps.isNotEmpty()) Wire.fieldBytes(6, sealed(contentKey, appList)) else ByteArray(0))
        return Wire.fieldBytes(1, signDigest(authority, sha(body))) + Wire.fieldVarint(2, 0) + Wire.fieldBytes(3, body)
    }

    /** SetManifestAuthorityKey {1 the authority's 64-byte public key}. Installing it clears the glasses' manifest. */
    fun authorityKey(publicKey: ByteArray): ByteArray = Wire.fieldBytes(1, publicKey)

    /** The manifest in 1024-byte pieces, each with its message type: data, and the last one complete. */
    fun chunks(manifest: ByteArray): List<Pair<Int, ByteArray>> {
        val pieces = manifest.toList().chunked(CHUNK).map { it.toByteArray() }
        return pieces.mapIndexed { i, piece -> (if (i == pieces.lastIndex) FILE_COMPLETE else FILE_DATA) to piece }
    }

    // ConstellationManifest.PublicKey {1 key, 2 curve: secp256r1 is 0}.
    private fun publicKeyMessage(raw: ByteArray) = Wire.fieldBytes(1, raw) + Wire.fieldVarint(2, 0)

    // AirShield's padding: PKCS#7 lengths with the top two bits of each pad byte set.
    private fun pad(data: ByteArray): ByteArray {
        val n = 16 - data.size % 16
        return data + ByteArray(n) { (n or 0xC0).toByte() }
    }

    private fun aes(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    // HKDF-SHA256 to 32 bytes with AirShield's info, which its HKDF puts in when the caller gives none.
    private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, "AirShield".encodeToByteArray() + byteArrayOf(1))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach(::update)
        digest()
    }

    private inline fun <T> List<T>.joinBytes(each: (T) -> ByteArray): ByteArray = fold(ByteArray(0)) { acc, t -> acc + each(t) }
}
