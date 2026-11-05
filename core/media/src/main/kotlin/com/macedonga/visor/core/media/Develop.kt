package com.macedonga.visor.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A still upright and at full size, in memory the JPEG encoder can read. The glasses' HEIC holds the sensor's
 * landscape image with Exif orientation 6 and no HEIF rotation. ImageDecoder applies neither, and ExifInterface
 * reports the HEIF rotation over the Exif one, so the Exif block is read here and the image turned by it.
 */
fun decodeStill(file: File): Bitmap {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    val degrees = when (exifOrientation(file)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    // A decoder that already applied the quarter turn returns it portrait.
    if (degrees == 0 || (degrees != 180 && bitmap.height > bitmap.width)) return bitmap
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    if (turned !== bitmap) bitmap.recycle()
    return turned
}

/** A landscape capture's picture: the middle 4:3 band of the portrait frame, the whole width across. */
internal fun landscapeCrop(bitmap: Bitmap): Bitmap {
    if (bitmap.width >= bitmap.height) return bitmap
    val band = bitmap.width * 3 / 4
    return Bitmap.createBitmap(bitmap, 0, (bitmap.height - band) / 2, bitmap.width, band)
}

/** The Orientation tag of the first Exif block in [file], whatever holds it (HEIF item, JPEG APP1), or 1. */
private fun exifOrientation(file: File): Int {
    val data = file.inputStream().use { it.readNBytes(minOf(file.length(), EXIF_SEARCH_BYTES).toInt()) }
    val at = indexOf(data, EXIF_HEADER).takeIf { it >= 0 } ?: return ExifInterface.ORIENTATION_NORMAL
    val tiff = at + EXIF_HEADER.size
    if (tiff + 8 > data.size) return ExifInterface.ORIENTATION_NORMAL
    val little = data[tiff] == 'I'.code.toByte()
    fun u16(o: Int) = if (little) (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    else ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
    fun u32(o: Int) = if (little) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
    val ifd = tiff + u32(tiff + 4)
    if (ifd < tiff || ifd + 2 > data.size) return ExifInterface.ORIENTATION_NORMAL
    for (i in 0 until u16(ifd)) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > data.size) break
        if (u16(entry) == 0x0112) return u16(entry + 8)
    }
    return ExifInterface.ORIENTATION_NORMAL
}

private fun indexOf(data: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..data.size - needle.size) {
        for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
private const val EXIF_SEARCH_BYTES = 256L * 1024

/**
 * The clip's frame that looks most like [still], compared as small grey images with their brightness evened
 * out: where in the clip the photo was taken, as the frame's index and time.
 */
fun matchFrame(clip: File, still: Bitmap): Pair<Int, Long>? {
    val times = frameTimesUs(clip)
    if (times.isEmpty()) return null
    val target = grey(still)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(clip.path)
        // The still is one of the clip's first frames: decode only those, in one pass.
        val count = minOf(times.size, MATCH_FRAMES)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
        val frames = retriever.getFramesAtIndex(0, count, params)
        var best = -1
        var bestError = Double.MAX_VALUE
        for ((i, frame) in frames.withIndex()) {
            val g = grey(frame)
            frame.recycle()
            var e = 0.0
            for (k in g.indices) e += (g[k] - target[k]) * (g[k] - target[k])
            if (e < bestError) {
                bestError = e
                best = i
            }
        }
        if (best < 0) null else best to times[best]
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

/** Every frame's presentation time in the clip, in order. */
fun frameTimesUs(clip: File): List<Long> = frameTimesUs { setDataSource(clip.path) }

/** The same for a clip that sits [length] bytes long at [offset] in [fd], as the MP4 at the end of a Motion Photo. */
fun frameTimesUs(fd: FileDescriptor, offset: Long, length: Long): List<Long> = frameTimesUs { setDataSource(fd, offset, length) }

private fun frameTimesUs(open: MediaExtractor.() -> Unit): List<Long> {
    val extractor = MediaExtractor()
    return try {
        extractor.open()
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return emptyList()
        extractor.selectTrack(track)
        val out = ArrayList<Long>()
        while (true) {
            val t = extractor.sampleTime
            if (t < 0) break
            out += t
            extractor.advance()
        }
        out.sorted()
    } catch (_: Exception) {
        emptyList()
    } finally {
        extractor.release()
    }
}

private const val MATCH_FRAMES = 8
private const val GREY_W = 27
private const val GREY_H = 36

/** [bitmap] as a 27x36 grey image with zero mean and unit spread. */
private fun grey(bitmap: Bitmap): DoubleArray {
    val small = Bitmap.createScaledBitmap(bitmap, GREY_W, GREY_H, true)
    val px = IntArray(GREY_W * GREY_H)
    small.getPixels(px, 0, GREY_W, 0, 0, GREY_W, GREY_H)
    if (small !== bitmap) small.recycle()
    val g = DoubleArray(px.size) { 0.299 * (px[it] shr 16 and 0xFF) + 0.587 * (px[it] shr 8 and 0xFF) + 0.114 * (px[it] and 0xFF) }
    val mean = g.average()
    val spread = sqrt(g.sumOf { (it - mean) * (it - mean) } / g.size).coerceAtLeast(1e-6)
    for (k in g.indices) g[k] = (g[k] - mean) / spread
    return g
}

/** The frame nearest [us], full size, of the clip that sits [length] bytes long at [offset] in [fd]. */
fun clipFrame(fd: FileDescriptor, offset: Long, length: Long, us: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(fd, offset, length)
        retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
            if (f.config == Bitmap.Config.ARGB_8888) f else f.copy(Bitmap.Config.ARGB_8888, false).also { f.recycle() }
        }
    } finally {
        retriever.release()
    }
}

/** How much a w×h image turned by [degrees] has to grow to cover its own frame with no empty corners. */
fun coverScale(w: Float, h: Float, degrees: Float): Float {
    val r = Math.toRadians(abs(degrees).toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return max((w * c + h * s) / w, (w * s + h * c) / h)
}

/** [source] straightened by the edit's angle and cut to its crop, at the source's own resolution. */
fun develop(source: Bitmap, edit: Edit): Bitmap {
    if (edit.angle == 0f && edit.crop == Edit.FULL) return source
    val w = source.width.toFloat()
    val h = source.height.toFloat()
    val (l, t, r, b) = edit.crop
    val out = Bitmap.createBitmap(((r - l) * w).roundToInt().coerceAtLeast(1), ((b - t) * h).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val scale = coverScale(w, h, edit.angle)
    val matrix = Matrix().apply {
        postRotate(edit.angle, w / 2, h / 2)
        postScale(scale, scale, w / 2, h / 2)
        postTranslate(-l * w, -t * h)
    }
    Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}

/**
 * [bitmap] as a JPEG carrying the camera's Exif from [exifFrom] (the glasses' HEIC), upright, with the time it
 * was taken and, when known, where.
 */
fun jpegWithExif(bitmap: Bitmap, exifFrom: File?, takenAt: Long, place: Place?, scratch: File): ByteArray {
    scratch.parentFile?.mkdirs()
    try {
        scratch.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val exif = ExifInterface(scratch.path)
        val source = exifFrom?.let { runCatching { ExifInterface(it.path) }.getOrNull() }
        if (source != null) for (tag in COPIED) source.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
        // The glasses write UTC with no offset, which galleries read as local time or not at all: the capture's own
        // timestamp goes in instead, as local time with its offset.
        val local = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
        val stamp = EXIF_TIME.format(local)
        val offset = local.offset.id.let { if (it == "Z") "+00:00" else it }
        for (tag in listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED)) exif.setAttribute(tag, stamp)
        for (tag in listOf(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_DIGITIZED)) exif.setAttribute(tag, offset)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(takenAt % 1000))
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Visor")
        if (place != null) writeGps(exif, place)
        exif.saveAttributes()
        return scratch.readBytes()
    } finally {
        scratch.delete()
    }
}

private fun writeGps(exif: ExifInterface, place: Place) {
    fun dms(value: Double): String {
        val a = abs(value)
        val d = a.toInt()
        val m = ((a - d) * 60).toInt()
        val s = ((a - d - m / 60.0) * 3600 * 1000).roundToInt()
        return "$d/1,$m/1,$s/1000"
    }
    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(place.lat))
    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (place.lat >= 0) "N" else "S")
    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(place.lon))
    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (place.lon >= 0) "E" else "W")
}

/**
 * A Google Motion Photo (format 1.0): the JPEG with an XMP packet that says a [clipLength]-byte MP4 follows it,
 * then the MP4. [presentationUs] is the clip's instant the photo shows, or -1.
 */
fun motionPhoto(jpeg: ByteArray, clip: ByteArray, presentationUs: Long): ByteArray {
    val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Visor">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
   GCamera:MotionPhoto="1"
   GCamera:MotionPhotoVersion="1"
   GCamera:MotionPhotoPresentationTimestampUs="$presentationUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${clip.size}" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
    val out = ByteArrayOutputStream(jpeg.size + clip.size + 2048)
    out.write(withXmp(jpeg, xmp))
    out.write(clip)
    return out.toByteArray()
}

/** [jpeg] with an XMP APP1 segment after its leading APPn segments (JFIF, Exif). */
private fun withXmp(jpeg: ByteArray, xmp: String): ByteArray {
    require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
    var at = 2
    while (at + 4 <= jpeg.size && jpeg[at] == 0xFF.toByte() && (jpeg[at + 1].toInt() and 0xF0) == 0xE0) {
        at += 2 + (((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF))
    }
    val body = XMP_NAMESPACE + xmp.toByteArray(Charsets.UTF_8)
    val length = body.size + 2
    require(length <= 0xFFFF) { "XMP too long" }
    val out = ByteArrayOutputStream(jpeg.size + body.size + 4)
    out.write(jpeg, 0, at)
    out.write(0xFF)
    out.write(0xE1)
    out.write(length shr 8)
    out.write(length and 0xFF)
    out.write(body)
    out.write(jpeg, at, jpeg.size - at)
    return out.toByteArray()
}

private val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

private const val JPEG_QUALITY = 95

private val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/** The camera's own words about the shot, carried from the HEIC to the JPEG. */
private val COPIED = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
    // android.media.ExifInterface has no constants for these.
    "LensMake", "LensModel",
)
