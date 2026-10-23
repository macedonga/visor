package com.macedonga.visor.connection

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.macedonga.visor.R
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.notify.TextReplies
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc70, smartglasses_messaging, for the phone's own texts (provider 3, native), as the Meta app serves them from
 * Android's SMS store: the glasses load the threads (type 1) and one thread's messages (2), SMS and MMS together, and
 * send a text (8). A conversation whose notification is up answers through the Messages app's reply, as RCS when it
 * is one; any other goes as SMS, answered once the radio has sent it. Each request comes on a channel of its own and
 * is answered with its own type; a reply over 1000 bytes goes as type-32 chunks when the request allows them. Texts
 * that arrive reach the glasses as native notifications, from the SMS app's own notifications (see MirrorListener).
 * The Phone app's recent calls (type 11) come on this service too; [PhoneCalls] answers them, and the call control
 * (placing, answering) is [PhoneCallControl]'s.
 *
 * The RCS chats Google Messages files as MMS are marked restricted, and Android shows those rows only to the default
 * SMS app: a chat with nothing else in it is missing from the list here.
 */
@Singleton
class PhoneMessages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
    private val replies: TextReplies,
    private val calls: PhoneCalls,
    private val control: PhoneCallControl,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Messages: failed: $e") })

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        // Call control has types with empty payloads, so it is checked before the text's.
        if (message.info.messageType in PhoneCallControl.TYPES) return control.respond(message, connection)
        if (message.payload.isEmpty()) return
        val request = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        val type = message.info.messageType
        val reply = DataXConnection.mirror(message.info.channelId)
        // The Phone app's recent calls, asked once per provider, each waiting for its answer.
        if (type == MT_LOAD_CALL_LOG) return calls.loadCallLog(request, reply, connection)
        if (type !in SERVED) return log.get().add("Messages: type $type not served")
        // The phone's texts, and replies to a chat notification (Messenger, WhatsApp, Instagram) through its reply action.
        val provider = request.varint(1)
        val chat = type == MT_OUTGOING && provider in CHAT
        if (provider != NATIVE && !chat) return log.get().add("Messages: provider $provider not served")
        scope.launch {
            when (type) {
                MT_LOAD_THREADS -> threads(request, reply, connection)
                MT_LOAD_MESSAGES -> messages(request, reply, connection)
                MT_OUTGOING -> send(request, reply, connection, chat)
            }
        }
    }

    /** LoadThreadsRequest {2 request id, 3 count, 5 chunked}: the newest conversations, SMS, MMS and RCS alike. */
    private suspend fun threads(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val id = requests.start("sms://threads", SOURCE_SMS)
        val requestId = request.bytes(2)
        if (!allowed(Manifest.permission.READ_SMS)) {
            answer(mux, reply, MT_LOAD_THREADS, Wire.fieldBytes(1, requestId) + refused(), request.varint(5) != 0L)
            return requests.finish(id, "SMS not allowed", failed = true)
        }
        val count = request.varint(3).toInt().takeIf { it > 0 } ?: DEFAULT_THREADS
        val threads = conversations(count, null)
        val names = HashMap<String, String>()
        var body = Wire.fieldBytes(1, requestId)
        for (t in threads) {
            val name = t.addresses.joinToString(", ") { a -> names.getOrPut(a) { nameOf(a) } }
            val latest = t.snippet.ifBlank { messagesOf(t.id, 1).firstOrNull()?.text.orEmpty() }
            // SgThread: 1 id, 2 name, 3 type (1 group), 4 latest message, 5 its time, 6 Participant {1 id}.
            body += Wire.fieldBytes(
                2,
                Wire.fieldBytes(1, t.id.toString().encodeToByteArray()) + Wire.fieldBytes(2, name.encodeToByteArray()) +
                    (if (t.addresses.size > 1) Wire.fieldVarint(3, THREAD_GROUP) else ByteArray(0)) +
                    Wire.fieldBytes(4, latest.encodeToByteArray()) + Wire.fieldVarint(5, t.date) +
                    t.addresses.fold(ByteArray(0)) { acc, a -> acc + Wire.fieldBytes(6, Wire.fieldBytes(1, a.encodeToByteArray())) },
            )
        }
        body += Wire.fieldVarint(3, threads.maxOfOrNull { it.date } ?: 0)
        answer(mux, reply, MT_LOAD_THREADS, body, request.varint(5) != 0L)
        requests.finish(id, "${threads.size} threads", failed = false, bytes = body.size.toLong())
    }

    /** LoadMessagesRequest {2 request id, 3 count, 4 thread id, 8 chunked, 9 phone numbers}: oldest first. */
    private suspend fun messages(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val requestId = request.bytes(2)
        val numbers = request.repeatedBytes(9).map { it.decodeToString() }.filter { it.isNotBlank() }
        val chunked = request.varint(8) != 0L
        val id = requests.start("sms://thread/${request.bytes(4).decodeToString()}", SOURCE_SMS)
        if (!allowed(Manifest.permission.READ_SMS)) {
            answer(mux, reply, MT_LOAD_MESSAGES, Wire.fieldBytes(1, requestId) + refused(), chunked)
            return requests.finish(id, "SMS not allowed", failed = true)
        }
        val thread = request.bytes(4).decodeToString().toLongOrNull()
            ?: numbers.takeIf { it.isNotEmpty() }?.let { runCatching { Telephony.Threads.getOrCreateThreadId(context, it.toSet()) }.getOrNull() }
        val count = request.varint(3).toInt().takeIf { it > 0 }?.coerceAtMost(MAX_MESSAGES) ?: DEFAULT_MESSAGES
        val rows = if (thread != null) messagesOf(thread, count) else emptyList()
        var body = Wire.fieldBytes(1, requestId)
        for (m in rows.asReversed()) {
            // SgMessage: 1 id, 2 text, 3 time, 4 the address (the other side's, in or out), 7 attachment, 19 box, 20 service.
            body += Wire.fieldBytes(
                2,
                Wire.fieldBytes(1, m.id.encodeToByteArray()) + Wire.fieldBytes(2, m.text.encodeToByteArray()) +
                    Wire.fieldVarint(3, m.date) + Wire.fieldBytes(4, m.address.encodeToByteArray()) +
                    (if (m.attachment) Wire.fieldVarint(7, 1) else ByteArray(0)) +
                    Wire.fieldBytes(19, folder(m.box).encodeToByteArray()) + Wire.fieldVarint(20, m.service),
            )
        }
        body += Wire.fieldVarint(5, RESPONSE_FILE_BUS) + Wire.fieldBytes(7, (thread ?: 0).toString().encodeToByteArray())
        answer(mux, reply, MT_LOAD_MESSAGES, body, chunked)
        requests.finish(id, "${rows.size} messages", failed = false, bytes = body.size.toLong())
    }

    /** OutgoingMessageRequest {2 request id, 3 thread id, 4 text, 7 participants}: OutgoingMessageResponse {1, 2 status, 3 error}. */
    private suspend fun send(request: Wire.Fields, reply: Int, mux: DataXConnection, chat: Boolean) {
        val requestId = request.bytes(2)
        val text = request.bytes(4).decodeToString()
        val thread = request.bytes(3).decodeToString()
        val to = request.repeatedBytes(7).map { it.decodeToString() }.filter { it.isNotBlank() }.ifEmpty { addressesOf(thread) }
        // A conversation with its notification up answers through the Messages app, as RCS when it is one. A chat only does that.
        val viaApp = text.isNotBlank() && (chat || replies.has(thread))
        val id = requests.start("${if (viaApp) "messages" else "sms"}://send/${to.joinToString(",").ifEmpty { thread }}", SOURCE_SMS)
        val error = when {
            viaApp -> replies.reply(context, thread, text)
            chat -> if (text.isBlank()) "Empty message" else "No reply in that conversation"
            !allowed(Manifest.permission.SEND_SMS) -> "SMS not allowed in Visor"
            to.isEmpty() -> "No recipient"
            to.size > 1 -> "Group texts are not supported"
            text.isBlank() -> "Empty message"
            else -> deliver(to.single(), text)
        }
        val body = Wire.fieldBytes(1, requestId) + Wire.fieldVarint(2, if (error == null) OUT_SUCCESS else OUT_FAILURE) +
            (error?.let { Wire.fieldBytes(3, it.encodeToByteArray()) } ?: ByteArray(0))
        // The glasses may have closed the channel by now; the status is then theirs to miss.
        runCatching { mux.send(reply, body, messageType = MT_OUTGOING) }
        requests.finish(id, error ?: "sent", failed = error != null, bytes = text.length.toLong())
    }

    /** Sends one text and waits for the radio's word on every part: null when all went, else why not. */
    private suspend fun deliver(to: String, text: String): String? {
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(
                parts.indices.map {
                    PendingIntent.getBroadcast(context, it, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                },
            )
            // Android files a text a non-default app sends in the SMS store itself.
            if (parts.size == 1) sms.sendTextMessage(to, null, text, sent[0], null) else sms.sendMultipartTextMessage(to, null, parts, sent, null)
            val codes = withTimeoutOrNull(SEND_TIMEOUT_MS) { List(parts.size) { results.receive() } } ?: return "No answer from the phone's radio"
            return when (codes.firstOrNull { it != Activity.RESULT_OK }) {
                null -> null
                SmsManager.RESULT_ERROR_RADIO_OFF -> "The phone's radio is off"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                else -> "Not sent (error ${codes.first { it != Activity.RESULT_OK }})"
            }
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun answer(mux: DataXConnection, reply: Int, type: Int, body: ByteArray, chunked: Boolean) {
        if (!chunked || body.size <= CHUNK) return mux.send(reply, body, messageType = type)
        // The Meta app's chunk: the reply's type, 36 zero bytes, the index, 1000, the count, then up to 1000 bytes.
        val total = (body.size + CHUNK - 1) / CHUNK
        for (i in 0 until total) {
            val head = ByteBuffer.allocate(CHUNK_HEAD).putInt(type).put(ByteArray(36)).putInt(i).putInt(CHUNK).putInt(total).array()
            mux.send(reply, head + body.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, body.size)), messageType = MT_CHUNK)
        }
    }

    /** The numbers of a thread's other side. */
    fun addressesOf(thread: String): List<String> {
        val id = thread.toLongOrNull() ?: return emptyList()
        if (!allowed(Manifest.permission.READ_SMS)) return emptyList()
        return conversations(1, id).firstOrNull()?.addresses.orEmpty()
    }

    private class Conversation(val id: Long, val date: Long, val addresses: List<String>, val snippet: String)

    /** Conversations newest first, from Android's thread list, which holds SMS, MMS and the RCS chats Messages files as MMS. */
    private fun canonical(): Map<Long, String> {
        val canonical = HashMap<Long, String>()
        context.contentResolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canonical[c.getLong(0)] = c.getString(1).orEmpty()
        }
        return canonical
    }

    /** For the debug receiver: the newest rows of the thread list as Visor reads them, ids and counts only. */
    fun describeThreads(count: Int): String {
        val canonical = canonical()
        val columns = arrayOf(Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.SNIPPET, Telephony.Threads.MESSAGE_COUNT)
        fun rows(uri: Uri, where: String? = null) = context.contentResolver.query(uri, arrayOf("_id"), where, null, null)?.use { it.count } ?: -1
        val lines = mutableListOf(
            "canonical addresses: ${canonical.size}, sms ${rows(Telephony.Sms.CONTENT_URI)}, mms ${rows(Telephony.Mms.CONTENT_URI)}",
            "thread 489: sms ${rows(Telephony.Sms.CONTENT_URI, "thread_id=489")}, mms ${rows(Telephony.Mms.CONTENT_URI, "thread_id=489")}; " +
                "thread 509: sms ${rows(Telephony.Sms.CONTENT_URI, "thread_id=509")}; listed ${rows(CONVERSATIONS, "_id IN (489,509)")}",
        )
        context.contentResolver.query(CONVERSATIONS, columns, null, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
            lines += "rows: ${c.count}"
            while (c.moveToNext() && lines.size < count + 2) {
                val ids = c.getString(2).orEmpty()
                val known = ids.split(' ').count { canonical[it.toLongOrNull()]?.isNotBlank() == true }
                lines += "${c.getLong(0)} date=${c.getLong(1)} messages=${c.getInt(4)} recipients=[$ids] resolved=$known"
            }
        } ?: lines.add("no cursor")
        return lines.joinToString("\n")
    }

    private fun conversations(count: Int, only: Long?): List<Conversation> {
        val canonical = canonical()
        val out = mutableListOf<Conversation>()
        val where = only?.let { "${Telephony.Threads._ID} = $it" }
        val columns = arrayOf(Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.SNIPPET, Telephony.Threads.MESSAGE_COUNT)
        context.contentResolver.query(CONVERSATIONS, columns, where, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < count) {
                if (c.getInt(4) == 0) continue
                val addresses = c.getString(2).orEmpty().split(' ').mapNotNull { canonical[it.toLongOrNull()]?.takeIf(String::isNotBlank) }
                if (addresses.isEmpty()) continue
                out += Conversation(c.getLong(0), c.getLong(1), addresses, c.getString(3).orEmpty())
            }
        }
        return out
    }

    private class Message(val id: String, val text: String, val date: Long, val address: String, val box: Int, val service: Long, val attachment: Boolean)

    /** A thread's newest messages, SMS and MMS together, newest first. */
    private fun messagesOf(thread: Long, count: Int): List<Message> {
        val out = mutableListOf<Message>()
        read("${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString())) {
            out += Message("content://sms/${it.id}", it.body, it.date, it.address, it.box, SERVICE_SMS, false)
            out.size < count
        }
        val mms = mutableListOf<Message>()
        val columns = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
        context.contentResolver.query(Telephony.Mms.CONTENT_URI, columns, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(thread.toString()), "${Telephony.Mms.DATE} DESC")?.use { c ->
            while (c.moveToNext() && mms.size < count) {
                val id = c.getLong(0)
                val box = c.getInt(2)
                val (text, kind) = mmsText(id)
                // An MMS keeps its date in seconds.
                mms += Message("content://mms/$id", text.ifBlank { kind.orEmpty() }, c.getLong(1) * 1000, mmsAddress(id, box), box, SERVICE_MMS, kind != null)
            }
        }
        return (out + mms).sortedByDescending { it.date }.take(count)
    }

    /** An MMS's text parts, and a word for its picture or video when it has one. */
    private fun mmsText(id: Long): Pair<String, String?> {
        val text = StringBuilder()
        var kind: String? = null
        context.contentResolver.query(MMS_PARTS, arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT), "${Telephony.Mms.Part.MSG_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0).orEmpty()
                when {
                    type == "text/plain" -> c.getString(1)?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
                    type.startsWith("image/") -> kind = kind ?: context.getString(R.string.texts_photo)
                    type.startsWith("video/") -> kind = kind ?: context.getString(R.string.texts_video)
                    type != "application/smil" -> kind = kind ?: context.getString(R.string.texts_attachment)
                }
            }
        }
        return text.toString() to kind
    }

    /** The other side of an MMS: who sent one that came in, who one sent went to. */
    private fun mmsAddress(id: Long, box: Int): String {
        val wanted = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) PDU_FROM else PDU_TO
        context.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getInt(1) == wanted) c.getString(0)?.takeIf { it.isNotBlank() && it != INSERT_ADDRESS }?.let { return it }
        }
        return ""
    }

    private class Sms(val id: Long, val thread: Long, val address: String, val body: String, val date: Long, val box: Int)

    /** SMS rows newest first, handed to [more] until it says stop. */
    private fun read(selection: String?, args: Array<String>?, more: (Sms) -> Boolean) {
        val columns = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, columns, selection, args, "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(2).orEmpty()
                // A draft with no number yet is not a conversation.
                if (address.isBlank()) continue
                if (!more(Sms(c.getLong(0), c.getLong(1), address, c.getString(3).orEmpty(), c.getLong(4), c.getInt(5)))) break
            }
        }
    }

    /** The contact's name for a number, else the number as the phone writes it. */
    private fun nameOf(number: String): String {
        if (allowed(Manifest.permission.READ_CONTACTS)) {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            runCatching {
                context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val country = context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: Locale.getDefault().country
        return PhoneNumberUtils.formatNumber(number, country) ?: number
    }

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** SgError {1 title, 2 message} in a reply's field 6. */
    private fun refused(): ByteArray =
        Wire.fieldBytes(6, Wire.fieldBytes(1, "SMS not allowed".encodeToByteArray()) + Wire.fieldBytes(2, "Allow SMS for Visor on the phone.".encodeToByteArray()))

    companion object {
        const val SVC = 70
        private const val MT_LOAD_THREADS = 1
        private const val MT_LOAD_MESSAGES = 2
        private const val MT_OUTGOING = 8
        private const val MT_LOAD_CALL_LOG = 11
        private const val MT_CHUNK = 32
        private val SERVED = setOf(MT_LOAD_THREADS, MT_LOAD_MESSAGES, MT_OUTGOING)
        private const val NATIVE = 3L
        private val CHAT = setOf(1L, 2L, 4L)
        private const val DEFAULT_THREADS = 10
        private const val DEFAULT_MESSAGES = 25
        private const val MAX_MESSAGES = 100
        private const val SERVICE_SMS = 1L
        private const val SERVICE_MMS = 2L
        private const val THREAD_GROUP = 1L
        private const val PDU_FROM = 137
        private const val PDU_TO = 151
        private const val INSERT_ADDRESS = "insert-address-token"
        private val CONVERSATIONS: Uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        private val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val MMS_PARTS: Uri = Uri.parse("content://mms/part")
        private const val RESPONSE_FILE_BUS = 2L
        private const val OUT_SUCCESS = 2L
        private const val OUT_FAILURE = 3L
        private const val SEND_TIMEOUT_MS = 30_000L
        private const val CHUNK = 1000
        private const val CHUNK_HEAD = 52
        private const val SOURCE_SMS = -4

        // Telephony's box numbers as the Meta app names them. Lowercase: the glasses mark a message as sent only for
        // "sent", and any other one of ours shows as still sending.
        private fun folder(box: Int) = when (box) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
            else -> "inbox"
        }
    }
}
