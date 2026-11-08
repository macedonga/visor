@file:OptIn(KitInternal::class)

package com.macedonga.visor.dat

import android.content.Context
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Layout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * The cards apps keep on the Display home's Briefing panel, after Meta's own. They stay until the app removes them, so
 * the Briefing shows them whether or not the app runs. [com.macedonga.visor.connection.Panels] serves them.
 */
@Singleton
class BriefingCards @Inject constructor(@ApplicationContext context: Context) {

    /**
     * One card: [payload] is its Bloks JSON, [icon] its icon in the Briefing's list of cards, [detail] the Bloks tree of
     * the view a click on it opens, if any.
     */
    class Card(val app: String, val id: String, val name: String, val icon: String, val payload: String, val detail: String?) {
        /** The id the Display knows the card by: plain letters, as it goes inside the Briefing's own scripts. */
        val key: String get() = "app_" + md5("$app/$id").take(16)

        val detailHash: String get() = md5(detail.orEmpty())
    }

    /** The card the Display knows as [key]. */
    fun byKey(key: String): Card? = _all.value.firstOrNull { it.key == key }

    private val file = File(context.filesDir, "briefing_cards.json")
    private val _all = MutableStateFlow(read())

    val all: StateFlow<List<Card>> = _all.asStateFlow()

    /** Puts [app]'s card [id] on the Briefing, or replaces it. */
    @Synchronized
    fun set(app: String, id: String, name: String, icon: String, layout: Layout, detail: Layout?) {
        val card = Card(app, id, name, icon, DatLayout.briefing(layout), detail?.let(DatLayout::detail))
        _all.update { cards -> cards.filterNot { it.app == app && it.id == id } + card }
        write()
    }

    /** Takes [app]'s card [id] off the Briefing; true when there was one. */
    @Synchronized
    fun remove(app: String, id: String): Boolean {
        val before = _all.value
        _all.update { cards -> cards.filterNot { it.app == app && it.id == id } }
        write()
        return before.size != _all.value.size
    }

    private fun read(): List<Card> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            Card(it.getString("app"), it.getString("id"), it.getString("name"), it.getString("icon"), it.getString("payload"), it.optString("detail").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val array = JSONArray(_all.value.map { JSONObject().put("app", it.app).put("id", it.id).put("name", it.name).put("icon", it.icon).put("payload", it.payload).put("detail", it.detail.orEmpty()) })
        file.writeText(array.toString())
    }
}

private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
