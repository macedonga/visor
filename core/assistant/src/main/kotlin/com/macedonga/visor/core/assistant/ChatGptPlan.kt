package com.macedonga.visor.core.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.macedonga.visor.core.assistant.mcp.OAuth
import com.macedonga.visor.core.assistant.mcp.awaitLoopback
import com.macedonga.visor.core.assistant.mcp.loopbackSocket
import com.macedonga.visor.core.connection.objects
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sign in with ChatGPT: the person's Plus or Pro plan pays for the answers instead of an API key. OpenAI's flow for
 * open-source apps: a public client registered on first sign-in, PKCE, a loopback redirect on 127.0.0.1, then the
 * Responses API with the access token, streamed and stored nowhere.
 */
class ChatGptPlan(context: Context, private val store: AssistantConfigStore) {

    /** Where a sign-in stands. [Waiting.url] is the page to open in the browser. */
    sealed interface SignIn {
        data object Idle : SignIn
        data class Waiting(val url: String) : SignIn
        data object Finishing : SignIn
        data class Failed(val why: Why, val words: String = "") : SignIn
    }

    enum class Why { DECLINED, NOT_ELIGIBLE, EXPIRED, OTHER }

    private val prefs = context.getSharedPreferences("chatgpt_plan", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokens = Mutex()
    private var flow: Job? = null

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** What the sign-in and the plan did, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    private val _limited = MutableStateFlow(false)
    private val _notShared = MutableStateFlow(false)

    /**
     * ChatGPT answered that it does not share the plan with Visor (subscription_sharing_user_not_eligible): in #103 the
     * app's usage setting in ChatGPT, switched off and on again, made it share. OpenAI asks apps to say so, not to sign
     * in again; the sign-in and its registration stay, since the usage setting belongs to the registration.
     */
    val notShared: StateFlow<Boolean> = _notShared.asStateFlow()
    /** The plan's limit, or this app's, was reached; it clears with the next answer that goes through. */
    val limited: StateFlow<Boolean> = _limited.asStateFlow()

    private val _noteShown = MutableStateFlow(prefs.getBoolean(NOTE, false))
    /** The person has seen once that answers now use their plan. */
    val noteShown: StateFlow<Boolean> = _noteShown.asStateFlow()

    fun noteSeen() {
        _noteShown.value = true
        prefs.edit().putBoolean(NOTE, true).apply()
    }

    /**
     * Starts a sign-in: the listener first, then [SignIn.Waiting] with the page to open. A code the token endpoint
     * refuses is dropped and the page asked once more, now with the app id the first pass registered, as OpenAI's
     * guide says to.
     */
    fun start() {
        flow?.cancel()
        flow = scope.launch {
            try {
                try {
                    authorize()
                } catch (refused: TokenRefused) {
                    note("the code was refused (${refused.message}); asking again with the registered app id")
                    authorize()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val words = e.message ?: e.javaClass.simpleName
                note("sign-in failed: $words")
                _signIn.value = SignIn.Failed(Why.OTHER, words)
            }
        }
    }

    /** One pass through the browser and the token endpoint. */
    private suspend fun authorize() {
        val socket = runCatching { loopbackSocket(PORT) }.getOrElse { loopbackSocket(0) }
        try {
            val redirect = "http://127.0.0.1:${socket.localPort}$CALLBACK"
            val known = prefs.getString(CLIENT, null)
            val state = OAuth.secret(32)
            val nonce = OAuth.secret(32)
            val verifier = OAuth.secret(64)
            val page = Uri.parse(AUTHORIZE).buildUpon()
                .appendQueryParameter("client_id", known ?: DYNAMIC_CLIENT)
                .apply { if (known == null) appendQueryParameter("agent_name_hint", "Visor") }
                .appendQueryParameter("ext_agent_host_id", hostId())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("resource", OPENAI_API)
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge", OAuth.challengeOf(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .apply { prefs.getString(EMAIL, null)?.let { appendQueryParameter("login_hint", it) } }
                .build().toString()
            note("signing in " + (if (known == null) "as a new app" else "as app $known") + ", back on port ${socket.localPort}")
            _signIn.value = SignIn.Waiting(page)
            val back = withTimeoutOrNull(SIGN_IN_MS) { awaitLoopback(socket, CALLBACK) }
            if (back == null) {
                note("no answer from the browser in ${SIGN_IN_MS / 60_000} minutes")
                _signIn.value = SignIn.Idle
                return
            }
            _signIn.value = SignIn.Finishing
            val uri = Uri.parse(back)
            check(param(uri, "state") == state) { "the answer was not for this sign-in" }
            when (val error = param(uri, "error")) {
                null -> Unit
                "access_denied" -> {
                    note("consent declined")
                    _signIn.value = SignIn.Failed(Why.DECLINED)
                    return
                }
                else -> error(listOfNotNull(error, param(uri, "error_description")).joinToString(": "))
            }
            val issued = param(uri, "client_id")
            note("the browser came back" + (issued?.let { ", app id $it" } ?: ", no app id"))
            check(known == null || issued == null || issued == known) { "the answer came for another app ($issued)" }
            val client = known ?: issued ?: error("no app id came back")
            prefs.edit().putString(CLIENT, client).apply()
            val code = param(uri, "code") ?: error("no code came back")
            val t = form(
                "grant_type" to "authorization_code", "client_id" to client, "code" to code, "code_verifier" to verifier,
                "redirect_uri" to redirect, "resource" to OPENAI_API,
            )
            val granted = t.optString("scope").ifEmpty { uri.getQueryParameter("scope").orEmpty() }
            if (USE_SCOPE !in granted.split(' ', '+')) {
                note("signed in, but the plan was not granted (scope: $granted)")
                _signIn.value = SignIn.Failed(Why.NOT_ELIGIBLE)
                return
            }
            val claims = idClaims(t.optString("id_token"))
            // Straight from the token endpoint over TLS (OIDC Core 3.1.3.7): the claims are checked, not the signature.
            check(claims.optString("nonce") == nonce) { "the sign-in was not this one" }
            check(audience(claims).contains(client)) { "the sign-in was for another app" }
            check(claims.optString("iss").startsWith(ISSUER)) { "the sign-in came from ${claims.optString("iss")}" }
            keep(t)
            val email = claims.optString("email").ifEmpty { claims.optString("name") }.ifEmpty { "ChatGPT" }
            prefs.edit().putString(EMAIL, email).putString(ID_TOKEN, t.optString("id_token")).apply()
            note("signed in")
            _limited.value = false
            _notShared.value = false
            store.update { it.copy(chatGpt = email) }
            _signIn.value = SignIn.Idle
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Back in Visor while the page was still waiting: the person left it, so nothing waits on screen. The listener
     * stays, so a sign-in finished in the browser after all still counts.
     */
    fun leftBrowser() {
        val s = _signIn.value
        if (s is SignIn.Waiting && s.url == shown) _signIn.value = SignIn.Idle
    }

    // The page the screen opened; one not opened yet, such as the second pass's, is still to be shown.
    @Volatile private var shown = ""

    fun pageOpened(url: String) {
        shown = url
    }

    /** Stops a sign-in in progress, or clears how the last one went. */
    fun cancel() {
        flow?.cancel()
        flow = null
        _signIn.value = SignIn.Idle
    }

    /** Forgets the tokens; the app's registration stays, so signing in again is one consent. */
    fun signOut() {
        cancel()
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(ID_TOKEN).apply()
        _limited.value = false
        _notShared.value = false
        store.update { it.copy(chatGpt = "") }
    }

    /** The models the plan offers to this account, in the plan's order. */
    suspend fun models(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val http = URL("$OPENAI_API/models").openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 5_000
            http.readTimeout = 15_000
            http.setRequestProperty("Authorization", "Bearer ${token()}")
            val code = http.responseCode
            val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                note("the model list answered $code: ${text.take(200)}")
                throw ModelsError(refused = code == 401 || code == 403, message = problem(code, text))
            }
            // {"models": [{slug, display_name, visibility}]}: the ones to show are "list", in the server's order.
            val models = JSONObject(text).optJSONArray("models")?.objects().orEmpty()
                .filter { it.optString("visibility") == "list" && it.optString("slug").isNotBlank() }
                .map { ModelInfo(it.getString("slug"), it.optString("display_name").ifBlank { it.getString("slug") }, 0) }
            if (models.isEmpty()) note("the model list named no models: ${text.take(300)}")
            models
        } finally {
            http.disconnect()
        }
    }

    /**
     * One Responses API call paid by the plan: streamed, as the plan requires, and read to its last event. The final
     * response object, or an error worded as the other providers' are.
     */
    internal suspend fun respond(body: JSONObject): JSONObject {
        val request = JSONObject(body.toString()).put("stream", true).put("store", false)
        var refreshed = false
        while (true) {
            val (code, answer) = stream(request, token())
            if (code == 401 && !refreshed) {
                refreshed = true
                tokens.withLock { refresh() }
                continue
            }
            return answer
        }
    }

    /** The access token, renewed when it is about to run out. */
    private suspend fun token(): String = tokens.withLock {
        val access = prefs.getString(ACCESS, null)
        if (store.config.value.chatGpt.isEmpty() || access == null) error("not signed in to ChatGPT")
        if (System.currentTimeMillis() < prefs.getLong(EXPIRES, 0) - RENEW_EARLY_MS) access else refresh()
    }

    /** A new access token and its replacement refresh token; a refused refresh means signing in again. */
    private suspend fun refresh(): String {
        val client = prefs.getString(CLIENT, null)
        val refresh = prefs.getString(REFRESH, null)
        if (client == null || refresh == null) expired()
        val t = try {
            form("grant_type" to "refresh_token", "client_id" to client, "refresh_token" to refresh, "resource" to OPENAI_API)
        } catch (e: TokenRefused) {
            note("the session could not be renewed (${e.message}); signed out")
            expired()
        }
        keep(t)
        return t.getString("access_token")
    }

    private fun expired(): Nothing {
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).apply()
        store.update { it.copy(chatGpt = "") }
        _signIn.value = SignIn.Failed(Why.EXPIRED)
        error("the ChatGPT sign-in expired; sign in again in Meta AI settings")
    }

    private fun keep(t: JSONObject) {
        prefs.edit()
            .putString(ACCESS, t.getString("access_token"))
            .apply { t.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString(REFRESH, it) } }
            .putLong(EXPIRES, System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000)
            .apply()
    }

    private fun hostId(): String = prefs.getString(HOST, null) ?: ("urn:uuid:" + UUID.randomUUID()).also { prefs.edit().putString(HOST, it).commit() }

    /** A form post to the token endpoint. */
    private suspend fun form(vararg fields: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val http = URL(TOKEN).openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 10_000
            http.readTimeout = 20_000
            http.doOutput = true
            http.setRequestProperty("content-type", "application/x-www-form-urlencoded")
            http.setRequestProperty("accept", "application/json")
            http.outputStream.use { out ->
                out.write(fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray())
            }
            val code = http.responseCode
            val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                val err = json.optString("error")
                val words = listOf(err, json.optString("error_description")).filter { it.isNotEmpty() }.joinToString(": ")
                if (err in REFUSED_GRANTS) throw TokenRefused(words)
                error(words.ifEmpty { "the sign-in answered $code" })
            }
            json
        } finally {
            http.disconnect()
        }
    }

    /** The streamed call: the HTTP status and the final response; a stream that fails throws its error. */
    private suspend fun stream(body: JSONObject, token: String): Pair<Int, JSONObject> = suspendCancellableCoroutine { call ->
        val http = URL("$OPENAI_API/responses").openConnection() as HttpURLConnection
        call.invokeOnCancellation { thread { http.disconnect() } }
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 5_000
            http.readTimeout = 45_000
            http.doOutput = true
            http.setRequestProperty("content-type", "application/json")
            http.setRequestProperty("accept", "text/event-stream")
            http.setRequestProperty("Authorization", "Bearer $token")
            http.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = http.responseCode
            if (code == 401) {
                call.resume(401 to JSONObject())
                return@suspendCancellableCoroutine
            }
            if (code !in 200..299) {
                val text = http.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                failed(code, runCatching { JSONObject(text) }.getOrNull())
            }
            var done: JSONObject? = null
            val items = JSONArray()
            val deltas = StringBuilder()
            val seen = linkedMapOf<String, Int>()
            http.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val event = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    val type = event.optString("type")
                    seen[type] = (seen[type] ?: 0) + 1
                    when (type) {
                        "response.output_item.done" -> event.optJSONObject("item")?.let(items::put)
                        "response.output_text.delta" -> deltas.append(event.optString("delta"))
                        "response.completed", "response.incomplete" -> {
                            done = event.getJSONObject("response")
                            break
                        }
                        // Mid-stream there is no status: a failure counts as the server's, unless failed() knows better.
                        "response.failed" -> failed(502, event.optJSONObject("response"))
                        "error" -> failed(502, event)
                    }
                }
            }
            val answer = done ?: error("the answer stopped before it was complete")
            // OpenAI's own client reads the answer from the stream, not from the last event: the items, else the text.
            if ((answer.optJSONArray("output")?.length() ?: 0) == 0) {
                when {
                    items.length() > 0 -> answer.put("output", items)
                    deltas.isNotEmpty() -> answer.put(
                        "output",
                        JSONArray().put(
                            JSONObject().put("type", "message").put("role", "assistant")
                                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", deltas.toString()))),
                        ),
                    )
                    else -> note("an answer came back with no output (${answer.optString("status")}; events ${seen.entries.joinToString { "${it.key} x${it.value}" }})")
                }
            }
            _limited.value = false
            _notShared.value = false
            call.resume(code to answer)
        } catch (error: Exception) {
            call.resumeWithException(error)
        } finally {
            http.disconnect()
        }
    }

    /** An error from the plan, worded as the other providers' are; a reached limit is kept for the settings to show. */
    private fun failed(status: Int, json: JSONObject?): Nothing {
        // A response carries its error; a stream's error event is one.
        val err = json?.optJSONObject("error") ?: json
        val code = err?.optString("code").orEmpty()
        if (code == LIMIT) {
            if (!_limited.value) note("usage limit reached")
            _limited.value = true
            throw ModelRefused(429, "ChatGPT usage limit reached", Refusal.LIMIT)
        }
        if (code == NOT_ELIGIBLE) {
            if (!_notShared.value) note("ChatGPT does not share the plan with Visor; its usage setting for Visor decides")
            _notShared.value = true
            throw ModelRefused(403, "ChatGPT does not share this account's plan with Visor", Refusal.PLAN)
        }
        val words = err?.optString("message").orEmpty().ifEmpty { json?.optString("detail").orEmpty() }.ifEmpty { code }
        // A capability the plan does not offer, such as a tool, is refused as a server refuses a field.
        val shown = if (code == UNSUPPORTED) 400 else status
        error("the model answered $shown: ${words.ifEmpty { "no reason given" }}")
    }

    private fun problem(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        return json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
            ?: json?.optString("detail")?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
    }

    private class TokenRefused(code: String) : IOException(code)

    private companion object {
        const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
        const val ISSUER = "https://auth.openai.com"
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val USE_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $USE_SCOPE"
        const val CALLBACK = "/auth/callback"
        const val PORT = 1455
        const val SIGN_IN_MS = 10 * 60_000L
        const val RENEW_EARLY_MS = 2 * 60_000L
        const val LIMIT = "subscription_sharing_usage_limit_exceeded"
        const val NOT_ELIGIBLE = "subscription_sharing_user_not_eligible"
        const val UNSUPPORTED = "subscription_sharing_unsupported_capability"
        val REFUSED_GRANTS = setOf("invalid_grant", "refresh_token_reused", "refresh_token_expired", "refresh_token_invalidated")

        const val HOST = "host_id"
        const val CLIENT = "client_id"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expires_at"
        const val ID_TOKEN = "id_token"
        const val EMAIL = "email"
        const val NOTE = "plan_note_shown"

        /** A callback parameter as it came: only %-escapes decoded, so nothing in a code can turn into a space. */
        fun param(uri: Uri, name: String): String? = uri.encodedQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            if (Uri.decode(pair.substringBefore('=')) == name) Uri.decode(pair.substringAfter('=', "")) else null
        }

        fun idClaims(jwt: String): JSONObject {
            val payload = jwt.split('.').getOrNull(1) ?: error("no identity came back")
            return JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        }

        fun audience(claims: JSONObject): List<String> =
            claims.optJSONArray("aud")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(claims.optString("aud"))
    }
}
