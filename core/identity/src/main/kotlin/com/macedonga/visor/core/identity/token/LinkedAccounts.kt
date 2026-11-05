package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.graphPost
import com.macedonga.visor.core.identity.nonBlank
import com.macedonga.visor.core.identity.obj
import com.macedonga.visor.core.identity.path
import com.macedonga.visor.core.identity.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

private const val LINKED_LIST_DOC = "3214186391731893797068997674"
private const val LINKED_TOKENS_DOC = "254156320718283261599350148705"
private const val LINKED_TOKENS_ROOT = "fx_ar_linked_access_and_first_party_token_from_obid"
private const val TOKEN_PACKAGE = "com.facebook.stella"

/**
 * The provider accounts linked to the Meta account: data.wearable_linked_accounts [{obfuscated_id, account_type,
 * display_name, is_enabled_for_wearable}].
 */
fun fetchLinkedAccounts(accessToken: String): List<LinkedAccountRef> {
    val json = graphPost("OAuth $accessToken", "MetaLinkedAccounts", LINKED_LIST_DOC, "{}", locale = null).second
    val list = json.obj("data")?.get("wearable_linked_accounts") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val node = element as? JsonObject ?: return@mapNotNull null
        val id = node.text("obfuscated_id") ?: return@mapNotNull null
        val type = node.text("account_type")?.let { name -> LinkedAccountType.entries.firstOrNull { it.graphName == name.uppercase() } }
            ?: return@mapNotNull null
        LinkedAccountRef(type, id, node.text("display_name").orEmpty(), (node["is_enabled_for_wearable"] as? JsonPrimitive)?.content == "true")
    }.distinctBy { it.type }
}

/** The tokens of one linked account, from the accounts app's token; null when Meta gives no access token for it. */
fun fetchLinkedTokens(accountsToken: String, account: LinkedAccountRef, deviceId: String): LinkedTokens? {
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
    val json = graphPost("OAuth $accountsToken", "MetaLinkedAccountAccessAndFirstPartyTokensQuery", LINKED_TOKENS_DOC, variables, locale = null).second
    val node = json.path("data", LINKED_TOKENS_ROOT) ?: return null
    val token = node.nonBlank("token") ?: return null
    return LinkedTokens(token, node.nonBlank("first_party_token"))
}

/** The provider account's ids for LinkedAccountCredentials field 3, one compact JSON line; null when a required id is missing. */
fun fetchLinkedMetadata(type: LinkedAccountType, tokens: LinkedTokens): String? {
    val fields: Map<String, String> = when (type) {
        LinkedAccountType.FACEBOOK -> {
            val json = graphPost("OAuth ${tokens.accessToken}", "FacebookAccountQuery", FACEBOOK_DOC, "{}", url = FACEBOOK_URL, locale = null).second
            val actor = json.path("data", "viewer", "actor") ?: return null
            val id = actor.nonBlank("id") ?: return null
            mapOf("user_id" to id, "name" to actor.text("name").orEmpty())
        }
        LinkedAccountType.INSTAGRAM -> {
            val session = tokens.firstPartyToken ?: return null
            val json = graphPost(session, "InstagramAccountQuery", INSTAGRAM_DOC, "{}", url = INSTAGRAM_URL, locale = null).second
            val viewer = json.path("data", "ig_viewer") ?: return null
            val id = viewer.nonBlank("id") ?: return null
            val messaging = viewer.nonBlank("messaging_user_id") ?: return null
            mapOf("user_id" to id, "username" to viewer.text("username").orEmpty(), "messaging_user_id" to messaging)
        }
        LinkedAccountType.THREADS -> {
            val json = graphPost(tokens.accessToken, "ThreadsAccountQuery", THREADS_DOC, "{}", url = INSTAGRAM_URL, locale = null).second
            val viewer = json.path("data", "wearables_display_th_viewer") ?: return null
            val id = viewer.nonBlank("id") ?: return null
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
