package com.macedonga.visor.core.media

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The album imports go in: the folder under Pictures/ for photos and the one under Movies/ for videos, as Settings has
 * it. The gallery shows a folder as an album, so this is the album the user picks in their own gallery app.
 *
 * A slash cannot be part of a gallery album, and a dot at the front would hide the folder, so both go from a name that
 * carries them. A wildcard is kept, and escaped where MediaStore reads it as a pattern: a capture is never lost to the
 * name it was given.
 */
class Albums(context: Context) {
    private val prefs = context.getSharedPreferences("albums", Context.MODE_PRIVATE)

    private val _photos = MutableStateFlow(folder(prefs.getString(PHOTOS, null)))
    private val _videos = MutableStateFlow(folder(prefs.getString(VIDEOS, null)))

    /** The album photos go in. */
    val photos: StateFlow<String> = _photos.asStateFlow()

    /** The album videos go in. */
    val videos: StateFlow<String> = _videos.asStateFlow()

    fun setPhotos(album: String) {
        _photos.value = folder(album)
        prefs.edit().putString(PHOTOS, _photos.value).apply()
    }

    fun setVideos(album: String) {
        _videos.value = folder(album)
        prefs.edit().putString(VIDEOS, _videos.value).apply()
    }

    /** Where a photo lands, relative to the gallery's root, as MediaStore wants it. */
    fun photosPath(): String = "Pictures/" + _photos.value

    /** Where a video lands, relative to the gallery's root, as MediaStore wants it. */
    fun videosPath(): String = "Movies/" + _videos.value

    /** Where either lands: [video] picks the folder. */
    fun path(video: Boolean): String = if (video) videosPath() else photosPath()

    /** [photosPath] as a pattern over MediaStore, for a selection with `ESCAPE '\'`. */
    fun photosPattern(): String = pattern(photosPath())

    /** [videosPath] as a pattern over MediaStore, for a selection with `ESCAPE '\'`. */
    fun videosPattern(): String = pattern(videosPath())

    companion object {
        /** The album Visor has always written to, and the one an empty setting means. */
        const val DEFAULT = "Visor"

        private const val PHOTOS = "photos"
        private const val VIDEOS = "videos"
        private const val ESCAPE = '\\'

        /** The MediaStore selection that matches [photosPattern] or [videosPattern]: `ESCAPE` keeps a wildcard in an album name a letter. */
        val MATCH = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"

        /** A folder the gallery takes: never empty, no slashes, nothing hidden, trimmed. */
        fun folder(raw: String?): String = raw.orEmpty()
            .replace('/', ' ')
            .replace('\\', ' ')
            .trim()
            .trimStart('.')
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
