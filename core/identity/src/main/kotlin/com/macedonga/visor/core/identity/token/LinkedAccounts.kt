package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyUrlencode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A provider account of the Meta account: its svc89 account type, the Graph name and the app id its token is minted for. */
enum class LinkedAccountType(val wire: Int, val graphName: String, val appId: String) {
    FACEBOOK(1, "FACEBOOK", "1053491986377452"),
    INSTAGRAM(2, "INSTAGRAM", "1313693057438013"),
    THREADS(3, "THREADS", "1590413528774085"),
}

/** A linked account as the Meta account lists it: the id the Graph queries take. */
class LinkedAccountRef(val type: LinkedAccountType, val obfuscatedId: String)

/** A linked account's access token and, when Meta gives one, its first-party token. Secrets: never log them. */
class LinkedTokens(val accessToken: String, val firstPartyToken: String?)

private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"
private const val LINKED_LIST_DOC = "3214186391731893797068997674"
private const val LINKED_TOKENS_DOC = "254156320718283261599350148705"
private const val LINKED_TOKENS_ROOT = "fx_ar_linked_access_and_first_party_token_from_obid"
private const val TOKEN_PACKAGE = "com.facebook.stella"

/**
 * The provider accounts linked to the Meta account, from the accounts app's token minted by [exchangeAccountToken]. Each
 * node that carries an obfuscated id and an account type counts.
 */
fun fetchLinkedAccounts(accountsToken: String, http: HttpPost = UrlConnectionPost): List<LinkedAccountRef> {
    val found = mutableListOf<LinkedAccountRef>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val id = element.text("obfuscated_account_id")
                val type = element.text("account_type")?.let { name -> LinkedAccountType.entries.firstOrNull { it.graphName == name.uppercase() } }
                if (id != null && type != null) found += LinkedAccountRef(type, id)
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(graphql(accountsToken, "MetaLinkedAccounts", LINKED_LIST_DOC, "{}", http))
    return found.distinctBy { it.type }
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

/** One persisted query on Meta's ar GraphQL, as the Meta app's own calls send it. */
private fun graphql(accessToken: String, name: String, doc: String, variables: String, http: HttpPost): JsonObject {
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
            GRAPH_URL,
            mapOf("Authorization" to "OAuth $accessToken", "Content-Type" to "application/x-www-form-urlencoded"),
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
