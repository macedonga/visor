@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Layout
import com.macedonga.visor.kit.protocol.LayoutAlign
import com.macedonga.visor.kit.protocol.LayoutNode
import com.macedonga.visor.kit.protocol.LayoutStyle
import com.macedonga.visor.kit.protocol.LayoutType
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A kit [Layout] as the Display's toolkit app draws it: Bloks minified JSON, gzipped in DAT's envelope
 * `{"layout":{"bloks_payload":{"tree":…,"ft":{}}}}`. A component is `{chr(style): {chr(key): value}}`, with DAT's
 * own components, sizes and colours. A click comes back with the node's id.
 */
object DatLayout {
    fun payload(layout: Layout): ByteArray {
        val envelope = buildJsonObject {
            put("layout", buildJsonObject { put("bloks_payload", buildJsonObject { put("tree", node(layout.root)); put("ft", JsonObject(emptyMap())) }) })
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(envelope.toString().encodeToByteArray()) }
        return out.toByteArray()
    }

    /** The tree alone, for logs. */
    fun tree(layout: Layout): String = node(layout.root).toString()

    private fun node(n: LayoutNode): JsonObject = when (n.type) {
        LayoutType.COLUMN, LayoutType.ROW -> box(n)
        LayoutType.TEXT -> text(n)
        LayoutType.IMAGE -> image(n)
        LayoutType.BUTTON -> button(n)
        // A type this Visor does not know: an empty box, so its siblings keep their places.
        else -> component(FLEXBOX, emptyMap())
    }

    private fun box(n: LayoutNode): JsonObject {
        val row = n.type == LayoutType.ROW
        val gap = n.gap ?: 0
        val children = n.children.filter { it.type in KNOWN }.mapIndexed { i, child ->
            // As DAT does: no child shrinks, and the gap is a margin before every child but the first.
            val style = buildMap<Int, JsonElement> {
                put(FLEX_SHRINK, JsonPrimitive(0))
                if (i > 0 && gap > 0) put(if (row) MARGIN_START else MARGIN_TOP, dp(gap))
            }
            withStyle(node(child), style)
        }
        val padding = n.padding ?: 0
        val attrs = buildMap<Int, JsonElement> {
            put(FLEX_DIRECTION, JsonPrimitive(if (row) "row" else "column"))
            put(JUSTIFY_CONTENT, JsonPrimitive(justify(n.align)))
            // A column stretches its children across; a row places them.
            put(ALIGN_ITEMS, JsonPrimitive(if (row) items(n.crossAlign) else "stretch"))
            put(FLEX_WRAP, JsonPrimitive("no_wrap"))
            if (padding > 0) listOf(PADDING_TOP, PADDING_BOTTOM, PADDING_START, PADDING_END).forEach { put(it, dp(padding)) }
            put(CHILDREN, JsonArray(children))
            if (n.id != null && n.card != true) {
                put(ID, JsonPrimitive(n.id))
                put(FLEXBOX_ON_CLICK, JsonPrimitive(click(n.id!!)))
            }
        }
        val flexbox = component(FLEXBOX, attrs)
        if (n.card != true) return flexbox
        val material = component(MATERIAL, mapOf(MATERIAL_KIND to JsonPrimitive("default"), MATERIAL_CORNERS to JsonPrimitive("medium")))
        val id = n.id ?: return component(STATIC_CONTAINER, mapOf(CONTAINER_MATERIAL to material, CHILDREN to JsonArray(listOf(flexbox))))
        val extension = component(ON_CLICK_EXTENSION, mapOf(EXTENSION_ON_CLICK to JsonPrimitive(click(id))))
        return component(
            CONTAINER,
            mapOf(
                ID to JsonPrimitive(id), CONTAINER_MATERIAL to material, FOCUSABLE to JsonPrimitive(true),
                CHILDREN to JsonArray(listOf(flexbox)), EXTENSIONS to JsonArray(listOf(extension)),
            ),
        )
    }

    private fun text(n: LayoutNode): JsonObject {
        val (font, size, line) = when (n.style) {
            LayoutStyle.HEADING -> Triple("optimistic-iris-hd-700", "40sp", "48sp")
            LayoutStyle.META -> Triple("optimistic-iris-sd-400", "22sp", "28sp")
            else -> Triple("optimistic-iris-sd-400", "28sp", "36sp")
        }
        return component(
            TEXT,
            mapOf(
                TEXT_TEXT to JsonPrimitive(n.text.orEmpty()), FONT_FAMILY to JsonPrimitive(font), TEXT_SIZE to JsonPrimitive(size),
                LINE_HEIGHT to JsonPrimitive(line), TEXT_COLOR to JsonPrimitive(if (n.style == LayoutStyle.META) SECONDARY else PRIMARY),
            ),
        )
    }

    private fun image(n: LayoutNode): JsonObject {
        val image = component(IMAGE, mapOf(IMAGE_URL to JsonPrimitive(n.url.orEmpty()), SCALE_TYPE to JsonPrimitive("contain")))
        val sized = n.height?.let { withStyle(image, mapOf(HEIGHT to dp(it))) } ?: image
        if (n.card != true) return sized
        val material = component(MATERIAL, mapOf(MATERIAL_KIND to JsonPrimitive("default"), MATERIAL_CORNERS to JsonPrimitive("medium")))
        val holder = component(
            FLEXBOX,
            mapOf(
                FLEX_DIRECTION to JsonPrimitive("column"), JUSTIFY_CONTENT to JsonPrimitive("flex_start"),
                ALIGN_ITEMS to JsonPrimitive("stretch"), FLEX_WRAP to JsonPrimitive("no_wrap"),
                CHILDREN to JsonArray(listOf(withStyle(sized, mapOf(FLEX_SHRINK to JsonPrimitive(0))))),
            ),
        )
        return component(
            STATIC_CONTAINER,
            mapOf(BACKGROUND_STATE to JsonPrimitive("NONE"), CONTAINER_MATERIAL to material, CHILDREN to JsonArray(listOf(holder))),
        )
    }

    private fun button(n: LayoutNode): JsonObject {
        val attrs = buildMap<Int, JsonElement> {
            put(BUTTON_TITLE, JsonPrimitive(n.text.orEmpty()))
            put(ALWAYS_SHOW_TEXT, JsonPrimitive(true))
            n.id?.let {
                put(ID, JsonPrimitive(it))
                put(BUTTON_ON_CLICK, JsonPrimitive(click(it)))
            }
        }
        return withStyle(component(BUTTON, attrs), mapOf(HEIGHT to JsonPrimitive("88dp")))
    }

    private fun justify(align: LayoutAlign?) = when (align) {
        LayoutAlign.CENTER -> "center"
        LayoutAlign.END -> "flex_end"
        LayoutAlign.SPACE_BETWEEN -> "space_between"
        else -> "flex_start"
    }

    private fun items(align: LayoutAlign?) = when (align) {
        LayoutAlign.CENTER -> "center"
        LayoutAlign.END -> "flex_end"
        else -> "flex_start"
    }

    /** The click script the toolkit app runs: the DAT click component, named by its style id in base 36. */
    private fun click(id: String) = "(${DAT_CLICK.toString(36)} ${JsonPrimitive(id)})"

    private fun dp(value: Int) = JsonPrimitive("${value}dp")

    private fun key(k: Int) = String(Character.toChars(k))

    private fun component(style: Int, attrs: Map<Int, JsonElement>) =
        JsonObject(mapOf(key(style) to JsonObject(attrs.entries.associate { (k, v) -> key(k) to v })))

    /** [component] with [style] merged into its base style. */
    private fun withStyle(component: JsonObject, style: Map<Int, JsonElement>): JsonObject {
        val (name, inner) = component.entries.single()
        val attrs = inner as JsonObject
        val before = (attrs[key(STYLE)] as? JsonObject)?.get(key(BASE)) as? JsonObject ?: JsonObject(emptyMap())
        val merged = JsonObject(before + style.entries.associate { (k, v) -> key(k) to v })
        return JsonObject(mapOf(name to JsonObject(attrs + (key(STYLE) to JsonObject(mapOf(key(BASE) to merged))))))
    }

    private val KNOWN = setOf(LayoutType.COLUMN, LayoutType.ROW, LayoutType.TEXT, LayoutType.IMAGE, LayoutType.BUTTON)

    private const val PRIMARY = "#FFFFFF"
    private const val SECONDARY = "#CFD3D9"

    // Components (style ids) and their attribute keys, from the toolkit's generated Bloks constants.
    private const val FLEXBOX = 13320
    private const val TEXT = 13335
    private const val IMAGE = 13323
    private const val BASE = 24201
    private const val BUTTON = 24996
    private const val CONTAINER = 22655
    private const val STATIC_CONTAINER = 17256
    private const val MATERIAL = 23457
    private const val ON_CLICK_EXTENSION = 23426
    private const val DAT_CLICK = 25389

    private const val CHILDREN = 32
    private const val ID = 33
    private const val STYLE = 132
    private const val EXTENSIONS = 133

    private const val FLEX_DIRECTION = 41
    private const val FLEX_WRAP = 42
    private const val JUSTIFY_CONTENT = 44
    private const val ALIGN_ITEMS = 36
    private const val FLEXBOX_ON_CLICK = 45
    private const val PADDING_TOP = 59
    private const val PADDING_BOTTOM = 54
    private const val PADDING_START = 58
    private const val PADDING_END = 55

    private const val TEXT_TEXT = 41
    private const val TEXT_SIZE = 45
    private const val TEXT_COLOR = 43
    private const val FONT_FAMILY = 35
    private const val LINE_HEIGHT = 59

    private const val IMAGE_URL = 41
    private const val SCALE_TYPE = 40

    private const val HEIGHT = 41
    private const val MARGIN_TOP = 49
    private const val MARGIN_START = 48
    private const val FLEX_SHRINK = 65

    private const val BUTTON_TITLE = 54
    private const val ALWAYS_SHOW_TEXT = 56
    private const val BUTTON_ON_CLICK = 65

    private const val CONTAINER_MATERIAL = 41
    private const val FOCUSABLE = 43
    private const val BACKGROUND_STATE = 35
    private const val MATERIAL_KIND = 38
    private const val MATERIAL_CORNERS = 40
    private const val EXTENSION_ON_CLICK = 35
}
