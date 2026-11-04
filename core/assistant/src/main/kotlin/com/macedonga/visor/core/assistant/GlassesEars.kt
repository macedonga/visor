package com.macedonga.visor.core.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * The server's ears. When the glasses leave recognition to the server (a follow-up after an answer, or a whole
 * Live AI session) they stream their microphone as Opus on s0; here it is decoded and fed to the phone's speech
 * recogniser as its audio source. [continuous] keeps one session going across utterances for Live AI; otherwise
 * it hears one stream, until [finish], and stops. Each utterance goes to [onUtterance] with when it began and
 * ended on the glasses' audio clock.
 */
class GlassesEars(
    private val context: Context,
    private val locale: Locale,
    private val continuous: Boolean,
    private val onUtterance: (text: String, startMs: Long, endMs: Long) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onDone: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    // On-device models go by language and region: the glasses say "en", the phone may hold "en-GB" only.
    private var tag = speechTag(locale)
    private var recognizer: SpeechRecognizer? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private val queue = LinkedBlockingQueue<ShortArray>(800)
    private var opus: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    @Volatile
    private var accepting = false
    private var usingOnDevice = false
    private var reconnected = false
    @Volatile
    private var closed = false

    // The glasses' audio clock: the newest packet's time, and where the current utterance began.
    @Volatile
    private var clockMs = 0L
    @Volatile
    private var speechFrom = 0L

    // A chunk that did not parse, for the log.
    private var odd: ByteArray? = null

    /** From now on audio is taken; the recogniser starts on the main thread and reads what was queued meanwhile. */
    fun start() {
        if (accepting || closed) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return onProblem("this phone has no speech recognition")
        // The recogniser wants the microphone permission even when the audio is handed to it.
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return onProblem("speech recognition needs the microphone permission; allow it under Meta AI")
        }
        opus = runCatching { opusDecoder() }.getOrElse {
            onProblem("no Opus decoder: ${it.message}")
            return
        }
        accepting = true
        main.post { listen() }
    }

    /**
     * One chunk from the glasses' s0 stream, repeated records of either `[u32 LE len][u32 LE ms][u32 BE opus len]
     * [u32 BE range][opus]` (a follow-up) or `[u32 BE len][opus]` (the question itself, as the glasses send it in Italian,
     * with no time: each packet's own length is counted).
     */
    fun hear(chunk: ByteArray) {
        if (!accepting) return
        val b = ByteBuffer.wrap(chunk)
        // A little-endian length read big-endian is far larger than the chunk, so this tells the two apart.
        if (b.remaining() >= 5 && b.getInt(0) in 1..b.remaining() - 4) {
            while (b.remaining() >= 5) {
                val n = b.int
                if (n <= 0 || n > b.remaining()) {
                    if (odd == null) odd = chunk
                    return
                }
                val packet = ByteArray(n).also { b.get(it) }
                decode(packet, clockMs)
                clockMs += opusMs(packet)
            }
            return
        }
        while (b.remaining() >= 16) {
            b.order(ByteOrder.LITTLE_ENDIAN)
            b.int
            val ms = b.int.toLong() and 0xFFFFFFFFL
            b.order(ByteOrder.BIG_ENDIAN)
            val opusLen = b.int
            b.int
            if (opusLen <= 0 || opusLen > b.remaining()) {
                if (odd == null) odd = chunk
                return
            }
            val packet = ByteArray(opusLen).also { b.get(it) }
            clockMs = ms
            decode(packet, ms)
        }
    }

    /** The stream ended: what is queued still goes to the recogniser, then it hears the end and gives its result. */
    fun finish() {
        if (!accepting) return
        accepting = false
        odd?.let { c -> onProblem("a microphone chunk did not parse: " + c.take(32).joinToString("") { "%02x".format(it) }) }
        queue.offer(END)
    }

    /** Everything stops now, results or not. */
    fun stop() {
        accepting = false
        main.post { close() }
    }

    @Synchronized
    private fun decode(packet: ByteArray, ms: Long) {
        val codec = opus ?: return
        runCatching {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
                codec.getInputBuffer(i)!!.apply { clear(); put(packet) }
                codec.queueInputBuffer(i, 0, packet.size, ms * 1000, 0)
            }
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                val out = codec.getOutputBuffer(o)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val pcm = ShortArray(info.size / 2)
                    out.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                    // The decoder gives 48 kHz mono; the recogniser takes 16 kHz.
                    queue.offer(ShortArray(pcm.size / 3) { k -> ((pcm[k * 3] + pcm[k * 3 + 1] + pcm[k * 3 + 2]) / 3).toShort() })
                }
                codec.releaseOutputBuffer(o, false)
            }
        }
    }

    /** How long an Opus packet plays, from its TOC byte (RFC 6716, 3.1). */
    private fun opusMs(packet: ByteArray): Long {
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs = when {
            config < 12 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
            config < 16 -> intArrayOf(10_000, 20_000)[config and 1]
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            3 -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
            else -> 2
        }
        return frameUs.toLong() * frames / 1000
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
    }

    private fun listen() {
        if (closed) return
        // The glasses' language, or a held model of the same language while its own downloads (SpeechModels, at launch).
        tag = SpeechModels.pick(locale)
        val (read, write) = ParcelFileDescriptor.createPipe().also { pipe = it }
        // On the phone if it holds the language, else the phone's usual recogniser.
        val onDevice = onDeviceLanguages[tag] != false && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = recognizer ?: (
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            ).also {
            recognizer = it
            usingOnDevice = onDevice
            it.setRecognitionListener(Listener())
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            // One session for as long as the pipe is open; each utterance comes back as a segment.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        r.startListening(intent)
        val out = FileOutputStream(write.fileDescriptor)
        thread(name = "glasses-ears") {
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk === END) break
                    val bytes = ByteArray(chunk.size * 2)
                    for (i in chunk.indices) {
                        bytes[i * 2] = chunk[i].toByte()
                        bytes[i * 2 + 1] = (chunk[i].toInt() shr 8).toByte()
                    }
                    out.write(bytes)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                // The end of the pipe is the end of the session to the recogniser.
                runCatching { write.close() }
            }
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        accepting = false
        queue.clear()
        queue.offer(END)
        pipe?.forEach { runCatching { it.close() } }
        pipe = null
        recognizer?.let { r ->
            runCatching { r.stopListening() }
            r.destroy()
        }
        recognizer = null
        synchronized(this) {
            opus?.let { runCatching { it.stop() }; it.release() }
            opus = null
        }
        onDone()
    }

    // When the last few sessions began: a recogniser that ends at once every time is not listening.
    private val starts = ArrayDeque<Long>()

    /** A continuous session that ended starts again on a fresh pipe; a single one is over. */
    private fun ended() {
        main.post {
            if (!continuous || !accepting) return@post close()
            val now = System.currentTimeMillis()
            starts.addLast(now)
            while (starts.size > RESTARTS) starts.removeFirst()
            if (starts.size == RESTARTS && now - starts.first() < RESTART_WINDOW_MS) {
                onProblem("speech recognition keeps ending at once; stopped listening")
                return@post close()
            }
            pipe?.forEach { runCatching { it.close() } }
            listen()
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            speechFrom = clockMs
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            // The phone has no model for this language yet: ask it to fetch one, and hear on its usual recogniser.
            if (usingOnDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                onDeviceLanguages[tag] = false
                onProblem("no on-device speech model for ${tag}; asking the phone to download it, using its usual recogniser meanwhile")
                main.post {
                    recognizer?.let { r -> runCatching { r.triggerModelDownload(intent()) }; r.destroy() }
                    recognizer = null
                    if (!closed) {
                        pipe?.forEach { runCatching { it.close() } }
                        listen()
                    }
                }
                return
            }
            // Silence ends a session and the next one listens on; anything else stops listening for good.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return ended()
            // The recognition service dropped its connection: once, a fresh recogniser picks up where it was.
            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !reconnected) {
                reconnected = true
                main.post {
                    recognizer?.destroy()
                    recognizer = null
                    if (!closed) {
                        pipe?.forEach { runCatching { it.close() } }
                        listen()
                    }
                }
                return
            }
            onProblem(when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "the phone has no speech recognition for $tag; stopped listening"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "speech recognition for $tag is unavailable; download its speech model or check the network"
                else -> "speech recognition error $error for $tag; stopped listening"
            })
            main.post { close() }
        }

        override fun onResults(results: Bundle?) {
            heard(results)
            ended()
        }

        override fun onSegmentResults(segmentResults: Bundle) = heard(segmentResults)

        override fun onEndOfSegmentedSession() {
            ended()
        }
    }

    private fun heard(bundle: Bundle?) {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) onUtterance(text, speechFrom.takeIf { it > 0 } ?: clockMs, clockMs)
        speechFrom = 0
    }

    private companion object {
        const val RATE = 16_000
        const val RESTARTS = 5
        const val RESTART_WINDOW_MS = 5_000L
        val END = ShortArray(0)
        /** Languages the on-device recogniser turned out not to hold, for the life of the process. */
        val onDeviceLanguages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
}

/** A recogniser language tag with a region, which the on-device models need. */
fun speechTag(locale: Locale): String = when {
    locale.country.isNotEmpty() -> locale.toLanguageTag()
    else -> when (locale.language) {
        "en" -> "en-US"
        "pt" -> "pt-BR"
        "" -> "en-US"
        else -> "${locale.language}-${locale.language.uppercase()}"
    }
}

/** SILK wideband in an Opus decoder: an OpusHead for one channel at 16 kHz, no pre-skip, the usual pre-roll. */
internal fun opusDecoder(): MediaCodec {
    val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray()).put(1).put(1).putShort(0).putInt(16_000).putShort(0).put(0)
    head.flip()
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
        setByteBuffer("csd-0", head)
        setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
        setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
    }
    return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
        configure(format, null, null, 0)
        start()
    }
}
