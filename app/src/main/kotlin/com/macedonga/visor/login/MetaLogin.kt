package com.macedonga.visor.login

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.KeystoreSealer
import com.macedonga.visor.core.identity.UrlConnectionPost
import com.macedonga.visor.core.identity.bloks.BloksClient
import com.macedonga.visor.core.identity.bloks.BloksHost
import com.macedonga.visor.core.identity.bloks.BloksSession
import com.macedonga.visor.core.identity.pyUrlencode
import com.macedonga.visor.core.identity.token.AuthorizeRequest
import com.macedonga.visor.core.identity.token.SessionEnded
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.identity.token.WebLoginSource
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Meta login, for as long as the process lives. */
@Singleton
class MetaLogin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: EventLog,
) {
    val source = WebLoginSource(
        cacheFile = File(context.filesDir, "meta_login.sealed"),
        sealer = KeystoreSealer(alias = "visor.meta.login"),
    )

    sealed interface Status {
        data object LoggedOut : Status
        data object Working : Status
        data object LoggedIn : Status
        data class Failed(val reason: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<Status>(Status.LoggedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The request the login page is showing, or null when no login is under way. */
    var pending: AuthorizeRequest? = null
        private set

    init {
        scope.launch { refresh() }
    }

    fun refresh() {
        if (_status.value is Status.Working) return
        _status.value = if (source.hasCachedLogin) Status.LoggedIn else Status.LoggedOut
    }

    fun begin(): AuthorizeRequest = source.begin().also {
        pending = it
        log.add("Login: opening Meta's login page")
    }

    /** One query on Meta's ar GraphQL as the user, its answer as text. A token Meta has dropped is replaced, once. */
    fun graphql(name: String, doc: String, variables: String): String {
        val body = pyUrlencode(
            linkedMapOf(
                "fb_api_req_friendly_name" to name, "client_doc_id" to doc, "variables" to variables,
                "method" to "post", "locale" to "en_US", "pretty" to "false", "format" to "json",
            ),
        ).toByteArray()
        return source.withToken { token ->
            post(token, body).also { answer ->
                // OAuthException 190: the session behind the token has ended.
                if (runCatching { JSONObject(answer).optJSONObject("error")?.optInt("code") }.getOrNull() == 190) throw SessionEnded()
            }
        }
    }

    private fun post(token: Token, body: ByteArray): String =
        String(UrlConnectionPost.post(GRAPH_URL, mapOf("Authorization" to "OAuth ${token.value}", "Content-Type" to "application/x-www-form-urlencoded"), body).body)

    /** What the login page saw: hosts, paths and statuses, never a query string. */
    fun note(text: String) = log.add("Login page: $text")

    /** The WebView caught the redirect. Exchange its code and cache the FRLA. */
    fun finish(redirect: String) {
        val request = pending
        if (request == null) {
            log.add("Login: a redirect arrived with no login under way; ignored")
            return
        }
        pending = null
        _status.value = Status.Working
        val carried = Uri.parse(redirect).queryParameterNames.sorted().joinToString(", ").ifEmpty { "nothing" }
        log.add("Login: Meta redirected back carrying $carried; exchanging the code")
        scope.launch {
            _status.value = try {
                source.finish(redirect, request)
                log.add("Login: code exchanged, Meta login cached")
                log.add("Login: logged in to Meta")
                Status.LoggedIn
            } catch (error: Exception) {
                log.add("Login failed: ${error.message}")
                Status.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** The native login (Meta's Bloks screens, as the Meta AI app runs them) for the request [begin] made. */
    fun nativeSession(darkMode: Boolean, host: BloksHost): BloksSession {
        // Debug builds keep every answer, so a screen that misbehaves on a phone can be read afterwards.
        val dump = File(context.filesDir, "bloks").takeIf { BuildConfig.DEBUG }?.apply {
            deleteRecursively()
            mkdirs()
        }
        var answers = 0
        val http = HttpPost { url, headers, body ->
            UrlConnectionPost.post(url, headers, body).also { reply ->
                dump?.let { File(it, "%02d-%s.json".format(++answers, headers["X-FB-Friendly-Name"].orEmpty().substringAfterLast('.'))).writeBytes(reply.body) }
            }
        }
        val client = BloksClient(
            token = com.macedonga.visor.core.identity.BuildConfig.META_FRL_TOKEN,
            locale = Locale.getDefault().toString(),
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            http = http,
        )
        val trace = traceFile(context).apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val started = System.currentTimeMillis()
        return BloksSession(
            client,
            deviceId,
            darkMode,
            log = { line -> log.add("Login: $line") },
            trace = { line ->
                // A login is a few thousand lines; past a few megabytes something is looping and the rest says nothing new.
                if (trace.length() < TRACE_LIMIT) {
                    runCatching { trace.appendText("%7.3f  %s\n".format((System.currentTimeMillis() - started) / 1000.0, line)) }
                }
            },
            host = host,
        )
    }

    /** How the native login ended. A code goes through the same exchange as the web login's; a token is the FRLA. */
    fun finishNative(outcome: BloksSession.Outcome) {
        pending = null
        when (outcome) {
            is BloksSession.Outcome.Cancelled -> {
                log.add("Login: closed before the end")
                refresh()
            }
            is BloksSession.Outcome.Failed -> {
                log.add("Login failed: ${outcome.reason}")
                _status.value = Status.Failed(outcome.reason)
            }
            is BloksSession.Outcome.Code, is BloksSession.Outcome.Token -> {
                _status.value = Status.Working
                scope.launch {
                    _status.value = try {
                        if (outcome is BloksSession.Outcome.Code) {
                            log.add("Login: Meta handed a code; exchanging it")
                            source.finishCode(outcome.code)
                        } else {
                            log.add("Login: Meta handed the account token")
                            source.finishToken((outcome as BloksSession.Outcome.Token).accessToken)
                        }
                        log.add("Login: logged in to Meta")
                        Status.LoggedIn
                    } catch (error: Exception) {
                        log.add("Login failed: ${error.message}")
                        Status.Failed(error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    companion object {
        private const val TRACE_LIMIT = 4L * 1024 * 1024
        private const val GRAPH_URL = "https://ar-genai.graph.meta.com/graphql"

        /** Every Bloks call of the last native login, shared with the event log. */
        fun traceFile(context: Context): File = File(context.filesDir, "bloks-trace.log")
    }

    /** One id per install, as the Meta app keeps its `device_id`: the login's server state is tied to it. */
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("meta_login", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }

    /** Forget the cached login and the page's session, so the next login starts clean. */
    fun logOut() {
        source.logOut()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _status.value = Status.LoggedOut
        log.add("Login: logged out of Meta")
    }
}
