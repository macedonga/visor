package com.macedonga.visor.core.media

import android.media.MediaFormat

/** The video codecs a glasses stream may carry: HEVC from the Gen 2, H.264 (AVC) possibly from the Display. */
enum class VideoCodec(val mime: String, val label: String) {
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC"),
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
}

/**
 * The codec of Annex-B bytes, from an HEVC VPS, SPS or PPS (0x40, 0x42, 0x44 then 0x01) or an H.264 SPS
 * (nal_ref_idc set, type 7). HEVC base-layer units never start with a byte that reads as the H.264 SPS, so the
 * two cannot be confused. Null when the bytes show neither, such as slices with no parameter sets.
 */
fun detectVideoCodec(data: ByteArray): VideoCodec? {
    val units = annexBUnits(data)
    if (units.any { it.size >= 2 && (it[0].toInt() and 0xFF) in setOf(0x40, 0x42, 0x44) && it[1].toInt() == 1 }) return VideoCodec.HEVC
    if (units.any { isAvcSpsHeader(it[0].toInt() and 0xFF) }) return VideoCodec.AVC
    return null
}

private fun isAvcSpsHeader(b: Int): Boolean = (b and 0x80) == 0 && (b and 0x1F) == 7 && (b and 0x60) != 0

/** The codec of a config, HEVC unless it shows H.264. */
fun configCodec(config: ByteArray): VideoCodec = detectVideoCodec(config) ?: VideoCodec.HEVC

/** Width and height from the first SPS in [config], whichever codec it is in. */
fun videoSize(config: ByteArray): Pair<Int, Int>? = when (configCodec(config)) {
    VideoCodec.HEVC -> hevcParameterSets(config)[33]?.firstOrNull()?.let(::parseHevcSps)?.let { it.width to it.height }
    VideoCodec.AVC -> annexBUnits(config).firstOrNull { (it[0].toInt() and 0x1F) == 7 }?.let(::parseAvcSps)
}

/** Parameter sets: HEVC VPS, SPS, PPS (32 to 34); H.264 SPS and PPS (7, 8). */
fun VideoCodec.isParameterSet(unit: ByteArray): Boolean = when (this) {
    VideoCodec.HEVC -> hevcNalType(unit) in 32..34
    VideoCodec.AVC -> (unit[0].toInt() and 0x1F) in 7..8
}

/** A keyframe: HEVC IRAP units (16 to 21), H.264 IDR slices (5). */
fun VideoCodec.isKeyFrame(data: ByteArray): Boolean = when (this) {
    VideoCodec.HEVC -> nalTypes(data).any { it in 16..21 }
    VideoCodec.AVC -> annexBUnits(data).any { (it[0].toInt() and 0x1F) == 5 }
}

private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

/** Width and height of an H.264 SPS, cropping applied. Streams with scaling matrices are not read. */
fun parseAvcSps(unit: ByteArray): Pair<Int, Int>? = runCatching {
    val r = BitReader(unescape(unit), 1)
    val profile = r.bits(8)
    r.bits(16) // constraint flags and level
    r.ue() // seq_parameter_set_id
    var chroma = 1
    if (profile in HIGH_PROFILES) {
        chroma = r.ue()
        if (chroma == 3) r.bits(1)
        r.ue()
        r.ue() // bit depths
        r.bits(1) // qpprime_y_zero_transform_bypass
        check(r.bits(1) == 0) { "scaling matrices" }
    }
    r.ue() // log2_max_frame_num_minus4
    when (r.ue()) {
        0 -> r.ue()
        1 -> {
            r.bits(1)
            r.ue()
            r.ue()
            repeat(r.ue()) { r.ue() }
        }
    }
    r.ue() // max_num_ref_frames
    r.bits(1) // gaps_in_frame_num_value_allowed_flag
    val widthMbs = r.ue() + 1
    val mapUnits = r.ue() + 1
    val frameMbsOnly = r.bits(1)
    if (frameMbsOnly == 0) r.bits(1) // mb_adaptive_frame_field_flag
    r.bits(1) // direct_8x8_inference_flag
    var left = 0
    var right = 0
    var top = 0
    var bottom = 0
    if (r.bits(1) == 1) {
        left = r.ue()
        right = r.ue()
        top = r.ue()
        bottom = r.ue()
    }
    val cropX = if (chroma == 1 || chroma == 2) 2 else 1
    val cropY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
    (widthMbs * 16 - cropX * (left + right)) to ((2 - frameMbsOnly) * mapUnits * 16 - cropY * (top + bottom))
}.getOrNull()
