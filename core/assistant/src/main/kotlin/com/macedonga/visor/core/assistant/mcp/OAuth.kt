package com.macedonga.visor.core.assistant.mcp

import android.util.Base64
import com.macedonga.visor.core.connection.strings
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Where a server sends people to sign in, read from its own metadata. */
internal data class AuthServer(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String,
    val scope: String,
    val resource: String,
)

/** Where a sign-in sends the browser back. */
object OAuthRedirects {
    /** The app's own redirect, a private-use scheme (RFC 8252). */
    const val APP = "com.macedonga.visor:/oauth"

    /** For servers that only take loopback redirects; the port is fixed so a client registered by hand keeps working. */
    const val LOOPBACK_PORT = 47219
    const val LOOPBACK = "http://127.0.0.1:$LOOPBACK_PORT/callback"
}

/** The authorization server would not register this redirect. */
internal class RedirectRefused(message: String) : IOException(message)

/**
 * OAuth 2.1 the way MCP asks for it: protected resource metadata (RFC 9728), authorization server metadata (RFC 8414,
 * or OpenID discovery), dynamic client registration (RFC 7591), PKCE with S256, and the resource indicator (RFC 8707).
 */
internal class OAuth(private val http: OkHttpClient) {

    /** How [server] signs people in, from its 401 [challenge] and the well-known documents; null when it does not. */
    suspend fun discover(server: HttpUrl, challenge: String?): AuthServer? {
        val asked = challengeParams(challenge)
        val resourceDoc = (listOfNotNull(asked["resource_metadata"]) + wellKnown(server, "oauth-protected-resource"))
            .firstNotNullOfOrNull { getJson(it)?.takeIf { j -> j.has("authorization_servers") } }
        val issuer = resourceDoc?.optJSONArray("authorization_servers")?.optString(0)?.takeIf { it.startsWith("http") }
            ?: origin(server)
        val meta = issuerDocuments(issuer).firstNotNullOfOrNull { url ->
            getJson(url)?.takeIf { it.str("authorization_endpoint").isNotEmpty() && it.str("token_endpoint").isNotEmpty() }
        } ?: return null
        val scopes = resourceDoc?.optJSONArray("scopes_supported")?.strings()?.joinToString(" ")
        return AuthServer(
            issuer = meta.str("issuer").ifEmpty { issuer },
            authorizationEndpoint = meta.str("authorization_endpoint"),
            tokenEndpoint = meta.str("token_endpoint"),
            registrationEndpoint = meta.str("registration_endpoint"),
            scope = asked["scope"] ?: scopes.orEmpty(),
            resource = resourceDoc?.str("resource")?.takeIf { it.isNotEmpty() } ?: canonical(server),
        )
    }

    /** Registers this app as a public client for [redirect]. */
    suspend fun register(a: AuthServer, redirect: String): OAuthGrant {
        fun body(strict: Boolean) = JSONObject()
            .put("client_name", "Visor")
            .put("client_uri", "https://github.com/macedonga/visor")
            .put("redirect_uris", JSONArray().put(redirect))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .apply {
                if (strict) put("token_endpoint_auth_method", "none").put("application_type", "native")
                if (a.scope.isNotBlank()) put("scope", a.scope)
            }
        var answer: JSONObject? = null
        for (strict in listOf(true, false)) {
            val request = Request.Builder().url(a.registrationEndpoint).post(body(strict).toString().toRequestBody(JSON))
                .header("Accept", "application/json").build()
            val (code, text) = http.newCall(request).fetch { it.code to it.body.string() }
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && o != null && o.str("client_id").isNotEmpty()) {
                answer = o
                break
            }
            val error = o?.str("error").orEmpty()
            if (error == "invalid_redirect_uri") throw RedirectRefused(o?.str("error_description").orEmpty().ifEmpty { "redirect refused" })
            if (!strict) throw McpException(McpException.Kind.UNAUTHORIZED, "the server would not register Visor: " + (o?.str("error_description")?.ifEmpty { null } ?: error.ifEmpty { "HTTP $code" }))
        }
        val o = answer!!
        val secret = o.str("client_secret")
        return OAuthGrant(
            issuer = a.issuer,
            authorizationEndpoint = a.authorizationEndpoint,
            tokenEndpoint = a.tokenEndpoint,
            registrationEndpoint = a.registrationEndpoint,
            clientId = o.str("client_id"),
            clientSecret = secret,
            tokenAuth = o.str("token_endpoint_auth_method").ifEmpty { if (secret.isEmpty()) "none" else "client_secret_basic" },
            redirectUri = redirect,
            scope = a.scope,
            resource = a.resource,
        )
    }

    /** Where the browser goes to sign in. */
    fun authorizeUrl(g: OAuthGrant, state: String, verifier: String): String {
        val url = g.authorizationEndpoint.toHttpUrlOrNull() ?: throw McpException(McpException.Kind.PROTOCOL, "bad authorization endpoint")
        return url.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", g.clientId)
            .addQueryParameter("redirect_uri", g.redirectUri)
            .addQueryParameter("code_challenge", challengeOf(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .apply {
                if (g.scope.isNotBlank()) addQueryParameter("scope", g.scope)
                if (g.resource.isNotBlank()) addQueryParameter("resource", g.resource)
            }
            .build().toString()
    }

    suspend fun exchange(g: OAuthGrant, code: String, verifier: String): OAuthGrant = token(
        g,
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to g.redirectUri, "code_verifier" to verifier,
    )

    suspend fun refresh(g: OAuthGrant): OAuthGrant = token(g, "grant_type" to "refresh_token", "refresh_token" to g.refreshToken)

    private suspend fun token(g: OAuthGrant, vararg fields: Pair<String, String>): OAuthGrant {
        val form = FormBody.Builder()
        fields.forEach { (k, v) -> form.add(k, v) }
        if (g.resource.isNotBlank()) form.add("resource", g.resource)
        val request = Request.Builder().url(g.tokenEndpoint).header("Accept", "application/json")
        when (g.tokenAuth) {
            "client_secret_basic" -> request.header("Authorization", "Basic " + b64("${enc(g.clientId)}:${enc(g.clientSecret)}".toByteArray(), Base64.NO_WRAP))
            "client_secret_post" -> form.add("client_id", g.clientId).add("client_secret", g.clientSecret)
            else -> form.add("client_id", g.clientId)
        }
        val (code, text) = http.newCall(request.post(form.build()).build()).fetch { it.code to it.body.string() }
        val o = runCatching { JSONObject(text) }.getOrNull()
        val access = o?.str("access_token").orEmpty()
        if (code !in 200..299 || o == null || access.isEmpty()) {
            val why = o?.str("error_description")?.ifEmpty { null } ?: o?.str("error")?.ifEmpty { null } ?: "HTTP $code"
            throw McpException(McpException.Kind.UNAUTHORIZED, "sign-in refused: $why")
        }
        val life = o.optLong("expires_in", 0)
        return g.copy(
            accessToken = access,
            // A server that does not rotate refresh tokens leaves the old one good.
            refreshToken = o.str("refresh_token").ifEmpty { g.refreshToken },
            expiresAt = if (life > 0) System.currentTimeMillis() + life * 1000 else 0,
            lifeMs = life * 1000,
        )
    }

    private suspend fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("MCP-Protocol-Version", McpConnection.LATEST).build()
        http.newCall(request).fetch { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
    }.getOrNull()

    companion object {
        private val random = SecureRandom()
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun secret(length: Int = 64): String = String(CharArray(length) { UNRESERVED[random.nextInt(UNRESERVED.length)] })

        fun challengeOf(verifier: String): String =
            b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun b64(bytes: ByteArray, flags: Int) = Base64.encodeToString(bytes, flags)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** The parameters of a WWW-Authenticate header, quoted or not. */
        fun challengeParams(header: String?): Map<String, String> {
            header ?: return emptyMap()
            return Regex("""([A-Za-z_]+)=(?:"([^"]*)"|([^\s,]+))""").findAll(header).associate { m ->
                m.groupValues[1] to m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
        }

        private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}" + if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""

        /** The server's URL as the resource it is: no fragment, no trailing slash. */
        fun canonical(url: HttpUrl): String = url.newBuilder().fragment(null).build().toString().trimEnd('/')

        /** RFC 9728's places for [name]: under the endpoint's path first, then at the root. */
        private fun wellKnown(url: HttpUrl, name: String): List<String> {
            val path = url.encodedPath.trimEnd('/')
            return listOfNotNull(
                if (path.isNotEmpty()) "${origin(url)}/.well-known/$name$path" else null,
                "${origin(url)}/.well-known/$name",
            )
        }

        /** The places an issuer's metadata can be, in the order MCP gives. */
        private fun issuerDocuments(issuer: String): List<String> {
            val url = issuer.toHttpUrlOrNull() ?: return emptyList()
            val path = url.encodedPath.trimEnd('/')
            val o = origin(url)
            return if (path.isEmpty()) {
                listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
            } else {
                listOf(
                    "$o/.well-known/oauth-authorization-server$path",
                    "$o/.well-known/openid-configuration$path",
                    "${issuer.trimEnd('/')}/.well-known/openid-configuration",
                )
            }
        }
    }
}

/**
 * The loopback redirect: one request to 127.0.0.1, answered with a page that sends the person back to the app.
 * Returns the full redirect URL; cancelling closes the socket.
 */
internal suspend fun awaitLoopback(port: Int): String = coroutineScope {
    val server = withContext(Dispatchers.IO) { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }
    val watcher = launch { try { awaitCancellation() } finally { runCatching { server.close() } } }
    try {
        withContext(Dispatchers.IO) {
            var target = ""
            while (!target.startsWith("/callback")) {
                target = server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val page = "<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px sans-serif;" +
                        "background:#000;color:#fff;display:grid;place-items:center;height:90vh'><div style='text-align:center'>" +
                        "<a href='${OAuthRedirects.APP}' style='background:#fff;color:#000;padding:12px 24px;border-radius:999px;text-decoration:none'>Visor</a></div>" +
                        "<script>location.href='${OAuthRedirects.APP}'</script>"
                    val bytes = page.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                    line.split(' ').getOrNull(1).orEmpty()
                }
            }
            "http://127.0.0.1:$port$target"
        }
    } finally {
        watcher.cancel()
        runCatching { server.close() }
    }
}
