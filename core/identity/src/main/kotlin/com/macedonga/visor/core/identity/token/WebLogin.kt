package com.macedonga.visor.core.identity.token

import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.text
import java.io.File
import java.security.SecureRandom
import java.util.Base64

// One Meta login flow: these four values only work together.
private const val APP_ID = "934401142814369"
private const val META_REDIRECT_URI = "https://accounts.meta.com/native/oidc/redirect/metaai"
private const val AUTHORIZE_URL = "https://accounts.meta.com/oidc/"
private const val EXCHANGE_URL = "https://api.accounts.meta.com/native/oidc/token-exchange/"

/** The URL to open. */
class AuthorizeRequest(val url: String)

private val random = SecureRandom()

/** [bytes] random bytes, base64url, no padding. */
private fun tokenUrlsafe(bytes: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

/** The auth code for an FRLA. Returns the token; throws [TokenError] otherwise. */
private fun exchangeCode(code: String): String {
    val payload = authPost(EXCHANGE_URL, linkedMapOf("mt_app_id" to APP_ID, "oidc_auth_code" to code, "redirect_uri" to META_REDIRECT_URI))
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
class WebLoginSource(private val cacheFile: File, private val sealer: KeystoreSealer) {

    /** True when an FRLA is cached. Says nothing about whether Meta still accepts it. */
    val hasCachedLogin: Boolean get() = loggedIn ?: (readCache() != null).also { loggedIn = it }

    @Volatile private var loggedIn: Boolean? = null

    // One token for the run: each exchange opens a new Meta session, and Meta ends the older ones, with the
    // requests still in flight on them.
    @Volatile private var token: Token? = null

    fun get(): Token {
        token?.let { return it }
        synchronized(this) {
            token?.let { return it }
            val cached = readCache() ?: throw TokenError("not logged in to Meta")
            try {
                return exchangeFrla(cached).also { token = it }
            } catch (error: TokenError) {
                // Only an FRLA Meta turned down means log in again.
                if (error is LoginRefused) throw TokenError("the saved Meta login no longer works (${error.message}); log in again")
                throw error
            }
        }
    }

    /** Meta no longer takes [dropped]: the next [get] exchanges the login for a new token. */
    fun forget(dropped: Token) {
        synchronized(this) { if (token == dropped) token = null }
    }

    fun begin(): AuthorizeRequest {
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
        return AuthorizeRequest("$AUTHORIZE_URL?$query")
    }

    /** The native login was handed a code: exchange it and cache the FRLA. */
    fun finishCode(code: String) {
        writeCache(exchangeCode(code))
    }

    /** The native login was handed the FRLA itself. */
    fun finishToken(frla: String) {
        writeCache(frla)
    }

    /** Forget the cached login. */
    fun logOut() {
        token = null
        cacheFile.delete()
        loggedIn = false
    }

    private fun readCache(): String? {
        if (!cacheFile.exists()) return null
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
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(sealer.seal(frla.toByteArray(Charsets.UTF_8)))
        loggedIn = true
    }
}
