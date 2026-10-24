package com.macedonga.visor.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Gathers each capture's files as they land, in any order, and once the photo or video and what goes with it are
 * here, makes what the phone's gallery keeps: a Motion Photo for a live photo, a JPEG for a photo, the MP4 for a
 * video. The HEIC and the two sensor logs stay in files/captures for the viewer and the editor. Making them takes
 * seconds, so it runs on a queue of its own and never holds up what is still arriving.
 */
class Assembly(
    private val context: Context,
    private val index: GalleryIndex,
    private val places: PlaceLog,
    private val say: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    /** A capture that could not be saved, and why. Its pieces stay for the next try. */
    private val onFailed: (String, String) -> Unit,
) {
    private val staging = File(context.filesDir, "staging")
    private val scratch = File(context.cacheDir, "develop")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (task in queue) task() }
    }

    /** Keeps [file], one of [capture]'s assets; queues the capture when it was the last piece. */
    @Synchronized
    fun take(capture: Capture, assetId: String, file: File) {
        val name = partName(capture, assetId) ?: return
        val dir = File(staging, capture.handle).apply { mkdirs() }
        file.copyTo(File(dir, name), overwrite = true)
        File(dir, META).writeText(
            JSONObject().put("type", capture.type.name).put("takenAt", capture.takenAtMillis ?: System.currentTimeMillis())
                .put("landscape", capture.landscape).toString(),
        )
        if (capture.wanted.mapNotNull { partName(capture, it.id) }.any { !File(dir, it).exists() }) return
        queue.trySend { safely(dir) }
    }

    /** Whether [handle]'s photo or video is here: it is saved when the rest come, or by [finish] without them. */
    fun waiting(handle: String): Boolean = File(File(staging, handle), MAIN).exists()

    /**
     * Whatever has its photo or video and has had nothing new for [olderThanMs] is saved with what came; a capture
     * still missing that waits.
     */
    fun finish(olderThanMs: Long) {
        queue.trySend {
            val now = System.currentTimeMillis()
            for (dir in staging.listFiles().orEmpty()) {
                if (now - dir.lastModified() < olderThanMs) continue
                when {
                    File(dir, MAIN).exists() -> safely(dir)
                    // A piece that came after its capture was already saved.
                    index.entries.value.containsKey(dir.name) -> dir.deleteRecursively()
                }
            }
        }
    }

    private fun safely(dir: File) {
        if (!dir.isDirectory) return
        runCatching { assemble(dir) }.onFailure {
            say("Could not save ${dir.name.take(8)}: ${it.message}")
            onFailed(dir.name, it.message ?: it.javaClass.simpleName)
        }
    }

    private fun assemble(dir: File) {
        val meta = JSONObject(File(dir, META).readText())
        val handle = dir.name
        val type = CaptureType.valueOf(meta.getString("type"))
        val takenAt = meta.getLong("takenAt")
        val landscape = meta.optBoolean("landscape")
        val place = places.near(takenAt)
        val keep = index.originals(handle).apply { mkdirs() }
        val main = File(dir, MAIN)
        var format = if (type == CaptureType.VIDEO) Format.MP4 else Format.JPEG
        val kept = when (type) {
            CaptureType.VIDEO -> {
                val band = if (landscape) landscapeVideo(main, handle) else null
                val (uri, where) = try {
                    insert(format, takenAt) { out -> (band ?: main).inputStream().use { it.copyTo(out) } }
                } finally {
                    band?.delete()
                }
                say(if (band != null) "Saved a landscape video: $where" else "Saved a video: $where")
                Kept(handle, uri.toString(), type, takenAt, lat = place?.lat, lon = place?.lon)
            }
            else -> {
                main.copyTo(File(keep, STILL), overwrite = true)
                runCatching { developed(dir, handle, type, takenAt, place, landscape) }.getOrElse { error ->
                    // A photo this phone cannot develop still goes to the gallery, as the glasses' own HEIC.
                    say("Could not develop ${handle.take(8)} (${error.message}): saving the HEIC as it came")
                    format = Format.HEIC
                    val (uri, where) = insert(format, takenAt) { out -> main.inputStream().use { it.copyTo(out) } }
                    say("Saved a photo: $where")
                    Kept(handle, uri.toString(), CaptureType.PHOTO, takenAt, lat = place?.lat, lon = place?.lon)
                }
            }
        }
        File(dir, IMU).takeIf { it.exists() }?.copyTo(File(keep, IMU), overwrite = true)
        File(dir, FRAMES).takeIf { it.exists() }?.copyTo(File(keep, FRAMES), overwrite = true)
        val old = index.entries.value[handle]?.uri?.let(Uri::parse)
        index.put(kept)
        if (old != null && old.toString() != kept.uri) {
            // The new copy took a "(1)" name beside the old one; with the old gone it takes the plain name.
            runCatching { context.contentResolver.delete(old, null, null) }
            val name = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(format, takenAt)) }
            runCatching { context.contentResolver.update(Uri.parse(kept.uri), name, null, null) }
        }
        dir.deleteRecursively()
        onSaved(handle)
    }

    /** A photo or live photo as the gallery keeps it: a JPEG, with the clip after it for a live photo. */
    private fun developed(dir: File, handle: String, type: CaptureType, takenAt: Long, place: Place?, landscape: Boolean): Kept {
        val main = File(dir, MAIN)
        val whole = decodeStill(main)
        val motion = File(dir, MOTION).takeIf { type == CaptureType.LIVE && it.exists() }
        val frames = readFrames(File(dir, FRAMES)).orEmpty()
        // The clip is matched against the whole frame, which it shows.
        val key = motion?.let { matchFrame(it, whole) }
        val bitmap = if (landscape) Landscape.crop(whole).also { if (it !== whole) whole.recycle() } else whole
        val jpeg = try {
            jpegWithExif(bitmap, main, takenAt, place, File(scratch, "$handle.jpg"))
        } finally {
            bitmap.recycle()
        }
        val keyUs = key?.second ?: -1
        // The frame log has one record per clip frame, or a still's one record.
        val shutterNs = when {
            key != null -> frames.getOrNull(key.first)?.timeNs ?: 0
            frames.size == 1 -> frames[0].timeNs
            else -> 0
        }
        val bytes = if (motion != null) motionPhoto(jpeg, motion.readBytes(), keyUs) else jpeg
        val (uri, where) = insert(Format.JPEG, takenAt) { it.write(bytes) }
        val what = if (motion != null) "live photo" else "photo"
        say(if (landscape) "Saved a landscape $what: $where" else "Saved a $what: $where")
        return Kept(
            handle, uri.toString(), if (motion != null) CaptureType.LIVE else CaptureType.PHOTO, takenAt,
            clipBytes = motion?.length() ?: 0, keyFrameUs = keyUs, shutterNs = shutterNs, lat = place?.lat, lon = place?.lon,
        )
    }

    /** A landscape video's 4:3 band, or null to keep it whole when this phone cannot make one. */
    private fun landscapeVideo(main: File, handle: String): File? {
        val band = File(scratch, "$handle.mp4").apply { parentFile?.mkdirs() }
        return runCatching { Landscape.crop(context, main, band) }.map { band }.getOrElse {
            say("Could not crop ${handle.take(8)} to landscape (${it.message}): saving it whole")
            band.delete()
            null
        }
    }

    private enum class Format(val ext: String, val mime: String, val video: Boolean) {
        JPEG("jpg", "image/jpeg", false),
        HEIC("heic", "image/heic", false),
        MP4("mp4", "video/mp4", true),
    }

    /** The new row, and where it is as MediaStore has it. */
    private fun insert(format: Format, takenAt: Long, write: (OutputStream) -> Unit): Pair<Uri, String> {
        val video = format.video
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(format, takenAt))
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Visor" else "Pictures/Visor")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("the gallery would not take it")
        try {
            checkNotNull(resolver.openOutputStream(uri)).use(write)
            return uri to publish(resolver, uri)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    companion object {
        const val STILL = "still.heic"
        const val IMU = "imu"
        const val FRAMES = "frames"
        private const val MAIN = "main"
        private const val MOTION = "motion"
        private const val META = "meta.json"

        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

        private fun fileName(format: Format, takenAt: Long) = "Visor_" + NAME.format(Instant.ofEpochMilli(takenAt)) + "." + format.ext

        private fun partName(capture: Capture, assetId: String): String? = when (assetId) {
            capture.main?.id -> MAIN
            capture.motion?.id -> MOTION
            capture.imu?.id -> IMU
            capture.frames?.id -> FRAMES
            else -> null
        }
    }
}
