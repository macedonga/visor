@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Layout
import com.macedonga.visor.kit.protocol.LayoutAlign
import com.macedonga.visor.kit.protocol.LayoutColor
import com.macedonga.visor.kit.protocol.LayoutCorners
import com.macedonga.visor.kit.protocol.LayoutIconName
import com.macedonga.visor.kit.protocol.LayoutImageSize
import com.macedonga.visor.kit.protocol.LayoutNode
import com.macedonga.visor.kit.protocol.LayoutStyle
import com.macedonga.visor.kit.protocol.LayoutType
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
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

    /**
     * The Bloks tree of the detail view of [card], which the Briefing opens when the wearer clicks the card: padded as
     * a card, unless it is pages alone, whose cards pad themselves. Its clicks go to the phone as Bloks async actions
     * ([CARD_CLICK]), which [com.macedonga.visor.connection.BloksRelay] hands to the card's app.
     */
    fun detail(layout: Layout, card: String): String {
        val build = Build(card)
        val pages = layout.root.children.singleOrNull()?.type == LayoutType.PAGER
        val tree = if (pages) {
            withStyle(flexbox(layout.root, build, width = SCREEN), mapOf(WIDTH to JsonPrimitive("100%"), HEIGHT to JsonPrimitive("100%")))
        } else {
            inset(layout.root, build, SCREEN)
        }
        return tree.toString()
    }

    /** The app id of a click in a card's detail, which Visor answers itself. */
    const val CARD_CLICK = "com.bloks.www.visor.card_click"

    /**
     * A card for the Display home's Briefing panel, framed as Meta's own cards: the layout in a container of the
     * panel's material, padded 24 dp unless the layout pads itself. It shows there and takes no clicks. Bloks JSON.
     */
    fun briefing(layout: Layout): String {
        val content = inset(layout.root, Build(), BRIEFING_CARD)
        val kinetic = component(MATERIAL, mapOf(MATERIAL_KIND to JsonPrimitive("meta-ai-kinetic"), MATERIAL_CORNERS to JsonPrimitive(CORNERS_MEDIUM)))
        val card = component(CONTAINER, mapOf(CHILDREN to JsonArray(listOf(content)), CONTAINER_MATERIAL to kinetic))
        return buildJsonObject {
            put("layout", buildJsonObject { put("bloks_payload", buildJsonObject { put("data", JsonArray(emptyList())); put("tree", card) }) })
        }.toString()
    }

    /** The video of a layout that is a video alone, as DAT allows it. */
    fun video(layout: Layout): LayoutNode? = layout.root.children.singleOrNull()?.takeIf { it.type == LayoutType.VIDEO }

    // DAT focuses the first primary button of a layout, and only that one. [card]: the Briefing card whose detail
    // this is, null for a layout of the toolkit app.
    private class Build(val card: String? = null) {
        var focused = false
        var pagers = 0
    }

    private fun root(n: LayoutNode): JsonObject {
        val build = Build()
        val flexbox = flexbox(n, build, width = SCREEN)
        // As DAT's ContentScope: a card root stays a static container, and takes the box's click itself.
        val node = if (n.card == true) card(flexbox) else flexbox
        return n.id?.let { with(node, mapOf(ID to JsonPrimitive(it), FLEXBOX_ON_CLICK to click(n, build))) } ?: node
    }

    // [width]: the pixels its box has across, at most, for what is drawn on the phone to fit.
    private fun element(n: LayoutNode, build: Build, width: Int): JsonObject? = when (n.type) {
        LayoutType.COLUMN, LayoutType.ROW -> box(n, build, width)
        LayoutType.TEXT -> text(n)
        LayoutType.IMAGE -> image(n)
        LayoutType.ICON -> n.icon?.let { icon(n, glyph(it.id, n.style)) }
        LayoutType.BUTTON -> button(n, grouped = false, build)
        LayoutType.BUTTON_GROUP -> buttons(n, build)
        LayoutType.PAGER -> pager(n, build, width)
        LayoutType.CHART -> chart(n, width)
        // A video fills a layout alone; a type this Visor does not know is left out.
        else -> null
    }

    private fun box(n: LayoutNode, build: Build, width: Int): JsonObject {
        val flexbox = flexbox(n, build, width = width)
        val id = n.id
        return when {
            n.card == true && id != null -> component(
                CONTAINER,
                mapOf(
                    CONTAINER_MATERIAL to material(CORNERS_MEDIUM), FOCUSABLE to JsonPrimitive(true),
                    CHILDREN to JsonArray(listOf(flexbox)), ID to JsonPrimitive(id),
                    EXTENSIONS to JsonArray(listOf(component(ON_CLICK_EXTENSION, mapOf(EXTENSION_SCRIPT to click(n, build))))),
                ),
            )
            n.card == true -> card(flexbox)
            id != null -> with(flexbox, mapOf(ID to JsonPrimitive(id), FLEXBOX_ON_CLICK to click(n, build)))
            else -> flexbox
        }
    }

    private fun card(content: JsonObject) =
        component(STATIC_CONTAINER, mapOf(CONTAINER_MATERIAL to material(CORNERS_MEDIUM), CHILDREN to JsonArray(listOf(content))))

    /** [n] as a flexbox [width] pixels wide at most, padded [fallback] on each edge it gives no padding for. */
    private fun flexbox(n: LayoutNode, build: Build, fallback: Int = 0, width: Int): JsonObject {
        val row = n.type == LayoutType.ROW
        val direction = (if (row) "row" else "column") + if (n.reverse == true) "_reverse" else ""
        val padding = n.padding ?: fallback
        val inset = Inset(n.paddingTop ?: padding, n.paddingBottom ?: padding, n.paddingStart ?: padding, n.paddingEnd ?: padding)
        val attrs = buildMap<Int, JsonElement> {
            put(FLEX_DIRECTION, JsonPrimitive(direction))
            put(JUSTIFY_CONTENT, JsonPrimitive(justify(n.align)))
            // A column always stretches its children across; a row places them.
            put(ALIGN_ITEMS, JsonPrimitive(if (row) items(n.crossAlign) else "stretch"))
            put(FLEX_WRAP, JsonPrimitive(if (n.wrap == true) "wrap" else "no_wrap"))
            inset.top.takeIf { it > 0 }?.let { put(PADDING_TOP, dp(it)) }
            inset.bottom.takeIf { it > 0 }?.let { put(PADDING_BOTTOM, dp(it)) }
            inset.start.takeIf { it > 0 }?.let { put(PADDING_START, dp(it)) }
            inset.end.takeIf { it > 0 }?.let { put(PADDING_END, dp(it)) }
            put(CHILDREN, JsonArray(children(n.children, direction, n.gap ?: 0, inset, build, width - inset.start - inset.end)))
            rgb(n.background)?.let { put(BACKGROUND, background(it)) }
        }
        val box = component(FLEXBOX, attrs)
        return n.height?.takeIf { it > 0 }?.let { withStyle(box, mapOf(HEIGHT to dp(it))) } ?: box
    }

    private class Inset(val top: Int, val bottom: Int, val start: Int, val end: Int)

    // A fill as Meta's screens set one: a decoration whose drawable is a solid colour.
    private fun background(rgb: String) =
        component(DECORATION, mapOf(DECORATION_DRAWABLE to component(SOLID, mapOf(SOLID_COLOR to themed(rgb)))))

    private fun themed(rgb: String): JsonObject {
        val argb = JsonPrimitive("#FF" + rgb.drop(1))
        return JsonObject(mapOf(key(COLOR) to JsonObject(mapOf(key(COLOR_LIGHT) to argb, key(COLOR_DARK) to argb))))
    }

    /**
     * Meta's Weather and Stocks details: a horizontal pager with dots under it. Each page is a card of the panel's
     * material with an id, inset 24 dp; the wearer's swipes move the focus from card to card, so the pager focuses
     * the first one as it binds, 100 ms on, as Meta's does.
     */
    private fun pager(n: LayoutNode, build: Build, width: Int): JsonObject {
        val pager = build.pagers++
        val pages = n.children.filter { it.type == LayoutType.COLUMN || it.type == LayoutType.ROW }.mapIndexed { i, page ->
            val card = component(
                CONTAINER,
                mapOf(
                    CHILDREN to JsonArray(listOf(inset(page, build, width - 2 * PAGE_INSET))),
                    CONTAINER_MATERIAL to component(MATERIAL, mapOf(MATERIAL_KIND to JsonPrimitive("meta-ai-kinetic"))),
                    ID to JsonPrimitive(pageId(pager, i)),
                ),
            )
            component(
                FLEXBOX,
                mapOf(
                    FLEX_DIRECTION to JsonPrimitive("column"), JUSTIFY_CONTENT to JsonPrimitive("center"),
                    PAGE_INSET_START to dp(PAGE_INSET), PAGE_INSET_END to dp(PAGE_INSET),
                    CHILDREN to JsonArray(listOf(withStyle(card, mapOf(WIDTH to JsonPrimitive("100%"))))),
                ),
            )
        }
        val first = "(${FIND.toString(36)} ${JsonPrimitive(pageId(pager, 0))})"
        val bind = "\t(${CALL.toString(36)} (${FUNC.toString(36)} (${DELAY.toString(36)} 100 (${FUNC.toString(36)} " +
            "(${CALL.toString(36)} (${FUNC.toString(36)} (${REQUEST_FOCUS.toString(36)} $first)))))))"
        return component(
            PAGER,
            mapOf(
                CHILDREN to JsonArray(pages), PAGER_ORIENTATION to JsonPrimitive("horizontal"), PAGER_SNAP to JsonPrimitive(true),
                PAGER_INDICATOR to JsonPrimitive("DOTS"), PAGER_INDICATOR_PLACE to JsonPrimitive("UNDER"),
                PAGER_ALIGN to JsonPrimitive("flex_end"),
                EXTENSIONS to JsonArray(listOf(component(BIND_EXTENSION, mapOf(BIND_SCRIPT to JsonPrimitive(bind))))),
            ),
        )
    }

    private fun pageId(pager: Int, page: Int) = "visor_pager${pager}_page$page"

    // A box padded 24 dp unless it pads itself, across the whole width.
    private fun inset(n: LayoutNode, build: Build, width: Int): JsonObject {
        val own = n.padding != null || n.paddingTop != null || n.paddingBottom != null || n.paddingStart != null || n.paddingEnd != null
        return withStyle(flexbox(n, build, if (own) 0 else CARD_PADDING, width), mapOf(WIDTH to JsonPrimitive("100%")))
    }

    /** DAT's FlexboxKt.addChildren: no child shrinks unless it says so, and the gap is a margin before each one but the first. */
    private fun children(nodes: List<LayoutNode>, direction: String, gap: Int, inset: Inset, build: Build, width: Int): List<JsonObject> =
        nodes.mapNotNull { child -> element(child, build, width)?.let { Triple(placed(it, child, direction, inset), child, pinned(child)) } }
            .let { placed ->
                // The gap goes between the children in line; a pinned one takes no space.
                val first = placed.indexOfFirst { !it.third }
                placed.mapIndexed { i, (node, _, pinned) ->
                    val style = buildMap<Int, JsonElement> {
                        if (!hasStyle(node, FLEX_SHRINK)) put(FLEX_SHRINK, JsonPrimitive(0))
                        if (!pinned && i > first && gap > 0) put(GAP_MARGIN.getValue(direction), dp(gap))
                    }
                    withStyle(node, style)
                }
            }

    private fun pinned(n: LayoutNode) = n.pinTop != null || n.pinBottom != null || n.pinStart != null || n.pinEnd != null

    /** DAT's flexChildStyle: grow, shrink and its own cross alignment; a growing child of a row starts at width 0. */
    private fun placed(node: JsonObject, n: LayoutNode, parentDirection: String, inset: Inset): JsonObject {
        val grow = n.grow ?: 0.0
        val shrink = n.shrink ?: 0.0
        if (grow == 0.0 && shrink == 0.0 && n.alignSelf == null && !pinned(n)) return node
        val style = buildMap<Int, JsonElement> {
            if (grow != 0.0 && parentDirection.startsWith("row")) put(WIDTH, JsonPrimitive("0"))
            if (grow != 0.0) put(FLEX_GROW, number(grow))
            if (shrink != 0.0) put(FLEX_SHRINK, number(shrink))
            n.alignSelf?.let { put(ALIGN_SELF, JsonPrimitive(items(it))) }
            // Bloks places a pinned child from its box's outer edges; the kit's pins count from inside the padding.
            if (pinned(n)) {
                put(POSITION, JsonPrimitive("absolute"))
                n.pinTop?.let { put(TOP, dp(it + inset.top)) }
                n.pinBottom?.let { put(BOTTOM, dp(it + inset.bottom)) }
                n.pinStart?.let { put(START, dp(it + inset.start)) }
                n.pinEnd?.let { put(END, dp(it + inset.end)) }
            }
        }
        return withStyle(node, style)
    }

    private fun text(n: LayoutNode): JsonObject {
        val (font, size, line) = when (n.style) {
            LayoutStyle.HEADING -> Triple("optimistic-iris-hd-700", "40sp", "48sp")
            LayoutStyle.TITLE -> Triple("optimistic-iris-sd-700", "24sp", "32sp")
            LayoutStyle.META -> Triple("optimistic-iris-sd-400", "22sp", "28sp")
            else -> Triple("optimistic-iris-sd-400", "28sp", "36sp")
        }
        // DAT's text is white unless asked; the kit's meta text is grey unless asked.
        val color = rgb(n.color) ?: if (n.style == LayoutStyle.META) SECONDARY else PRIMARY
        val attrs = buildMap<Int, JsonElement> {
            put(TEXT_TEXT, JsonPrimitive(n.text.orEmpty()))
            put(FONT_FAMILY, JsonPrimitive(font))
            put(TEXT_SIZE, JsonPrimitive(size))
            put(LINE_HEIGHT, JsonPrimitive(line))
            put(TEXT_COLOR, JsonPrimitive(color))
            n.maxLines?.takeIf { it > 0 }?.let { put(MAX_LINES, JsonPrimitive(it)) }
            when (n.align) {
                LayoutAlign.CENTER -> put(TEXT_ALIGN, JsonPrimitive("center"))
                LayoutAlign.END -> put(TEXT_ALIGN, JsonPrimitive("end"))
                else -> Unit
            }
        }
        return component(TEXT, attrs)
    }

    /** `#RRGGBB` for one of the Display's colours or a valid hex one; null for none or an unknown name. */
    private fun rgb(color: LayoutColor?): String? = when {
        color == null -> null
        color == LayoutColor.PRIMARY -> PRIMARY
        color == LayoutColor.SECONDARY -> SECONDARY
        HEX.matches(color.id) -> color.id.uppercase()
        else -> null
    }

    // An icon as Meta's cards draw theirs: the glyph tinted, white unless asked, as large as asked. A weather
    // illustration keeps its colours unless one is asked.
    private fun icon(n: LayoutNode, url: String): JsonObject {
        val side = n.height?.takeIf { it > 0 } ?: ICON_SIZE
        val illustration = n.icon?.id?.startsWith(ILLUSTRATION) == true
        val attrs = buildMap<Int, JsonElement> {
            put(IMAGE_URL, JsonPrimitive(url))
            put(SCALE_TYPE, JsonPrimitive(if (illustration) "contain" else "cover"))
            if (!illustration || n.color != null) put(IMAGE_TINT, themed(rgb(n.color) ?: PRIMARY))
        }
        return withStyle(component(IMAGE, attrs), mapOf(WIDTH to dp(side), HEIGHT to dp(side)))
    }

    /**
     * A chart drawn on the phone, [width] pixels across as it shows, as a PNG in a data URI: Bloks has no chart of
     * its own. A line runs from the lowest value to the highest; bars rise from zero, or from the lowest value
     * below it.
     */
    private fun chart(n: LayoutNode, width: Int): JsonObject? {
        val values = n.values.orEmpty().filter { it.isFinite() }
        if (values.isEmpty()) return null
        val w = width.coerceIn(CHART_MIN, SCREEN)
        val h = (n.height ?: CHART_HEIGHT).coerceIn(CHART_MIN, SCREEN)
        val argb = (0xFF000000 or (rgb(n.color) ?: BLUE).drop(1).toLong(16)).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (n.style == LayoutStyle.BARS) {
            val base = minOf(0.0, values.min())
            val span = (maxOf(0.0, values.max()) - base).takeIf { it > 0 } ?: 1.0
            val slot = w.toFloat() / values.size
            val bar = slot * BAR_SHARE
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
            values.forEachIndexed { i, v ->
                val left = i * slot + (slot - bar) / 2
                val top = (h - (v - base) / span * h).toFloat().coerceIn(0f, h - 1f)
                canvas.drawRoundRect(RectF(left, top, left + bar, h.toFloat()), minOf(bar / 2, BAR_RADIUS), minOf(bar / 2, BAR_RADIUS), paint)
            }
        } else {
            val lo = values.min()
            val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
            val edge = LINE_WIDTH
            fun x(i: Int) = if (values.size == 1) w / 2f else edge + i * (w - 2 * edge) / (values.size - 1)
            fun y(v: Double) = if (span == 1.0 && values.max() == lo) h / 2f else (h - edge - (v - lo) / span * (h - 2 * edge)).toFloat()
            val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            // A wash under the line, fading to nothing, as charts on the Display's own screens.
            val area = Path(line).apply { lineTo(x(values.lastIndex), h.toFloat()); lineTo(x(0), h.toFloat()); close() }
            val wash = (argb and 0x00FFFFFF) or (WASH_ALPHA shl 24)
            canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), wash, argb and 0x00FFFFFF, Shader.TileMode.CLAMP) })
            canvas.drawPath(
                line,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = argb
                    style = Paint.Style.STROKE
                    strokeWidth = LINE_WIDTH
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val uri = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val image = component(IMAGE, mapOf(IMAGE_URL to JsonPrimitive(uri), SCALE_TYPE to JsonPrimitive("contain")))
        return withStyle(image, mapOf(WIDTH to JsonPrimitive("100%"), HEIGHT to dp(h)))
    }

    private fun image(n: LayoutNode): JsonObject =
        image(fitted(n.url.orEmpty()), n.size ?: LayoutImageSize.FILL, n.height, n.corners ?: if (n.card == true) LayoutCorners.MEDIUM else LayoutCorners.NONE)

    /**
     * A `data:` image larger than the Display draws, at most 600 pixels a side, scaled down on the phone, so an app
     * may send a photo as it is. Anything else as given: the glasses fetch `https://` images through the phone, which
     * fits them already.
     */
    private fun fitted(url: String): String {
        if (!url.startsWith("data:image/")) return url
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return url
        val bytes = runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return url
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= IMAGE_SIDE) return url
        var sample = 1
        while (side / (sample * 2) >= IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return url
        val scale = IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val image = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        val png = image.hasAlpha()
        val out = ByteArrayOutputStream()
        image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else JPEG_QUALITY, out)
        if (image !== decoded) image.recycle()
        decoded.recycle()
        return "data:image/${if (png) "png" else "jpeg"};base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

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

    /**
     * A Display glyph as DAT names it, `drawable://<name>_filled_32` or `_outline_32`; a weather illustration by its
     * own name. A name DAT does not have carries Meta's copy on its CDN, which the Display fetches when it lacks the
     * drawable, as Meta's cards do.
     */
    private fun glyph(name: String, style: LayoutStyle?): String {
        val illustration = name.startsWith(ILLUSTRATION)
        val local = if (illustration) name else name + if (style == LayoutStyle.OUTLINE) "_outline_32" else "_filled_32"
        if (name in DAT_GLYPHS) return "drawable://$local"
        val cdn = local.replace('_', '-') + if (illustration) "" else "-light"
        return "drawable://$local/?img_url=" + URLEncoder.encode("$ICON_CDN$cdn&density=1", "UTF-8")
    }

    private fun button(n: LayoutNode, grouped: Boolean, build: Build): JsonObject {
        val id = n.id
        val attrs = buildMap<Int, JsonElement> {
            put(BUTTON_TITLE, JsonPrimitive(n.text.orEmpty()))
            // DAT shows a bar's labels only when the bar has room; a lone button's always.
            put(ALWAYS_SHOW_TEXT, JsonPrimitive(!grouped))
            n.icon?.let { put(BUTTON_ICON, JsonPrimitive(glyph(it.id, n.style))) }
            if (id != null) {
                put(ID, JsonPrimitive(id))
                put(BUTTON_ON_CLICK, click(n, build))
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
    private fun click(n: LayoutNode, build: Build): JsonPrimitive {
        val id = n.id.orEmpty()
        val card = build.card ?: return JsonPrimitive("(${DAT_CLICK.toString(36)} ${JsonPrimitive(id)})")
        // As the buttons of Meta's Stocks detail call the phone: bk.action.bloks.AsyncActionWithDataManifestV2 with an
        // app id, {params: JSON of server_params and client_input_params}, and callbacks (36 done, 35 failed, 40 the
        // client params, 38 the screen). Done shows the element's toast with bk.action.wearables.ShowToast.
        val params = buildJsonObject {
            put("server_params", buildJsonObject { put("card", JsonPrimitive(card)); put("id", JsonPrimitive(id)) })
            put("client_input_params", JsonObject(emptyMap()))
        }.toString()
        val done = n.toast?.takeIf { it.isNotBlank() }?.let { "(${f(SHOW_TOAST)} ${JsonPrimitive(it)} null \"AFFIRMATIVE\")" } ?: "1"
        val callbacks = "(${f(TREE_MAKE)} $ACTION_CALLBACKS 36 (${f(FUNC)} $done) 35 (${f(FUNC)} 1) " +
            "40 (${f(FUNC)} (${f(CALL_RUNTIME)} 6)) 38 \"current-screen\")"
        return JsonPrimitive(
            "\t(${f(ASYNC_ACTION)} ${JsonPrimitive(CARD_CLICK)} (${f(CALL_RUNTIME)} 6 \"params\" ${JsonPrimitive(params)}) $callbacks (${f(ARRAY_MAKE)}))",
        )
    }

    private fun f(id: Int) = id.toString(36)

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
    private const val BLUE = "#4DA3FF"
    private const val SCREEN = 600
    private const val IMAGE_SIDE = 600
    private const val JPEG_QUALITY = 85

    // About a Briefing card's width: the screen less 24 px each side. Not measured.
    private const val BRIEFING_CARD = 552
    private const val CHART_HEIGHT = 120
    private const val CHART_MIN = 16
    private const val BAR_SHARE = 0.6f
    private const val BAR_RADIUS = 6f
    private const val LINE_WIDTH = 4f
    private const val WASH_ALPHA = 0x55
    private const val ILLUSTRATION = "bks_"
    private const val ICON_CDN = "https://static.xx.fbcdn.net/assets/?set=wearables_icons&name="
    private val DAT_GLYPHS = LayoutIconName.dat.map { it.id }.toSet()
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
    private const val CARD_PADDING = 24
    private const val PADDING_TOP = 59
    private const val PADDING_BOTTOM = 54
    private const val PADDING_START = 58
    private const val PADDING_END = 55

    private val HEX = Regex("#[0-9A-Fa-f]{6}")

    // From Meta's Briefing screens: a flexbox's background, the pager of the Stocks detail.
    private const val BACKGROUND = 38
    private const val DECORATION = 13314
    private const val DECORATION_DRAWABLE = 35
    private const val SOLID = 13340
    private const val SOLID_COLOR = 35
    private const val PAGER = 17259
    private const val PAGER_ORIENTATION = 35
    private const val PAGER_SNAP = 36
    private const val PAGER_INDICATOR = 38
    private const val PAGER_INDICATOR_PLACE = 40
    private const val PAGER_ALIGN = 42
    private const val PAGE_INSET = 24
    private const val PAGE_INSET_START = 56
    private const val PAGE_INSET_END = 57
    private const val BIND_EXTENSION = 13914
    private const val BIND_SCRIPT = 42
    private const val CALL = 18212
    private const val ASYNC_ACTION = 17659
    private const val CALL_RUNTIME = 19678
    private const val TREE_MAKE = 20326
    private const val ARRAY_MAKE = 17580
    private const val SHOW_TOAST = 23898
    private const val ACTION_CALLBACKS = 16131
    private const val FUNC = 18220
    private const val DELAY = 18219

    private const val COLOR = 13365
    private const val COLOR_LIGHT = 35
    private const val COLOR_DARK = 36

    private const val TEXT_TEXT = 41
    private const val TEXT_ALIGN = 42
    private const val MAX_LINES = 38
    private const val TEXT_SIZE = 45
    private const val TEXT_COLOR = 43
    private const val FONT_FAMILY = 35
    private const val LINE_HEIGHT = 59

    private const val IMAGE_URL = 41
    private const val SCALE_TYPE = 40
    private const val IMAGE_TINT = 35

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
    private const val POSITION = 54
    private const val TOP = 57
    private const val BOTTOM = 36
    private const val START = 42
    private const val END = 55

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
