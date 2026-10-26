package com.macedonga.visor.notify

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SMS app's conversations as its notifications show them, for this process only. Google Messages files RCS chats
 * as restricted MMS rows that the phone's texts list cannot read, so the Display's list takes these instead. Each chat
 * keeps its newest messages, and the least recently updated chat goes first.
 */
@Singleton
class RecentChats @Inject constructor() {
    class Message(val text: String, val sender: String, val number: String, val time: Long, val outgoing: Boolean)

    class Chat(val id: String, val title: String, val group: Boolean, val numbers: List<String>, val messages: List<Message>)

    private val chats = LinkedHashMap<String, Chat>()

    /** Adds [incoming] (any order) to chat [id], newest first, without repeats. */
    @Synchronized
    fun remember(id: String, title: String, group: Boolean, numbers: List<String>, incoming: List<Message>) {
        val old = chats.remove(id)
        val messages = ((old?.messages.orEmpty()) + incoming)
            .distinctBy { it.time to it.text }
            .sortedByDescending { it.time }
            .take(MAX_MESSAGES)
        chats[id] = Chat(id, title, group, ((old?.numbers.orEmpty()) + numbers).distinct(), messages)
        while (chats.size > MAX_CHATS) chats.remove(chats.keys.first())
    }

    /** The chats, newest message first. */
    @Synchronized
    fun chats(): List<Chat> = chats.values.sortedByDescending { it.messages.firstOrNull()?.time ?: 0L }

    @Synchronized
    fun find(id: String): Chat? = chats[id]

    /** The chat with a participant in [numbers], if any. */
    @Synchronized
    fun withNumber(numbers: List<String>): Chat? =
        chats.values.firstOrNull { c -> c.numbers.any { n -> numbers.any { sameNumber(n, it) } } }

    companion object {
        private const val MAX_CHATS = 30
        private const val MAX_MESSAGES = 50

        /** Two numbers are one line when their last ten digits match. */
        fun sameNumber(a: String, b: String): Boolean {
            val x = a.filter(Char::isDigit).takeLast(10)
            return x.isNotEmpty() && x == b.filter(Char::isDigit).takeLast(10)
        }
    }
}
