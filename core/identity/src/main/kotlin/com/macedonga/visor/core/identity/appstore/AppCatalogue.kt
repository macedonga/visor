package com.macedonga.visor.core.identity.appstore

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.language.graphPost
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A web app in Meta's catalogue for the glasses. [id] is Meta's; the glasses key it when it is written. */
data class CatalogueApp(
    val id: String,
    val name: String,
    val iconUrl: String,
    val url: String,
    /** Base64 of Meta's SignedWebAppManifest. Null when the entry has none. */
    val manifest: String?,
)

private const val CATALOGUE_DOC = "162073133818421355301019414873"

/**
 * WearablesAppStoreCatalogQuery, the store's list for [serial] running [osVersion], the glasses' wearables release
 * version. Only web apps come back: an app with a web address; the others name a package and are not listed here.
 */
fun fetchAppCatalogue(
    token: Token,
    serial: String,
    osVersion: String,
    http: HttpPost = UrlConnectionPost,
): List<CatalogueApp> {
    val variables = buildJsonObject {
        put("osVersion", osVersion)
        put("serial", serial)
    }
    val (_, json) = graphPost(token, "WearablesAppStoreCatalogQuery", CATALOGUE_DOC, variables, http)
    val edges = json.obj("data")?.obj("viewer")?.obj("user")?.obj("discovery_applications")?.get("edges") as? JsonArray
        ?: return emptyList()
    return edges.mapNotNull { edge ->
        val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
        CatalogueApp(
            id = node.text("id") ?: return@mapNotNull null,
            name = node.text("display_name") ?: return@mapNotNull null,
            iconUrl = node.text("pdp_icon_url").orEmpty(),
            url = node.text("web_app_url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            manifest = node.text("signed_web_app_manifest")?.takeIf { it.isNotEmpty() },
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
