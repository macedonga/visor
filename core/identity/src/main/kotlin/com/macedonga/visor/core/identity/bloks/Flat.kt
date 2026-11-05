package com.macedonga.visor.core.identity.bloks

/** A Meta screen reduced to what it asks of the user, in reading order, to draw in Visor's own look. */
sealed interface Item

class Heading(val text: String) : Item

class Paragraph(val spans: List<Span>, val error: Boolean) : Item {
    val text get() = spans.joinToString("") { it.text }
}

class Field(val input: Input, val label: String) : Item

class Button(val label: String, val event: Event, val primary: Boolean, val enabled: Boolean) : Item

class Page(val back: Event?, val items: List<Item>)

object Flat {
    /** [title] is a bottom sheet's header, which sits outside its tree. */
    fun of(ui: Ui?, title: String? = null): Page {
        var primaryTaken = false
        val out = ArrayList<Item>()
        var back: Event? = null
        if (ui != null) title?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Heading(it) }

        fun visible(u: Ui) = !u.style.invisible && u.style.alpha > 0.05f

        // A button's label may sit in a box faded out for a spinner: read it whatever its alpha.
        fun texts(u: Ui, into: MutableList<String>) {
            if (u.style.invisible) return
            when (u) {
                is Text -> u.text.trim().takeIf { it.isNotEmpty() }?.let(into::add)
                is Box -> u.children.forEach { texts(it, into) }
                else -> {}
            }
        }

        fun inputs(u: Ui): List<Input> = when (u) {
            is Input -> listOf(u)
            is Box -> u.children.flatMap(::inputs)
            else -> emptyList()
        }

        fun filled(u: Ui): Boolean = u.style.background != null || (u is Box && u.children.any(::filled))

        fun walk(u: Ui) {
            val fields = inputs(u)
            // Code fields are a transparent input over drawn boxes: an input counts even when it is not seen.
            if (!visible(u) && fields.isEmpty()) return
            when (u) {
                is Box -> {
                    val click = u.onClick
                    if (click != null) {
                        val words = ArrayList<String>().also { texts(u, it) }
                        if (fields.isNotEmpty()) {
                            fields.forEach { f -> out += Field(f, f.label.ifEmpty { words.firstOrNull().orEmpty() }) }
                            return
                        }
                        val label = words.distinct().joinToString(" ")
                        // A code field's drawn boxes, one character each, only focus the field.
                        if (words.isNotEmpty() && words.all { it.length <= 1 }) return
                        if (label.isEmpty()) {
                            if (back == null && out.none { it is Field || it is Button }) back = click
                        } else {
                            // The first filled button is the screen's main one; later filled ones are secondary.
                            val primary = filled(u) && !primaryTaken
                            if (primary) primaryTaken = true
                            out += Button(label, click, primary, enabled = u.enabled)
                        }
                        return
                    }
                    u.children.forEach(::walk)
                }
                is Text -> {
                    val spans = u.spans
                    if (spans.all { it.text.isBlank() }) return
                    val whole = u.onClick ?: spans.singleOrNull()?.onClick
                    if (whole != null) {
                        out += Button(u.text.trim(), whole, primary = false, enabled = true)
                        return
                    }
                    val size = spans.mapNotNull { it.sizeSp }.maxOrNull() ?: 0f
                    val error = spans.any { s -> s.color?.let(::reddish) == true }
                    if (size >= 20f && !error) out += Heading(u.text.trim()) else out += Paragraph(spans, error)
                }
                is Input -> if (!u.style.invisible) out += Field(u, u.label)
            }
        }

        if (ui != null) walk(ui)
        // Labels the screen repeats around a field (a floating label and its copy) are the field's, not paragraphs.
        val labels = out.filterIsInstance<Field>().map { it.label.trim().trimEnd(',') }.toSet()
        val kept = out.filterNot { it is Paragraph && it.text.trim().trimEnd(',') in labels }
            .distinctBy { if (it is Paragraph) "p:${it.text}" else if (it is Heading) "h:${it.text}" else it }
        // Without a large text, the first line before the first field is the screen's title.
        val items = if (kept.any { it is Heading }) kept else {
            val first = kept.indexOfFirst { it is Paragraph && !it.error }
            val field = kept.indexOfFirst { it is Field }
            if (first >= 0 && (field < 0 || first < field)) kept.toMutableList().apply { set(first, Heading((kept[first] as Paragraph).text)) } else kept
        }
        return Page(back, items)
    }

    private fun reddish(argb: Long): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r > 180 && g < 110 && b < 110
    }
}
