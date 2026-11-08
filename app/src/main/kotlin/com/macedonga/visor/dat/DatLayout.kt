@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Layout
import com.macedonga.visor.kit.protocol.LayoutAlign
import com.macedonga.visor.kit.protocol.LayoutColor
import com.macedonga.visor.kit.protocol.LayoutCorners
import com.macedonga.visor.kit.protocol.LayoutImageSize
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
 * `{"layout":{"bloks_payload":{"tree":…,"ft":{}}}}`. A component is `{chr(style): {chr(key): value}}`. Every rule
 * here is DAT's own builder's (its FlexBoxScope, FlexboxKt and NovaRendererProfile), checked against its output; a
 * click comes back with the node's id. A video is not a tree: [video] finds it, and it goes to the app another way.
 */
object DatLayout {
    fun payload(layout: Layout): ByteArray {
        val envelope = buildJsonObject {
            put("layout", buildJsonObject { put("bloks_payload", buildJsonObject { put("tree", root(layout.root)); put("ft", JsonObject(emptyMap())) }) })
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(envelope.toString().encodeToByteArray()) }
        return out.toByteArray()
    }

    /** The tree alone, for logs. */
    fun tree(layout: Layout): String = root(layout.root).toString()

    /** The video of a layout that is a video alone, as DAT allows it. */
    fun video(layout: Layout): LayoutNode? = layout.root.children.singleOrNull()?.takeIf { it.type == LayoutType.VIDEO }

    // DAT focuses the first primary button of a layout, and only that one.
    private class Build {
        var focused = false
    }

    private fun root(n: LayoutNode): JsonObject {
        val build = Build()
        val flexbox = flexbox(n, build)
        // As DAT's ContentScope: a card root stays a static container, and takes the box's click itself.
        val node = if (n.card == true) card(flexbox) else flexbox
        return n.id?.let { with(node, mapOf(ID to JsonPrimitive(it), FLEXBOX_ON_CLICK to click(it))) } ?: node
    }

    private fun element(n: LayoutNode, build: Build): JsonObject? = when (n.type) {
        LayoutType.COLUMN, LayoutType.ROW -> box(n, build)
        LayoutType.TEXT -> text(n)
        LayoutType.IMAGE -> image(n)
        LayoutType.ICON -> n.icon?.let { image(glyph(it.id, n.style), LayoutImageSize.ICON, null, LayoutCorners.NONE) }
        LayoutType.BUTTON -> button(n, grouped = false, build)
        LayoutType.BUTTON_GROUP -> buttons(n, build)
        // A video fills a layout alone; a type this Visor does not know is left out.
        else -> null
    }

    private fun box(n: LayoutNode, build: Build): JsonObject {
        val flexbox = flexbox(n, build)
        val id = n.id
        return when {
            n.card == true && id != null -> component(
                CONTAINER,
                mapOf(
                    CONTAINER_MATERIAL to material(CORNERS_MEDIUM), FOCUSABLE to JsonPrimitive(true),
                    CHILDREN to JsonArray(listOf(flexbox)), ID to JsonPrimitive(id),
                    EXTENSIONS to JsonArray(listOf(component(ON_CLICK_EXTENSION, mapOf(EXTENSION_SCRIPT to click(id))))),
                ),
            )
            n.card == true -> card(flexbox)
            id != null -> with(flexbox, mapOf(ID to JsonPrimitive(id), FLEXBOX_ON_CLICK to click(id)))
            else -> flexbox
        }
    }

    private fun card(content: JsonObject) =
        component(STATIC_CONTAINER, mapOf(CONTAINER_MATERIAL to material(CORNERS_MEDIUM), CHILDREN to JsonArray(listOf(content))))

    private fun flexbox(n: LayoutNode, build: Build): JsonObject {
        val row = n.type == LayoutType.ROW
        val direction = (if (row) "row" else "column") + if (n.reverse == true) "_reverse" else ""
        val padding = n.padding ?: 0
        val attrs = buildMap<Int, JsonElement> {
            put(FLEX_DIRECTION, JsonPrimitive(direction))
            put(JUSTIFY_CONTENT, JsonPrimitive(justify(n.align)))
            // A column always stretches its children across; a row places them.
            put(ALIGN_ITEMS, JsonPrimitive(if (row) items(n.crossAlign) else "stretch"))
            put(FLEX_WRAP, JsonPrimitive(if (n.wrap == true) "wrap" else "no_wrap"))
            (n.paddingTop ?: padding).takeIf { it > 0 }?.let { put(PADDING_TOP, dp(it)) }
            (n.paddingBottom ?: padding).takeIf { it > 0 }?.let { put(PADDING_BOTTOM, dp(it)) }
            (n.paddingStart ?: padding).takeIf { it > 0 }?.let { put(PADDING_START, dp(it)) }
            (n.paddingEnd ?: padding).takeIf { it > 0 }?.let { put(PADDING_END, dp(it)) }
            put(CHILDREN, JsonArray(children(n.children, direction, n.gap ?: 0, build)))
        }
        return component(FLEXBOX, attrs)
    }

    /** DAT's FlexboxKt.addChildren: no child shrinks unless it says so, and the gap is a margin before each one but the first. */
    private fun children(nodes: List<LayoutNode>, direction: String, gap: Int, build: Build): List<JsonObject> =
        nodes.mapNotNull { child -> element(child, build)?.let { placed(it, child, direction) } }.mapIndexed { i, node ->
            val style = buildMap<Int, JsonElement> {
                if (!hasStyle(node, FLEX_SHRINK)) put(FLEX_SHRINK, JsonPrimitive(0))
                if (i > 0 && gap > 0) put(GAP_MARGIN.getValue(direction), dp(gap))
            }
            withStyle(node, style)
        }

    /** DAT's flexChildStyle: grow, shrink and its own cross alignment; a growing child of a row starts at width 0. */
    private fun placed(node: JsonObject, n: LayoutNode, parentDirection: String): JsonObject {
        val grow = n.grow ?: 0.0
        val shrink = n.shrink ?: 0.0
        if (grow == 0.0 && shrink == 0.0 && n.alignSelf == null) return node
        val style = buildMap<Int, JsonElement> {
            if (grow != 0.0 && parentDirection.startsWith("row")) put(WIDTH, JsonPrimitive("0"))
            if (grow != 0.0) put(FLEX_GROW, number(grow))
            if (shrink != 0.0) put(FLEX_SHRINK, number(shrink))
            n.alignSelf?.let { put(ALIGN_SELF, JsonPrimitive(items(it))) }
        }
        return withStyle(node, style)
    }

    private fun text(n: LayoutNode): JsonObject {
        val (font, size, line) = when (n.style) {
            LayoutStyle.HEADING -> Triple("optimistic-iris-hd-700", "40sp", "48sp")
            LayoutStyle.META -> Triple("optimistic-iris-sd-400", "22sp", "28sp")
            else -> Triple("optimistic-iris-sd-400", "28sp", "36sp")
        }
        // DAT's text is white unless asked; the kit's meta text is grey unless asked.
        val color = n.color ?: if (n.style == LayoutStyle.META) LayoutColor.SECONDARY else LayoutColor.PRIMARY
        return component(
            TEXT,
            mapOf(
                TEXT_TEXT to JsonPrimitive(n.text.orEmpty()), FONT_FAMILY to JsonPrimitive(font), TEXT_SIZE to JsonPrimitive(size),
                LINE_HEIGHT to JsonPrimitive(line), TEXT_COLOR to JsonPrimitive(if (color == LayoutColor.SECONDARY) SECONDARY else PRIMARY),
            ),
        )
    }

    private fun image(n: LayoutNode): JsonObject =
        image(n.url.orEmpty(), n.size ?: LayoutImageSize.FILL, n.height, n.corners ?: if (n.card == true) LayoutCorners.MEDIUM else LayoutCorners.NONE)

    private fun image(url: String, size: LayoutImageSize, height: Int?, corners: LayoutCorners): JsonObject {
        val icon = size == LayoutImageSize.ICON
        var image = component(IMAGE, mapOf(IMAGE_URL to JsonPrimitive(url), SCALE_TYPE to JsonPrimitive(if (icon) "cover" else "contain")))
        if (icon) {
            image = withStyle(image, mapOf(WIDTH to dp(ICON_SIZE), HEIGHT to dp(ICON_SIZE)))
        } else if (height != null) {
            image = withStyle(image, mapOf(HEIGHT to dp(height)))
        }
        val radius = when (corners) {
            LayoutCorners.SMALL -> CORNERS_SMALL
            LayoutCorners.MEDIUM -> CORNERS_MEDIUM
            else -> return image
        }
        // DAT's emitRoundedImage: the image in a column, on a container that clips its corners.
        val holder = component(
            FLEXBOX,
            mapOf(
                FLEX_DIRECTION to JsonPrimitive("column"), JUSTIFY_CONTENT to JsonPrimitive("flex_start"),
                ALIGN_ITEMS to JsonPrimitive("stretch"), FLEX_WRAP to JsonPrimitive("no_wrap"),
                CHILDREN to JsonArray(listOf(withStyle(image, mapOf(FLEX_SHRINK to JsonPrimitive(0))))),
            ),
        )
        return component(
            STATIC_CONTAINER,
            mapOf(BACKGROUND_STATE to JsonPrimitive("NONE"), CONTAINER_MATERIAL to material(radius), CHILDREN to JsonArray(listOf(holder))),
        )
    }

    /** A Display glyph as DAT names it: `drawable://<name>_filled_32` or `_outline_32`. */
    private fun glyph(name: String, style: LayoutStyle?) =
        "drawable://$name" + if (style == LayoutStyle.OUTLINE) "_outline_32" else "_filled_32"

    private fun button(n: LayoutNode, grouped: Boolean, build: Build): JsonObject {
        val id = n.id
        val attrs = buildMap<Int, JsonElement> {
            put(BUTTON_TITLE, JsonPrimitive(n.text.orEmpty()))
            // DAT shows a bar's labels only when the bar has room; a lone button's always.
            put(ALWAYS_SHOW_TEXT, JsonPrimitive(!grouped))
            n.icon?.let { put(BUTTON_ICON, JsonPrimitive(glyph(it.id, n.style))) }
            if (id != null) {
                put(ID, JsonPrimitive(id))
                put(BUTTON_ON_CLICK, click(id))
            }
        }
        val button = withStyle(component(BUTTON, attrs), mapOf(HEIGHT to JsonPrimitive("88dp")))
        if (grouped || !claimsFocus(n, build)) return button
        return with(button, mapOf(EXTENSIONS to JsonArray(listOf(focus(id!!)))))
    }

    private fun buttons(n: LayoutNode, build: Build): JsonObject {
        val members = n.children.filter { it.type == LayoutType.BUTTON }
        val focused = members.firstOrNull { claimsFocus(it, build) }
        val attrs = buildMap<Int, JsonElement> {
            put(GROUP_ALIGNMENT, JsonPrimitive(items(n.align ?: LayoutAlign.CENTER).removePrefix("flex_")))
            put(CHILDREN, JsonArray(members.map { button(it, grouped = true, build) }))
            focused?.id?.let { put(EXTENSIONS, JsonArray(listOf(focus(it)))) }
        }
        return withStyle(component(BUTTON_GROUP, attrs), mapOf(HEIGHT to JsonPrimitive("88dp")))
    }

    private fun claimsFocus(n: LayoutNode, build: Build): Boolean {
        if (n.primary != true || n.id == null || build.focused) return false
        build.focused = true
        return true
    }

    /** DAT's initial focus: a render extension that runs `(requestFocus (find id))`. */
    private fun focus(id: String) = component(
        RENDER_EXTENSION,
        mapOf(EXTENSION_SCRIPT to JsonPrimitive("(${REQUEST_FOCUS.toString(36)} (${FIND.toString(36)} ${JsonPrimitive(id)}))")),
    )

    private fun justify(align: LayoutAlign?) = when (align) {
        LayoutAlign.CENTER -> "center"
        LayoutAlign.END -> "flex_end"
        LayoutAlign.SPACE_BETWEEN -> "space_between"
        else -> "flex_start"
    }

    private fun items(align: LayoutAlign?) = when (align) {
        LayoutAlign.CENTER -> "center"
        LayoutAlign.END -> "flex_end"
        LayoutAlign.STRETCH -> "stretch"
        else -> "flex_start"
    }

    private fun material(corners: String) =
        component(MATERIAL, mapOf(MATERIAL_KIND to JsonPrimitive("default"), MATERIAL_CORNERS to JsonPrimitive(corners)))

    /** The click script the toolkit app runs: the DAT click component, named by its style id in base 36. */
    private fun click(id: String) = JsonPrimitive("(${DAT_CLICK.toString(36)} ${JsonPrimitive(id)})")

    private fun dp(value: Int) = JsonPrimitive("${value}dp")

    // DAT writes whole numbers without a fraction.
    private fun number(value: Double) = if (value % 1.0 == 0.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun key(k: Int) = String(Character.toChars(k))

    private fun component(style: Int, attrs: Map<Int, JsonElement>) =
        JsonObject(mapOf(key(style) to JsonObject(attrs.entries.associate { (k, v) -> key(k) to v })))

    /** [component] with more attributes. */
    private fun with(component: JsonObject, attrs: Map<Int, JsonElement>): JsonObject {
        val (name, inner) = component.entries.single()
        return JsonObject(mapOf(name to JsonObject(inner as JsonObject + attrs.entries.associate { (k, v) -> key(k) to v })))
    }

    private fun hasStyle(component: JsonObject, attr: Int): Boolean {
        val inner = component.values.single() as JsonObject
        return ((inner[key(STYLE)] as? JsonObject)?.get(key(BASE)) as? JsonObject)?.containsKey(key(attr)) == true
    }

    /** [component] with [style] merged into its base style. */
    private fun withStyle(component: JsonObject, style: Map<Int, JsonElement>): JsonObject {
        if (style.isEmpty()) return component
        val (name, inner) = component.entries.single()
        val attrs = inner as JsonObject
        val before = (attrs[key(STYLE)] as? JsonObject)?.get(key(BASE)) as? JsonObject ?: JsonObject(emptyMap())
        val merged = JsonObject(before + style.entries.associate { (k, v) -> key(k) to v })
        return JsonObject(mapOf(name to JsonObject(attrs + (key(STYLE) to JsonObject(mapOf(key(BASE) to merged))))))
    }

    private const val PRIMARY = "#FFFFFF"
    private const val SECONDARY = "#CFD3D9"
    private const val ICON_SIZE = 32
    private const val CORNERS_SMALL = "small"
    private const val CORNERS_MEDIUM = "medium"

    // Components (style ids) and their attribute keys, from the toolkit's generated Bloks constants.
    private const val FLEXBOX = 13320
    private const val TEXT = 13335
    private const val IMAGE = 13323
    private const val BASE = 24201
    private const val BUTTON = 24996
    private const val BUTTON_GROUP = 24998
    private const val CONTAINER = 22655
    private const val STATIC_CONTAINER = 17256
    private const val MATERIAL = 23457
    private const val ON_CLICK_EXTENSION = 23426
    private const val RENDER_EXTENSION = 16529
    private const val DAT_CLICK = 25389
    private const val REQUEST_FOCUS = 17776
    private const val FIND = 17679

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

    // Base style keys.
    private const val HEIGHT = 41
    private const val WIDTH = 58
    private const val MARGIN_TOP = 49
    private const val MARGIN_BOTTOM = 43
    private const val MARGIN_START = 48
    private const val MARGIN_END = 44
    private const val ALIGN_SELF = 61
    private const val FLEX_GROW = 63
    private const val FLEX_SHRINK = 65

    private val GAP_MARGIN = mapOf(
        "column" to MARGIN_TOP, "column_reverse" to MARGIN_BOTTOM, "row" to MARGIN_START, "row_reverse" to MARGIN_END,
    )

    private const val BUTTON_TITLE = 54
    private const val ALWAYS_SHOW_TEXT = 56
    private const val BUTTON_ICON = 45
    private const val BUTTON_ON_CLICK = 65
    private const val GROUP_ALIGNMENT = 35

    private const val CONTAINER_MATERIAL = 41
    private const val FOCUSABLE = 43
    private const val BACKGROUND_STATE = 35
    private const val MATERIAL_KIND = 38
    private const val MATERIAL_CORNERS = 40
    private const val EXTENSION_SCRIPT = 35
}
