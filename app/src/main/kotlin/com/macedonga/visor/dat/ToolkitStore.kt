package com.macedonga.visor.dat

import android.content.Context
import android.util.Base64
import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.protocol.Ecdh
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** What Visor set up on one pair for Meta's toolkit: the manifest it signs, and whether the grants went in. */
data class ToolkitPair(
    /** The authority key Visor signs this pair's manifest with, PKCS#8, and its 64-byte public half. */
    val authority: ByteArray,
    val authorityPublic: ByteArray,
    /** The manifest installed: its version and key salt, which the app link's EnableTrust names. */
    val version: Long,
    val salt: ByteArray,
    /** The constellation's id in the device list; kept across versions. */
    val groupId: ByteArray,
    /** The device keys the installed manifest lists, hex, to notice a band added since. */
    val devices: Set<String>,
    val granted: Boolean,
    /** How the manifest was built: one built before [MANIFEST_FORMAT] is built again. */
    val format: Int = MANIFEST_FORMAT,
)

/** 4: the band gets Visor's authority key and the manifest too. 3: wraps made with the authority key. */
const val MANIFEST_FORMAT = 4

/**
 * Visor's toolkit state, sealed with a key in the phone's keystore: one app key for every pair (the key Visor's app
 * links prove on svc79), whether the person allowed the setup, and each pair's [ToolkitPair] by serial.
 */
@Singleton
class ToolkitStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "toolkit.sealed")
    private val sealer = KeystoreSealer(alias = "visor.toolkit")

    /** The app key, made the first time: the private key and its 64-byte public half. */
    @Synchronized
    fun appKey(): Pair<PrivateKey, ByteArray> {
        val json = read()
        val kept = json.optString("app_key")
        if (kept.isNotEmpty()) return pkcs8(Base64.decode(kept, Base64.NO_WRAP)) to json.getString("app_pub").hexToByteArray()
        val made = Ecdh.generateEphemeral()
        write(json.put("app_key", Base64.encodeToString(made.privateKey.encoded, Base64.NO_WRAP)).put("app_pub", made.publicKey64.toHexString()))
        return made.privateKey to made.publicKey64
    }

    @get:Synchronized
    @set:Synchronized
    var allowed: Boolean
        get() = read().optBoolean("allowed")
        set(value) = write(read().put("allowed", value))

    @Synchronized
    fun pair(serial: String): ToolkitPair? {
        val o = read().optJSONObject("pairs")?.optJSONObject(serial) ?: return null
        return ToolkitPair(
            authority = Base64.decode(o.getString("authority"), Base64.NO_WRAP),
            authorityPublic = o.getString("authority_pub").hexToByteArray(),
            version = o.getLong("version"),
            salt = o.getString("salt").hexToByteArray(),
            groupId = o.getString("group").hexToByteArray(),
            devices = o.optString("devices").split(',').filter { it.isNotEmpty() }.toSet(),
            granted = o.optBoolean("granted"),
            format = o.optInt("format", 1),
        )
    }

    @Synchronized
    fun save(serial: String, pair: ToolkitPair) {
        val json = read()
        val pairs = json.optJSONObject("pairs") ?: JSONObject()
        pairs.put(
            serial,
            JSONObject()
                .put("authority", Base64.encodeToString(pair.authority, Base64.NO_WRAP))
                .put("authority_pub", pair.authorityPublic.toHexString())
                .put("version", pair.version)
                .put("salt", pair.salt.toHexString())
                .put("group", pair.groupId.toHexString())
                .put("devices", pair.devices.joinToString(","))
                .put("granted", pair.granted)
                .put("format", pair.format),
        )
        write(json.put("pairs", pairs))
    }

    private fun read(): JSONObject =
        if (file.exists()) JSONObject(sealer.unseal(file.readBytes()).decodeToString()) else JSONObject()

    private fun write(json: JSONObject) {
        val next = File(file.path + ".new")
        next.writeBytes(sealer.seal(json.toString().encodeToByteArray()))
        check(next.renameTo(file) || (file.delete() && next.renameTo(file))) { "could not keep the toolkit state" }
    }

    companion object {
        fun pkcs8(encoded: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))
    }
}
