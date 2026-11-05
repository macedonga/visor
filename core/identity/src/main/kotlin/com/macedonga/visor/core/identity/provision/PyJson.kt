package com.macedonga.visor.core.identity.provision

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Python's `json.dumps(value)` with its defaults: `, ` and `: ` separators, ensure_ascii. */
internal fun pyJsonDumps(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

private fun write(element: JsonElement, out: StringBuilder) {
    when (element) {
        is JsonNull -> out.append("null")
        is JsonObject -> {
            out.append('{')
            element.entries.forEachIndexed { i, (key, value) ->
                if (i > 0) out.append(", ")
                writeString(key, out)
                out.append(": ")
                write(value, out)
            }
            out.append('}')
        }
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { i, value ->
                if (i > 0) out.append(", ")
                write(value, out)
            }
            out.append(']')
        }
        is JsonPrimitive -> if (element.isString) writeString(element.content, out) else out.append(element.content)
    }
}

private fun writeString(value: String, out: StringBuilder) {
    out.append('"')
    for (c in value) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            // ensure_ascii: a Kotlin Char is a UTF-16 unit, so a character outside the BMP
            // already arrives as the surrogate pair Python writes for it.
            c.code < 0x20 || c.code > 0x7E -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
    }
    out.append('"')
}
