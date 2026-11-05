package com.macedonga.visor.gallery

import com.macedonga.visor.core.media.Assembly
import com.macedonga.visor.core.media.FrameRecord
import com.macedonga.visor.core.media.ImuSample
import com.macedonga.visor.core.media.readFrames
import com.macedonga.visor.core.media.readImu
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A capture's sensor logs, read; Y is up, roll and pitch come from the accelerometer. */
class SensorReading(
    val samples: Int,
    val rateHz: Float,
    val spanS: Float,
    /** Degrees; positive is the head tilted to its right. */
    val rollDeg: Float,
    /** Degrees; positive is looking down. */
    val pitchDeg: Float,
    /** m/s² at the shutter. */
    val accel: FloatArray,
    /** °/s at the shutter. */
    val gyroDps: FloatArray,
    val dieC: Float,
    /** Angular speed in °/s across the log, evenly spaced, [MOTION_POINTS] long. */
    val motion: FloatArray,
    /** Where the shutter falls in the log, 0 to 1, if known. */
    val shutterAt: Float?,
    /** The clip's frames, as a span of the log, 0 to 1. */
    val clip: ClosedFloatingPointRange<Float>?,
    /** Exposure of each frame (one for a still), in nanoseconds. */
    val exposuresNs: List<Long>,
    /** The fastest the head turned, sample by sample. */
    val peakDps: Float,
) {

    companion object {
        const val MOTION_POINTS = 120

        fun read(originals: File, shutterNs: Long): SensorReading? {
            val imu = readImu(File(originals, Assembly.IMU)) ?: return null
            if (imu.size < 2) return null
            val frames = readFrames(File(originals, Assembly.FRAMES)).orEmpty()
            val t0 = imu.first().timeNs
            val t1 = imu.last().timeNs
            val span = (t1 - t0).coerceAtLeast(1)
            fun at(t: Long) = ((t - t0).toFloat() / span).coerceIn(0f, 1f)

            val shutter = shutterNs.takeIf { it in t0..t1 }
            // Around the shutter, ±40 ms; a video, all of it.
            val around = if (shutter != null) imu.filter { abs(it.timeNs - shutter) <= 40_000_000L } else imu
            val window = around.ifEmpty { imu }
            val ax = window.map { it.ax }.average().toFloat()
            val ay = window.map { it.ay }.average().toFloat()
            val az = window.map { it.az }.average().toFloat()
            val nearest = shutter?.let { s -> imu.minBy { abs(it.timeNs - s) } } ?: imu[imu.size / 2]

            return SensorReading(
                samples = imu.size,
                rateHz = (imu.size - 1) / (span / 1e9f),
                spanS = span / 1e9f,
                rollDeg = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat(),
                pitchDeg = Math.toDegrees(atan2(az.toDouble(), ay.toDouble())).toFloat(),
                accel = floatArrayOf(nearest.ax, nearest.ay, nearest.az),
                gyroDps = floatArrayOf(deg(nearest.gx), deg(nearest.gy), deg(nearest.gz)),
                dieC = nearest.dieC,
                motion = motion(imu, t0, span),
                shutterAt = shutter?.let(::at),
                clip = frames.takeIf { it.size > 1 }?.let { at(it.first().timeNs)..at(it.last().timeNs) },
                exposuresNs = frames.map(FrameRecord::exposureNs).filter { it > 0 },
                peakDps = imu.maxOf { it.speedDps() },
            )
        }

        /** Angular speed, averaged over each slice: the sensor's own tremor stays out of the line. */
        private fun motion(imu: List<ImuSample>, t0: Long, span: Long): FloatArray {
            val sum = FloatArray(MOTION_POINTS)
            val count = IntArray(MOTION_POINTS)
            for (s in imu) {
                val i = (((s.timeNs - t0).toDouble() / span) * (MOTION_POINTS - 1)).toInt().coerceIn(0, MOTION_POINTS - 1)
                sum[i] += s.speedDps()
                count[i]++
            }
            return FloatArray(MOTION_POINTS) { if (count[it] > 0) sum[it] / count[it] else 0f }
        }

        private fun deg(radPerS: Float) = Math.toDegrees(radPerS.toDouble()).toFloat()

        private fun ImuSample.speedDps() = deg(sqrt(gx * gx + gy * gy + gz * gz))
    }
}

/** 1/60 s, or 0.5 s for the long ones, in the unit [seconds]. */
fun exposureWords(ns: Long, seconds: String): String {
    val s = ns / 1e9
    return if (s >= 0.3) "%.1f %s".format(s, seconds) else "1/%d %s".format((1 / s).roundToInt().coerceAtLeast(1), seconds)
}
