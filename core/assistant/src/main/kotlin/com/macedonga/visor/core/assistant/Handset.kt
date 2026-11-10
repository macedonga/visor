package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's own things the model may use for the wearer. Each answer is in words for the model: [HandsetDone.failed]
 * when it did not work, with why.
 */
interface Handset {
    /**
     * Calls [who]: a contact's name, with [kind] (mobile, home, work) when the wearer said it, or a number; on WhatsApp
     * when [whatsApp].
     */
    suspend fun call(who: String, kind: String, whatsApp: Boolean): HandsetDone

    /** Texts [message] to [who], named as for [call]. */
    suspend fun text(who: String, kind: String, message: String): HandsetDone

    /** The last [count] calls in the phone's call log, newest first; only missed ones when [missed]. */
    suspend fun recentCalls(count: Int, missed: Boolean): HandsetDone

    /** Rings the phone so the wearer can find it, or stops it when [stop]. */
    suspend fun findPhone(stop: Boolean): HandsetDone
}

/**
 * What a phone action did: the words the model reads, whether it failed, and whom it was for, for the diary. [asked]:
 * nothing was done, the wearer has to choose first.
 */
data class HandsetDone(val text: String, val failed: Boolean = false, val subject: String = "", val asked: Boolean = false)

/** [Handset] and the phone's notifications ([messaging]) as tools for the model. */
internal class HandsetTools(private val handset: Handset, private val messaging: () -> Messaging?) {

    val prompt = " On the phone you can also call a contact or a number with call, at once when the wearer asks, and send a " +
        "text message with send_text. messages reads the chats in the phone's notifications and reply_message answers one. recent_calls says who called, find_phone rings the phone."

    val tools: List<Triple<String, String, JSONObject>> = listOf(
        Triple(
            "call",
            "Call a contact or a number from the wearer's phone. When several contacts or numbers match, the result lists them: " +
                "ask the wearer which, then call again. Never say a call started unless this tool says so.",
            schema(
                "who" to text("The contact's name as the wearer said it, or a number the wearer said or a search gave."),
                "kind" to text("The number's kind, only when the wearer said it.", "mobile", "home", "work", "other"),
                "app" to text("whatsapp only when the wearer asked for a WhatsApp call.", "phone", "whatsapp"),
                required = listOf("who"),
            ),
        ),
        Triple(
            "send_text",
            "Send a text message (SMS) from the wearer's phone to a contact or a number. Send the wearer's own words. When " +
                "the recipient or the words are unclear, ask first. Never say it was sent unless this tool says so.",
            schema(
                "to" to text("The contact's name as the wearer said it, or the number."),
                "text" to text("The message, as the wearer dictated it."),
                "kind" to text("The number's kind, only when the wearer said it.", "mobile", "home", "work", "other"),
                required = listOf("to", "text"),
            ),
        ),
        Triple(
            "messages",
            "Read the messages waiting in the phone's notifications (texts, WhatsApp, Telegram and other chats), newest first.",
            schema("from" to text("A person, group or app to keep only their conversations; empty for all.")),
        ),
        Triple(
            "reply_message",
            "Reply in a conversation from the phone's notifications, through its own app. Send the wearer's own words; ask " +
                "first when they are unclear. Never say it was sent unless this tool says so.",
            schema(
                "to" to text("The conversation's name, as messages lists it or the wearer says it."),
                "app" to text("The app, only when the wearer named it."),
                "text" to text("The reply, as the wearer dictated it."),
                required = listOf("to", "text"),
            ),
        ),
        Triple(
            "recent_calls",
            "The calls in the phone's call log, newest first: who called the wearer, whom they called, and the missed ones.",
            schema(
                "count" to prop("integer", "How many, 1 to 20; 5 when the wearer did not say."),
                "missed" to prop("boolean", "True for missed calls only."),
            ),
        ),
        Triple(
            "find_phone",
            "Ring the wearer's phone loudly, even on silent, so they can find it. It stops by itself when the phone's screen turns on.",
            schema("stop" to prop("boolean", "True to stop the ringing.")),
        ),
    )

    /** Runs [name] when it is one of these tools: what the model is told, and the diary's line for it. Null for another tool. */
    suspend fun run(name: String, input: JSONObject): Pair<ToolResult, ToolAction?>? = when (name) {
        "call" -> {
            val who = input.optString("who").trim()
            if (who.isEmpty()) ToolResult("no one to call", true) to null
            else acted(handset.call(who, input.optString("kind").trim(), input.optString("app").trim().equals("whatsapp", true)), "Call", "call", who)
        }
        "send_text" -> {
            val to = input.optString("to").trim()
            val message = input.optString("text").trim()
            when {
                to.isEmpty() -> ToolResult("no one to text", true) to null
                message.isEmpty() -> ToolResult("no message; ask the wearer what to say", true) to null
                else -> acted(handset.text(to, input.optString("kind").trim(), message), "Text", "send_text", to)
            }
        }
        "messages" -> messages(input.optString("from").trim()) to null
        "find_phone" -> {
            val stop = input.optBoolean("stop")
            val done = handset.findPhone(stop)
            ToolResult(done.text, done.failed) to if (stop) null else ToolAction("", "Ring the phone", !done.failed, kind = "find_phone")
        }
        "recent_calls" -> handset.recentCalls(input.optInt("count", 5).coerceIn(1, 20), input.optBoolean("missed")).let {
            ToolResult(it.text, it.failed) to null
        }
        "reply_message" -> {
            val to = input.optString("to").trim()
            val message = input.optString("text").trim()
            when {
                to.isEmpty() -> ToolResult("no conversation named", true) to null
                message.isEmpty() -> ToolResult("no reply; ask the wearer what to say", true) to null
                else -> reply(to, input.optString("app").trim(), message)
            }
        }
        else -> null
    }

    private fun messages(from: String): ToolResult {
        val phone = messaging() ?: return ToolResult(NO_LISTENER, true)
        val chats = phone.chats().filter { from.isEmpty() || matches(it, from) }
        if (chats.isEmpty()) {
            return ToolResult(if (from.isEmpty()) "No messages in the phone's notifications." else "No messages from $from in the phone's notifications.", false)
        }
        return ToolResult(chats.joinToString("\n") { chat ->
            val c = chat.conversation
            "[${c.app}] ${c.sender}${if (chat.group) " (group)" else ""}${if (chat.canReply) "" else " (no reply from here)"}: " +
                chat.lines.joinToString(" / ") { "${clock(it.time)} ${it.sender.ifEmpty { "the wearer" }}: ${it.text}" }
        }, false)
    }

    private fun reply(to: String, app: String, message: String): Pair<ToolResult, ToolAction?> {
        val phone = messaging() ?: return ToolResult(NO_LISTENER, true) to null
        val found = phone.chats().filter { matches(it, to) && (app.isEmpty() || plain(it.conversation.app).contains(plain(app))) }
        val chat = found.singleOrNull() ?: found.singleOrNull { plain(it.conversation.sender) == plain(to) }
            ?: return if (found.isEmpty()) {
                ToolResult("No conversation with $to in the phone's notifications. For a text message, use send_text.", false) to null
            } else {
                ToolResult("Several conversations match: ${found.joinToString("; ") { "${it.conversation.sender} on ${it.conversation.app}" }}. Ask the wearer which one.", false) to null
            }
        val c = chat.conversation
        if (!chat.canReply) return ToolResult("${c.app} takes no reply from its notification.", true) to null
        val why = phone.reply(c, message)
        val ok = why == null
        return ToolResult(if (ok) "Sent to ${c.sender} on ${c.app}: \"$message\"." else "The reply to ${c.sender} did not go: $why.", !ok) to
            ToolAction("", "Reply · ${c.sender}", ok, kind = "reply_message", subject = c.sender)
    }

    /** A conversation named [what]: by its name, a sender in it, or its app. */
    private fun matches(chat: PhoneChat, what: String): Boolean {
        val w = plain(what)
        return plain(chat.conversation.sender).contains(w) || plain(chat.conversation.app).contains(w) ||
            chat.lines.any { plain(it.sender).contains(w) }
    }

    /** The result, and the diary's line "[what] · whom" for an action that went or failed; none when the wearer has to choose. */
    private fun acted(done: HandsetDone, what: String, kind: String, asked: String): Pair<ToolResult, ToolAction?> {
        if (done.asked) return ToolResult(done.text, false) to null
        val whom = done.subject.ifEmpty { asked }
        return ToolResult(done.text, done.failed) to ToolAction("", "$what · $whom", !done.failed, kind = kind, subject = whom)
    }

    private companion object {
        const val NO_LISTENER = "Visor cannot see the phone's notifications. Tell the wearer to allow Notification access in Visor, " +
            "Settings > Phone access."

        fun plain(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        /** "14:02" today, else "Mon 9 Nov 14:02". */
        fun clock(time: Long): String {
            val at = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            val pattern = if (at.toLocalDate() == LocalDate.now()) "HH:mm" else "EEE d MMM HH:mm"
            return at.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        }

        fun prop(type: String, help: String): JSONObject = JSONObject().put("type", type).put("description", help)

        fun text(help: String, vararg options: String): JSONObject = JSONObject().put("type", "string").put("description", help)
            .apply { if (options.isNotEmpty()) put("enum", JSONArray(options.toList())) }

        fun schema(vararg fields: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))
    }
}
