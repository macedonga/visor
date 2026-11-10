package com.macedonga.visor.phone

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.net.Uri
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** One of a contact's numbers. [kind] is mobile, home, work, the contact's own label, or empty. */
data class ContactNumber(val contact: Long, val name: String, val number: String, val kind: String, val primary: Boolean) {
    /** "Anna Rossi, mobile", as the model says it. */
    val label: String get() = if (kind.isEmpty()) name else "$name, $kind"
}

/** The one number a request names, or the words for the model when there is none or several. */
sealed interface Pick {
    data class One(val number: ContactNumber) : Pick

    /** Nothing to dial or text: [ask] when the wearer only has to choose. */
    data class Not(val text: String, val ask: Boolean) : Pick
}

/** Finds the number the wearer means by a contact's name, as they say it to the glasses. */
@Singleton
class ContactFinder @Inject constructor(@ApplicationContext private val context: Context) {

    /** The number [who] names: a number as it is, else a contact by name or nickname, narrowed by [kind]. */
    fun pick(who: String, kind: String): Pick {
        if (isNumber(who)) return Pick.One(ContactNumber(0, who, who.filter { it.isDigit() || it == '+' }, "", false))
        if (!context.allows(Manifest.permission.READ_CONTACTS)) return Pick.Not(NO_CONTACTS, false)
        val found = runCatching { byName(who).ifEmpty { byNickname(who) } }.getOrDefault(emptyList())
        if (found.isEmpty()) {
            return Pick.Not("No contact matches \"$who\". Ask the wearer for the name as it is saved on the phone, or the number.", false)
        }
        val people = found.groupBy { it.contact }.values
        // Several people match "Anna": the one saved as exactly that, else the wearer picks.
        val person = people.singleOrNull() ?: people.singleOrNull { plain(it.first().name) == plain(who) }
            ?: return Pick.Not(
                "Several contacts match \"$who\": ${people.take(MAX_LISTED).joinToString("; ") { it.first().name }}. " +
                    "Ask the wearer which one, then try again with the full name.",
                true,
            )
        val name = person.first().name
        val numbers = person.distinctBy { digits(it.number) }
        val wanted = if (kind.isEmpty()) numbers else numbers.filter { it.kind.equals(kind, ignoreCase = true) }
        val chosen = wanted.singleOrNull()
            ?: wanted.singleOrNull { it.primary }
            // With no kind said, the one mobile number is the one people mean.
            ?: (if (kind.isEmpty()) numbers.singleOrNull { it.kind == MOBILE } else null)
        if (chosen != null) return Pick.One(chosen)
        val listed = numbers.joinToString("; ") { "${it.kind.ifEmpty { "number" }} ${it.number}" }
        val lead = if (wanted.isEmpty()) "$name has no $kind number" else "$name has several numbers"
        return Pick.Not("$lead: $listed. Ask the wearer which one, then try again with that number.", true)
    }

    private fun byName(who: String): List<ContactNumber> =
        numbers(Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(who)), null, null)

    private fun byNickname(who: String): List<ContactNumber> {
        val ids = context.contentResolver.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID), "${Data.MIMETYPE} = ? AND ${Nickname.NAME} LIKE ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE, who.trim()), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        if (ids.isEmpty()) return emptyList()
        return numbers(Phone.CONTENT_URI, "${Phone.CONTACT_ID} IN (${ids.joinToString(",")})", null)
    }

    private fun numbers(uri: Uri, selection: String?, args: Array<String>?): List<ContactNumber> =
        context.contentResolver.query(
            uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
            selection, args, "${Phone.STARRED} DESC, ${Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    add(ContactNumber(c.getLong(0), c.getString(1).orEmpty(), number, kindOf(c.getInt(3), c.getString(4)), c.getInt(5) != 0))
                }
            }
        }.orEmpty()

    private companion object {
        const val MOBILE = "mobile"
        const val MAX_LISTED = 6
        const val NO_CONTACTS = "Visor may not read the phone's contacts. Tell the wearer to allow Contacts in Visor, Settings > Phone access, " +
            "or to say the number."

        fun kindOf(type: Int, label: String?): String = when (type) {
            Phone.TYPE_MOBILE -> MOBILE
            Phone.TYPE_HOME -> "home"
            Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
            Phone.TYPE_CUSTOM -> label?.trim().orEmpty().lowercase()
            else -> "other"
        }

        fun isNumber(s: String): Boolean = s.count { it.isDigit() } >= 3 && s.all { it.isDigit() || it in "+ -()./" }

        fun digits(s: String) = s.filter { it.isDigit() }.takeLast(9)

        fun plain(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}
