package com.macedonga.visor.stage

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** What the glasses reflect: a black studio with a softbox overhead and a side strip; half float, as panels outshine white. */
internal object Studio {
    private const val SIZE = 64

    fun reflections(engine: Engine, radiance: FloatArray): Texture {
        val texture = Texture.Builder()
            .width(SIZE).height(SIZE).levels(7)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA16F)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        val buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 6 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until SIZE * SIZE * 6) buffer.put(radiance[i * 3]).put(radiance[i * 3 + 1]).put(radiance[i * 3 + 2]).put(1f)
        buffer.rewind()
        texture.setImage(engine, 0, 0, 0, 0, SIZE, SIZE, 6, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.FLOAT))
        texture.generateMipmaps(engine)
        return texture
    }

    /** Two bands of irradiance in Filament's pre-scaled form: sh[0] + sh[1] y + sh[2] z + sh[3] x, per channel. */
    fun irradiance(radiance: FloatArray): FloatArray {
        var weight = 0.0
        val mean = DoubleArray(3)
        val axis = Array(3) { DoubleArray(3) }
        var i = 0
        forEachTexel { d, w ->
            for (c in 0 until 3) {
                val l = radiance[i * 3 + c] * w
                mean[c] += l
                for (k in 0 until 3) axis[k][c] += l * d[k]
            }
            weight += w
            i++
        }
        val sh = FloatArray(12)
        for (c in 0 until 3) {
            sh[c] = (mean[c] / weight).toFloat()
            sh[3 + c] = (2 * axis[1][c] / weight).toFloat()
            sh[6 + c] = (2 * axis[2][c] / weight).toFloat()
            sh[9 + c] = (2 * axis[0][c] / weight).toFloat()
        }
        return sh
    }

    /** The studio's light in every texel of the cube, RGB. */
    internal fun radiance(): FloatArray {
        val out = FloatArray(SIZE * SIZE * 6 * 3)
        var i = 0
        forEachTexel { d, _ ->
            val v = studioAt(d[0], d[1], d[2])
            // Warm overhead, a touch cooler from the side strip.
            out[i * 3] = v.first
            out[i * 3 + 1] = v.first * 0.985f + v.second * 0.02f
            out[i * 3 + 2] = v.first * 0.95f + v.second * 0.08f
            i++
        }
        return out
    }

    /** (overall, cool part) towards a direction; every panel fades over a wide band, so reflections stay soft. */
    private fun studioAt(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val room = 0.006f + 0.012f * ((y + 1) / 2).pow(2)
        val overhead = smooth(y, 0.62f, 0.98f) * smooth(-abs(x), -0.85f, -0.3f) * 1.7f
        val strip = smooth(x, 0.62f, 0.97f) * smooth(-abs(y - 0.1f), -0.7f, -0.25f) * smooth(-abs(z), -0.8f, -0.15f) * 1.3f
        val back = smooth(-z, 0.5f, 0.97f) * smooth(y, -0.2f, 0.6f) * 0.4f
        // A broad panel behind the camera, so the front of the frame has something to reflect.
        val front = smooth(z, 0.35f, 0.92f) * smooth(-abs(y - 0.3f), -0.75f, -0.15f) * smooth(-abs(x), -0.9f, -0.2f) * 0.45f
        return Pair(room + overhead + strip + back + front, strip + back)
    }

    private fun smooth(v: Float, from: Float, to: Float): Float {
        val t = edge(v, from, to)
        return t * t * (3 - 2 * t)
    }

    private fun edge(v: Float, from: Float, to: Float): Float = ((v - from) / (to - from)).coerceIn(0f, 1f)

    private inline fun forEachTexel(block: (FloatArray, Double) -> Unit) {
        for (face in 0 until 6) for (row in 0 until SIZE) for (col in 0 until SIZE) {
            val u = (col + 0.5f) / SIZE * 2 - 1
            val v = (row + 0.5f) / SIZE * 2 - 1
            block(direction(face, u, v), 1.0 / (1.0 + u * u + v * v).pow(1.5))
        }
    }

    /** OpenGL face order +X, -X, +Y, -Y, +Z, -Z, rows from the top. */
    private fun direction(face: Int, u: Float, v: Float): FloatArray {
        val (x, y, z) = when (face) {
            0 -> Triple(1f, -v, -u)
            1 -> Triple(-1f, -v, u)
            2 -> Triple(u, 1f, v)
            3 -> Triple(u, -1f, -v)
            4 -> Triple(u, -v, 1f)
            else -> Triple(-u, -v, -1f)
        }
        val len = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / len, y / len, z / len)
    }
}
