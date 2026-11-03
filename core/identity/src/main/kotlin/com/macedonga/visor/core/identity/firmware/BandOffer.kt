package com.macedonga.visor.core.identity.firmware

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.language.graphPost
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.TokenError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The band's wrapped KEK as Meta gives it, base64, and the algorithm it names. */
data class BandKek(val encrypted: String, val algorithm: String?)

private const val KEK_DOC = "313699925716490204858433749875"

/** FetchEncryptedKEKInfoByKEKId: the band's KEK for [kekId], wrapped for the band with [serial]. */
fun fetchBandKek(
    token: Token,
    serial: String,
    kekId: Long,
    http: HttpPost = UrlConnectionPost,
): BandKek {
    val variables = buildJsonObject {
        put("request", buildJsonObject {
            put("device_serial_number", serial)
            put("kek_id", kekId)
        })
    }
    val (status, json) = graphPost(token, "FetchEncryptedKEKInfoByKEKId", KEK_DOC, variables, http)
    val node = json["data"]?.jsonObject?.get("xar_fetch_encrypted_kek_info_by_kek_id") as? JsonObject
        ?: throw TokenError("Meta answered $status without a key")
    val encrypted = node.text("encrypted_kek") ?: throw TokenError("Meta's key has no value")
    return BandKek(encrypted, node.text("encryption_algo"))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
