package com.macedonga.visor.core.settings

import com.macedonga.visor.core.connection.objects
import org.json.JSONArray
import org.json.JSONObject

/** How a setting is shown and what it accepts, from the firmware and Meta app mapping in settings_ui.json. */
data class SettingSpec(
    val key: String,
    val label: String,
    val note: String?,
    val group: String,
    val control: Control,
    // The key says "disabled" and the label is the positive form, so the switch shows the opposite.
    val invert: Boolean,
    val confidence: String,
    // Meta product ids the Meta app shows this on (null: all), and ones it never does.
    val products: Set<Int>? = null,
    val notProducts: Set<Int> = emptySet(),
)

/** Whether the Meta app offers this on [product]; anything goes while the product is unknown. */
fun SettingSpec.offeredOn(product: Int?) = offered(products, notProducts, product)

fun Control.Option.offeredOn(product: Int?) = offered(products, notProducts, product)

private fun offered(only: Set<Int>?, not: Set<Int>, product: Int?) =
    product == null || (product !in not && (only == null || product in only))

sealed interface Control {
    data object Toggle : Control

    /** [detail] is the Meta app's second line, such as "3K at 30fps" under "High resolution". */
    data class Option(
        val value: String,
        val label: String,
        val hidden: Boolean = false,
        val detail: String? = null,
        val products: Set<Int>? = null,
        val notProducts: Set<Int> = emptySet(),
    )

    data class Choice(val options: List<Option>) : Control

    data class Range(val min: Double, val max: Double, val step: Double, val unit: String) : Control

    /** An open count or size: no bounds exist beyond the optional [min] and [max]. */
    data class Number(val unit: String, val min: Long?, val max: Long?) : Control

    data class Text(val max: Int, val what: String) : Control

    /** A locale as the phone app writes it, en_GB. */
    data object Locale : Control

    /** An installed app's package, for [role] "sms" or "media", plus fixed [extra] values. */
    data class App(val role: String, val extra: List<Option>) : Control

    /** Phone numbers joined by [separator]. */
    data class PhoneList(val separator: String) : Control

    /** One number packing several values: [width] bits from [shift], named by [options] when wider than one bit. */
    data class BitField(val label: String, val shift: Int, val width: Int, val options: List<Option>)

    data class Bits(val fields: List<BitField>) : Control

    /** The continuous AI camera's JSON calendar: profiles of settings, and weekly windows that pick one. */
    data object Schedule : Control

    /** An encoded value with a known layout; [schema] is the field map an editor is built from. */
    data class Structured(val format: String, val schema: JSONObject) : Control

    data object ReadOnly : Control
}

/** The display order of groups. */
val GROUPS = listOf(
    "Glasses", "Sound", "Touch and gestures", "Camera", "Video and live", "Meta AI", "Live AI and translation",
    "Calls and messages", "Notifications", "Music and media", "Connectivity", "Health and fitness",
    "Accessibility", "Privacy and data", "Updates and system", "Display", "Neural band gestures",
    "Developer and tests",
)

internal fun loadSpecs(): Map<String, SettingSpec> {
    val stream = GlassesSettings::class.java.getResourceAsStream("settings_ui.json") ?: return emptyMap()
    val array = JSONArray(stream.bufferedReader().use { it.readText() })
    return array.objects().map(::parseSpec).associateBy { it.key }
}

// The Meta app's own translation where the catalogue has one ("label_it"), else the English.
private val language = java.util.Locale.getDefault().language

private fun JSONObject.text(name: String): String? =
    optString("${name}_$language").ifEmpty { null } ?: optString(name).ifEmpty { null }

private fun JSONObject.ids(name: String): Set<Int>? = optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }

private fun parseOption(o: JSONObject) = Control.Option(
    o.getString("value"), o.text("label")!!, o.optBoolean("hidden"), o.text("detail"), o.ids("products"), o.ids("not_products").orEmpty(),
)

private fun parseSpec(o: JSONObject): SettingSpec = SettingSpec(
    key = o.getString("key"),
    label = o.text("label")!!,
    note = o.text("note"),
    group = o.getString("group"),
    control = parseControl(o.getJSONObject("control")),
    invert = o.optBoolean("invert"),
    confidence = o.optString("confidence", "low"),
    products = o.ids("products"),
    notProducts = o.ids("not_products").orEmpty(),
)

private fun parseControl(c: JSONObject): Control = when (c.getString("type")) {
    "toggle" -> Control.Toggle
    "choice" -> Control.Choice(c.getJSONArray("options").objects().map(::parseOption))
    "range" -> Control.Range(c.getDouble("min"), c.getDouble("max"), c.optDouble("step", 1.0), c.optString("unit"))
    "number" -> Control.Number(
        unit = c.optString("unit"),
        min = if (c.has("min")) c.getLong("min") else null,
        max = if (c.has("max")) c.getLong("max") else null,
    )
    "text" -> Control.Text(c.optInt("max", 100), c.optString("what"))
    "locale" -> Control.Locale
    "app" -> Control.App(
        role = c.optString("role"),
        extra = c.optJSONArray("extra")?.objects()?.map(::parseOption).orEmpty(),
    )
    "phone_list" -> Control.PhoneList(c.optString("separator", ","))
    "bits" -> c.getJSONArray("fields").let { a ->
        Control.Bits(
            a.objects().map { f ->
                Control.BitField(
                    label = f.text("label")!!,
                    shift = f.getInt("shift"),
                    width = f.optInt("width", 1),
                    options = f.optJSONArray("options")?.objects()?.map(::parseOption).orEmpty(),
                )
            },
        )
    }
    "schedule" -> Control.Schedule
    "structured" -> Control.Structured(c.getString("format"), c.optJSONObject("schema") ?: JSONObject())
    else -> Control.ReadOnly
}

/** The phone's words for a switch's two states and for no value. */
data class DisplayWords(val on: String, val off: String, val notSet: String)

/** The value as a person reads it: the option's name, or a number with its unit. */
fun SettingSpec.display(value: String, words: DisplayWords): String {
    val v = value.trim()
    return when (val c = control) {
        Control.Toggle -> if (asBool(v) != invert) words.on else words.off
        // A short spec line ("3K at 30fps") says more than its title ("High resolution").
        is Control.Choice -> c.options.firstOrNull { it.value == v }?.let { o -> o.detail?.takeIf { it.length <= 24 } ?: o.label } ?: v.ifEmpty { words.notSet }
        is Control.Range -> v.toDoubleOrNull()?.let { number(it) + unitSuffix(c.unit) } ?: v
        is Control.Number -> v.toDoubleOrNull()?.let { number(it) + unitSuffix(c.unit) } ?: v
        Control.Locale -> v.takeIf { it.isNotEmpty() }?.let { localeOf(it).displayName } ?: words.notSet
        is Control.App -> c.extra.firstOrNull { it.value == v }?.label ?: v.ifEmpty { words.notSet }
        is Control.PhoneList -> v.split(c.separator).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { words.notSet }
        else -> v.ifEmpty { words.notSet }
    }
}

fun number(x: Double): String = if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, x).trimEnd('0').trimEnd('.')

fun unitSuffix(unit: String): String = when (unit) {
    "" -> ""
    "%" -> "%"
    "count" -> ""
    else -> " $unit"
}

/** "en_GB" or "en-GB" as a Locale. */
fun localeOf(tag: String): java.util.Locale = java.util.Locale.forLanguageTag(tag.trim().replace('_', '-'))

/** A Locale the way the phone app writes it: language, underscore, country. */
fun localeValue(locale: java.util.Locale): String = if (locale.country.isEmpty()) locale.language else "${locale.language}_${locale.country}"
