package com.macedonga.visor.display

import android.content.Context
import com.macedonga.visor.core.connection.CaptionLine
import com.macedonga.visor.core.connection.Captions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One captions session's turns, named by when it began. */
data class SavedCaptions(val session: Long, val lines: List<CaptionLine>)

/** The last sessions' turns, kept in the app's files so a transcript can still be shared after the app restarts. */
@Singleton
class CaptionTranscripts @Inject constructor(@ApplicationContext context: Context, captions: Captions) {
    private val file = File(context.filesDir, "captions_transcripts.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _saved = MutableStateFlow<List<SavedCaptions>>(emptyList())
    val saved: StateFlow<List<SavedCaptions>> = _saved.asStateFlow()

    init {
        scope.launch {
            _saved.value = read()
            captions.state.map { it.session to it.lines }.distinctUntilChanged().conflate().collect { (session, lines) ->
                if (lines.isEmpty()) return@collect
                val kept = (_saved.value.filter { it.session != session } + SavedCaptions(session, lines)).sortedBy { it.session }.takeLast(SESSIONS)
                _saved.value = kept
                write(kept)
                // Turns change word by word: the newest is written once this pause is over.
                delay(WRITE_EVERY_MS)
            }
        }
    }

    private fun read(): List<SavedCaptions> = runCatching {
        val sessions = JSONArray(file.readText())
        (0 until sessions.length()).map { i ->
            val s = sessions.getJSONObject(i)
            val turns = s.getJSONArray("lines")
            SavedCaptions(
                s.getLong("session"),
                (0 until turns.length()).map { j ->
                    val t = turns.getJSONObject(j)
                    CaptionLine(t.getString("id"), t.getString("text"), if (t.has("source")) t.getString("source") else null, true, t.getLong("time"))
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(sessions: List<SavedCaptions>) {
        val all = JSONArray()
        sessions.forEach { s ->
            val turns = JSONArray()
            s.lines.forEach { l ->
                turns.put(JSONObject().put("id", l.id).put("text", l.text).put("time", l.time).apply { l.source?.let { put("source", it) } })
            }
            all.put(JSONObject().put("session", s.session).put("lines", turns))
        }
        runCatching {
            val part = File(file.path + ".part")
            part.writeText(all.toString())
            part.renameTo(file)
        }
    }

    private companion object {
        const val SESSIONS = 10
        const val WRITE_EVERY_MS = 3_000L
    }
}
