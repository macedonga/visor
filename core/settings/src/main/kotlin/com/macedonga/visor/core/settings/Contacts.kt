package com.macedonga.visor.core.settings

import com.google.flatbuffers.FlexBuffersBuilder
import java.nio.ByteBuffer

/**
 * The glasses' contact store: RLDrive namespaces contacts_native, contacts_whats_app,
 * contacts_messenger and contacts_instagram, objects of type "contact" keyed by the provider's
 * contact id, data a FlexBuffers map as the Meta app writes it. The assistant resolves the senders
 * it announces against it.
 */
data class GlassesContact(
    val id: String,
    val name: String,
    // Each number as the Meta app writes it: type, country code, number, an empty field, label.
    val numbers: List<Number> = emptyList(),
    val favorite: Boolean = false,
    val nicknames: List<String> = emptyList(),
) {
    // type: 0 work, 1 home, 2 mobile, 3 other.
    data class Number(val type: Int, val countryCode: String, val label: String, val number: String)
}

const val TYPE_CONTACT = "contact"

fun contactsNamespace(provider: String) = "contacts_$provider"

/** One contact as a FlexBuffers map: name, phoneNumbers ("type,cc,number,,label" joined by ";"), ... */
fun encodeContact(c: GlassesContact): ByteArray {
    val b = FlexBuffersBuilder(ByteBuffer.allocate(512), FlexBuffersBuilder.BUILDER_FLAG_SHARE_KEYS)
    val start = b.startMap()
    b.putString("name", c.name)
    b.putString("nicknames", c.nicknames.joinToString(";") { csv(it) })
    b.putString("relationship", "")
    b.putString("phoneNumbers", c.numbers.joinToString(";") { listOf(typeName(it.type), it.countryCode, it.number, "", csv(it.label)).joinToString(",") })
    b.putInt("contactType", 0)
    b.putBoolean("isFavorite", c.favorite)
    b.putString("profilePictureUri", "")
    b.putString("shareableEncryptedChatID", "")
    b.putInt("scoreSharedAt", 0)
    b.putBoolean("isE2EE", false)
    b.putString("groupParticipants", "")
    b.endMap(null, start)
    val out = b.finish()
    return ByteArray(out.remaining()).also { out.get(it) }
}

// The Meta app writes the type by name: CUSTOM for anything other than work, home and mobile.
private fun typeName(type: Int) = when (type) { 0 -> "WORK"; 1 -> "HOME"; 2 -> "CELL"; else -> "CUSTOM" }

private fun csv(s: String) = if (s.any { it == ',' || it == ';' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** A contact object for [readNamespace]'s push. */
fun contactObject(c: GlassesContact) = PushObject(c.id, TYPE_CONTACT, encodeContact(c))
