package com.macedonga.visor.core.media

import android.content.Context
import com.macedonga.visor.core.connection.strings
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** How a photo was changed in Visor: which instant of a live photo, the straightening angle, the crop. */
data class Edit(
    /** The clip's frame used as the photo, or null for the still itself. */
    val frameUs: Long? = null,
    /** Degrees, clockwise. */
    val angle: Float = 0f,
    /** Left, top, right, bottom as fractions of the straightened image. */
    val crop: List<Float> = FULL,
) {
    val isOriginal: Boolean get() = frameUs == null && angle == 0f && crop == FULL

    companion object {
        val FULL = listOf(0f, 0f, 1f, 1f)
    }
}

/**
 * A capture brought to this phone: where it went in MediaStore and what MediaStore cannot hold. A live photo's
 * clip sits at the end of its JPEG, [clipBytes] long.
 */
data class Kept(
    val handle: String,
    val uri: String,
    val type: CaptureType,
    val takenAt: Long,
    val clipBytes: Long = 0,
    val keyFrameUs: Long = -1,
    /** The photo's instant on the sensor logs' clock, or 0. */
    val shutterNs: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val edit: Edit = Edit(),
)

/** Every [Kept], in files/gallery.json, and each one's originals (HEIC, sensor logs) under files/captures. */
class GalleryIndex(context: Context) {
    private val file = File(context.filesDir, "gallery.json")
    private val root = File(context.filesDir, "captures")

    private val _entries = MutableStateFlow(read())

    private val dismissedFile = File(context.filesDir, "dismissed.json")
    private val _dismissed = MutableStateFlow(readDismissed())

    /** Captures the person deleted from the phone: their copy on the glasses is not offered again. */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    @Synchronized
    fun dismiss(handle: String) {
        _dismissed.update { it + handle }
        dismissedFile.replaceWith(JSONArray(_dismissed.value.toList()).toString())
    }

    private fun readDismissed(): Set<String> =
        runCatching { JSONArray(dismissedFile.readText()).strings().toSet() }.getOrDefault(emptySet())

    /** By capture handle. */
    val entries: StateFlow<Map<String, Kept>> = _entries.asStateFlow()

    fun originals(handle: String): File = File(root, handle)

    fun put(kept: Kept) = write { it + (kept.handle to kept) }

    @Synchronized
    private fun write(change: (Map<String, Kept>) -> Map<String, Kept>) {
        _entries.update(change)
        val array = JSONArray()
        for (k in _entries.value.values) array.put(k.toJson())
        file.replaceWith(array.toString())
    }

    private fun read(): Map<String, Kept> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toKept() }.getOrNull() }.associateBy { it.handle }
    }.getOrDefault(emptyMap())
}

/** Writes [text] beside this file, then renames it over, so a crash never leaves half a file. */
private fun File.replaceWith(text: String) {
    val tmp = File("$path.tmp")
    tmp.writeText(text)
    tmp.renameTo(this)
}

private fun Kept.toJson() = JSONObject().apply {
    put("handle", handle)
    put("uri", uri)
    put("type", type.name)
    put("takenAt", takenAt)
    put("clipBytes", clipBytes)
    put("keyFrameUs", keyFrameUs)
    put("shutterNs", shutterNs)
    lat?.let { put("lat", it) }
    lon?.let { put("lon", it) }
    if (!edit.isOriginal) {
        put("edit", JSONObject().apply {
            edit.frameUs?.let { put("frameUs", it) }
            put("angle", edit.angle.toDouble())
            put("crop", JSONArray(edit.crop.map { it.toDouble() }))
        })
    }
}

private fun JSONObject.toKept() = Kept(
    handle = getString("handle"),
    uri = getString("uri"),
    type = CaptureType.valueOf(getString("type")),
    takenAt = getLong("takenAt"),
    clipBytes = optLong("clipBytes"),
    keyFrameUs = optLong("keyFrameUs", -1),
    shutterNs = optLong("shutterNs"),
    lat = if (has("lat")) getDouble("lat") else null,
    lon = if (has("lon")) getDouble("lon") else null,
    edit = optJSONObject("edit")?.let { e ->
        Edit(
            frameUs = if (e.has("frameUs")) e.getLong("frameUs") else null,
            angle = e.optDouble("angle").toFloat(),
            crop = e.getJSONArray("crop").let { a -> List(4) { a.getDouble(it).toFloat() } },
        )
    } ?: Edit(),
)
