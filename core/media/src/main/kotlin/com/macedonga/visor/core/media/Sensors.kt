package com.macedonga.visor.core.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Sensor logs: 2 ASCII magic bytes, a uint16 version, then fixed little-endian records; a file that is not whole records is refused.
private const val HEADER = 4
private const val IMU_RECORD = 38
private const val FRAME_RECORD = 18

/** One IMU sample, ~960 Hz. Monotonic nanoseconds; m/s², rad/s, and the IMU die's own temperature in °C. */
class ImuSample(
    val timeNs: Long,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val dieC: Float,
)

/** One camera frame: the camera's own frame counter, the time on the IMU's clock, and the exposure. */
class FrameRecord(val frame: Int, val timeNs: Long, val exposureNs: Long)

/** `SI`, asset kind 3: 38-byte records. */
fun readImu(file: File): List<ImuSample>? {
    val b = records(file, "SI", IMU_RECORD) ?: return null
    return List((b.limit() - HEADER) / IMU_RECORD) { i ->
        val o = HEADER + i * IMU_RECORD
        ImuSample(
            b.getLong(o + 2),
            b.getFloat(o + 10), b.getFloat(o + 14), b.getFloat(o + 18),
            b.getFloat(o + 22), b.getFloat(o + 26), b.getFloat(o + 30),
            b.getFloat(o + 34),
        )
    }
}

/** `SF`, asset kind 4: 18-byte records, one per frame. A still's log holds exactly one. */
fun readFrames(file: File): List<FrameRecord>? {
    val b = records(file, "SF", FRAME_RECORD) ?: return null
    return List((b.limit() - HEADER) / FRAME_RECORD) { i ->
        val o = HEADER + i * FRAME_RECORD
        FrameRecord(b.getShort(o).toInt() and 0xFFFF, b.getLong(o + 2), b.getLong(o + 10))
    }
}

private fun records(file: File, magic: String, size: Int): ByteBuffer? {
    if (!file.isFile) return null
    val data = file.readBytes()
    if (data.size < HEADER || String(data, 0, 2, Charsets.US_ASCII) != magic) return null
    if ((data.size - HEADER) % size != 0) return null
    return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
}
