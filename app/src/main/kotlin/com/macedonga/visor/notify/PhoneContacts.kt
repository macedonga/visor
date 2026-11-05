package com.macedonga.visor.notify

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesContact
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.PushObject
import com.macedonga.visor.core.settings.TYPE_CONTACT
import com.macedonga.visor.core.settings.contactObject
import com.macedonga.visor.core.settings.contactsNamespace
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's address book in the glasses' contacts_native: one object
 * per Android contact id, with every number that has a normalised form. Synced on each connection
 * and whenever the address book changes.
 */
@Singleton
class PhoneContacts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val settings: GlassesSettings,
) {
    private val running = Mutex()
    private var pending: Job? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { ready ->
                if (ready) later(scope, FIRST_DELAY_MS)
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = later(scope, CHANGE_DELAY_MS)
        }
        runCatching { context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }
        scope.coroutineContext.job.invokeOnCompletion { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    @Synchronized
    private fun later(scope: CoroutineScope, ms: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(ms)
            Log.i("Visor", "Contacts: " + sync())
        }
    }

    suspend fun sync(): String = running.withLock {
        if (!context.allows(Manifest.permission.READ_CONTACTS)) return "no contacts permission"
        if (connection.state.value.link != LinkState.READY) return "not connected"
        val mine = withContext(Dispatchers.IO) { read() }
        val namespace = contactsNamespace("native")
        val held = (settings.readNamespace(namespace) ?: return "no answer from the glasses").filter { it.type == TYPE_CONTACT && !it.deleted }.associate { it.uri to it.bytes }
        val changed = mine.map(::contactObject).filter { !held[it.uri].contentEquals(it.data) }
        val gone = held.keys - mine.map { it.id }.toSet()
        val push = changed + gone.map { PushObject(it, TYPE_CONTACT, ByteArray(0), deleted = true) }
        push.chunked(BATCH).forEach { settings.readNamespace(namespace, it) }
        "${mine.size} on the phone, ${held.size} on the glasses, ${changed.size} sent, ${gone.size} removed"
    }

    // Phone rows with a contact id, a name and a normalised number.
    private fun read(): List<GlassesContact> {
        val byId = LinkedHashMap<String, GlassesContact>()
        val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL, Phone.STARRED)
        context.contentResolver.query(Phone.CONTENT_URI, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val type = c.getInt(3)
                val label = (if (type == Phone.TYPE_CUSTOM) c.getString(4) else Phone.getTypeLabel(context.resources, type, "")?.toString())
                    .orEmpty().ifBlank { "phone" }
                val n = GlassesContact.Number(typeCode(type), callingCode(number), label, number)
                val old = byId[id]
                byId[id] = old?.copy(numbers = (old.numbers + n).distinctBy { it.number })
                    ?: GlassesContact(id, name, listOf(n), favorite = c.getInt(5) != 0)
            }
        }
        return byId.values.toList()
    }

    private companion object {
        const val FIRST_DELAY_MS = 10_000L
        const val CHANGE_DELAY_MS = 5_000L
        const val BATCH = 100

        // Android's phone type to the glasses': home 1, mobile 2, work 0, anything else 3.
        fun typeCode(type: Int) = when (type) {
            Phone.TYPE_HOME -> 1
            Phone.TYPE_MOBILE -> 2
            Phone.TYPE_WORK -> 0
            else -> 3
        }

        // E.164 calling codes are prefix-free: 1 and 7 alone, these two-digit ones, the rest three digits.
        val TWO_DIGIT = ("20 27 30 31 32 33 34 36 39 40 41 43 44 45 46 47 48 49 51 52 53 54 55 56 57 58 " +
            "60 61 62 63 64 65 66 81 82 84 86 90 91 92 93 94 95 98").split(' ').toSet()

        fun callingCode(e164: String): String {
            val d = e164.removePrefix("+")
            if (d.length == e164.length || d.isEmpty()) return ""
            if (d[0] == '1' || d[0] == '7') return d.take(1)
            return d.take(2).takeIf { it in TWO_DIGIT } ?: d.take(3)
        }
    }
}
