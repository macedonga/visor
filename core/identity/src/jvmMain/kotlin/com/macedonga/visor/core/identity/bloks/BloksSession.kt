package com.macedonga.visor.core.identity.bloks

import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** What a login screen may ask of the phone itself, and what it may ask about it. */
interface BloksHost {
    fun openUrl(url: String) {}

    fun haptic() {}

    fun shareText(text: String) {}

    fun dial(number: String) {}

    fun copy(text: String) {}

    /** FLAG_SECURE on the login, while a screen with a secret asks for it. */
    fun secure(on: Boolean) {}

    fun keepScreenOn(on: Boolean) {}

    /** A date (and maybe a time) picker; [done] gets unix seconds, or null when the user cancels. */
    fun pickDate(initialSeconds: Long?, withTime: Boolean, done: (Long?) -> Unit) = done(null)

    val apiLevel: Int get() = 35
    val fontScale: Float get() = 1f
    val densityDpi: Int get() = 420
    val screenReader: Boolean get() = false
    val reduceMotion: Boolean get() = false
}

/** Meta's native login: runs the server's Bloks screens and scripts on one thread until a completion action ends it. */
class BloksSession(
    internal val client: BloksClient,
    internal val deviceId: String,
    internal val darkMode: Boolean,
    internal val log: (String) -> Unit,
    internal val trace: (String) -> Unit = {},
    internal val host: BloksHost = object : BloksHost {},
) {
    sealed interface Outcome {
        /** The server handed an OIDC code for the app's redirect (`DeliverOidcAuthCode`). */
        class Code(val code: String) : Outcome

        /** The server handed the Meta account token itself (`CompleteMetaLogin` and friends). */
        class Token(val accessToken: String) : Outcome

        class Failed(val reason: String) : Outcome

        data object Cancelled : Outcome
    }

    /** [failed]: a request failed with no error handler of its own; the UI says so in its words. */
    class Dialog(val title: String?, val message: String?, val buttons: List<Pair<String?, Lambda?>>, val failed: Boolean = false)

    /** What the UI shows: the top screen drawn, a dialog, whether a request is out. [version] changes on every change. */
    class State(
        val ui: Ui?,
        val depth: Int,
        val dialog: Dialog?,
        val busy: Boolean,
        val version: Long,
        /** A toast's text and how long it shows; [toastCount] moves on every new one. */
        val toast: String?,
        val toastMillis: Long,
        val toastCount: Int,
        /** A bottom sheet's header title, which sits outside its tree. */
        val title: String?,
    )

    internal val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bloks").apply { isDaemon = true } }
    private val network = Executors.newCachedThreadPool { r -> Thread(r, "bloks-net").apply { isDaemon = true } }
    internal val interpreter = Interpreter()
    internal val globals = HashMap<String, Any?>()
    internal val screens = ArrayList<Screen>()
    internal var dialog: Dialog? = null
    private var pending = 0
    private var version = 0L

    @Volatile
    private var done = false

    internal var toast: String? = null
    internal var toastMillis = 0L
    internal var toastCount = 0

    /** State the Meta app keeps in its preferences between logins; here for the length of one. */
    internal val prefs = HashMap<String, Any?>()
    internal val timers = HashMap<String, Timer>()
    internal val animations = HashMap<String, Animation>()
    private val quiet = HashMap<String, Int>()

    /** `session_store.Get`: one mutable map the scripts share for the whole login. */
    internal val sessionStore = HashMap<Any?, Any?>()

    /** Every text the user typed this login, kept out of the trace. */
    private val typed = IdentityHashMap<Node, String>()

    /** Component types already reported as not drawn. */
    internal val undrawn = HashSet<Int>()

    var onState: (State) -> Unit = {}
    var onOutcome: (Outcome) -> Unit = {}

    /** Opens the login: the same first request the Meta AI app makes, with [next] the OIDC authorize URL. */
    fun start(next: String) = post {
        val first = linkedMapOf<String, Any?>(
            "logging_session_id" to UUID.randomUUID().toString(),
            "logging_user_intent" to "meta_ai",
            "spi_profile_pic_url" to null,
            "spi_frl_email" to null,
            "spi_name" to null,
            "id_for_aymh" to null,
            "should_show_explicit_oxygen_preload_tos" to false,
            "waterfall_id" to UUID.randomUUID().toString(),
            "next" to next,
        )
        // With no first screen there is nothing to show the error on, so the login ends with it.
        request("action $START", { client.action(START, BloksJson.encode(first, boolsAsInts = false)) }, then = { payload ->
            runAction(payload, null, null)
        }, failed = { error -> finish(Outcome.Failed(error.message ?: error.javaClass.simpleName)) })
    }

    /** A script handler from the UI: a click, a text change. Runs on the Bloks thread. */
    fun invoke(handler: Lambda, args: List<Any?>) = post { interpreter.call(handler, args) }

    fun setText(node: Node, text: String) = post {
        screens.lastOrNull()?.texts?.put(node, text)
        typed[node] = text
    }

    fun dismissDialog() = post { dialog = null }

    /** The system back: a screen's own back handler if it set one, else the top screen goes, and with the last one the login. */
    fun back() = post {
        val top = screens.lastOrNull()
        when {
            dialog != null -> dialog = null
            top == null -> finish(Outcome.Cancelled)
            top.backOverride != null -> {
                trace("back: ${top.appId} handles it")
                interpreter.call(top.backOverride, listOf(top))
            }
            else -> {
                trace("back: closing ${top.appId}")
                pop()
            }
        }
    }

    /** Takes the top screen off; with the last one the login is cancelled. */
    internal fun pop() {
        remove(screens.lastOrNull() ?: return)
        if (screens.isEmpty()) finish(Outcome.Cancelled)
    }

    /** Takes [screen] off the stack: its timers stop, its dismiss callback runs. */
    internal fun remove(screen: Screen) {
        screens.remove(screen)
        timers.values.removeAll { t -> (t.screen === screen).also { if (it) t.cancel() } }
        screen.onClose?.let { interpreter.call(it, emptyList()) }
    }

    /** The page waited and the top screen still has nothing to draw, with nothing on its way: name it in the log. */
    fun stuck() {
        if (done) return
        try {
            thread.execute {
                val top = screens.lastOrNull()?.appId
                log("$top shows nothing to draw")
                trace("STUCK $top: nothing to draw, nothing pending")
            }
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    fun close() {
        done = true
        thread.shutdownNow()
        network.shutdownNow()
    }

    internal fun post(block: () -> Unit) {
        if (done) return
        try {
            thread.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    val why = if (error is BloksException) error.message else "${error.javaClass.simpleName}: ${error.message}"
                    log("script failed: $why")
                    trace("SCRIPT FAILED: $why")
                }
                publish()
                flushCounts()
            }
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    private fun publish() {
        version++
        val top = screens.lastOrNull()
        onState(State(render(), screens.size, dialog, pending > 0, version, toast, toastMillis, toastCount, top?.title))
    }

    /** Built-ins and chatty actions run by the thousand: one line of counts per run, not one line each. */
    private fun flushCounts() {
        val counts = interpreter.drainCounts()
        if (counts.isNotEmpty()) trace("built-ins: " + tally(counts))
        if (quiet.isNotEmpty()) {
            trace("quiet actions: " + tally(quiet))
            quiet.clear()
        }
    }

    private fun tally(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key.removePrefix("bk.action.")}x${it.value}" }

    /** The top screen as [Ui]. Lifecycle scripts change state as they run, so the walk repeats until they are done. */
    private fun render(): Ui? {
        val screen = screens.lastOrNull() ?: return null
        val tree = screen.payload.tree ?: return null
        var ui: Ui? = null
        for (pass in 0 until 4) {
            val builder = UiBuilder(this, screen)
            ui = try {
                builder.build(tree, screen)
            } catch (error: Exception) {
                log("drawing ${screen.appId} failed: ${error.javaClass.simpleName}: ${error.message}")
                trace("RENDER FAILED ${screen.appId}: ${error.javaClass.simpleName}: ${error.message}")
                return ui
            }
            if (!builder.ranLifecycle) break
        }
        return ui
    }

    internal fun finish(outcome: Outcome) {
        if (done) return
        trace("OUTCOME ${outcome.javaClass.simpleName}")
        done = true
        onOutcome(outcome)
    }

    internal fun request(label: String, call: () -> Payload, then: (Payload) -> Unit, failed: ((Throwable) -> Unit)? = null) {
        pending++
        log("asking $label")
        trace("REQUEST $label")
        network.execute {
            val result = runCatching(call)
            post {
                pending--
                result.fold(
                    onSuccess = { p ->
                        trace(
                            "ANSWER $label: tree ${p.tree?.type ?: "none"}, action ${if (p.action != null) "yes" else "no"}, " +
                                "${p.scripts.size} scripts, ${p.variables.size} variables, ${p.embedded.size} embedded",
                        )
                        then(p)
                    },
                    onFailure = { error ->
                        log("$label failed: ${error.message}")
                        trace("ANSWER $label FAILED: ${error.javaClass.simpleName}: ${error.message}")
                        if (failed != null) failed(error) else dialog = Dialog(null, null, listOf(null to null), failed = true)
                    },
                )
            }
        }
    }

    // ---- Screens and contexts ----

    /** A screen on the stack: its payload, the parameters it was opened with, and its state. */
    inner class Screen(val appId: String, val payload: Payload, val params: Map<*, *>) : Ctx {
        val vars = HashMap<String, Any?>()
        val globalKeys = HashMap<String, String>()
        val texts = HashMap<Node, String>()

        /** Lifecycle scripts already run, and template or embedded instances by key, so a redraw reuses them. */
        val started = HashSet<Any>()
        val instances = HashMap<String, Nested>()

        /** Children put in by `ReplaceEmbeddedChildren`, by component id. */
        val replaced = HashMap<String, List<Any?>>()

        /** Children put in by the other tree actions (append, insert, remove), by the parent node itself. */
        val children = IdentityHashMap<Node, List<Any?>>()

        /** Attributes set by `component.SetAttr`, by node. */
        val attrs = IdentityHashMap<Node, MutableMap<Int, Any?>>()

        /** Whether it is a bottom sheet, a sheet's header title, its dismiss callback, and a back handler it set for itself. */
        var sheet = false
        var title: String? = null
        var onClose: Lambda? = null
        var backOverride: Lambda? = null

        /** A legacy-stack screen's back-stack name, which CloseScreenV2 pops to. */
        var name: String? = null

        /** `bloks.GetValue` results: each value is computed once per screen. */
        val values = HashMap<String, Any?>()

        /** The children a node shows now: what a tree action put there, else [own]. */
        fun childrenOf(node: Node, own: Any? = node[primaryChildren(node.type)]): List<Any?> =
            children[node] ?: node[ID]?.toString()?.let { replaced[it] } ?: (own as? List<*>) ?: listOfNotNull(own)

        init {
            declare(payload, this)
        }

        override fun script(id: String): Lambda = scriptIn(payload, id, this)

        override fun extension(interpreter: Interpreter, name: String, args: List<Any?>): Any? =
            this@BloksSession.traced(this, this, name, args)
    }

    /** Scripts from an action's answer or an embedded payload: their own table first, the screen's after. */
    inner class Nested(
        val payload: Payload,
        val screen: Screen?,
        private val parent: Ctx?,
        var templateArgs: Map<*, *> = emptyMap<Any, Any>(),
        // An async component's client params, read by `asynccomponents.GetClientParamV2`.
        private val clientParams: Map<*, *> = emptyMap<Any, Any>(),
    ) : Ctx {
        override fun script(id: String): Lambda =
            payload.scripts[id]?.let { Lambda(it.expr, emptyList(), this) } ?: parent?.script(id) ?: throw BloksException("no script $id")

        override fun extension(interpreter: Interpreter, name: String, args: List<Any?>): Any? =
            this@BloksSession.traced(screen, this, name, args)

        fun templateArg(name: String): Any? = templateArgs[name] ?: (parent as? Nested)?.templateArg(name)

        fun clientParam(name: String): Any? = clientParams[name] ?: (parent as? Nested)?.clientParam(name)
    }

    /** A payload made ready for a tree: by `GetPayload` or a template, with the context its scripts run in. */
    class Instance(val payload: Payload, val ctx: Ctx)

    private fun scriptIn(payload: Payload, id: String, ctx: Ctx): Lambda =
        payload.scripts[id]?.let { Lambda(it.expr, emptyList(), ctx) } ?: throw BloksException("no script $id")

    internal fun declare(payload: Payload, ctx: Ctx) {
        val screen = (ctx as? Screen) ?: (ctx as? Nested)?.screen ?: return
        for (v in payload.variables) {
            when (v.type) {
                "gs" -> {
                    val key = v.key ?: continue
                    screen.globalKeys[v.id] = key
                    if (v.mode == "p" || !globals.containsKey(key)) globals[key] = v.initial
                }
                else -> if (!screen.vars.containsKey(v.id)) {
                    screen.vars[v.id] = v.initialScript?.let { interpreter.run(it.expr, ctx) } ?: v.initial
                }
            }
        }
    }

    internal fun variable(screen: Screen?, id: String): Any? {
        screen ?: return null
        screen.globalKeys[id]?.let { return globals[it] }
        return screen.vars[id]
    }

    /** Puts a screen on top; [setup] configures it before its action runs. */
    internal fun push(appId: String, payload: Payload, params: Map<*, *>, setup: Screen.() -> Unit = {}) {
        val screen = Screen(appId, payload, params)
        screens += screen
        screen.setup()
        log("screen $appId")
        trace("SCREEN $appId (depth ${screens.size})")
        payload.action?.let { interpreter.run(it.expr, screen, listOf(screen)) }
        fetchComponents(payload, screen, screen)
    }

    /** Fetches each async component and puts it in place of its target's skeleton. */
    private fun fetchComponents(payload: Payload, screen: Screen, ctx: Ctx) {
        for (q in payload.componentQueries) {
            val target = q.target ?: continue
            val appId = q.appId?.let { interpreter.run(it.expr, ctx) } as? String ?: continue
            val params = q.params?.let { interpreter.run(it.expr, ctx) } as? Map<*, *> ?: emptyMap<Any, Any>()
            val clientParams = q.clientParams?.let { interpreter.run(it.expr, ctx) } as? Map<*, *> ?: emptyMap<Any, Any>()
            val query = BloksJson.encode(mapOf("params" to params), boolsAsInts = false)
            request("component $appId", { client.component(appId, query) }, { answer ->
                val nested = Nested(answer, screen, ctx, clientParams = clientParams)
                declare(answer, nested)
                screen.replaced[target] = listOf(Instance(answer, nested))
                answer.action?.let { interpreter.run(it.expr, nested, listOf(screen)) }
                fetchComponents(answer, screen, nested)
            })
        }
    }

    /** An action's answer: its data and props go into the screen, then its script runs with the screen as context. */
    internal fun runAction(payload: Payload, screen: Screen?, props: Map<*, *>?) {
        val ctx = Nested(payload, screen, screen)
        declare(payload, ctx)
        if (screen != null) {
            props?.forEach { (k, v) ->
                val id = payload.props[k.toString()] ?: k.toString()
                screen.vars[id] = v
            }
        }
        payload.action?.let { interpreter.run(it.expr, ctx, listOfNotNull(screen)) }
    }

    /** Every action call goes through here: what was called, with what, and how it ended. */
    private fun traced(screen: Screen?, ctx: Ctx, name: String, args: List<Any?>): Any? {
        val handler = Actions.table[name]
        if (name in QUIET) {
            quiet.merge(name, 1, Int::plus)
            return handler?.invoke(ActionCall(this, screen, ctx, name, args))
        }
        // What the user typed (an email, a code, a password) is copied around by the scripts: never shown.
        val hidden = (typed.values + screen?.texts?.values.orEmpty()).filter { it.isNotEmpty() }.toSet()
        val shown = if (name in SECRET_ARGS) "<hidden>" else args.joinToString(", ") { brief(it, hidden) }
        if (handler == null) {
            // Skipped rather than stopping the script, so what follows (a loading flag, the next screen) still runs.
            log("unsupported $name, skipped")
            trace("call $name($shown) UNSUPPORTED, skipped")
            return null
        }
        val result = try {
            handler(ActionCall(this, screen, ctx, name, args))
        } catch (error: Exception) {
            trace("call $name($shown) FAILED: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
        trace("call $name($shown) ok -> ${if (name in SECRET_RESULT) "<hidden>" else brief(result, hidden)}")
        return result
    }

    /** A value in a trace line: short, and with no secret in it (long strings cut, maps by their keys only). */
    private fun brief(x: Any?, hidden: Set<String>): String = when (x) {
        null -> "null"
        is Boolean, is Number -> x.toString()
        // A password the scripts carry is tagged #PWD; long strings (tokens, server context) keep only their start.
        is String -> when {
            "#PWD" in x || x in hidden -> "<typed>"
            EMAIL.containsMatchIn(x) -> "<email>"
            x.length > 40 -> "\"${x.take(8)}...\"(${x.length})"
            else -> "\"$x\""
        }
        is Node -> "node ${x.type}"
        is Lambda -> "fn"
        is Ref -> "ref"
        is Instance -> "payload"
        is Screen -> "ctx"
        is List<*> -> "[${x.size}]"
        is Map<*, *> -> "{" + x.keys.take(8).joinToString(",") + (if (x.size > 8) ",+${x.size - 8}" else "") + "}"
        else -> x.javaClass.simpleName
    }

    fun text(screen: Screen?, node: Node): String = screen?.texts?.get(node) ?: node[INPUT_TEXT] as? String ?: ""

    /** The node with component id [id] in a screen's tree, tree actions included. */
    internal fun find(screen: Screen, node: Node, id: String): Node? = walk(screen, node) { it[ID]?.toString() == id }

    /** The node whose children hold the one with component id [id]. */
    internal fun parentOf(screen: Screen, node: Node, id: String): Node? =
        walk(screen, node) { n -> screen.childrenOf(n).any { (it as? Node)?.get(ID)?.toString() == id } }

    private fun walk(screen: Screen, node: Node, test: (Node) -> Boolean): Node? {
        if (test(node)) return node
        val primary = primaryChildren(node.type)
        // The children the screen shows now, then every other attribute.
        val others = node.attrs.filter { (k, _) -> k != primary }.values
        for (value in listOf<Any?>(screen.childrenOf(node)) + others) {
            for (item in value as? List<*> ?: listOf(value)) {
                when (item) {
                    is Node -> walk(screen, item, test)?.let { return it }
                    is Instance -> item.payload.tree?.let { walk(screen, it, test) }?.let { return it }
                }
            }
        }
        return null
    }

    companion object {
        const val START = "com.bloks.www.meta.access.aymh_with_nta_fallback.async"

        /** The attribute every component carries its id in, what bloks.Find matches. */
        const val ID = 33

        /** A text input's initial text, and its "sensitive" flag (a password: GetText answers null). */
        const val INPUT_TEXT = 50
        const val INPUT_SENSITIVE = 99

        internal val MACHINE_ID = UUID.randomUUID().toString()

        /** Where a component keeps the children tree actions edit. */
        fun primaryChildren(type: Int): Int = when (type) {
            13327 -> 35
            13708 -> 41
            UiBuilder.SCREEN, UiBuilder.SCREEN_QUERY -> 36
            16444 -> 42
            else -> 32
        }

        private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

        /** Calls whose arguments are a password, a code or a token. */
        private val SECRET_ARGS = setOf(
            "bk.mt.action.DeliverOidcAuthCode", "bk.fx.action.CompleteMetaLogin", "bk.action.mwa.CompleteFRLLoginWithCallback",
            "bk.action.mma.CompleteTokenExchangeV2", "bk.action.textinput.SetTextV2", "bk.action.text_input.AppendText",
            "bk.action.text_input.InsertTextAtCursor",
        )
        private val SECRET_RESULT = setOf("bk.action.textinput.GetText", "bk.action.textinput.GetSensitiveText", "bk.action.text.GetText")

        /** Logging, metrics and plain reads, run by the hundred: counted, not listed. */
        private val QUIET = LOGGING + setOf(
            "bk.action.bloks.GetVariable2", "bk.action.bloks.GetVariableWithScope", "bk.action.core.GetTemplateArg",
            "bk.action.bloks.GetScript", "bk.action.tree.Make", "bk.action.ref.Read", "bk.action.bloks.GetParameter",
            "bk.action.mwa.IsDarkModeEnabled",
        )
    }
}
