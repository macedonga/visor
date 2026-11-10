package com.macedonga.visor.core.assistant

/** A conversation the glasses announced, by the notification it came from. */
data class Conversation(val key: String, val sender: String, val app: String)

/** One message of a [PhoneChat]: [sender] is empty for the wearer's own. */
data class ChatLine(val sender: String, val text: String, val time: Long)

/** A conversation in the phone's notifications, by its name: its last messages, oldest first, and whether its app takes a reply. */
data class PhoneChat(val conversation: Conversation, val group: Boolean, val lines: List<ChatLine>, val canReply: Boolean)

/** The phone's side of replying by voice: the app that posted the message sends the answer. */
interface Messaging {
    /** The conversation the glasses announced last, if it can still be answered. */
    fun lastAnnounced(): Conversation?

    /** Send [text] to [conversation]; null when it went, else why it did not. */
    fun reply(conversation: Conversation, text: String): String?

    /** The conversations in the phone's notifications now, newest first. */
    fun chats(): List<PhoneChat>
}
