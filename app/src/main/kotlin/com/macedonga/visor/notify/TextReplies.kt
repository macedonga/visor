package com.macedonga.visor.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Messages app's reply actions, by the SMS store's thread each conversation is, while its notification is up. A
 * text sent through one goes the way the app sends it, RCS included, which no API offers other apps.
 */
@Singleton
class TextReplies @Inject constructor() {
    private val byThread = ConcurrentHashMap<String, Pair<String, Notification.Action>>()

    fun put(thread: String, key: String, action: Notification.Action) {
        byThread[thread] = key to action
    }

    /** The notification [key] is gone, and its reply with it. */
    fun remove(key: String) {
        byThread.values.removeIf { it.first == key }
    }

    fun has(thread: String) = byThread.containsKey(thread)

    /** Sends [text] in [thread] through the app's reply; null when it went, else why not. */
    fun reply(context: Context, thread: String, text: String): String? {
        val action = byThread[thread]?.second ?: return "No reply in that conversation"
        val inputs = action.remoteInputs ?: return "The app gives no reply field"
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { action.actionIntent.send(context, 0, fill) }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }
}
