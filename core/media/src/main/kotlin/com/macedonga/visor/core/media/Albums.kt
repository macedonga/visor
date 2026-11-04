package com.macedonga.visor.core.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The albums imports go in: a folder under Pictures/ for photos and one under Movies/ for videos, or with [together]
 * one folder under DCIM/, named as the photos album; DCIM is the one top folder that takes photos and videos alike.
 * The gallery shows a folder as an album. A change moves everything Visor saved, so the app never loses sight of it.
 */
class Albums(private val context: Context) {
    private val prefs = context.getSharedPreferences("albums", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Moves run one after another in the order asked: each starts from where the last one left the files.
    private val moves = Channel<Move>(Channel.UNLIMITED)

    private val _photos = MutableStateFlow(folder(prefs.getString(PHOTOS, null)))
    private val _videos = MutableStateFlow(folder(prefs.getString(VIDEOS, null)))
    private val _together = MutableStateFlow(prefs.getBoolean(TOGETHER, false))
    private val _moving = MutableStateFlow(0)
    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The album photos go in, and with [together] videos too. */
    val photos: StateFlow<String> = _photos.asStateFlow()

    /** The album videos go in while they have their own. */
    val videos: StateFlow<String> = _videos.asStateFlow()

    /** Photos and videos in one album. */
    val together: StateFlow<Boolean> = _together.asStateFlow()

    /** How many changes are still moving files. */
    val moving: StateFlow<Int> = _moving.asStateFlow()

    /** What the moves did, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    init {
        scope.launch {
            for (move in moves) {
                try {
                    move.from.forEach { move("$it/", "${move.toPhotos}/", "${move.toVideos}/") }
                } finally {
                    _moving.update { it - 1 }
                }
            }
        }
    }

    fun setPhotos(album: String) = change { it.copy(photos = folder(album)) }

    fun setVideos(album: String) = change { it.copy(videos = folder(album)) }

    fun setTogether(on: Boolean) = change { it.copy(together = on) }

    /** Where a photo lands, relative to the gallery's root, as MediaStore wants it. */
    fun photosPath(): String = layout().photosPath

    /** Where a video lands, relative to the gallery's root, as MediaStore wants it. */
    fun videosPath(): String = layout().videosPath

    /** Where either lands: [video] picks the folder. */
    fun path(video: Boolean): String = if (video) videosPath() else photosPath()

    /** Everything in the photos album, as a pattern for [MATCH]. */
    fun photosPattern(): String = pattern("${photosPath()}/")

    /** Everything in the videos album, as a pattern for [MATCH]. */
    fun videosPattern(): String = pattern("${videosPath()}/")

    /**
     * A file saved under [path] that was still being written when the album changed: the move did not see it, so it
     * goes after it now.
     */
    fun follow(uri: Uri, path: String, video: Boolean) {
        val now = path(video)
        if (now == path) return
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, now) }, null, null) }
            .onFailure { note("Could not move a new file from $path to $now: ${it.message}") }
    }

    private data class Layout(val photos: String, val videos: String, val together: Boolean) {
        val photosPath get() = if (together) "$DCIM/$photos" else "$PICTURES/$photos"
        val videosPath get() = if (together) "$DCIM/$photos" else "$MOVIES/$videos"
    }

    /** Every photo and video under [from] goes to the folder of its kind. */
    private class Move(val from: Set<String>, val toPhotos: String, val toVideos: String)

    private fun layout() = Layout(_photos.value, _videos.value, _together.value)

    private fun change(edit: (Layout) -> Layout) {
        synchronized(this) {
            val before = layout()
            val after = edit(before)
            if (after == before) return
            _photos.value = after.photos
            _videos.value = after.videos
            _together.value = after.together
            prefs.edit().putString(PHOTOS, after.photos).putString(VIDEOS, after.videos).putBoolean(TOGETHER, after.together).apply()
            // The videos' own name changes while they share the photos' album: nothing moves.
            if (before.photosPath == after.photosPath && before.videosPath == after.videosPath) return
            _moving.update { it + 1 }
            moves.trySend(Move(setOf(before.photosPath, before.videosPath), after.photosPath, after.videosPath))
        }
    }

    /** Every photo and video Visor saved under [from], into its kind's folder with the same name and any folder below kept. */
    private fun move(from: String, toPhotos: String, toVideos: String) {
        val resolver = context.contentResolver
        var moved = 0
        var failed = 0
        for ((collection, video) in COLLECTIONS) {
            val to = if (video) toVideos else toPhotos
            if (to == from) continue
            val rows = ArrayList<Pair<Long, String>>()
            runCatching {
                resolver.query(collection, COLUMNS, MATCH, arrayOf(pattern(from)), null)?.use { c ->
                    while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
                }
            }.onFailure { note("Could not list $from: ${it.message}") }
            for ((id, path) in rows) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, to + path.substring(from.length)) }
                val ok = runCatching { resolver.update(ContentUris.withAppendedId(collection, id), values, null, null) > 0 }.getOrDefault(false)
                if (ok) moved++ else failed++
            }
        }
        if (moved > 0 || failed > 0) {
            val where = if (toPhotos == toVideos) toPhotos else "$toPhotos and $toVideos"
            note("Moved $moved files from $from to $where" + if (failed > 0) ", $failed did not move" else "")
        }
    }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        /** The album Visor has always written to, and the one an empty setting means. */
        const val DEFAULT = "Visor"

        private const val PHOTOS = "photos"
        private const val VIDEOS = "videos"
        private const val TOGETHER = "together"
        private const val PICTURES = "Pictures"
        private const val MOVIES = "Movies"
        private const val DCIM = "DCIM"
        private const val ESCAPE = '\\'
        private const val MAX_NAME = 80

        private val COLLECTIONS = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to false,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to true,
        )
        private val COLUMNS = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH)

        // MediaStore turns these into '_' in a folder name; doing it first keeps the name the one it stores.
        private val NOT_IN_NAMES = Regex("[\\u0000-\\u001f\\u007f\"*:<>?|]")

        /** The selection for [photosPattern] and [videosPattern]: the escape keeps a wildcard in an album name a letter. */
        val MATCH = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"

        /** A folder the gallery takes: never empty, one level, nothing hidden, no characters MediaStore would change. */
        fun folder(raw: String?): String = raw.orEmpty()
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace(NOT_IN_NAMES, "_")
            .trim()
            .trimStart('.')
            .trim()
            .take(MAX_NAME)
            .trim()
            .ifEmpty { DEFAULT }

        private fun pattern(path: String): String = buildString {
            for (c in path) {
                if (c == '%' || c == '_' || c == ESCAPE) append(ESCAPE)
                append(c)
            }
            append('%')
        }
    }
}
