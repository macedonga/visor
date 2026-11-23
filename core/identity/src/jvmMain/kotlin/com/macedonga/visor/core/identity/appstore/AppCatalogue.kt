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
import kotlinx.serialization.json.put

/** A web app in Meta's catalogue for the glasses. */
data class CatalogueApp(
    val name: String,
    val url: String,
    /** Base64 of Meta's SignedWebAppManifest. Null when the entry has none. */
    val manifest: String?,
)

private const val CATALOGUE_DOC = "162073133818421355301019414873"

/**
 * WearablesAppStoreCatalogQuery, the store's list for [serial] running [osVersion], the glasses' wearables release
 * version. Only web apps come back: an app with a web address; the others name a package and are not listed here.
 */
fun fetchAppCatalogue(token: Token, serial: String, osVersion: String): List<CatalogueApp> {
    val variables = buildJsonObject {
        put("osVersion", osVersion)
        put("serial", serial)
    }
    val (_, json) = graphPost(token, "WearablesAppStoreCatalogQuery", CATALOGUE_DOC, variables)
    val edges = json.path("data", "viewer", "user", "discovery_applications")?.get("edges") as? JsonArray
        ?: return emptyList()
    return edges.mapNotNull { edge ->
        val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
        CatalogueApp(
            name = node.text("display_name") ?: return@mapNotNull null,
            url = node.nonBlank("web_app_url") ?: return@mapNotNull null,
            manifest = node.nonBlank("signed_web_app_manifest"),
        )
    }
}
