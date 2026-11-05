package com.macedonga.visor.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.Capture
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.core.media.ImportProblem
import com.macedonga.visor.core.media.Kept
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One photo, live photo or video in the timeline, wherever it is: on this phone, on the glasses, or both. */
@Immutable
data class GalleryItem(
    /** The capture's handle when it is known, else the phone copy's uri. */
    val key: String,
    val type: CaptureType,
    val takenAt: Long,
    val phone: PhoneItem?,
    val capture: Capture?,
    val kept: Kept?,
) {
    val onGlassesOnly: Boolean get() = phone == null
    val date: LocalDate get() = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val favorite: Boolean get() = phone?.favorite == true
}

@Immutable
data class Day(val date: LocalDate, val items: List<GalleryItem>)

@HiltViewModel
class GalleryModel @Inject constructor(
    val media: MediaImport,
    val library: PhoneLibrary,
    connection: GlassesConnection,
) : ViewModel() {

    private val merged = combine(
        library.listed.filterNotNull(), library.leaving, media.index.entries, media.index.dismissed, media.captures,
    ) { phone, leaving, kept, dismissed, captures -> merge(phone, leaving, kept, dismissed, captures) }
        .stateIn<Merged?>(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the phone's library is first read. */
    val items: StateFlow<List<GalleryItem>?> = merged.map { it?.items }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Clip-only copies of live photos go.
        viewModelScope.launch { merged.collect { m -> m?.superseded?.takeIf { it.isNotEmpty() }?.let(library::remove) } }
    }

    val previews: StateFlow<Map<String, File>> = media.previews
    val inFlight: StateFlow<Set<String>> = media.inFlight
    val progress: StateFlow<Map<String, Float?>> = media.progress
    val problem: StateFlow<ImportProblem?> = media.problem
    val overBluetooth: StateFlow<Boolean> = media.overBluetooth
    val ready: StateFlow<Boolean> = connection.state.map { it.link == LinkState.READY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, connection.state.value.link == LinkState.READY)

    fun import(items: Collection<GalleryItem>) = media.import(items.filter { it.onGlassesOnly }.mapNotNull { it.capture })

    fun delete(items: Collection<GalleryItem>) {
        val handles = items.mapNotNull { i -> i.phone?.let { it.uri to (i.kept?.handle ?: i.capture?.handle) } }.toMap()
        library.delete(handles.keys) { uri -> handles[uri]?.let(media.index::dismiss) }
    }

    val undoable: StateFlow<Int> = library.undoable

    fun undoDelete() = library.undo()

    fun favorite(items: Collection<GalleryItem>, on: Boolean) = library.setFavorite(items.mapNotNull { it.phone?.uri }, on)

    /** The system's share sheet for what is on the phone among [items]. */
    fun shareIntent(items: Collection<GalleryItem>): Intent? {
        val phone = items.mapNotNull { it.phone }
        if (phone.isEmpty()) return null
        val type = when {
            phone.all { it.video } -> "video/*"
            phone.none { it.video } -> "image/*"
            else -> "*/*"
        }
        val send = if (phone.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, phone[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(phone.map { it.uri }))
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    /** The timeline, and clip-only copies of live photos to drop. */
    class Merged(val items: List<GalleryItem>, val superseded: List<Uri>)

    companion object {
        /** Phone copies matched to captures by index, else by time within [MATCH_MS]; then what is only on the glasses, less what was deleted. */
        fun merge(
            phone: List<PhoneItem>,
            leaving: Set<Uri>,
            kept: Map<String, Kept>,
            dismissed: Set<String>,
            captures: List<Capture>,
        ): Merged {
            val media = captures.filter { it.isMedia }
            val byHandle = media.associateBy { it.handle }
            val timed = media.filter { it.takenAtMillis != null }.sortedBy { it.takenAtMillis }
            val stills = timed.filter { it.type != CaptureType.VIDEO }
            val clips = timed.filter { it.type != CaptureType.PHOTO }
            val keptByUri = kept.values.associateBy { it.uri }
            val shown = HashSet<String>()
            val keys = HashSet<String>()
            val out = ArrayList<GalleryItem>(phone.size + media.size)
            val superseded = ArrayList<Uri>()
            for (p in phone) {
                val k = keptByUri[p.uri.toString()]
                val capture = if (k != null) byHandle[k.handle] else nearest(if (p.video) clips else stills, p.takenAt)
                val handle = k?.handle ?: capture?.handle
                // A live photo's clip saved alone is not a video: it goes.
                if (k == null && p.video && capture?.type == CaptureType.LIVE && p.durationMs in 1L..3_000L) {
                    superseded += p.uri
                    continue
                }
                handle?.let(shown::add)
                if (p.uri in leaving) continue
                val type = when {
                    k != null -> k.type
                    p.video -> CaptureType.VIDEO
                    else -> CaptureType.PHOTO
                }
                val key = handle?.takeIf { keys.add(it) } ?: p.uri.toString().also(keys::add)
                out += GalleryItem(key, type, p.takenAt, p, capture, k)
            }
            for (c in media) {
                if (c.handle in shown || c.handle in dismissed || !keys.add(c.handle)) continue
                out += GalleryItem(c.handle, c.type, c.takenAtMillis ?: 0, null, c, kept[c.handle])
            }
            return Merged(out.sortedByDescending { it.takenAt }, superseded)
        }

        /** The capture taken nearest [at], within [MATCH_MS]. */
        private fun nearest(timed: List<Capture>, at: Long): Capture? {
            var i = timed.binarySearchBy(at) { it.takenAtMillis }
            if (i < 0) i = -i - 1
            return listOfNotNull(timed.getOrNull(i - 1), timed.getOrNull(i))
                .minByOrNull { abs(it.takenAtMillis!! - at) }
                ?.takeIf { abs(it.takenAtMillis!! - at) <= MATCH_MS }
        }

        private const val MATCH_MS = 3_000L

        fun days(items: List<GalleryItem>): List<Day> =
            items.groupBy { it.date }.map { (date, list) -> Day(date, list) }
    }
}
