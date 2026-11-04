package com.macedonga.visor.core.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The albums imports go in: a folder under Pictures/ for photos and one under Movies/ for videos. The gallery shows a
 * folder as an album. A new name moves everything Visor saved under the old one, so the app never loses sight of it.
 */
class Albums(private val context: Context) {
    private val prefs = context.getSharedPreferences("albums", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One move at a time: a second rename moves what the first put in place.
    private val moves = Mutex()

    private val _photos = MutableStateFlow(folder(prefs.getString(PHOTOS, null)))
    private val _videos = MutableStateFlow(folder(prefs.getString(VIDEOS, null)))
    private val _moving = MutableStateFlow(0)
    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The album photos go in. */
    val photos: StateFlow<String> = _photos.asStateFlow()

    /** The album videos go in. */
    val videos: StateFlow<String> = _videos.asStateFlow()

    /** How many renames are still moving files. */
    val moving: StateFlow<Int> = _moving.asStateFlow()

    /** What the moves did, for the event log. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    fun setPhotos(album: String) = rename(_photos, PHOTOS, PICTURES, album)

    fun setVideos(album: String) = rename(_videos, VIDEOS, MOVIES, album)

    /** Where a photo lands, relative to the gallery's root, as MediaStore wants it. */
    fun photosPath(): String = "$PICTURES/${_photos.value}"

    /** Where a video lands, relative to the gallery's root, as MediaStore wants it. */
    fun videosPath(): String = "$MOVIES/${_videos.value}"

    /** Where either lands: [video] picks the folder. */
    fun path(video: Boolean): String = if (video) videosPath() else photosPath()

    /** Everything in the photos album, as a pattern for [MATCH]. */
    fun photosPattern(): String = pattern("${photosPath()}/")

    /** Everything in the videos album, as a pattern for [MATCH]. */
    fun videosPattern(): String = pattern("${videosPath()}/")

    /**
     * A file saved under [path] that was still being written when the album got a new name: the move did not see it,
     * so it goes after it now.
     */
    fun follow(uri: Uri, path: String, video: Boolean) {
        val now = path(video)
        if (now == path) return
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, now) }, null, null) }
            .onFailure { note("Could not move a new file from $path to $now: ${it.message}") }
    }

    private fun rename(state: MutableStateFlow<String>, key: String, root: String, album: String) {
        val to = folder(album)
        val from = state.getAndUpdate { to }
        if (from == to) return
        prefs.edit().putString(key, to).apply()
        _moving.update { it + 1 }
        scope.launch {
            try {
                moves.withLock { move("$root/$from/", "$root/$to/") }
            } finally {
                _moving.update { it - 1 }
            }
        }
    }

    /** Every photo and video Visor saved under [from], into [to] with the same name and any folder below kept. */
    private fun move(from: String, to: String) {
        val resolver = context.contentResolver
        var moved = 0
        var failed = 0
        for (collection in COLLECTIONS) {
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
        if (moved > 0 || failed > 0) note("Moved $moved files from $from to $to" + if (failed > 0) ", $failed did not move" else "")
    }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        /** The album Visor has always written to, and the one an empty setting means. */
        const val DEFAULT = "Visor"

        private const val PHOTOS = "photos"
        private const val VIDEOS = "videos"
        private const val PICTURES = "Pictures"
        private const val MOVIES = "Movies"
        private const val ESCAPE = '\\'
        private const val MAX_NAME = 80

        private val COLLECTIONS = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
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
