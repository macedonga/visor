package com.macedonga.visor.gallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.macedonga.visor.core.media.Albums
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** Visor's photo and video albums in MediaStore; a delete waits out its undo, then goes to the trash. */
@Singleton
class PhoneLibrary @Inject constructor(@ApplicationContext private val context: Context, private val albums: Albums) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Null until MediaStore is first read. */
    private val _items = MutableStateFlow<List<PhoneItem>?>(null)
    val listed: StateFlow<List<PhoneItem>?> = _items.asStateFlow()
    val items: StateFlow<List<PhoneItem>> = _items.map { it.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Deleted, but still within their undo. */
    private val _leaving = MutableStateFlow<Set<Uri>>(emptySet())
    val leaving: StateFlow<Set<Uri>> = _leaving.asStateFlow()

    private class Batch(val uris: Set<Uri>, val then: (Uri) -> Unit)

    /** The last deletion, while it can be undone. */
    private val pending = MutableStateFlow<Batch?>(null)
    val undoable: StateFlow<Int> = pending.map { it?.uris?.size ?: 0 }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ask in reloads) _items.value = (query(IMAGES, albums.photosPattern(), false) + query(VIDEOS, albums.videosPattern(), true)).sortedByDescending { it.takenAt }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        for (collection in listOf(IMAGES, VIDEOS)) context.contentResolver.registerContentObserver(collection, true, observer)
        reload()
    }

    fun reload() {
        reloads.trySend(Unit)
    }

    /** Takes [uris] out of sight at once; they go to the trash once [UNDO_MS] passes without [undo]. */
    fun delete(uris: Collection<Uri>, then: (Uri) -> Unit = {}) {
        commitPending()
        val batch = Batch(uris.toSet(), then)
        _leaving.update { it + batch.uris }
        pending.value = batch
        scope.launch {
            delay(UNDO_MS)
            if (pending.compareAndSet(batch, null)) commit(batch.uris, batch.then)
        }
    }

    fun undo() {
        val batch = pending.getAndUpdate { null } ?: return
        _leaving.update { it - batch.uris }
    }

    private fun commitPending() {
        val batch = pending.getAndUpdate { null } ?: return
        scope.launch { commit(batch.uris, batch.then) }
    }

    private fun commit(uris: Set<Uri>, then: (Uri) -> Unit) {
        for (uri in uris) {
            runCatching {
                trash(uri)
                then(uri)
            }
        }
        _leaving.update { it - uris }
        reload()
    }

    /** Sends copies something else replaced to the trash. */
    fun remove(uris: Collection<Uri>) {
        scope.launch {
            for (uri in uris) runCatching { trash(uri) }
            reload()
        }
    }

    /** The system's trash keeps it for 30 days. */
    private fun trash(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
    }

    fun setFavorite(uris: Collection<Uri>, favorite: Boolean) {
        _items.update { list -> list?.map { if (it.uri in uris) it.copy(favorite = favorite) else it } }
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
            add(MediaStore.MediaColumns.IS_FAVORITE)
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
                        favorite = c.getInt(8) == 1,
                        name = c.getString(7).orEmpty(),
                    )
                }
            }
        }
        return out
    }

    companion object {
        private const val UNDO_MS = 5_000L
        private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
