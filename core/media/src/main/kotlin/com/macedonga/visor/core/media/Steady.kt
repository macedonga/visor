package com.macedonga.visor.core.media

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per frame, which input pixel each output pixel shows: column-major 3x3 matrices on pixels with y down, one per frame
 * of the video in presentation order.
 */
internal class Warps(
    val inWidth: Int,
    val inHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    private val ptsUs: LongArray,
    private val matrices: List<FloatArray>,
    /** Turned frame by frame, not only cut. */
    val steadied: Boolean,
) {
    private var offset: Long? = null

    /** The warp for the frame shown at [presentationTimeUs], the first one setting where the file's clock starts. */
    fun at(presentationTimeUs: Long): FloatArray {
        if (matrices.size == 1) return matrices[0]
        val base = offset ?: (presentationTimeUs - ptsUs[0]).also { offset = it }
        val t = presentationTimeUs - base
        val found = ptsUs.binarySearch(t)
        val i = if (found >= 0) found else {
            val after = -found - 1
            when {
                after == 0 -> 0
                after >= ptsUs.size -> ptsUs.size - 1
                t - ptsUs[after - 1] <= ptsUs[after] - t -> after - 1
                else -> after
            }
        }
        return matrices[i]
    }
}

/**
 * Stabilization on the phone, for videos the glasses leave shaking (3K, which firmware 7238 does not steady, or any video
 * with two-stage stabilization off), as the Meta app does when it brings one over. The motion log gives the head's
 * rotation at each frame; the path is smoothed and each frame turned by the difference, inside a crop margin.
 *
 * Measured on the Gen 2 against a shaky 1080p walk: the IMU's axes are Android's (x right, y up, z toward the wearer),
 * a frame's moment is its mid-exposure plus 10 ms, and the focal length is 0.78 of the video's width. Within the margin,
 * shake fell by 40 to 60 percent, roll by a third. Rolling shutter is left alone: modelling it, along either axis, made
 * no measurable difference.
 */
internal object Steady {
    private const val FOCAL_SHARE = 0.78125
    private const val LAG_NS = 10_000_000L
    private const val ROUNDS = 8

    /**
     * The warps for a [width] x [height] video whose frames show at [ptsUs]: steadied when [steady] and the logs cover it
     * frame for frame, cut to a 4:3 band across the whole width when [landscape]. Null when neither happens.
     */
    fun plan(
        width: Int,
        height: Int,
        ptsUs: LongArray,
        imu: List<ImuSample>?,
        frames: List<FrameRecord>?,
        strength: Int,
        steady: Boolean,
        landscape: Boolean,
    ): Warps? {
        val path = if (steady && imu != null && imu.size > 1 && frames != null && frames.size == ptsUs.size && frames.size > 2) {
            Path(imu, frames)
        } else {
            null
        }
        if (path == null && !landscape) return null
        val (keep, sigmaSeconds) = if (path != null) feel(strength) else 1.0 to 0.0
        val ow = even(width * keep)
        val oh = if (landscape) even(ow * 3.0 / 4.0) else even(height * keep)
        val f = FOCAL_SHARE * width
        val geometry = Geometry(width, height, ow, oh, f)
        val turns = path?.corrections(sigmaSeconds, geometry) ?: listOf(DoubleArray(3))
        return Warps(width, height, ow, oh, ptsUs, turns.map(geometry::matrix), steadied = path != null)
    }

    /**
     * The share of each side kept, and the seconds of smoothing, for the capture's stabilization: 1 High, 2 Medium,
     * 3 Low, 0 Auto-select. On the walk they were measured against, any of them took out about half the shake; more
     * margin buys steadier sway, not less shake.
     */
    private fun feel(strength: Int) = when (strength) {
        1 -> 0.8 to 0.3
        3 -> 0.9 to 0.12
        else -> 0.85 to 0.2
    }

    private fun even(v: Double) = (v.toInt() / 2) * 2

    /** Pinhole cameras sharing a focal length: the video and the centred crop the output is. */
    private class Geometry(val w: Int, val h: Int, val ow: Int, val oh: Int, val f: Double) {
        /** Output pixel to input pixel, for the camera turned by rotation vector [r]: K_in R K_out⁻¹, column-major. */
        fun matrix(r: DoubleArray): FloatArray {
            val m = rotation(r)
            // K_out⁻¹ maps (x, y) to ((x - ow/2) / f, (y - oh/2) / f, 1); K_in maps (X, Y, Z) to (f X + w/2 Z, f Y + h/2 Z, Z).
            val a = Array(3) { DoubleArray(3) }
            for (row in 0 until 3) {
                a[row][0] = m[row][0] / f
                a[row][1] = m[row][1] / f
                a[row][2] = m[row][2] - m[row][0] * ow / 2 / f - m[row][1] * oh / 2 / f
            }
            val k = arrayOf(doubleArrayOf(f, 0.0, w / 2.0), doubleArrayOf(0.0, f, h / 2.0), doubleArrayOf(0.0, 0.0, 1.0))
            val out = FloatArray(9)
            for (row in 0 until 3) for (col in 0 until 3) {
                out[col * 3 + row] = (k[row][0] * a[0][col] + k[row][1] * a[1][col] + k[row][2] * a[2][col]).toFloat()
            }
            return out
        }

        /** Whether every edge of the output, turned by [r], still falls inside the video. */
        fun covered(r: DoubleArray): Boolean {
            val m = matrix(r)
            for (x in listOf(0.0, ow / 2.0, ow.toDouble())) for (y in listOf(0.0, oh / 2.0, oh.toDouble())) {
                val z = m[2] * x + m[5] * y + m[8]
                if (z <= 0) return false
                val u = (m[0] * x + m[3] * y + m[6]) / z
                val v = (m[1] * x + m[4] * y + m[7]) / z
                if (u < 0 || u > w || v < 0 || v > h) return false
            }
            return true
        }
    }

    /** The head's rotation at each frame, from the motion log, in the camera's axes (x right, y down, z ahead). */
    private class Path(imu: List<ImuSample>, frames: List<FrameRecord>) {
        private val at: List<DoubleArray>
        private val fps: Double

        init {
            val q = ArrayList<DoubleArray>(imu.size)
            q += doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            for (k in 1 until imu.size) {
                val dt = (imu[k].timeNs - imu[k - 1].timeNs) / 1e9
                val a = imu[k - 1]
                val b = imu[k]
                val step = doubleArrayOf((a.gx + b.gx) / 2.0 * dt, -(a.gy + b.gy) / 2.0 * dt, -(a.gz + b.gz) / 2.0 * dt)
                q += normalize(multiply(q[k - 1], quaternionOf(step)))
            }
            val times = LongArray(imu.size) { imu[it].timeNs }
            at = frames.map { frame ->
                val t = frame.timeNs + frame.exposureNs / 2 + LAG_NS
                val i = times.binarySearch(t).let { if (it >= 0) it else (-it - 1) }.coerceIn(1, times.size - 1)
                val share = ((t - times[i - 1]).toDouble() / (times[i] - times[i - 1]).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                normalize(DoubleArray(4) { c -> q[i - 1][c] * (1 - share) + aligned(q[i - 1], q[i])[c] * share })
            }
            val span = (frames.last().timeNs - frames.first().timeNs).coerceAtLeast(1)
            fps = (frames.size - 1) / (span / 1e9)
        }

        /**
         * Per frame, the turn from where the head was to the smoothed path. Where a frame would leave the margin, the path
         * is drawn toward the head there and smoothed again, a few times over: the slow sway is followed, and the fast
         * shake still goes. Scaling each turn down instead kept a fraction of the shake with it.
         */
        fun corrections(sigmaSeconds: Double, geometry: Geometry): List<DoubleArray> {
            val sigma = (sigmaSeconds * fps).coerceAtLeast(1.0)
            var path = smooth(at, sigma)
            repeat(ROUNDS) {
                val fits = DoubleArray(at.size) { i -> fit(geometry, turn(i, path)) }
                if (fits.all { it >= 1.0 }) return List(at.size) { turn(it, path) }
                path = smooth(List(at.size) { i -> normalize(multiply(at[i], quaternionOf(scale(turn(i, path), fits[i])))) }, (sigma / 4).coerceAtLeast(1.0))
            }
            return List(at.size) { i -> turn(i, path).let { scale(it, fit(geometry, it)) } }
        }

        private fun turn(i: Int, path: List<DoubleArray>) = vectorOf(multiply(conjugate(at[i]), path[i]))

        private fun fit(geometry: Geometry, turn: DoubleArray) = largest { s -> geometry.covered(scale(turn, s)) }

        /** [qs] averaged over a Gaussian [sigma] frames wide. */
        private fun smooth(qs: List<DoubleArray>, sigma: Double): List<DoubleArray> {
            val reach = (3 * sigma).toInt()
            val weights = DoubleArray(2 * reach + 1) { exp(-((it - reach) * (it - reach)) / (2 * sigma * sigma)) }
            val n = qs.size
            return List(n) { i ->
                val sum = DoubleArray(4)
                for (k in -reach..reach) {
                    val q = aligned(qs[i], qs[(i + k).coerceIn(0, n - 1)])
                    for (c in 0 until 4) sum[c] += weights[k + reach] * q[c]
                }
                normalize(sum)
            }
        }

        /** The largest share in [0, 1] that [ok] accepts, by halving. */
        private fun largest(ok: (Double) -> Boolean): Double {
            if (ok(1.0)) return 1.0
            var low = 0.0
            var high = 1.0
            repeat(12) {
                val mid = (low + high) / 2
                if (ok(mid)) low = mid else high = mid
            }
            return low
        }
    }

    private fun scale(r: DoubleArray, s: Double) = doubleArrayOf(r[0] * s, r[1] * s, r[2] * s)

    private fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    private fun conjugate(q: DoubleArray) = doubleArrayOf(q[0], -q[1], -q[2], -q[3])

    private fun normalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q.sumOf { it * it })
        return DoubleArray(4) { q[it] / n }
    }

    /** [q], or its negative, whichever is on [reference]'s side: the same rotation, fit to average with it. */
    private fun aligned(reference: DoubleArray, q: DoubleArray): DoubleArray =
        if ((0 until 4).sumOf { reference[it] * q[it] } < 0) DoubleArray(4) { -q[it] } else q

    /** The unit quaternion turning by rotation vector [r]. */
    private fun quaternionOf(r: DoubleArray): DoubleArray {
        val angle = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        if (angle < 1e-12) return doubleArrayOf(1.0, r[0] / 2, r[1] / 2, r[2] / 2)
        val s = sin(angle / 2) / angle
        return doubleArrayOf(cos(angle / 2), r[0] * s, r[1] * s, r[2] * s)
    }

    /** The rotation vector of unit quaternion [q], the shorter way round. */
    private fun vectorOf(q: DoubleArray): DoubleArray {
        val p = if (q[0] < 0) DoubleArray(4) { -q[it] } else q
        val v = sqrt(p[1] * p[1] + p[2] * p[2] + p[3] * p[3])
        if (v < 1e-12) return doubleArrayOf(2 * p[1], 2 * p[2], 2 * p[3])
        val angle = 2 * atan2(v, p[0])
        return doubleArrayOf(p[1] / v * angle, p[2] / v * angle, p[3] / v * angle)
    }

    /** The rotation matrix of rotation vector [r], rows first. */
    private fun rotation(r: DoubleArray): Array<DoubleArray> {
        val q = quaternionOf(r)
        val (w, x, y, z) = q.toList()
        return arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
            doubleArrayOf(2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
            doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)),
        )
    }
}
