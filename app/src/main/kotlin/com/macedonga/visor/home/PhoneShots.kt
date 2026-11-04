package com.macedonga.visor.home

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.macedonga.visor.core.media.Albums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video the app has already brought to this phone. */
data class PhoneShot(val uri: Uri, val video: Boolean, val takenAt: Long)

/**
 * The newest shots in Pictures/Visor and Movies/Visor. The app wrote them, so it may read them back
 * without a media permission.
 */
suspend fun recentShots(context: Context, albums: Albums, limit: Int = 80): List<PhoneShot> = withContext(Dispatchers.IO) {
    val photos = query(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), albums.photosPattern(), video = false, limit)
    val videos = query(context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), albums.videosPattern(), video = true, limit)
    (photos + videos).sortedByDescending { it.takenAt }.take(limit)
}

/** Newest-first shots cut into moments wherever more than [GAP_MS] passed between two, the newest [max]. */
fun moments(shots: List<PhoneShot>, max: Int = 5): List<List<PhoneShot>> {
    val out = ArrayList<List<PhoneShot>>()
    var start = 0
    for (i in 1..shots.size) {
        if (i == shots.size || shots[i - 1].takenAt - shots[i].takenAt > GAP_MS) {
            out += shots.subList(start, i)
            if (out.size == max) break
            start = i
        }
    }
    return out
}

private fun query(context: Context, collection: Uri, path: String, video: Boolean, limit: Int): List<PhoneShot> {
    val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED)
    val out = ArrayList<PhoneShot>()
    runCatching {
        context.contentResolver.query(
            collection, columns, Albums.MATCH, arrayOf(path),
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                out += PhoneShot(ContentUris.withAppendedId(collection, c.getLong(0)), video, taken)
            }
        }
    }
    return out
}

private const val GAP_MS = 2 * 60 * 60 * 1000L
