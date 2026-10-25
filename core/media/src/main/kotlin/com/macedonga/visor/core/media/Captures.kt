package com.macedonga.visor.core.media

import com.macedonga.visor.core.session.DataXConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

/** svc18, media management: lists the captures and re-queues one onto airship. */
const val SVC_MEDIA = 18

private const val MT_ENUMERATE = 1
const val MT_CAPTURE = 6
const val MT_LIST_DONE = 2
private const val MT_DELETE = 3
const val MT_DELETE_OK = 4
const val MT_DELETE_FAIL = 5
private const val MT_ASSET_REQUEST = 0x83
const val MT_ASSET_GONE = 0x85
private const val MT_LINK_CHANGE = 0x86
const val MT_LINK_CHANGE_OK = 0x87
const val MT_CAPTURE_GONE = 0x82

/** Link type 1 is the RFCOMM link already up. The glasses accept 1, 3 and 7. */
const val LINK_BLUETOOTH = 1

const val KIND_VIDEO = 1
const val KIND_STILL = 2
const val KIND_IMU = 3
const val KIND_FRAMES = 4

// The Display's kinds are the wire CaptureType plus 0xC00 (inferred). Any other kind is classed by its assets.
private const val CAPTURE_VIDEO = 3074L
private const val ASSET_DELIVERED = 0x100000L

data class Asset(val id: String, val typeCode: Long, val size: Long) {
    /** The low byte: 1 video, 2 still, 3 IMU log, 4 frame log. The rest are flags. */
    val kind: Int get() = (typeCode and 0xFF).toInt()

    /** Set once the glasses have handed the asset to a phone. */
    val delivered: Boolean get() = typeCode and ASSET_DELIVERED != 0L
}

/** What a capture is, read from its kind and, where the kind says nothing, from its assets. */
enum class CaptureType { PHOTO, LIVE, VIDEO, OTHER }

/**
 * One entry of the glasses' list. A photo carries a thumbnail, the HEIC still and two sensor logs; a live photo
 * (kind 0) carries a short HEVC clip as well; a video carries the video, one poster still and the logs.
 */
data class Capture(
    val handle: String,
    val kindCode: Long,
    val assets: List<Asset>,
    val timestampNs: Long,
    // Taken with the media orientation at landscape: the glasses keep the portrait frame, the phone crops it.
    val landscape: Boolean = false,
    // The video stabilization it was taken with: 0 Auto-select, 1 High, 2 Medium, 3 Low.
    val steadiness: Int = 0,
) {
    private val stills: List<Asset> get() = assets.filter { it.kind == KIND_STILL }.sortedBy { it.size }

    /** The full-resolution still. A video's only still is its poster, not a photo. */
    val still: Asset? get() = if (kindCode == CAPTURE_VIDEO || stills.size < 2) null else stills.last()

    val type: CaptureType
        get() = when {
            kindCode == CAPTURE_VIDEO -> CaptureType.VIDEO
            still != null -> if (assets.any { it.kind == KIND_VIDEO }) CaptureType.LIVE else CaptureType.PHOTO
            assets.any { it.kind == KIND_VIDEO } -> CaptureType.VIDEO
            else -> CaptureType.OTHER
        }

    val isVideo: Boolean get() = type == CaptureType.VIDEO
    val isMedia: Boolean get() = type != CaptureType.OTHER

    /** The video, or the photo's still. */
    val main: Asset?
        get() = when (type) {
            CaptureType.VIDEO -> assets.filter { it.kind == KIND_VIDEO }.maxByOrNull { it.size }
            CaptureType.PHOTO, CaptureType.LIVE -> still
            CaptureType.OTHER -> null
        }

    /** A live photo's clip. */
    val motion: Asset? get() = if (type == CaptureType.LIVE) assets.filter { it.kind == KIND_VIDEO }.maxByOrNull { it.size } else null

    val imu: Asset? get() = assets.firstOrNull { it.kind == KIND_IMU }
    val frames: Asset? get() = assets.firstOrNull { it.kind == KIND_FRAMES }

    /** Everything an import keeps: the photo or video, a live photo's clip, and the two sensor logs. */
    val wanted: List<Asset> get() = if (isMedia) listOfNotNull(main, motion, imu, frames) else emptyList()

    /** The small JPEG the glasses make of every capture: a photo's thumbnail, a video's poster. */
    val preview: Asset? get() = stills.firstOrNull()

    /** The main asset has not gone to any phone yet. */
    val isNew: Boolean get() = main?.delivered == false

    val takenAtMillis: Long? get() = (timestampNs / 1_000_000).takeIf { it in 1_577_836_800_000..4_102_444_800_000 }
}

/** Ask for the capture list. Each capture comes back on its own as [MT_CAPTURE]. */
suspend fun enumerateCaptures(connection: DataXConnection) = request(connection, MT_ENUMERATE, ByteArray(0))

/** Put a whole capture back on the airship queue for [linkType]: payload `<handle>:<linkType>`. */
suspend fun requestCapture(connection: DataXConnection, handle: String, linkType: Int = LINK_BLUETOOTH) =
    request(connection, MT_LINK_CHANGE, "$handle:$linkType".toByteArray())

/** Queue one asset of a capture: payload `<handle>:<assetId>`. Used for previews, which ride on Bluetooth. */
suspend fun requestAsset(connection: DataXConnection, handle: String, assetId: String) =
    request(connection, MT_ASSET_REQUEST, "$handle:$assetId".toByteArray())

/**
 * Delete a capture from the glasses, as the Meta app does once it has one: payload `<handle>`. They answer
 * [MT_DELETE_OK] with the handle, or [MT_DELETE_FAIL] with `<handle>:<reason>`.
 */
suspend fun deleteCapture(connection: DataXConnection, handle: String) = request(connection, MT_DELETE, handle.toByteArray())

private suspend fun request(connection: DataXConnection, type: Int, payload: ByteArray) =
    connection.sendOnService(SVC_MEDIA, type, payload)

/**
 * One [MT_CAPTURE] flatbuffer. There is no schema, so slots are the ones lensbreak read off live
 * messages, and every string, table and vector is validated before it is believed.
 */
fun parseCapture(payload: ByteArray): Capture? {
    if (payload.size < 4) return null
    val f = Flat(payload)
    val root = f.u32(0)
    if (!f.isTable(root)) return null
    val info = f.field(root.toInt(), 4)?.let(f::target) ?: return null
    if (!f.isTable(info.toLong())) return null
    val handle = f.field(info, 4)?.let(f::string)?.takeIf { it.isNotEmpty() } ?: return null
    val assets = f.field(info, 10)?.let(f::tables).orEmpty().mapNotNull { pos ->
        val id = f.field(pos, 4)?.let(f::string)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Asset(id, f.number(f.field(pos, 6)), f.number(f.field(pos, 12)))
    }
    val meta = f.field(info, 12)?.let(f::target)?.takeIf { f.isTable(it.toLong()) }
    val timestamp = meta?.let { f.number(f.field(it, 4), wide = true) } ?: 0L
    // The capture's aspect ratio, a byte: 0 portrait 3:4, 1 landscape 4:3.
    val landscape = meta?.let { f.byte(f.field(it, 34)) } == 1
    // The capture's video settings, a table: 4 the stabilization, 6 the resolution mode.
    val video = meta?.let { f.field(it, 32) }?.let(f::target)?.takeIf { f.isTable(it.toLong()) }
    val steadiness = video?.let { f.byte(f.field(it, 4)) } ?: 0
    return Capture(handle, f.number(f.field(info, 8)), assets, timestamp, landscape, steadiness)
}

/** A flatbuffer read by its own vtables. Port of capture_list.py's `_Buf`. */
private class Flat(private val b: ByteArray) {
    private val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun fits(o: Long, need: Int = 0) = o >= 0 && o + need <= b.size
    private fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
    fun u32(o: Int) = bb.getInt(o).toLong() and 0xFFFFFFFFL

    /** (vtable offset, vtable size) when [pos] holds a table. */
    private fun vtable(pos: Long): Pair<Int, Int>? {
        if (!fits(pos, 4)) return null
        val vt = pos - bb.getInt(pos.toInt())
        if (!fits(vt, 4)) return null
        val vtSize = u16(vt.toInt())
        val tableSize = u16(vt.toInt() + 2)
        if (vtSize < 4 || vtSize % 2 != 0 || !fits(vt, vtSize)) return null
        if (tableSize < 4 || !fits(pos, tableSize)) return null
        return vt.toInt() to vtSize
    }

    fun isTable(pos: Long) = vtable(pos) != null

    fun field(pos: Int, slot: Int): Int? {
        val (vt, vtSize) = vtable(pos.toLong()) ?: return null
        val index = (slot - 4) / 2
        if (index < 0 || 4 + index * 2 + 2 > vtSize) return null
        val offset = u16(vt + 4 + index * 2)
        if (offset == 0 || !fits(pos.toLong() + offset)) return null
        return pos + offset
    }

    fun target(fp: Int): Int? {
        if (!fits(fp.toLong(), 4)) return null
        val o = fp + u32(fp)
        return if (fits(o, 4)) o.toInt() else null
    }

    fun string(fp: Int): String? {
        val o = target(fp) ?: return null
        val n = u32(o)
        // Flatbuffers always writes a NUL after a string, which rules out a number posing as a length.
        if (n > 8192 || !fits(o + 4L, n.toInt() + 1) || b[o + 4 + n.toInt()] != 0.toByte()) return null
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b, o + 4, n.toInt())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun tables(fp: Int): List<Int> {
        val o = target(fp) ?: return emptyList()
        val n = u32(o)
        if (n == 0L || n > 4096 || !fits(o + 4L, n.toInt() * 4)) return emptyList()
        return (0 until n.toInt()).map { k ->
            val element = o + 4 + k * 4
            val table = element + u32(element)
            if (!isTable(table)) return emptyList()
            table.toInt()
        }
    }

    fun byte(fp: Int?): Int = if (fp != null && fits(fp.toLong(), 1)) b[fp].toInt() and 0xFF else 0

    fun number(fp: Int?, wide: Boolean = false): Long = when {
        fp == null -> 0
        wide && fits(fp.toLong(), 8) -> bb.getLong(fp)
        fits(fp.toLong(), 4) -> u32(fp)
        else -> 0
    }
}
