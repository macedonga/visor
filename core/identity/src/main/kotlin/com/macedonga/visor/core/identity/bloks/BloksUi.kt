package com.macedonga.visor.core.identity.bloks

/** A screen ready to draw: every binding evaluated, every handler an [Event] the session runs. */
sealed interface Ui {
    val style: Style
}

/** What [Flat] reads of a node's look: its fill, its alpha, and whether it is hidden. */
class Style(val background: Long?, val alpha: Float, val invisible: Boolean)

class Event(val handler: Lambda, val args: List<Any?>)

class Box(override val style: Style, val children: List<Ui>, val onClick: Event?, val enabled: Boolean) : Ui

class Span(val text: String, val sizeSp: Float?, val color: Long?, val onClick: Event?)

class Text(override val style: Style, val spans: List<Span>, val onClick: Event?) : Ui {
    val text get() = spans.joinToString("") { it.text }
}

class Input(
    override val style: Style,
    val node: Node,
    val text: String,
    val hint: String,
    /** What the field is, from its accessibility extension ("Mobile number or email"). */
    val label: String,
    val keyboard: String?,
    val secret: Boolean,
    val maxLength: Int,
    val enabled: Boolean,
    val onChange: Event?,
    val onSubmit: Event?,
    val onFocus: Event?,
    val onBlur: Event?,
) : Ui

/** Walks a screen's component tree (Meta AI 294 ids) into [Ui]: boxes, text and inputs. */
internal class UiBuilder(private val session: BloksSession, private val screen: BloksSession.Screen) {
    /** True when a lifecycle script ran during the walk: state may have changed, so walk again. */
    var ranLifecycle = false
        private set

    fun build(node: Node, ctx: Ctx): Ui? {
        val a = resolve(node, ctx)
        runLifecycle(node, a, ctx)
        if (a[49] == "gone") return null
        val style = style(node, a)
        return when (node.type) {
            SCREEN -> Box(style, listOfNotNull(child(a[40], ctx), child(a[36], ctx)), onClick = null, enabled = true)
            SCREEN_QUERY -> child(a[36], ctx)
            FLEXBOX -> Box(style, children(node, a[32], ctx), onClick = event(a[45], ctx, node), enabled = a[40] != false)
            COLLECTION -> Box(style, children(node, a[32], ctx), onClick = null, enabled = true)
            RICH_TEXT -> Text(style, spans(node, a, ctx), event(a[41], ctx, node))
            PLAIN_TEXT -> Text(
                style,
                listOf(Span(a[41]?.toString().orEmpty(), dim(a[45]), (a[48] as? Node)?.let(::color) ?: hex(a[43]), null)),
                event(a[40], ctx, node),
            )
            INPUT -> Input(
                style,
                node,
                session.text(screen, node),
                a[36]?.toString().orEmpty(),
                label(a, ctx),
                a[56] as? String,
                secret = a[BloksSession.INPUT_SENSITIVE] == true || a[56] == "password" || a[56] == "passcode",
                maxLength = (a[40] as? Number)?.toInt() ?: -1,
                enabled = a[74] != false,
                onChange = event(a[48], ctx, node),
                onSubmit = event(a[46], ctx, node),
                onFocus = event(a[42], ctx, node),
                onBlur = event(a[45], ctx, node),
            )
            IMAGE, SPINNER, SPINNER_V1, GLIMMER -> null
            else -> {
                val kids = children(node, a[32], ctx)
                if (kids.isEmpty()) {
                    unknown(node.type)
                    null
                } else {
                    Box(style, kids, onClick = null, enabled = true)
                }
            }
        }
    }

    private fun child(value: Any?, ctx: Ctx): Ui? = when (value) {
        is Node -> build(value, ctx)
        is BloksSession.Instance -> value.payload.tree?.let { build(it, value.ctx) }
        is List<*> -> value.firstOrNull()?.let { child(it, ctx) }
        else -> null
    }

    private fun children(node: Node, value: Any?, ctx: Ctx): List<Ui> {
        return screen.childrenOf(node, value).flatMap { if (it is Node && it.type == FRAGMENT) (it[32] as? List<*>).orEmpty() else listOf(it) }.mapNotNull { child(it, ctx) }
    }

    /** The node's attributes with its bindings (attribute 135: `[key, script, ...]`) evaluated in its context. */
    private fun resolve(node: Node, ctx: Ctx): Map<Int, Any?> {
        val set = screen.attrs[node]
        val bindings = node[135] as? List<*> ?: return if (set == null) node.attrs else node.attrs + set
        val out = HashMap(node.attrs)
        var i = 0
        while (i + 1 < bindings.size) {
            val key = bindings[i] as? String
            val script = bindings[i + 1] as? Script
            if (key != null && key.length == 1 && script != null) {
                try {
                    out[key[0].code] = session.interpreter.run(script.expr, ctx)
                } catch (error: Exception) {
                    trace("binding ${key[0].code} of ${node.type} failed: ${error.message}")
                }
            }
            i += 2
        }
        set?.let { out.putAll(it) }
        return out
    }

    /** Extensions (attribute 133) that set up state: 13914 runs 41 and 42 at the first bind, 16529 runs 35. */
    private fun runLifecycle(node: Node, a: Map<Int, Any?>, ctx: Ctx) {
        val extensions = a[133] as? List<*> ?: return
        for (ext in extensions.filterIsInstance<Node>()) {
            val scripts = when (ext.type) {
                LIFECYCLE -> listOf(ext[41], ext[42])
                RENDER_LIFECYCLE -> listOf(ext[35])
                else -> continue
            }
            if (!screen.started.add(Pair(ext, ctx))) continue
            for (s in scripts) {
                val e = event(s, ctx, node) ?: continue
                try {
                    session.interpreter.call(e.handler, e.args)
                    ranLifecycle = true
                } catch (error: Exception) {
                    trace("lifecycle of ${node.type} failed: ${error.message}")
                }
            }
        }
    }

    /** A field's name: its accessibility description (14001.35, its tooltip 50) or the legacy one (13313.36). */
    private fun label(a: Map<Int, Any?>, ctx: Ctx): String {
        val ext = (a[133] as? List<*>).orEmpty().filterIsInstance<Node>()
        val text = ext.firstOrNull { it.type == ACCESSIBILITY }?.let { resolve(it, ctx) }?.let { it[35] ?: it[50] }
            ?: ext.firstOrNull { it.type == ACCESSIBILITY_LEGACY }?.let { resolve(it, ctx) }?.get(36)
        return text?.toString()?.trim()?.trimEnd(',')?.trim().orEmpty()
    }

    private fun event(value: Any?, ctx: Ctx, node: Node): Event? = when (value) {
        is Script -> Event(Lambda(value.expr, emptyList(), ctx), listOf(node, screen))
        is Lambda -> Event(value, listOf(node, screen))
        else -> null
    }

    private fun spans(node: Node, a: Map<Int, Any?>, ctx: Ctx): List<Span> {
        val out = ArrayList<Span>()
        fun add(item: Any?, inherited: List<Node>) {
            val n = item as? Node ?: return
            val na = resolve(n, ctx)
            when (n.type) {
                SPAN_GROUP -> (na[36] as? List<*>).orEmpty().forEach { add(it, inherited + (na[38] as? List<*>).orEmpty().filterIsInstance<Node>()) }
                SPAN -> {
                    val styles = inherited + (na[36] as? List<*>).orEmpty().filterIsInstance<Node>().map { Node(it.type, resolve(it, ctx)) }
                    out += Span(
                        text = na[38]?.toString().orEmpty(),
                        sizeSp = dim(styles.lastOrNull { it.type == FONT }?.get(41)),
                        color = (styles.lastOrNull { it.type == COLOR }?.get(35) as? Node)?.let(::color),
                        onClick = styles.lastOrNull { it.type == LINK }?.let { event(it[36], ctx, node) },
                    )
                }
                LEGACY_SPAN -> out += Span(na[38]?.toString().orEmpty(), dim(na[42]), (na[44] as? Node)?.let(::color), event(na[36], ctx, node))
            }
        }
        val list = if (a[68] == true) a[69] else a[32]
        (list as? List<*>).orEmpty().forEach { add(it, emptyList()) }
        return out.filter { it.text.isNotEmpty() }
    }

    private fun style(node: Node, a: Map<Int, Any?>): Style {
        val deco = (a[38] as? Node)?.takeIf { node.type == FLEXBOX && it.type == DECORATION }
        val alpha = (a[141] as? Number)?.toFloat()
            ?: (a[133] as? List<*>)?.filterIsInstance<Node>()?.firstOrNull { it.type == TRANSFORMS }?.get(141)?.let { (it as? Number)?.toFloat() }
            ?: 1f
        return Style(
            background = (deco?.get(35) as? Node)?.let { d -> (d[35] as? Node)?.let(::color) ?: hex(d[35]) },
            alpha = alpha,
            invisible = a[49] == "invisible",
        )
    }

    private fun color(node: Node): Long? = hex(if (session.darkMode) node[35] ?: node[36] else node[36] ?: node[35])

    private fun hex(value: Any?): Long? {
        val s = (value as? String)?.removePrefix("#") ?: return null
        val v = s.toLongOrNull(16) ?: return null
        return when (s.length) {
            8 -> v
            6 -> 0xFF000000 or v
            else -> null
        }
    }

    private fun unknown(type: Int) {
        if (session.undrawn.add(type)) trace("component $type not drawn")
    }

    private fun trace(line: String) = session.trace("render: $line")

    companion object {
        const val SCREEN = 14093
        const val SCREEN_QUERY = 16675
        const val FLEXBOX = 13320
        const val COLLECTION = 13317
        const val RICH_TEXT = 13329
        const val PLAIN_TEXT = 13335
        const val INPUT = 13336
        const val IMAGE = 13323
        const val SPINNER = 23990
        const val SPINNER_V1 = 15728
        const val GLIMMER = 15778
        const val DECORATION = 13314
        const val SPAN_GROUP = 16887
        const val SPAN = 16880
        const val LEGACY_SPAN = 13339
        const val FONT = 16870
        const val COLOR = 16875
        const val LINK = 16873
        const val LIFECYCLE = 13914
        const val RENDER_LIFECYCLE = 16529
        const val TRANSFORMS = 13688
        const val ACCESSIBILITY = 14001
        const val ACCESSIBILITY_LEGACY = 13313

        /** A fragment: its children stand in its place. */
        const val FRAGMENT = 13346

        /** `"16dp"`, `"16sp"`, `"16.00sp"`, a bare number: dp. Pixels at the 3x the query declares. Percentages: null. */
        fun dim(value: Any?): Float? = when (value) {
            is Number -> value.toFloat()
            is String -> when {
                value.endsWith("dp") -> value.removeSuffix("dp").toFloatOrNull()
                value.endsWith("sp") -> value.removeSuffix("sp").toFloatOrNull()
                value.endsWith("px") -> value.removeSuffix("px").toFloatOrNull()?.div(BloksClient.PIXEL_RATIO)
                else -> value.toFloatOrNull()
            }
            else -> null
        }
    }
}
