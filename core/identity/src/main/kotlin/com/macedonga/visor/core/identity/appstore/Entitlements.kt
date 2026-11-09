package com.macedonga.visor.core.identity.appstore

import com.macedonga.visor.core.identity.graphPost
import com.macedonga.visor.core.identity.nonBlank
import com.macedonga.visor.core.identity.obj
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.text
import com.macedonga.visor.core.identity.token.Token
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A glasses app the account holds in Meta's store, with where to download it for the glasses it was asked for. */
data class Entitlement(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** The binary's address, for the serial it was asked for, read with the account's token. */
    val downloadUrl: String,
    /** SHA-256 of the binary, hex. */
    val sha256: String,
)

private const val BINARY_DOC = "31207897482983152130374864073"

/**
 * WearablesAppStoreEntitlementBinaryQuery: the glasses apps the account holds for [serial] running [osVersion], the
 * glasses' wearables release. Entries without a binary (some of Meta's own apps) are left out.
 */
fun fetchEntitlements(token: Token, serial: String, osVersion: String): List<Entitlement> {
    val variables = buildJsonObject {
        put("osVersion", osVersion)
        put("serial", serial)
    }
    val (_, json) = graphPost(token, "WearablesAppStoreEntitlementBinaryQuery", BINARY_DOC, variables)
    val edges = json.path("data", "viewer", "user", "binary_entitlements")?.get("edges") as? JsonArray ?: return emptyList()
    return edges.mapNotNull { edge ->
        val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
        val url = node.nonBlank("download_url") ?: return@mapNotNull null
        Entitlement(
            name = node.text("name").orEmpty(),
            packageName = node.nonBlank("package_name") ?: return@mapNotNull null,
            versionCode = node["version_code"]?.jsonPrimitive?.longOrNull ?: 0,
            versionName = node.text("version_name").orEmpty(),
            downloadUrl = url + (if ('?' in url) "&" else "?") + "device_serial=$serial",
            sha256 = node.text("expected_hash").orEmpty(),
        )
    }
}
