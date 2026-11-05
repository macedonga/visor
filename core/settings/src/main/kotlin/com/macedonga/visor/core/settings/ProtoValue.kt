package com.macedonga.visor.core.settings

import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.protocol.Wire
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** One field as it sits in the message: varints in [varint], everything else as raw [bytes]. */
class ProtoField(val number: Int, val wireType: Int, val varint: Long = 0, val bytes: ByteArray = ByteArray(0))

/** A message read in field order, so whatever an edit does not touch is written back as it was. Null if malformed. */
fun parseProto(data: ByteArray): List<ProtoField>? {
    val out = ArrayList<ProtoField>()
    var at = 0
    try {
        while (at < data.size) {
            val tag = Wire.decodeVarint(data, at)
            at = tag.offset
            val number = (tag.value ushr 3).toInt()
            val type = (tag.value and 7).toInt()
            if (number <= 0) return null
            when (type) {
                0 -> Wire.decodeVarint(data, at).let { out += ProtoField(number, 0, varint = it.value); at = it.offset }
                1, 5 -> {
                    val size = if (type == 1) 8 else 4
                    if (at + size > data.size) return null
                    out += ProtoField(number, type, bytes = data.copyOfRange(at, at + size))
                    at += size
                }
                2 -> {
                    val len = Wire.decodeVarint(data, at)
                    at = len.offset
                    if (len.value < 0 || len.value > data.size - at) return null
                    val end = at + len.value.toInt()
                    out += ProtoField(number, 2, bytes = data.copyOfRange(at, end))
                    at = end
                }
                else -> return null
            }
        }
    } catch (_: Exception) {
        return null
    }
    return out
}

fun encodeProto(fields: List<ProtoField>): ByteArray {
    val out = ByteArrayOutputStream()
    for (f in fields) {
        out.write(Wire.encodeVarint((f.number.toLong() shl 3) or f.wireType.toLong()))
        when (f.wireType) {
            0 -> out.write(Wire.encodeVarintUnsigned(f.varint))
            2 -> {
                out.write(Wire.encodeVarint(f.bytes.size.toLong()))
                out.write(f.bytes)
            }
            else -> out.write(f.bytes)
        }
    }
    return out.toByteArray()
}

/** The layout of an encoded setting: its messages and enums, from the mapping. */
class ProtoSchema(
    val root: String,
    val messages: Map<String, List<ProtoFieldSpec>>,
    val enums: Map<String, List<Control.Option>>,
    // Status the phone or the glasses keep; shown, not edited.
    val readOnly: Boolean,
)

/** [type] is bool, int, enum, string or message; [ref] names the enum or message. */
class ProtoFieldSpec(val number: Int, val label: String, val type: String, val ref: String?, val repeated: Boolean)

fun parseSchema(o: JSONObject): ProtoSchema? {
    val root = o.optString("root").ifEmpty { return null }
    val messages = o.optJSONObject("messages") ?: return null
    val enums = o.optJSONObject("enums") ?: JSONObject()
    return ProtoSchema(
        root = root,
        messages = messages.keys().asSequence().associateWith { name ->
            val a: JSONArray = messages.getJSONArray(name)
            a.objects().map {
                ProtoFieldSpec(it.getInt("n"), it.getString("label"), it.getString("type"), it.optString("ref").ifEmpty { null }, it.optBoolean("repeated"))
            }
        },
        enums = enums.keys().asSequence().associateWith { name ->
            val a = enums.getJSONArray(name)
            a.objects().map { Control.Option(it.getString("v"), it.getString("label")) }
        },
        readOnly = o.optBoolean("read_only"),
    )
}
