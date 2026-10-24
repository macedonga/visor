package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone does for the wearer, as the Meta app does for the glasses: directions in the map app, and the music
 * that plays on the phone.
 */
interface Phone {
    /** Directions to [destination], by [mode] (driving, walking, bicycling, transit, or empty), in the map app. */
    suspend fun directions(destination: String, mode: String): PhoneDone

    /** [query] in the music app named [app], or in the one the wearer uses when [app] is empty; an empty query plays anything. */
    suspend fun play(query: String, app: String): PhoneDone

    suspend fun control(control: MediaControl): PhoneDone

    fun nowPlaying(): NowPlaying
}

/**
 * The app an action used, and why it did not work if it did not. [opened]: the app is open on the phone for it;
 * [tapToOpen]: it waits in a notification. [playing]: what plays now, when known.
 */
data class PhoneDone(
    val app: String,
    val failure: String? = null,
    val opened: Boolean = false,
    val tapToOpen: Boolean = false,
    val playing: String = "",
)

/** What plays on the phone, or [unknown] with the reason nothing is known. */
data class NowPlaying(val app: String = "", val title: String = "", val artist: String = "", val playing: Boolean = false, val unknown: String? = null)

enum class MediaControl(val what: String, val done: String) {
    PAUSE("Pause", "the music is paused"),
    RESUME("Resume", "the music plays again"),
    NEXT("Next track", "the next track plays"),
    PREVIOUS("Previous track", "the previous track plays"),
    STOP("Stop", "the music has stopped"),
    RESTART("Start over", "the track starts over"),
}

/** The line in the diary for [control] done in [done]'s app. */
internal fun MediaControl.action(done: PhoneDone) = ToolAction(done.app.ifEmpty { PHONE }, what, done.failure == null)

/** [Phone] as tools for the model. */
internal class PhoneTools(private val phone: Phone) {

    val prompt = " You can also act on the wearer's phone: directions opens the route to a place in the map app, play_media " +
        "plays music, podcasts or radio in a phone app, media_control pauses, resumes or skips what plays, now_playing " +
        "says what plays. Use them whenever the wearer asks for these, without asking first; say it was done only when the " +
        "tool says so."

    /** Name, description and input schema of each tool. */
    val tools: List<Triple<String, String, JSONObject>> = listOf(
        Triple(
            "directions", "Open the route to a place in the phone's map app, ready for the wearer to start.",
            schema(
                "destination" to text("The place as precisely as known: a name with its town, or an address."),
                "mode" to text("Only when the wearer says how they travel.", "driving", "walking", "bicycling", "transit"),
                required = "destination",
            ),
        ),
        Triple(
            "play_media", "Play music, a podcast or radio in a phone app.",
            schema(
                "query" to text("What to play as the wearer asked for it: a song, artist, album, playlist, genre, podcast or station. Empty for anything."),
                "app" to text("The app the wearer named; empty for the one they use."),
                required = "query",
            ),
        ),
        Triple(
            "media_control", "Pause, resume, skip or stop what plays on the phone.",
            schema("action" to text("What to do.", *MediaControl.entries.map { it.name.lowercase() }.toTypedArray()), required = "action"),
        ),
        Triple("now_playing", "What plays on the phone now.", schema()),
    )

    /** Runs [name] when it is one of these tools: what the model is told, and the diary's line for it. Null for another tool. */
    suspend fun run(name: String, input: JSONObject): Pair<ToolResult, ToolAction?>? = when (name) {
        "directions" -> {
            val to = input.optString("destination").trim()
            if (to.isEmpty()) ToolResult("no destination", true) to null
            else phone.directions(to, input.optString("mode").trim()).let { done ->
                told(done) {
                    if (it.tapToOpen) "The route to $to waits in a notification on the phone; tapping it opens ${it.app.ifEmpty { "the map app" }}."
                    else "The route to $to is open in ${it.app.ifEmpty { "the map app" }} on the phone; the wearer starts it there."
                } to ToolAction(done.app.ifEmpty { PHONE }, "Directions · $to", done.failure == null)
            }
        }
        "play_media" -> {
            val query = input.optString("query").trim()
            phone.play(query, input.optString("app").trim()).let { done ->
                told(done) {
                    when {
                        // Leading with the negative: a small model turned "open on the search" into "playing" (issue #37).
                        it.tapToOpen -> "Nothing is playing yet. ${it.app} needs a tap: a notification on the phone opens it on the search."
                        it.opened -> "Nothing is playing yet. ${it.app} is open on the phone on the search; the wearer picks the song there."
                        it.playing.isNotEmpty() -> "Playing in ${it.app}: ${it.playing}."
                        else -> "Playing in ${it.app}."
                    }
                } to ToolAction(done.app.ifEmpty { PHONE }, if (query.isEmpty()) "Play" else "Play · $query", done.failure == null)
            }
        }
        "media_control" -> {
            val control = MediaControl.entries.firstOrNull { it.name.equals(input.optString("action").trim(), ignoreCase = true) }
            if (control == null) ToolResult("unknown action", true) to null
            else phone.control(control).let { done -> told(done) { "Done: ${control.done}." } to control.action(done) }
        }
        "now_playing" -> phone.nowPlaying().let { now ->
            val text = now.unknown ?: (if (now.playing) "Playing" else "Paused") + " in ${now.app}: " +
                now.title.ifEmpty { "an untitled item" } + if (now.artist.isNotEmpty()) " by ${now.artist}" else ""
            ToolResult(text, false) to null
        }
        else -> null
    }

    private fun told(done: PhoneDone, ok: (PhoneDone) -> String) =
        if (done.failure != null) ToolResult("It did not work: ${done.failure}.", true) else ToolResult(ok(done), false)

    private companion object {
        fun text(help: String, vararg options: String): JSONObject = JSONObject().put("type", "string").put("description", help)
            .apply { if (options.isNotEmpty()) put("enum", JSONArray(options.toList())) }

        fun schema(vararg fields: Pair<String, JSONObject>, required: String? = null): JSONObject = JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
            .apply { required?.let { put("required", JSONArray().put(it)) } }
    }
}

/** Who did it, in the diary, when no app is known. */
private const val PHONE = "Phone"
