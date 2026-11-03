package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A provider account of the Meta account: its svc89 account type, the Graph name and the app id its token is minted for. */
enum class LinkedAccountType(val wire: Int, val graphName: String, val appId: String) {
    FACEBOOK(1, "FACEBOOK", "1053491986377452"),
    INSTAGRAM(2, "INSTAGRAM", "1313693057438013"),
    THREADS(3, "THREADS", "1590413528774085"),
}

/** A linked account as the Meta account lists it: the id the Graph queries take, its name, and whether glasses may use it. */
class LinkedAccountRef(val type: LinkedAccountType, val obfuscatedId: String, val name: String, val forGlasses: Boolean)

/** A linked account's access token and, when Meta gives one, its first-party token. Secrets: never log them. */
class LinkedTokens(val accessToken: String, val firstPartyToken: String?)

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"
private const val LINKED_LIST_DOC = "3214186391731893797068997674"
private const val LINKED_TOKENS_DOC = "254156320718283261599350148705"
private const val LINKED_TOKENS_ROOT = "fx_ar_linked_access_and_first_party_token_from_obid"
private const val TOKEN_PACKAGE = "com.facebook.stella"

/**
 * The provider accounts linked to the Meta account: data.wearable_linked_accounts [{obfuscated_id, account_type,
 * display_name, is_enabled_for_wearable}], as Meta answered it on 2026-10-23.
 */
fun fetchLinkedAccounts(accessToken: String, http: HttpPost = UrlConnectionPost): List<LinkedAccountRef> {
    val json = graphql(accessToken, "MetaLinkedAccounts", LINKED_LIST_DOC, "{}", http)
    val list = (json["data"] as? JsonObject)?.get("wearable_linked_accounts") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val node = element as? JsonObject ?: return@mapNotNull null
        val id = node.text("obfuscated_id") ?: return@mapNotNull null
        val type = node.text("account_type")?.let { name -> LinkedAccountType.entries.firstOrNull { it.graphName == name.uppercase() } }
            ?: return@mapNotNull null
        LinkedAccountRef(type, id, node.text("display_name").orEmpty(), (node["is_enabled_for_wearable"] as? JsonPrimitive)?.content == "true")
    }.distinctBy { it.type }
}

/** The tokens of one linked account, from the accounts app's token; null when Meta gives no access token for it. */
fun fetchLinkedTokens(
    accountsToken: String,
    account: LinkedAccountRef,
    deviceId: String,
    http: HttpPost = UrlConnectionPost,
): LinkedTokens? {
    val variables = JsonObject(
        mapOf(
            "obfuscated_account_id" to JsonPrimitive(account.obfuscatedId),
            "account_type" to JsonPrimitive(account.type.graphName),
            "app_id" to JsonPrimitive(account.type.appId),
            "package_name" to JsonPrimitive(TOKEN_PACKAGE),
            "device_id" to JsonPrimitive(deviceId),
            "service" to JsonPrimitive("WEARABLES_LINKED_ACCOUNTS"),
        ),
    ).toString()
    val json = graphql(accountsToken, "MetaLinkedAccountAccessAndFirstPartyTokensQuery", LINKED_TOKENS_DOC, variables, http)
    val node = (json["data"] as? JsonObject)?.get(LINKED_TOKENS_ROOT) as? JsonObject ?: return null
    val token = node.text("token")?.takeIf { it.isNotEmpty() } ?: return null
    return LinkedTokens(token, node.text("first_party_token")?.takeIf { it.isNotEmpty() })
}

/** The provider account's ids for LinkedAccountCredentials field 3, one compact JSON line, empty ones left out; null when an id is missing. */
fun fetchLinkedMetadata(type: LinkedAccountType, tokens: LinkedTokens, http: HttpPost = UrlConnectionPost): String? {
    fun root(json: JsonObject, name: String) = (json["data"] as? JsonObject)?.get(name) as? JsonObject
    val fields: Map<String, String> = when (type) {
        LinkedAccountType.FACEBOOK -> {
            val json = graphql(FACEBOOK_URL, "OAuth ${tokens.accessToken}", "FacebookAccountQuery", FACEBOOK_DOC, "{}", http)
            val actor = root(json, "viewer")?.get("actor") as? JsonObject ?: return null
            val id = actor.text("id")?.takeIf { it.isNotEmpty() } ?: return null
            mapOf("user_id" to id, "name" to actor.text("name").orEmpty())
        }
        LinkedAccountType.INSTAGRAM -> {
            val session = tokens.firstPartyToken ?: return null
            val viewer = root(graphql(INSTAGRAM_URL, session, "InstagramAccountQuery", INSTAGRAM_DOC, "{}", http), "ig_viewer") ?: return null
            val id = viewer.text("id")?.takeIf { it.isNotEmpty() } ?: return null
            val messaging = viewer.text("messaging_user_id")?.takeIf { it.isNotEmpty() } ?: return null
            mapOf("user_id" to id, "username" to viewer.text("username").orEmpty(), "messaging_user_id" to messaging)
        }
        LinkedAccountType.THREADS -> {
            val viewer = root(graphql(INSTAGRAM_URL, tokens.accessToken, "ThreadsAccountQuery", THREADS_DOC, "{}", http), "wearables_display_th_viewer") ?: return null
            val id = viewer.text("id")?.takeIf { it.isNotEmpty() } ?: return null
            // Both have defaults on the glasses, so an empty one is left out.
            mapOf("user_id" to id, "username" to viewer.text("username").orEmpty()).filterValues { it.isNotEmpty() }
        }
    }
    return JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
}

private const val FACEBOOK_URL = "https://graph.facebook.com/graphql"
private const val INSTAGRAM_URL = "https://i.instagram.com/graphql_www"
private const val FACEBOOK_DOC = "28485218910396071647441165961"
private const val INSTAGRAM_DOC = "202856306910776971470899218030"
private const val THREADS_DOC = "131157504210866109592346958212"

/** One persisted query on Meta's ar GraphQL, as the Meta app's own calls send it. */
private fun graphql(accessToken: String, name: String, doc: String, variables: String, http: HttpPost): JsonObject =
    graphql(GRAPH_URL, "OAuth $accessToken", name, doc, variables, http)

/** The same form on [url], with the whole Authorization value: the providers' own GraphQL takes a bare token. */
private fun graphql(url: String, authorization: String, name: String, doc: String, variables: String, http: HttpPost): JsonObject {
    val body = pyUrlencode(
        linkedMapOf(
            "fb_api_req_friendly_name" to name,
            "client_doc_id" to doc,
            "variables" to variables,
            "method" to "post",
            "pretty" to "false",
            "format" to "json",
        ),
    ).toByteArray(Charsets.US_ASCII)
    val reply = try {
        http.post(
            url,
            mapOf("Authorization" to authorization, "Content-Type" to "application/x-www-form-urlencoded"),
            body,
        )
    } catch (error: Exception) {
        throw TokenError("Meta could not be reached: ${error.message}", unreachable = true)
    }
    val json = runCatching { Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject }
        .getOrElse { throw TokenError("Meta answered ${reply.status} with no JSON") }
    if (json["errors"] != null) throw TokenError("Meta refused the query $name")
    return json
}
