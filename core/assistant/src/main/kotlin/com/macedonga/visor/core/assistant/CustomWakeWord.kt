package com.macedonga.visor.core.assistant

import android.media.MediaCodec
import com.macedonga.visor.core.protocol.ThriftWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** What the glasses answered to an enrolment. */
data class WakeWordResult(val success: Boolean, val error: String, val wakeword: String, val transcript: String, val enrollmentId: String)

/**
 * The glasses' own custom wake word enrolment, as the Meta app runs it when it enrols on the device: the phone opens a
 * session (390) that keeps the glasses from waking on the phrase, asks for each recording (394), sends the phrase and
 * the recordings in one batch (386), and closes the session (391). The glasses build the model themselves and answer
 * with a result (368); there is no server in it. Thrift fields from the Meta app's schema, 1-based.
 */
class CustomWakeWord internal constructor(
    private val send: suspend (Int, ByteArray) -> Boolean,
    // The glasses' mic as a Bluetooth headset, for when they send no audio over the link.
    private val headset: suspend (Long, (String) -> Unit, (Int) -> Unit) -> ByteArray? = { _, _, _ -> null },
) {
    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val _results = MutableSharedFlow<WakeWordResult>(extraBufferCapacity = 4)

    /** Every answer the glasses give, also one that comes after [enroll] stopped waiting. */
    val results: SharedFlow<WakeWordResult> = _results.asSharedFlow()

    @Volatile
    private var answer: CompletableDeferred<WakeWordResult>? = null

    // Set when the glasses' own stream brought next to no audio in this session.
    @Volatile
    private var starved = false

    /** Open an enrolment session for [phrase]; the id it returns closes it again. Null without a link. */
    suspend fun begin(phrase: String): String? {
        starved = false
        val session = UUID.randomUUID().toString()
        return session.takeIf { send(EV_SESSION_BEGIN, ThriftWriter().str(1, it).str(2, phrase).done()) }
    }

    /** Ask the glasses to start or stop recording one sample. */
    suspend fun capture(start: Boolean, request: String): Boolean = send(EV_CAPTURE_CONTROL, ThriftWriter().bool(1, start).str(2, request).done())

    @Volatile
    private var take: Take? = null

    private val _level = MutableStateFlow(0f)

    /** How loud the voice is in the recording under way, 0 to 1; 0 between recordings. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * Record one sample on the glasses' microphone, up to [ms] long, as 16 kHz 16-bit mono PCM with the silence around
     * it cut; null when nothing was said or nothing came. The glasses stream it to the phone, as the Meta app takes it.
     * Some pairs open that stream and send next to no audio: after such a take the samples come from the glasses'
     * microphone as a Bluetooth headset, when they are one to this phone.
     */
    suspend fun record(ms: Long = TAKE_MS): ByteArray? {
        if (starved) headsetTake(ms)?.let { return speechOf(it) }
        return ownTake(ms)
    }

    private suspend fun headsetTake(ms: Long): ByteArray? = try {
        runCatching { headset(ms, ::note) { peak -> _level.value = (peak / LOUD).coerceIn(0f, 1f) } }
            .onFailure { note("Custom wake word: headset take failed: ${it.message}") }
            .getOrNull()
    } finally {
        _level.value = 0f
    }

    /** One sample from the glasses' own stream on the assistant's channel (header 6106, data 6100, end 6101). */
    private suspend fun ownTake(ms: Long): ByteArray? {
        val request = UUID.randomUUID().toString()
        val t = Take(_level, runCatching { opusDecoder() }.getOrElse { note("Custom wake word: no Opus decoder: ${it.message}"); return null })
        take = t
        val started = System.currentTimeMillis()
        var elapsed = 0L
        try {
            if (!capture(true, request)) return null
            withTimeoutOrNull(ms) { t.ended.await() }
            elapsed = System.currentTimeMillis() - started
            capture(false, request)
            withTimeoutOrNull(END_MS) { t.ended.await() }
        } finally {
            take = null
            _level.value = 0f
            t.close()
        }
        note("Custom wake word: recorded ${t.pcm.size() / BYTES_PER_MS} ms in ${t.packets} packets" + (t.header?.let { ", header $it" } ?: ""))
        // Under half its time in audio is a starved stream, as the Meta app counts it.
        if (!starved && t.pcm.size() / BYTES_PER_MS < elapsed / 2) {
            starved = true
            note("Custom wake word: the glasses' stream brought next to no audio; the next takes try their headset mic")
        }
        return speechOf(t.pcm.toByteArray())
    }

    internal fun onStream(type: Int, payload: ByteArray) {
        val t = take ?: return
        when (type) {
            MT_ENROLLMENT_HEADER -> t.header = payload.take(48).joinToString("") { "%02x".format(it) }
            MT_STREAMER_DATA -> for (packet in packetsOf(payload)) t.decode(packet)
            // The glasses send an empty 6101 right after the header, as the stream opens: only one after audio ends the take.
            MT_STREAMER_END -> if (t.packets > 0) t.ended.complete(Unit)
        }
    }

    /** One recording on its way in: the decoder, and the 16 kHz PCM so far. */
    private class Take(val level: MutableStateFlow<Float>, val codec: MediaCodec) {
        val pcm = ByteArrayOutputStream()
        val ended = CompletableDeferred<Unit>()
        var header: String? = null
        var packets = 0
        private val info = MediaCodec.BufferInfo()

        @Synchronized
        fun decode(packet: ByteArray) {
            packets++
            runCatching {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    codec.getInputBuffer(i)!!.apply { clear(); put(packet) }
                    codec.queueInputBuffer(i, 0, packet.size, 0, 0)
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val out = codec.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val wide = ShortArray(info.size / 2)
                        out.order(ByteOrder.nativeOrder()).asShortBuffer().get(wide)
                        // 48 kHz from the decoder, 16 kHz for the glasses' model.
                        val bytes = ByteBuffer.allocate(wide.size / 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (k in 0 until wide.size / 3) bytes.putShort(((wide[k * 3] + wide[k * 3 + 1] + wide[k * 3 + 2]) / 3).toShort())
                        pcm.write(bytes.array())
                        val loud = (0 until wide.size / 3).maxOfOrNull { k -> kotlin.math.abs(bytes.getShort(k * 2).toInt()) } ?: 0
                        level.value = (loud / LOUD).coerceIn(0f, 1f)
                    }
                    codec.releaseOutputBuffer(o, false)
                }
            }
        }

        @Synchronized
        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Send the phrase and its recordings (16 kHz 16-bit mono PCM); the glasses' answer, or null after three minutes. */
    suspend fun enroll(phrase: String, clips: List<ByteArray>): WakeWordResult? {
        val id = UUID.randomUUID().toString()
        val waiting = CompletableDeferred<WakeWordResult>()
        answer = waiting
        if (!send(EV_BATCH_ENROLLMENT, ThriftWriter().str(1, phrase).listOfBinaries(2, clips).str(3, id).done())) return null
        note("Custom wake word: sent \"$phrase\" with ${clips.size} recordings (${clips.sumOf { it.size }} bytes)")
        return withTimeoutOrNull(ENROLL_MS) { waiting.await() }
    }

    /** Delete the enrolled phrase, as the Meta app does: an empty utterance under a new id. The glasses answer with a result. */
    suspend fun clear(): Boolean =
        send(EV_UTTERANCE, ThriftWriter().i32(1, 0).i32(2, 1).str(3, "").str(4, ByteArray(0)).str(5, UUID.randomUUID().toString()).done())

    /** Close the session; false when the close could not go out. */
    suspend fun end(session: String, reason: Int = 0): Boolean =
        send(EV_SESSION_END, ThriftWriter().str(1, session).i32(2, reason).done())

    internal fun onResult(fields: Map<Int, Any?>) {
        val result = WakeWordResult(
            success = fields[1] == true,
            error = fields[2].asText(),
            wakeword = fields[3].asText(),
            transcript = fields[4].asText(),
            enrollmentId = fields[5].asText(),
        )
        note("Custom wake word: ${if (result.success) "enrolled \"${result.wakeword}\"" else "refused: ${result.error}"} (heard \"${result.transcript}\")")
        answer?.complete(result)
        _results.tryEmit(result)
    }

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        internal const val MT_STREAMER_DATA = 6100
        internal const val MT_STREAMER_END = 6101
        internal const val MT_ENROLLMENT_HEADER = 6106
        internal const val EV_RESULT = 368
        private const val TAKE_MS = 3_000L
        private const val END_MS = 2_000L
        private const val BYTES_PER_MS = 32
        // A voice near the glasses peaks around a third of full scale.
        private const val LOUD = 12_000f
        private const val EV_UTTERANCE = 365
        private const val EV_BATCH_ENROLLMENT = 386
        private const val EV_SESSION_BEGIN = 390
        private const val EV_SESSION_END = 391
        private const val EV_CAPTURE_CONTROL = 394
        // The Meta app waits 60 s; these glasses answered after 61.
        private const val ENROLL_MS = 180_000L
    }
}

/**
 * The Opus packets in one stream chunk. The AudioStreamer spec names several framings; whichever reads the chunk to
 * its last byte is the one: a 4-byte little-endian length per packet, a big-endian one, the 16-byte record of the
 * assistant's own stream (see [GlassesEars.hear]), or one bare packet.
 */
internal fun packetsOf(chunk: ByteArray): List<ByteArray> {
    fun lengthPrefixed(order: ByteOrder): List<ByteArray>? {
        val b = ByteBuffer.wrap(chunk).order(order)
        val out = mutableListOf<ByteArray>()
        while (b.remaining() >= 5) {
            val n = b.int
            if (n <= 0 || n > b.remaining()) return null
            out += ByteArray(n).also { b.get(it) }
        }
        return out.takeIf { b.remaining() == 0 && it.isNotEmpty() }
    }
    fun records(): List<ByteArray>? {
        val b = ByteBuffer.wrap(chunk)
        val out = mutableListOf<ByteArray>()
        while (b.remaining() >= 16) {
            b.position(b.position() + 8)
            val n = b.order(ByteOrder.BIG_ENDIAN).int
            b.int
            if (n <= 0 || n > b.remaining()) return null
            out += ByteArray(n).also { b.get(it) }
        }
        return out.takeIf { b.remaining() == 0 && it.isNotEmpty() }
    }
    return lengthPrefixed(ByteOrder.LITTLE_ENDIAN) ?: lengthPrefixed(ByteOrder.BIG_ENDIAN) ?: records() ?: listOf(chunk)
}

/** [pcm] (16 kHz 16-bit LE) cut to where the voice is, with a little room each side; null when it never rises. */
internal fun speechOf(pcm: ByteArray): ByteArray? {
    val frame = 320 // 10 ms
    val frames = pcm.size / frame
    if (frames == 0) return null
    val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val levels = DoubleArray(frames) { f ->
        var sum = 0.0
        for (k in 0 until frame / 2) {
            val v = b.getShort(f * frame + k * 2).toDouble()
            sum += v * v
        }
        kotlin.math.sqrt(sum / (frame / 2))
    }
    // The quiet tenth of the recording is the room; the voice stands well above it.
    val floor = levels.sorted()[frames / 10]
    val loud = maxOf(floor * 4, 300.0)
    val first = levels.indexOfFirst { it > loud }
    if (first < 0) return null
    val last = levels.indexOfLast { it > loud }
    val from = maxOf(0, first - 15) * frame
    val to = minOf(frames, last + 25) * frame
    return pcm.copyOfRange(from, to)
}
