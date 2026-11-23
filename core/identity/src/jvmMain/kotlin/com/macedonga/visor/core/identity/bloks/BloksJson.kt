package com.macedonga.visor.core.identity.bloks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** JSON as Bloks writes it (`android.util.JsonWriter`): insertion order, and booleans as 1/0 unless asked otherwise. */
object BloksJson {
    fun encode(x: Any?, boolsAsInts: Boolean): String = when (x) {
        null -> ""
        is Map<*, *>, is List<*> -> StringBuilder().also { write(it, x, boolsAsInts) }.toString()
        else -> x.toString()
    }

    private fun write(out: StringBuilder, x: Any?, boolsAsInts: Boolean) {
        when (x) {
            null -> out.append("null")
            is Boolean -> out.append(if (boolsAsInts) (if (x) "1" else "0") else x.toString())
            is Int, is Long -> out.append(x.toString())
            is Number -> out.append(x.toDouble().let { if (it.isNaN() || it.isInfinite()) "null" else it.toString() })
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in x) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k.toString())
                    out.append(':')
                    write(out, v, boolsAsInts)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                x.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v, boolsAsInts)
                }
                out.append(']')
            }
            else -> string(out, x.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                // JsonWriter escapes the line and paragraph separators too.
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    fun decode(s: String): Any? = from(Json.parseToJsonElement(s))

    private fun from(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: e.doubleOrNull
        is JsonArray -> e.mapTo(ArrayList(), ::from)
        is JsonObject -> e.entries.associateTo(LinkedHashMap()) { (k, v) -> k to from(v) }
    }
}
