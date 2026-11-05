package com.macedonga.visor.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An encoded audio packet, AAC-LC, on the glasses' clock like the video. */
class AudioPacket(val ptsUs: Long, val data: ByteArray)

/** AAC-LC as the recorder and RTMP take it: [asc] is the AudioSpecificConfig. */
class AacFormat(val sampleRate: Int, val channels: Int, val asc: ByteArray)

/** An AAC AudioSpecificConfig read for what a decoder and a container need. Null if it is not one. */
fun aacFormatOf(asc: ByteArray): AacFormat? {
    if (asc.size < 2) return null
    val type = (asc[0].toInt() and 0xFF) shr 3
    if (type !in 1..4) return null
    val index = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() and 0x80) shr 7)
    val rate = AAC_RATES.getOrNull(index) ?: return null
    val channels = (asc[1].toInt() shr 3) and 0x0F
    return AacFormat(rate, channels.coerceAtLeast(1), asc)
}

/** The AAC-LC AudioSpecificConfig for what [config] asks for: 11 90 for 48 kHz stereo. */
fun aacFormatFor(config: AudioConfig): AacFormat? {
    val index = AAC_RATES.indexOf(config.sampleRate).takeIf { it >= 0 } ?: return null
    return aacFormatOf(byteArrayOf(((2 shl 3) or (index shr 1)).toByte(), (((index and 1) shl 7) or (config.channels shl 3)).toByte()))
}

private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

/** The glasses' AAC in the phone's speaker while [listening]: a decoder and a low-latency track. One per audio format. */
class AacPlayer(private val format: AacFormat) {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var track: AudioTrack? = null

    @Volatile
    var listening = false
        set(value) {
            field = value
            if (value) track?.play() else track?.pause()
        }

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(format.asc))
        codec.configure(f, null, null, 0)
        codec.start()
    }

    /** One packet in; whatever PCM is ready comes out. Called from one thread. */
    fun feed(packet: AudioPacket) {
        runCatching {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val b = codec.getInputBuffer(i)!!
                b.clear()
                b.put(packet.data)
                codec.queueInputBuffer(i, 0, packet.data.size, packet.ptsUs, 0)
            }
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                val out = codec.getOutputBuffer(o)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2)
                    out.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                    if (listening) speaker().write(shorts, 0, shorts.size, AudioTrack.WRITE_NON_BLOCKING)
                }
                codec.releaseOutputBuffer(o, false)
            }
        }
    }

    private fun speaker(): AudioTrack = track ?: AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(format.sampleRate)
                .setChannelMask(if (format.channels > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
        .also {
            track = it
            if (listening) it.play()
        }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
