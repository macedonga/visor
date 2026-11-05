package com.macedonga.visor.core.assistant.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** What a server is doing, for the settings and for the assistant. */
data class McpStatus(
    val phase: Phase = Phase.IDLE,
    val tools: List<McpTool> = emptyList(),
    val info: ServerInfo? = null,
    val error: String = "",
    val checkedAt: Long = 0,
    val latencyMs: Long = 0,
    // A check is running; the rest still says how the last one went.
    val busy: Boolean = false,
    // The server's icon, as image bytes, when one was found.
    val icon: ByteArray? = null,
) {
    enum class Phase { IDLE, OFF, READY, SIGN_IN, NEEDS_KEY, REFUSED, LOCAL_NETWORK, UNREACHABLE, NOT_MCP, FAILED }
}

/** A tool as the model sees it, under a name unique across servers. */
data class ExposedTool(val name: String, val server: McpServer, val tool: McpTool)

/** The tools for one answer, and what the model should know about them. */
class Toolbox(val tools: List<ExposedTool>, val prompt: String) {
    fun find(name: String): ExposedTool? = tools.firstOrNull { it.name == name }
}

/** Something the assistant did through a tool, for the diary. [kind] and [subject] name Visor's own tools for the app to word; empty for a server's. */
data class ToolAction(val server: String, val what: String, val ok: Boolean, val kind: String = "", val subject: String = "")

/** A sign-in in progress. */
sealed interface SignIn {
    val id: String

    data object Idle : SignIn {
        override val id = ""
    }

    data class Preparing(override val id: String) : SignIn
    data class InBrowser(override val id: String, val url: String) : SignIn
    data class Finishing(override val id: String) : SignIn
    data class Done(override val id: String) : SignIn

    // needsClient: the server registers no apps by itself, so a client ID has to be typed in.
    data class Failed(override val id: String, val why: String, val needsClient: Boolean = false) : SignIn
}

/** What trying an address found, before it is saved. */
sealed interface Probe {
    data object Works : Probe
    data object SignIn : Probe
    data object NeedsKey : Probe
    data class Failed(val phase: McpStatus.Phase) : Probe
}

/**
 * Every MCP server the person added: one session each, their tools listed ahead of the questions and kept fresh, the
 * calls the model makes, tokens refreshed before they run out, and sign-ins through the browser.
 */
class McpHub(private val context: Context, val store: McpStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // The older transport's event stream may stay quiet for minutes.
    private val stream = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val oauth = OAuth(http)
    private val icons = ServerIcons(http)
    private val iconsTried = ConcurrentHashMap<String, String>()
    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"

    private val connections = HashMap<String, Pair<String, McpConnection>>()
    private val checks = ConcurrentHashMap<String, Job>()
    private val tokenLocks = ConcurrentHashMap<String, Mutex>()

    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** What happened, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    private class Pending(val id: String, val state: String, val verifier: String, val grant: OAuthGrant, val loopback: Job?)

    @Volatile
    private var pending: Pending? = null

    private var networkJob: Job? = null

    init {
        // Follows the list: a server added or changed is checked, one removed or turned off is let go.
        scope.launch {
            var before = emptyMap<String, McpServer>()
            store.servers.collect { list ->
                val now = list.associateBy { it.id }
                for (id in before.keys - now.keys) {
                    stop(id)
                    _status.update { it - id }
                }
                for (s in list) {
                    val old = before[s.id]
                    when {
                        !s.enabled -> {
                            stop(s.id)
                            setStatus(s.id) { it.copy(phase = McpStatus.Phase.OFF, busy = false) }
                        }
                        old == null || !old.enabled || old.connectionKey != s.connectionKey -> {
                            stop(s.id)
                            check(s.id)
                        }
                    }
                }
                before = now
            }
        }
        // Back on a network, a server that could not be reached may be reachable: the home one, coming home.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
            })
        }
        // Sessions nobody used for a while are let go; the older transport holds a stream open for each.
        scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val idle = synchronized(connections) {
                    connections.filterValues { now() - it.second.lastUsed > IDLE_MS }.keys.toList()
                }
                idle.forEach(::close)
            }
        }
    }

    /** Lists [id]'s tools again; a check already running is not doubled. */
    fun check(id: String) {
        if (checks[id]?.isActive == true) return
        checks[id] = scope.launch { list(id) }
    }

    private suspend fun list(id: String) {
        val s = store.get(id)?.takeIf { it.enabled } ?: return
        setStatus(id) { it.copy(busy = true) }
        val started = now()
        try {
            val c = connection(s)
            val tools = c.tools()
            val info = c.info
            if (info != null && info.transport != s.transport) store.update(id) { it.copy(transport = info.transport) }
            setStatus(id) { it.copy(phase = McpStatus.Phase.READY, tools = tools, info = info, error = "", checkedAt = now(), latencyMs = now() - started, busy = false) }
            findIcon(s, info)
            note("${s.name}: ${tools.size} tools, ${transportWords(info?.transport)}, MCP ${info?.protocol}, ${now() - started} ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val e = (error as? McpException) ?: McpException(McpException.Kind.PROTOCOL, error.message ?: error.javaClass.simpleName, cause = error)
            val phase = phaseOf(s, e)
            setStatus(id) { it.copy(phase = phase, error = e.message.orEmpty(), checkedAt = now(), busy = false) }
            // A server that wants a sign-in still has a website with an icon.
            if (phase !in setOf(McpStatus.Phase.UNREACHABLE, McpStatus.Phase.LOCAL_NETWORK)) findIcon(s, null)
            note("${s.name}: ${e.message}")
        }
    }

    /** Looks for [s]'s icon once per address, and again when the server has said who it is. */
    private fun findIcon(s: McpServer, info: ServerInfo?) {
        val key = "${s.url}|${info != null}"
        if (iconsTried.put(s.id, key) == key) return
        scope.launch {
            val url = s.url.trim().toHttpUrlOrNull() ?: return@launch
            val icon = icons.find(url, info) ?: return@launch
            if (store.get(s.id)?.url == s.url) setStatus(s.id) { it.copy(icon = icon) }
        }
    }

    /** What the person has to do about [e], given how [s] signs in. */
    private suspend fun phaseOf(s: McpServer, e: McpException): McpStatus.Phase = when (e.kind) {
        McpException.Kind.UNAUTHORIZED -> when (s.auth) {
            AuthKind.OAUTH -> McpStatus.Phase.SIGN_IN
            AuthKind.NONE -> if (selfService(s.url, e.challenge) != null) McpStatus.Phase.SIGN_IN else McpStatus.Phase.NEEDS_KEY
            else -> McpStatus.Phase.REFUSED
        }
        McpException.Kind.FORBIDDEN -> McpStatus.Phase.REFUSED
        McpException.Kind.LOCAL_NETWORK -> McpStatus.Phase.LOCAL_NETWORK
        McpException.Kind.UNREACHABLE, McpException.Kind.TIMEOUT -> McpStatus.Phase.UNREACHABLE
        McpException.Kind.NOT_MCP -> McpStatus.Phase.NOT_MCP
        else -> McpStatus.Phase.FAILED
    }

    /**
     * The tools for an answer. Lists that are missing or old are asked for again, and the answer waits for them only
     * briefly; a server that cannot be reached is left out rather than holding the wearer up.
     */
    suspend fun toolbox(): Toolbox {
        val servers = store.servers.value.filter { it.enabled }
        if (servers.isEmpty()) return Toolbox(emptyList(), "")
        val stale = servers.filter { s ->
            val st = _status.value[s.id] ?: return@filter true
            val age = now() - st.checkedAt
            when (st.phase) {
                McpStatus.Phase.READY -> age > STALE_MS
                McpStatus.Phase.IDLE -> true
                McpStatus.Phase.SIGN_IN, McpStatus.Phase.NEEDS_KEY, McpStatus.Phase.REFUSED, McpStatus.Phase.OFF -> false
                else -> age > RETRY_MS
            }
        }
        stale.forEach { check(it.id) }
        if (stale.isNotEmpty()) withTimeoutOrNull(WAIT_MS) { stale.forEach { checks[it.id]?.join() } }
        val exposed = ArrayList<ExposedTool>()
        val taken = HashSet<String>()
        val instructions = StringBuilder()
        val names = ArrayList<String>()
        for (s in servers) {
            val st = _status.value[s.id]
            if (st == null || st.phase != McpStatus.Phase.READY) {
                note("${s.name}: left out of this answer, ${st?.error?.takeIf { it.isNotEmpty() } ?: "still connecting"}")
                continue
            }
            val tools = st.tools.filter { it.name !in s.off }
            if (tools.isEmpty()) continue
            names += s.name
            for (t in tools) exposed += ExposedTool(exposedName(s, t, taken), s, t)
            st.info?.instructions?.takeIf { it.isNotBlank() }?.let { instructions.append(" Notes from ${s.name}: ${it.trim().take(MAX_INSTRUCTIONS)}") }
        }
        if (exposed.isEmpty()) return Toolbox(emptyList(), "")
        val prompt = " You can also use tools from ${names.joinToString()}. Use them whenever the wearer asks you to do, check " +
            "or find something they cover, without asking first unless the request is unclear; after acting, say in a few " +
            "words what was done. Never say something was done unless a tool confirmed it. Earlier answers end with Visor's note " +
            "in square brackets of what the tools did for them; one without a note did nothing. Never write such a note " +
            "yourself, and call the tool each time an action is asked, even one done before." + instructions
        return Toolbox(exposed, prompt)
    }

    /**
     * Runs [t] for the model, with [photo] in place of its placeholders. A failure comes back as text the model can
     * say, never as an exception.
     */
    suspend fun call(t: ExposedTool, arguments: JSONObject, photo: ByteArray? = null): Pair<ToolResult, ToolAction?> {
        val started = now()
        val s = store.get(t.server.id)?.takeIf { it.enabled } ?: run {
            note("${t.server.name}: ${t.tool.name} -> not run, the server is off")
            return ToolResult("The tool did not run: its server is off.", true) to null
        }
        val result = try {
            connection(s).call(t.tool.name, withPhoto(arguments, photo), CALL_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if ((error as? McpException)?.kind != McpException.Kind.TIMEOUT) check(s.id)
            ToolResult("The tool did not run: ${error.message}", true)
        }
        note("${s.name}: ${t.tool.name} (${arguments.keys().asSequence().joinToString()}) -> ${if (result.isError) "error: " + result.text.take(160) else "ok"}, ${now() - started} ms")
        val lookup = t.tool.readOnly || LOOKUP.containsMatchIn(t.tool.name)
        return result to if (lookup) null else ToolAction(s.name, describe(t.tool, arguments), !result.isError)
    }

    /** Tries an address before it is saved: whether it answers, and what it needs to. */
    suspend fun probe(url: String, auth: AuthKind, secret: String, header: String): Probe {
        val u = url.trim().toHttpUrlOrNull() ?: return Probe.Failed(McpStatus.Phase.NOT_MCP)
        val trial = McpServer(name = "", url = url, auth = auth, secret = secret, header = header)
        val c = McpConnection(u, Transport.AUTO, http, stream, fixedAuth(trial), scope, version) {}
        return try {
            c.tools()
            Probe.Works
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val e = (error as? McpException) ?: McpException(McpException.Kind.PROTOCOL, error.message.orEmpty())
            when {
                e.kind == McpException.Kind.UNAUTHORIZED && auth == AuthKind.NONE ->
                    if (selfService(url, e.challenge) != null) Probe.SignIn else Probe.NeedsKey
                else -> Probe.Failed(phaseOf(trial, e))
            }
        } finally {
            c.close()
        }
    }

    /** Signs [id] in; [signIn] carries the page to open, then how it went. Runs on even if the screen goes. */
    fun signIn(id: String) {
        scope.launch { beginSignIn(id) }
    }

    /** Finds where [id] signs in, registers Visor there unless a client was typed in, and puts the page to open in [signIn]. */
    private suspend fun beginSignIn(id: String) {
        val s = store.get(id) ?: return
        pending?.loopback?.cancel()
        pending = null
        _signIn.value = SignIn.Preparing(id)
        try {
            val url = s.url.toHttpUrlOrNull() ?: throw McpException(McpException.Kind.NOT_MCP, "not a web address")
            val at = oauth.discover(url, challengeOf(url))
                ?: throw McpException(McpException.Kind.PROTOCOL, "this server does not say where to sign in")
            val kept = s.oauth?.takeIf { it.clientId.isNotEmpty() && !it.manualClient && it.issuer == at.issuer && it.registrationEndpoint == at.registrationEndpoint }
            val grant = when {
                s.clientId.isNotBlank() -> OAuthGrant(
                    issuer = at.issuer, authorizationEndpoint = at.authorizationEndpoint, tokenEndpoint = at.tokenEndpoint,
                    clientId = s.clientId.trim(), clientSecret = s.clientSecret.trim(),
                    tokenAuth = if (s.clientSecret.isBlank()) "none" else "client_secret_post",
                    redirectUri = s.redirect.ifEmpty { OAuthRedirects.APP },
                    scope = at.scope, resource = at.resource, manualClient = true,
                )
                kept != null -> kept.copy(authorizationEndpoint = at.authorizationEndpoint, tokenEndpoint = at.tokenEndpoint, scope = at.scope, resource = at.resource)
                at.registrationEndpoint.isBlank() -> {
                    _signIn.value = SignIn.Failed(id, "", needsClient = true)
                    return
                }
                else -> try {
                    oauth.register(at, OAuthRedirects.APP)
                } catch (refused: RedirectRefused) {
                    oauth.register(at, OAuthRedirects.LOOPBACK)
                }
            }
            // The registration is kept at once, so trying again does not register Visor a second time.
            if (!grant.manualClient) store.update(id) { it.copy(oauth = grant.copy(accessToken = it.oauth?.accessToken.orEmpty(), refreshToken = it.oauth?.refreshToken.orEmpty(), expiresAt = it.oauth?.expiresAt ?: 0)) }
            val state = OAuth.secret(32)
            val verifier = OAuth.secret(64)
            val loopback = if (grant.redirectUri == OAuthRedirects.LOOPBACK) {
                scope.launch { withTimeoutOrNull(SIGN_IN_MS) { runCatching { awaitLoopback(OAuthRedirects.LOOPBACK_PORT) }.getOrNull() }?.let(::complete) }
            } else null
            pending = Pending(id, state, verifier, grant, loopback)
            val page = oauth.authorizeUrl(grant, state, verifier)
            _signIn.value = SignIn.InBrowser(id, page)
            note("${s.name}: signing in at ${at.issuer}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _signIn.value = SignIn.Failed(id, error.message ?: error.javaClass.simpleName)
            note("${s.name}: sign-in could not start: ${error.message}")
        }
    }

    /** The browser came back: trades the code for tokens. False when [redirect] was not this sign-in's. */
    fun complete(redirect: String): Boolean {
        val uri = Uri.parse(redirect)
        val state = uri.getQueryParameter("state") ?: return false
        val p = pending?.takeIf { it.state == state } ?: return false
        pending = null
        scope.launch {
            p.loopback?.cancel()
            _signIn.value = SignIn.Finishing(p.id)
            val name = store.get(p.id)?.name.orEmpty()
            try {
                uri.getQueryParameter("error")?.let { throw McpException(McpException.Kind.UNAUTHORIZED, uri.getQueryParameter("error_description") ?: it) }
                // RFC 9207: an answer from another issuer is a mix-up, not a sign-in.
                uri.getQueryParameter("iss")?.let { if (it.trimEnd('/') != p.grant.issuer.trimEnd('/')) throw McpException(McpException.Kind.UNAUTHORIZED, "answer from the wrong server ($it)") }
                val code = uri.getQueryParameter("code") ?: throw McpException(McpException.Kind.PROTOCOL, "no code came back")
                val grant = oauth.exchange(p.grant, code, p.verifier)
                store.update(p.id) { it.copy(auth = AuthKind.OAUTH, oauth = grant) }
                _signIn.value = SignIn.Done(p.id)
                note("$name: signed in")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _signIn.value = SignIn.Failed(p.id, error.message ?: error.javaClass.simpleName)
                note("$name: sign-in failed: ${error.message}")
            }
        }
        return true
    }

    /** Back to no sign-in in progress, once the screen has shown how it went. */
    fun clearSignIn() {
        pending?.loopback?.cancel()
        pending = null
        _signIn.value = SignIn.Idle
    }

    /** Forgets the tokens; the client registration stays for the next sign-in. */
    fun signOut(id: String) = store.update(id) { it.copy(oauth = it.oauth?.copy(accessToken = "", refreshToken = "", expiresAt = 0)) }

    private fun connection(s: McpServer): McpConnection = synchronized(connections) {
        connections[s.id]?.let { (key, c) -> if (key == s.connectionKey) return c else c.close() }
        val url = s.url.trim().toHttpUrlOrNull() ?: throw McpException(McpException.Kind.NOT_MCP, "not a web address")
        val c = McpConnection(url, s.transport, http, stream, authorizer(s.id), scope, version) { notice(s.id, it) }
        connections[s.id] = s.connectionKey to c
        c
    }

    private fun close(id: String) {
        synchronized(connections) { connections.remove(id) }?.second?.close()
    }

    /** Lets [id] go, with any check still running on its old settings. */
    private fun stop(id: String) {
        checks.remove(id)?.cancel()
        close(id)
    }

    /** Credentials read fresh from the store at each request, so a refreshed token is used at once. */
    private fun authorizer(id: String) = object : Authorizer {
        override suspend fun headers(): Map<String, String> {
            val s = store.get(id) ?: return emptyMap()
            if (s.auth != AuthKind.OAUTH) return fixedAuth(s).headers()
            val g = grant(id, force = false) ?: return emptyMap()
            return if (g.accessToken.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer ${g.accessToken}")
        }

        override suspend fun recover(challenge: String?): Boolean {
            val s = store.get(id) ?: return false
            if (s.auth != AuthKind.OAUTH || OAuth.challengeParams(challenge)["error"] == "insufficient_scope") return false
            return grant(id, force = true)?.accessToken?.isNotEmpty() == true
        }
    }

    private fun fixedAuth(s: McpServer) = object : Authorizer {
        override suspend fun headers(): Map<String, String> = when (s.auth) {
            AuthKind.TOKEN -> if (s.secret.isBlank()) emptyMap() else mapOf("Authorization" to bearer(s.secret))
            AuthKind.HEADER -> if (s.header.isBlank()) emptyMap() else mapOf(s.header.trim() to s.secret.trim())
            else -> emptyMap()
        }

        override suspend fun recover(challenge: String?) = false
    }

    /**
     * [id]'s grant, refreshed when it is about to run out or when [force]d after a refusal. One refresh at a time per
     * server, since a server may rotate the refresh token. A refresh the server refuses signs the person out.
     */
    private suspend fun grant(id: String, force: Boolean): OAuthGrant? = tokenLocks.getOrPut(id) { Mutex() }.withLock {
        val g = store.get(id)?.oauth ?: return@withLock null
        val margin = if (g.lifeMs > 0) minOf(EXPIRY_MARGIN_MS, g.lifeMs / 4) else EXPIRY_MARGIN_MS
        val expiring = g.expiresAt != 0L && g.expiresAt - now() < margin
        if (!force && !expiring) return@withLock g
        if (g.refreshToken.isEmpty()) return@withLock if (force) null else g
        try {
            val fresh = oauth.refresh(g)
            store.update(id) { it.copy(oauth = fresh) }
            fresh
        } catch (refused: McpException) {
            if (refused.kind == McpException.Kind.UNAUTHORIZED) {
                signOut(id)
                note("${store.get(id)?.name}: the sign-in ran out: ${refused.message}")
                null
            } else {
                g
            }
        }
    }

    /**
     * The sign-in a server offers that works without any setup: OAuth with dynamic client registration. A server whose
     * OAuth wants a client registered by hand is asked for a key first instead.
     */
    private suspend fun selfService(url: String, challenge: String?): AuthServer? = url.trim().toHttpUrlOrNull()?.let { u ->
        runCatching { oauth.discover(u, challenge) }.getOrNull()?.takeIf { it.registrationEndpoint.isNotBlank() }
    }

    /** The 401 an unauthenticated initialize gets, with its WWW-Authenticate. */
    private suspend fun challengeOf(url: HttpUrl): String? = runCatching {
        val hello = rpcMessage("initialize", McpConnection.initializeParams(version), 0)
        val request = Request.Builder().url(url).post(hello.toString().toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream").build()
        http.newCall(request).fetch { r -> if (r.code == 401) r.header("WWW-Authenticate") else null }
    }.getOrNull()

    private fun notice(id: String, message: JSONObject) {
        val name = store.get(id)?.name.orEmpty()
        when (message.str("method")) {
            "notifications/tools/list_changed" -> check(id)
            "notifications/message" -> message.optJSONObject("params")?.let { note("$name says (${it.str("level")}): ${it.opt("data")}") }
        }
    }

    private fun networkChanged() {
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(NETWORK_SETTLE_MS)
            store.servers.value.filter { it.enabled && _status.value[it.id]?.phase != McpStatus.Phase.READY }.forEach { check(it.id) }
        }
    }

    private fun setStatus(id: String, change: (McpStatus) -> McpStatus) = _status.update { it + (id to change(it[id] ?: McpStatus())) }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    private companion object {
        const val CALL_MS = 25_000L
        const val WAIT_MS = 2_500L
        const val STALE_MS = 30 * 60_000L
        const val RETRY_MS = 60_000L
        const val IDLE_MS = 5 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
        const val EXPIRY_MARGIN_MS = 60_000L
        const val NETWORK_SETTLE_MS = 2_500L
        const val SIGN_IN_MS = 10 * 60_000L
        const val MAX_INSTRUCTIONS = 1_500
        const val MAX_NAME = 64

        // Tools that look things up rather than act: not listed under the answer.
        val LOOKUP = Regex("^(?i:get|list|search|find|read|fetch|query|lookup|describe|show)(?=[_.\\-A-Z]|$)")

        fun now() = System.currentTimeMillis()

        fun bearer(token: String) = if (token.trim().startsWith("Bearer ", ignoreCase = true)) token.trim() else "Bearer ${token.trim()}"

        fun transportWords(t: Transport?) = when (t) {
            Transport.SSE -> "HTTP+SSE"
            else -> "Streamable HTTP"
        }

        /** server__tool, within the 64 characters every provider accepts. */
        fun exposedName(s: McpServer, t: McpTool, taken: MutableSet<String>): String {
            val slug = s.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20).ifEmpty { "mcp" }
            val tool = t.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
            var name = "${slug}__$tool".let { if (it.length > MAX_NAME) it.take(MAX_NAME - 5) + "_" + Integer.toHexString(it.hashCode() and 0xfff) else it }
            var n = 2
            while (!taken.add(name)) name = name.take(MAX_NAME - 3) + "_" + n++
            return name
        }

        /** "Turn on · Kitchen light": the tool's title, or its name made readable, and what it was given. */
        fun describe(t: McpTool, arguments: JSONObject): String {
            val title = t.title.ifEmpty {
                t.name
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim()
                    .lowercase().replaceFirstChar { it.uppercase() }
            }
            val words = argumentWords(arguments)
            return if (words.isEmpty()) title else "$title · $words"
        }

        fun argumentWords(arguments: JSONObject): String = arguments.keys().asSequence()
            .mapNotNull { k -> arguments.opt(k)?.takeIf { it is String || it is Number || it is Boolean }?.toString()?.take(40) }
            .filter { it.isNotBlank() }
            .take(3).joinToString(", ")
    }
}

/** What the model writes as an argument's value to hand a tool the glasses' photo: as base64, or as a data URL. */
const val PHOTO_BASE64 = "visor:photo"
const val PHOTO_DATA_URL = "visor:photo-url"

/** [arguments] with every placeholder value, however deep, swapped for [photo]; the placeholders stay in the log. */
internal fun withPhoto(arguments: JSONObject, photo: ByteArray?): JSONObject {
    if (photo == null) return arguments
    val base64 by lazy { android.util.Base64.encodeToString(photo, android.util.Base64.NO_WRAP) }
    fun swap(value: Any?): Any? = when (value) {
        PHOTO_BASE64 -> base64
        PHOTO_DATA_URL -> "data:image/jpeg;base64,$base64"
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { k -> out.put(k, swap(value.opt(k))) } }
        is org.json.JSONArray -> org.json.JSONArray().also { out -> for (i in 0 until value.length()) out.put(swap(value.opt(i))) }
        else -> value
    }
    return swap(arguments) as JSONObject
}
