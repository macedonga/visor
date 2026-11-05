package com.macedonga.visor.stage

import com.macedonga.visor.core.identity.look.GlassesLook

/** A frame Visor has a model for, and the Meta style codes it covers. */
enum class FrameModel(val asset: String, val styles: Set<String>) {
    WAYFARER("glasses.glb", setOf("wayfarer", "wayfarer_large"))
}

/** Whether Visor has a model of [look]'s frame; an unknown look gets the one there is. */
fun modelled(look: GlassesLook?): Boolean = look?.style?.lowercase()?.let { style -> FrameModel.entries.any { style in it.styles } } ?: true

/** [look]'s material variants, best first: colour and lens, colour and tint, then the finish and tint every model has. */
fun variantsOf(look: GlassesLook?): List<String> {
    val color = look?.color?.lowercase()
    val tint = lensTint(look?.lens)
    val finish = if (color?.startsWith("matte") == true) "matte" else "shiny"
    return listOfNotNull(
        color?.let { c -> look.lens?.let { "${c}_${it.lowercase()}" } },
        color?.let { "${it}_$tint" },
        "${finish}_$tint",
    )
}

/** Lenses as a model can tint them: G15 and other greens green, clear ones clear, any other dark, Transitions too. */
private fun lensTint(lens: String?): String = lens?.lowercase().let {
    when {
        it == null -> "clear"
        "g15" in it || "green" in it || "emerald" in it -> "green"
        "transitions" in it -> "graphite"
        it.startsWith("clear") -> "clear"
        else -> "graphite"
    }
}
