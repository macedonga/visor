package com.macedonga.visor.core.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One Opus packet and how long it plays. */
class OpusPacket(val data: ByteArray, val durationUs: Long)

/**
 * Text to what the glasses play: the phone's own TextToSpeech, resampled to 48 kHz mono, encoded
 * to raw Opus packets by the platform encoder.
 */
class Voice(private val context: Context) {

    private val lock = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun checkLanguage(locale: Locale) = lock.withLock {
        selectLanguage(ready(), locale)
    }

    suspend fun speak(text: String, locale: Locale): List<OpusPacket> = lock.withLock {
        val wav = File(context.cacheDir, "assistant-tts.wav")
        synthesize(text, locale, wav)
        withContext(Dispatchers.Default) {
            val pcm = parseWav(wav.readBytes())
            encodeOpus(resample(pcm.samples, pcm.rate, 48_000))
        }
    }

    /** Audio synthesised elsewhere, such as by a cloud voice, as the same packets. */
    suspend fun encode(pcm: SpeechPcm): List<OpusPacket> =
        withContext(Dispatchers.Default) { encodeOpus(resample(pcm.samples, pcm.rate, 48_000)) }

    private suspend fun synthesize(text: String, locale: Locale, out: File) {
        val engine = ready()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        out.delete()
        val ok = try {
            check(engine.synthesizeToFile(text, Bundle(), out, id) == TextToSpeech.SUCCESS) { "the phone's text to speech refused the sentence" }
            withTimeout(15_000) { done.await() }
        } finally {
            pending.remove(id)
        }
        check(ok && out.length() > 44) { "the phone's text to speech failed" }
    }

    private fun selectLanguage(engine: TextToSpeech, locale: Locale) {
        val required = if (locale.language == "zh") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        if (engine.isLanguageAvailable(locale) < required || engine.setLanguage(locale) < required) {
            throw PhoneSpeechException(PhoneSpeech.NO_VOICE, "the phone has no text-to-speech voice for ${locale.toLanguageTag()}")
        }
    }

    private suspend fun ready(): TextToSpeech {
        tts?.let { return it }
        val initialized = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { initialized.complete(it) }
        try {
            val status = withTimeoutOrNull(10_000) { initialized.await() }
                ?: error("the phone's text to speech did not start")
            check(status == TextToSpeech.SUCCESS) { "the phone has no working text to speech" }
        } catch (error: Exception) {
            engine.shutdown()
            throw error
        }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    pending[utteranceId]?.complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    pending[utteranceId]?.complete(false)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    pending[utteranceId]?.complete(false)
                }
            },
        )
        tts = engine
        return engine
    }

    fun close() {
        tts?.shutdown()
        tts = null
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return input
        val n = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val frac = pos - j
            val a = input[minOf(j, input.size - 1)]
            val c = input[minOf(j + 1, input.size - 1)]
            out[i] = (a + (c - a) * frac).toInt().toShort()
        }
        return out
    }

    /** The platform Opus encoder, one packet per output buffer, codec config left out. */
    private fun encodeOpus(pcm: ShortArray): List<OpusPacket> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val packets = ArrayList<OpusPacket>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            val raw = bytes.array()
            var fed = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        val take = minOf(buffer.capacity(), raw.size - fed) and 1.inv()
                        val pts = fed / 2 * 1_000_000L / 48_000
                        if (take <= 0) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(raw, fed, take)
                            codec.queueInputBuffer(index, 0, take, pts, 0)
                            fed += take
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(out))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        packets += OpusPacket(data, opusDurationUs(data))
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return packets
    }
}

/** How long an Opus packet plays, from its TOC byte (RFC 6716, 3.1). */
internal fun opusDurationUs(packet: ByteArray): Long {
    if (packet.isEmpty()) return 0
    val toc = packet[0].toInt() and 0xFF
    val config = toc shr 3
    val frameUs = when {
        config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
        config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
        else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
    }
    val frames = when (toc and 3) {
        0 -> 1
        1, 2 -> 2
        else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
    }
    return frameUs * frames
}

/** 16-bit mono PCM and its sample rate. */
class SpeechPcm(val samples: ShortArray, val rate: Int)

/** 16-bit PCM WAV, mixed down to mono. Some engines and services leave the data size unset, so it runs to the end. */
internal fun parseWav(bytes: ByteArray): SpeechPcm {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var at = 12
    var rate = 0
    var channels = 1
    var bits = 16
    while (at + 8 <= bytes.size) {
        val id = String(bytes, at, 4)
        val size = b.getInt(at + 4)
        val body = at + 8
        if (id == "fmt " && body + 16 <= bytes.size) {
            channels = b.getShort(body + 2).toInt().coerceAtLeast(1)
            rate = b.getInt(body + 4)
            bits = b.getShort(body + 14).toInt()
        } else if (id == "data") {
            check(bits == 16 && rate > 0) { "the speech is $bits-bit at $rate Hz" }
            val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
            val frames = (end - body) / (2 * channels)
            val out = ShortArray(frames)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += b.getShort(body + (i * channels + c) * 2)
                out[i] = (sum / channels).toShort()
            }
            return SpeechPcm(out, rate)
        }
        if (size < 0) break
        at = body + size + (size and 1)
    }
    error("the WAV has no data")
}

/** [pcm] on this phone's own speaker, until it has played. */
suspend fun playAloud(pcm: SpeechPcm) {
    check(pcm.samples.isNotEmpty()) { "the speech is empty" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.samples.size * 2)
        .build()
    try {
        track.write(pcm.samples, 0, pcm.samples.size)
        track.play()
        delay(pcm.samples.size * 1000L / pcm.rate + 300)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}

internal fun pcmToBytes(samples: ShortArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
    return out
}

internal fun bytesToPcm(bytes: ByteArray, length: Int = bytes.size): ShortArray {
    val out = ShortArray(length / 2)
    ByteBuffer.wrap(bytes, 0, out.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    return out
}

/**
 * Stateful linear resampler for streaming 16-bit mono PCM across chunk boundaries without clicks or drift.
 */
internal class PcmResampler(private val from: Int, private val to: Int) {
    private val fromL = from.toLong()
    private val toL = to.toLong()
    private var hasPrev = false
    private var prev: Short = 0
    // Phase in 1/to units of an input sample; always >= -toL after a non-empty chunk.
    private var num = 0L

    fun push(input: ShortArray): ShortArray {
        if (input.isEmpty() || from == to) return input
        if (!hasPrev) {
            prev = input[0]
            hasPrev = true
            num = 0L
        }
        val limit = (input.size - 1).toLong() * toL
        val count = if (num < limit) ((limit - num + fromL - 1) / fromL).toInt() else 0
        val out = ShortArray(count)
        var n = 0
        while (num < limit) {
            val j = Math.floorDiv(num, toL).toInt()
            val rem = Math.floorMod(num, toL)
            val a = if (j < 0) prev.toLong() else input[j].toLong()
            val b = input[j + 1].toLong()
            out[n++] = (a + (b - a) * rem / toL).toInt().toShort()
            num += fromL
        }
        prev = input[input.size - 1]
        num -= input.size.toLong() * toL
        return out
    }

    fun flush(): ShortArray {
        if (!hasPrev || from == to) return ShortArray(0)
        val out = if (num <= 0L) shortArrayOf(prev) else ShortArray(0)
        hasPrev = false
        num = 0L
        return out
    }
}

/**
 * Incremental Opus encoder for streaming model audio onto s0 as 20 ms 48 kHz mono packets while chunks still arrive.
 */
internal class StreamingOpusEncoder(fromRate: Int) {
    private val resampler = PcmResampler(fromRate, RATE)
    private val info = MediaCodec.BufferInfo()
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private var pending = ByteArray(0)
    private var fedBytes = 0L
    private var closed = false

    @Synchronized
    fun encode(samples: ShortArray): List<OpusPacket> {
        if (closed || samples.isEmpty()) return emptyList()
        val resampled = resampler.push(samples)
        if (resampled.isEmpty()) return emptyList()
        pending += pcmToBytes(resampled)
        val packets = ArrayList<OpusPacket>()
        var offset = 0
        while (pending.size - offset >= FRAME_BYTES) {
            feed(pending, offset, FRAME_BYTES, eos = false, packets)
            offset += FRAME_BYTES
        }
        if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        drain(packets, 0)
        return packets
    }

    @Synchronized
    fun finish(): List<OpusPacket> {
        if (closed) return emptyList()
        val tail = resampler.flush()
        if (tail.isNotEmpty()) pending += pcmToBytes(tail)
        val packets = ArrayList<OpusPacket>()
        var offset = 0
        while (offset < pending.size) {
            val take = minOf(FRAME_BYTES, pending.size - offset) and 1.inv()
            if (take <= 0) break
            feed(pending, offset, take, eos = false, packets)
            offset += take
        }
        pending = ByteArray(0)
        feed(ByteArray(0), 0, 0, eos = true, packets)
        var spins = 0
        while (spins++ < 40) {
            if (drain(packets, 5_000)) break
        }
        return packets
    }

    private fun feed(bytes: ByteArray, offset: Int, length: Int, eos: Boolean, packets: MutableList<OpusPacket>) {
        var tries = 0
        while (tries++ < 40) {
            val index = codec.dequeueInputBuffer(5_000)
            if (index >= 0) {
                val buffer = checkNotNull(codec.getInputBuffer(index))
                buffer.clear()
                val take = minOf(buffer.capacity(), length) and 1.inv()
                if (take > 0) buffer.put(bytes, offset, take)
                val pts = fedBytes / 2 * 1_000_000L / RATE
                fedBytes += take
                codec.queueInputBuffer(index, 0, take, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(packets, 0)
                return
            }
            drain(packets, 5_000)
        }
    }

    /** True once the encoder signals end of stream. */
    private fun drain(packets: MutableList<OpusPacket>, timeoutUs: Long): Boolean {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, timeoutUs)
            if (out < 0) return false
            val buffer = codec.getOutputBuffer(out)
            if (buffer != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                packets += OpusPacket(data, opusDurationUs(data))
            }
            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(out, false)
            if (done) return true
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val RATE = 48_000
        // 20 ms of 16-bit mono PCM at 48 kHz.
        const val FRAME_BYTES = 960 * 2
    }
}
