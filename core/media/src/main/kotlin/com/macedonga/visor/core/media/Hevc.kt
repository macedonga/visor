package com.macedonga.visor.core.media

import java.io.ByteArrayOutputStream

/** The NAL units of an Annex-B buffer, without their start codes. */
fun annexBUnits(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var i = 0
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            // A four-byte start code leaves its leading zero on the unit before.
            if (starts.isNotEmpty()) ends += if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
            starts += i + 3
            i += 3
        } else {
            i++
        }
    }
    if (starts.isEmpty()) return emptyList()
    ends += data.size
    return starts.indices.map { data.copyOfRange(starts[it], ends[it]) }.filter { it.size >= 2 }
}

fun hevcNalType(unit: ByteArray): Int = (unit[0].toInt() shr 1) and 0x3F

/** Annex-B to four-byte length prefixes, as MP4 and RTMP carry HEVC. Parameter sets are left out with [dropParameterSets]. */
fun annexBToLengthPrefixed(data: ByteArray, dropParameterSets: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream(data.size + 16)
    for (unit in annexBUnits(data)) {
        if (dropParameterSets && hevcNalType(unit) in 32..34) continue
        out.write(unit.size ushr 24)
        out.write(unit.size ushr 16)
        out.write(unit.size ushr 8)
        out.write(unit.size)
        out.write(unit)
    }
    return out.toByteArray()
}

/** What an SPS says about the picture and the profile, enough for an hvcC record and a container's track. */
class HevcSps(
    val width: Int,
    val height: Int,
    /** The 12 bytes of general_profile_tier_level: profile space, tier, profile, compatibility flags, constraints, level. */
    val profileTierLevel: ByteArray,
    val chromaFormat: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
)

fun parseHevcSps(unit: ByteArray): HevcSps? = runCatching {
    val r = BitReader(unescape(unit), 2)
    r.bits(4) // sps_video_parameter_set_id
    val subLayersMinus1 = r.bits(3)
    r.bits(1) // temporal_id_nesting
    val ptl = ByteArray(12) { r.bits(8).toByte() }
    // Sub-layer flags and their profile/level data, skipped.
    val present = BooleanArray(subLayersMinus1) { false }
    val levelPresent = BooleanArray(subLayersMinus1) { false }
    for (i in 0 until subLayersMinus1) {
        present[i] = r.bits(1) == 1
        levelPresent[i] = r.bits(1) == 1
    }
    if (subLayersMinus1 > 0) for (i in subLayersMinus1 until 8) r.bits(2)
    for (i in 0 until subLayersMinus1) {
        if (present[i]) r.skip(88)
        if (levelPresent[i]) r.skip(8)
    }
    r.ue() // sps_seq_parameter_set_id
    val chroma = r.ue()
    if (chroma == 3) r.bits(1)
    var width = r.ue()
    var height = r.ue()
    if (r.bits(1) == 1) {
        // Conformance window, in chroma units.
        val subW = if (chroma == 1 || chroma == 2) 2 else 1
        val subH = if (chroma == 1) 2 else 1
        val left = r.ue()
        val right = r.ue()
        val top = r.ue()
        val bottom = r.ue()
        width -= subW * (left + right)
        height -= subH * (top + bottom)
    }
    val depthLuma = r.ue() + 8
    val depthChroma = r.ue() + 8
    HevcSps(width, height, ptl, chroma, depthLuma, depthChroma)
}.getOrNull()

/** The parameter sets in [config] (Annex-B), by type: 32 VPS, 33 SPS, 34 PPS. */
fun hevcParameterSets(config: ByteArray): Map<Int, List<ByteArray>> =
    annexBUnits(config).filter { hevcNalType(it) in 32..34 }.groupBy(::hevcNalType)

/** An HEVCDecoderConfigurationRecord (ISO/IEC 14496-15), as an MP4 hvcC box and enhanced RTMP carry it. */
fun hvcC(config: ByteArray): ByteArray? {
    val sets = hevcParameterSets(config)
    val sps = sets[33]?.firstOrNull()?.let(::parseHevcSps) ?: return null
    val out = ByteArrayOutputStream()
    out.write(1)
    out.write(sps.profileTierLevel)
    out.write(0xF0) // reserved, min_spatial_segmentation_idc 0
    out.write(0x00)
    out.write(0xFC) // reserved, parallelismType 0
    out.write(0xFC or sps.chromaFormat)
    out.write(0xF8 or (sps.bitDepthLuma - 8))
    out.write(0xF8 or (sps.bitDepthChroma - 8))
    out.write(0); out.write(0) // avgFrameRate unknown
    out.write(0x0F) // constantFrameRate 0, numTemporalLayers 1, temporalIdNested 1, lengthSizeMinusOne 3
    val types = listOf(32, 33, 34).filter { !sets[it].isNullOrEmpty() }
    out.write(types.size)
    for (type in types) {
        val units = sets.getValue(type)
        out.write(0x80 or type) // array_completeness 1
        out.write(units.size ushr 8); out.write(units.size)
        for (u in units) {
            out.write(u.size ushr 8); out.write(u.size)
            out.write(u)
        }
    }
    return out.toByteArray()
}

/** Drops the emulation-prevention bytes (00 00 03) from a NAL unit's payload. */
internal fun unescape(unit: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(unit.size)
    var zeros = 0
    for (b in unit) {
        if (zeros >= 2 && b == 3.toByte()) {
            zeros = 0
            continue
        }
        out.write(b.toInt())
        zeros = if (b == 0.toByte()) zeros + 1 else 0
    }
    return out.toByteArray()
}

internal class BitReader(private val data: ByteArray, startByte: Int) {
    private var pos = startByte * 8

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[pos ushr 3].toInt()
            v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
            pos++
        }
        return v
    }

    fun skip(n: Int) {
        pos += n
    }

    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) zeros++
        return (1 shl zeros) - 1 + bits(zeros)
    }
}
