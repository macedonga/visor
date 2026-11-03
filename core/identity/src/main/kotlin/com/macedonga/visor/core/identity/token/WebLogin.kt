package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.Sealer
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.pyQuote
import com.macedonga.visor.core.identity.pyUrlencode
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A token from a browser login. */

// Built from the same line in the Meta app that hands them to its Bloks login. All three
// values belong to one flow and cannot be mixed.
private const val APP_ID = "934401142814369"
const val META_REDIRECT_URI = "https://accounts.meta.com/native/oidc/redirect/metaai"
private const val AUTHORIZE_URL = "https://accounts.meta.com/oidc/"
private const val EXCHANGE_URL = "https://api.accounts.meta.com/native/oidc/token-exchange/"

/** The URL to open, and the state it carries so the redirect can be matched. */
class AuthorizeRequest(val url: String, val state: String)

private val random = SecureRandom()

/** `secrets.token_urlsafe(16)`: 16 random bytes, base64url, no padding. */
private fun tokenUrlsafe(bytes: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

fun authorizeUrl(): AuthorizeRequest {
    val state = tokenUrlsafe(16)
    val query = pyUrlencode(
        linkedMapOf(
            "app_id" to APP_ID,
            "scope" to "openid",
            "response_type" to "code",
            "redirect_uri" to META_REDIRECT_URI,
            "state" to state,
        ),
    )
    return AuthorizeRequest("$AUTHORIZE_URL?$query", state)
}

/** The auth code for an FRLA. Returns the token; throws [TokenError] otherwise. */
fun exchangeCode(code: String, http: HttpPost = UrlConnectionPost): String {
    val body = listOf(
        "mt_app_id" to APP_ID,
        "oidc_auth_code" to code,
        "redirect_uri" to META_REDIRECT_URI,
    ).joinToString("&") { (key, value) -> "$key=${pyQuote(value)}" }

    val reply = try {
        http.post(
            EXCHANGE_URL,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to META_USER_AGENT,
            ),
            body.toByteArray(Charsets.US_ASCII),
        )
    } catch (error: Exception) {
        throw TokenError("token-exchange: ${error.javaClass.simpleName}: ${error.message}", unreachable = true)
    }

    val payload = try {
        Json.parseToJsonElement(String(reply.body, Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw TokenError(
            "token-exchange answered HTTP ${reply.status} with a non-JSON body. A 500 here " +
                "means the code belongs to a different flow, not that the endpoint is closed.",
        )
    }
    val token = payload.text("access_token").orEmpty()
    val userId = payload.text("user_id").orEmpty()
    // The Meta app's own guard: it refuses the response unless both are present.
    if (token.isEmpty() || userId.isEmpty()) {
        // A refusal comes as a problem document: title, status, detail. Say what it said.
        val problem = listOf("title", "status", "detail").mapNotNull { key -> payload.text(key)?.let { "$key=$it" } }
        throw TokenError(
            if (problem.isNotEmpty()) "token-exchange refused the code: ${problem.joinToString(", ")}"
            else "token-exchange returned no access_token/user_id pair; keys: ${payload.sortedKeys()}",
        )
    }
    return token
}

/** Browser login to FRLA to ARAe, with the FRLA cached between runs. */
class WebLoginSource(
    private val cacheFile: File? = null,
    private val sealer: Sealer? = null,
    private val http: HttpPost = UrlConnectionPost,
) : TokenSource {

    /** True when an FRLA is cached. Says nothing about whether Meta still accepts it. */
    val hasCachedLogin: Boolean get() = readCache() != null

    // One token for the run: each exchange opens a new Meta session, and Meta ends the older ones, with the
    // requests still in flight on them.
    @Volatile private var token: Token? = null

    override fun get(): Token {
        token?.let { return it }
        synchronized(this) {
            token?.let { return it }
            val cached = readCache() ?: throw TokenError("not logged in to Meta")
            try {
                return exchangeFrla(cached, http = http).also { token = it }
            } catch (error: TokenError) {
                // No network is not a dead login: say so, and keep "log in again" for an FRLA Meta turned down.
                if (error.unreachable) throw TokenError("Meta could not be reached (${error.message})", unreachable = true)
                throw TokenError("the saved Meta login no longer works (${error.message}); log in again")
            }
        }
    }

    /** Meta no longer takes [dropped]: the next [get] exchanges the login for a new token. */
    override fun forget(dropped: Token) {
        synchronized(this) { if (token == dropped) token = null }
    }

    fun begin(): AuthorizeRequest = authorizeUrl()

    /** Take the redirect the WebView caught, exchange its code, and cache the FRLA. */
    fun finish(redirect: String, request: AuthorizeRequest) {
        val params = parseQs(URI(redirect).rawQuery.orEmpty())
        val code = params["code"]?.firstOrNull()
            ?: throw TokenError(
                "the redirect carried no code; parameters: " +
                    params.keys.sorted().joinToString(", ", "[", "]") { "'$it'" },
            )
        if (params["state"]?.firstOrNull() != request.state) {
            throw TokenError("state mismatch: that redirect belongs to another login")
        }
        writeCache(exchangeCode(code, http))
    }

    /** The native login was handed a code: exchange it and cache the FRLA. */
    fun finishCode(code: String) {
        writeCache(exchangeCode(code, http))
    }

    /** The native login was handed the FRLA itself. */
    fun finishToken(frla: String) {
        writeCache(frla)
    }

    /** Forget the cached login. The WebView's cookies are the app's to clear. */
    fun logOut() {
        token = null
        cacheFile?.delete()
    }

    private fun readCache(): String? {
        if (cacheFile == null || sealer == null || !cacheFile.exists()) return null
        return try {
            String(sealer.unseal(cacheFile.readBytes()), Charsets.UTF_8).trim().ifEmpty { null }
        } catch (error: Exception) {
            // A cache sealed by another install is not an error worth failing on: log in
            // again and overwrite it.
            null
        }
    }

    private fun writeCache(frla: String) {
        token = null
        if (cacheFile == null || sealer == null) return
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(sealer.seal(frla.toByteArray(Charsets.UTF_8)))
    }
}

/** `urllib.parse.parse_qs`: blank values dropped, as its default does. */
private fun parseQs(query: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        if (eq < 0) continue
        val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        if (value.isEmpty()) continue
        val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
        out.getOrPut(key) { mutableListOf() }.add(value)
    }
    return out
}
