package com.macedonga.visor.gallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.macedonga.visor.core.media.Albums
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo or video Visor saved, as MediaStore has it. */
data class PhoneItem(
    val uri: Uri,
    val video: Boolean,
    val takenAt: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val favorite: Boolean,
    val name: String,
)

/**
 * Pictures/Visor and Movies/Visor, kept current by watching MediaStore. Visor wrote these files, so it reads and
 * changes them with no media permission. A deletion waits out its undo before it goes to the system's trash.
 */
@Singleton
class PhoneLibrary @Inject constructor(@ApplicationContext private val context: Context, private val albums: Albums) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<PhoneItem>>(emptyList())
    val items: StateFlow<List<PhoneItem>> = _items.asStateFlow()

    /** Deleted, but still within their undo. */
    private val _leaving = MutableStateFlow<Set<Uri>>(emptySet())
    val leaving: StateFlow<Set<Uri>> = _leaving.asStateFlow()

    private var pending: Job? = null

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        for (collection in listOf(IMAGES, VIDEOS)) context.contentResolver.registerContentObserver(collection, true, observer)
        reload()
    }

    fun reload() {
        scope.launch { _items.value = (query(IMAGES, albums.photosPattern(), false) + query(VIDEOS, albums.videosPattern(), true)).sortedByDescending { it.takenAt } }
    }

    /** Takes [uris] out of sight at once; they go to the trash once [undoMs] passes without [undo]. */
    fun delete(uris: Collection<Uri>, undoMs: Long = UNDO_MS, then: (Uri) -> Unit = {}) {
        commitPending()
        _leaving.update { it + uris }
        pending = scope.launch {
            delay(undoMs)
            commit(uris.toSet(), then)
        }
    }

    fun undo() {
        pending?.cancel()
        pending = null
        _leaving.value = emptySet()
    }

    private fun commitPending() {
        val waiting = _leaving.value
        pending?.cancel()
        if (waiting.isNotEmpty()) scope.launch { commit(waiting) {} }
    }

    private fun commit(uris: Set<Uri>, then: (Uri) -> Unit) {
        for (uri in uris) {
            runCatching {
                // The system's trash keeps them for 30 days where it exists; before Android 11 they simply go.
                if (Build.VERSION.SDK_INT >= 30) {
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
                then(uri)
            }
        }
        _leaving.update { it - uris }
        reload()
    }

    /** Deletes outright, no undo: for copies something else has replaced. */
    fun remove(uris: Collection<Uri>) {
        scope.launch {
            for (uri in uris) runCatching { context.contentResolver.delete(uri, null, null) }
            reload()
        }
    }

    fun setFavorite(uris: Collection<Uri>, favorite: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        _items.update { list -> list.map { if (it.uri in uris) it.copy(favorite = favorite) else it } }
        scope.launch {
            for (uri in uris) {
                runCatching { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }, null, null) }
            }
        }
    }

    private fun query(collection: Uri, path: String, video: Boolean): List<PhoneItem> {
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()
        val out = ArrayList<PhoneItem>()
        runCatching {
            context.contentResolver.query(collection, columns, Albums.MATCH, arrayOf(path), null)?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    out += PhoneItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        video = video,
                        takenAt = taken,
                        size = c.getLong(3),
                        width = c.getInt(4),
                        height = c.getInt(5),
                        durationMs = c.getLong(6),
                        favorite = Build.VERSION.SDK_INT >= 30 && c.getInt(8) == 1,
                        name = c.getString(7).orEmpty(),
                    )
                }
            }
        }
        return out
    }

    companion object {
        const val UNDO_MS = 5_000L
        private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
