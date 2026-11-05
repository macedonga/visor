package com.macedonga.visor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Makes a photo again from what it was taken as: the glasses' HEIC, or the frame of its clip that was picked, turned
 * and cut as the edit says, and written over the phone copy, still a Motion Photo when it was one. Nothing is lost:
 * the HEIC and the clip stay, so an edit can always be changed or undone.
 */
class Darkroom(private val context: Context, private val index: GalleryIndex) {
    private companion object {
        const val STRIP_HEIGHT = 128
        const val STRIP_BATCH = 6
    }

    private val scratch = File(context.cacheDir, "darkroom")
    private val stripLock = Mutex()

    /**
     * Runs [read] on the live photo's clip where it sits, the last [Kept.clipBytes] of its Motion Photo, as a file
     * descriptor, an offset and a length. Nothing is copied out.
     */
    private fun <T> readClip(kept: Kept, read: (FileDescriptor, Long, Long) -> T): T? {
        if (kept.clipBytes <= 0) return null
        return runCatching {
            checkNotNull(context.contentResolver.openFileDescriptor(Uri.parse(kept.uri), "r")).use { pfd ->
                read(pfd.fileDescriptor, pfd.statSize - kept.clipBytes, kept.clipBytes)
            }
        }.getOrNull()
    }

    /** The clip's bytes, for a Motion Photo written again. Read before the file is opened for writing. */
    private fun clipBytes(kept: Kept): ByteArray? = readClip(kept) { fd, offset, length ->
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = Os.pread(fd, bytes, read, bytes.size - read, offset + read)
            check(n > 0) { "the clip ended early" }
            read += n
        }
        bytes
    }

    /** The picture an edit starts from, full size: the picked frame of the clip, or the still. */
    suspend fun source(kept: Kept, frameUs: Long?): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (frameUs != null) readClip(kept) { fd, offset, length -> clipFrame(fd, offset, length, frameUs) }
            else {
                val heic = File(index.originals(kept.handle), Assembly.STILL)
                if (heic.isFile) decodeStill(heic)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(kept.uri))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            }
        }.getOrNull()
    }

    /** Every frame's time in the clip, for the editor's strip. */
    suspend fun frameTimes(kept: Kept): List<Long> = withContext(Dispatchers.IO) { readClip(kept) { fd, offset, length -> frameTimesUs(fd, offset, length) } }.orEmpty()

    /** The clip's frames, small, for the editor's strip: decoded a few at a time from the start, in one pass. */
    suspend fun strip(kept: Kept): List<Bitmap> = stripLock.withLock {
        withContext(Dispatchers.IO) {
            readClip(kept) { fd, offset, length -> stripFrames(fd, offset, length) }.orEmpty()
        }
    }

    private fun stripFrames(fd: FileDescriptor, offset: Long, length: Long): List<Bitmap> {
        val count = frameTimesUs(fd, offset, length).size
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fd, offset, length)
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            val out = ArrayList<Bitmap>(count)
            var at = 0
            while (at < count) {
                val batch = retriever.getFramesAtIndex(at, minOf(STRIP_BATCH, count - at), params)
                for (frame in batch) {
                    out += Bitmap.createScaledBitmap(frame, STRIP_HEIGHT * frame.width / frame.height, STRIP_HEIGHT, true)
                    frame.recycle()
                }
                at += batch.size.coerceAtLeast(1)
            }
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

    /** Writes [kept] again with [edit], in place. The photo keeps its name, its time and its clip. */
    suspend fun apply(kept: Kept, edit: Edit): Kept = withContext(Dispatchers.IO) {
        val clip = if (kept.clipBytes > 0) checkNotNull(clipBytes(kept)) { "the live photo's clip could not be read" } else null
        val source = checkNotNull(source(kept, edit.frameUs)) { "the photo's source could not be read" }
        val developed = develop(source, edit)
        val heic = File(index.originals(kept.handle), Assembly.STILL).takeIf { it.isFile }
        val place = if (kept.lat != null && kept.lon != null) Place(kept.takenAt, kept.lat, kept.lon, 0f) else null
        val jpeg = try {
            jpegWithExif(developed, heic, kept.takenAt, place, File(scratch, kept.handle + ".jpg"))
        } finally {
            if (developed !== source) developed.recycle()
            source.recycle()
        }
        val bytes = if (clip != null) motionPhoto(jpeg, clip, edit.frameUs ?: kept.keyFrameUs) else jpeg
        checkNotNull(context.contentResolver.openOutputStream(Uri.parse(kept.uri), "wt")).use { it.write(bytes) }
        val next = kept.copy(edit = edit)
        index.put(next)
        next
    }
}
