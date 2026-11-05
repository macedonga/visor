package com.macedonga.visor.core.media

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Which video a broadcast carries: the glasses' HEVC as it is (enhanced RTMP), or H.264 made on the phone. */
enum class BroadcastCodec { HEVC, H264 }

/**
 * A publishing RTMP client, rtmp:// or rtmps://: the simple handshake, connect, createStream and publish, then
 * FLV tags. HEVC goes out as enhanced RTMP (FourCC hvc1), H.264 and AAC the classic way. One instance is one
 * connection; it throws on the first failure and is not reused.
 */
class RtmpPublisher(private val url: String, private val key: String) {
    private lateinit var socket: Socket
    @Volatile private var tcp: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val writeLock = ReentrantLock()
    private var outChunk = 128
    private var inChunk = 128
    private var streamId = 0
    private var txn = 1.0
    private val waiting = ConcurrentHashMap<Double, CompletableDeferred<List<Any?>>>()
    private val published = CompletableDeferred<String>()
    private var reader: Job? = null
    private var received = 0L
    private var acked = 0L
    private var window = 2_500_000L

    /** Why the server ended it, once it has. */
    @Volatile
    var failure: String? = null
        private set

    suspend fun connect(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        val uri = URI(url.trim().trimEnd('/'))
        val secure = uri.scheme.equals("rtmps", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 1935
        val plain = Socket().apply { tcpNoDelay = true }
        tcp = plain
        plain.connect(InetSocketAddress(uri.host, port), 8_000)
        // Bounds the TLS and RTMP handshakes against a server that accepts and never answers.
        plain.soTimeout = 10_000
        socket = if (secure) {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, uri.host, port, true) as SSLSocket
            tls.startHandshake()
            // A bare SSLSocket checks the chain but not the name.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.host, tls.session)) {
                tls.close()
                throw SSLPeerUnverifiedException("the certificate is not for ${uri.host}")
            }
            tls
        } else plain
        input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
        output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        handshake()
        socket.soTimeout = 0
        reader = scope.launch(Dispatchers.IO) { readLoop() }
        send(2, 0, MSG_SET_CHUNK_SIZE, 0, int32(OUT_CHUNK))
        outChunk = OUT_CHUNK

        val app = uri.path.trim('/').ifEmpty { error("the server address has no application, like /live2") }
        val tcUrl = uri.toString()
        withTimeout(10_000) {
            call(
                "connect",
                linkedMapOf(
                    "app" to app, "type" to "nonprivate", "flashVer" to "FMLE/3.0 (compatible; Visor)", "tcUrl" to tcUrl,
                    "fourCcList" to listOf("hvc1"),
                ),
            )
        }
        notify("releaseStream", null, key)
        notify("FCPublish", null, key)
        val created = withTimeout(10_000) { call("createStream", null) }
        streamId = (created.getOrNull(1) as? Double)?.toInt() ?: 1
        sendCommand(streamId, "publish", 0.0, null, key, "live")
        val status = withTimeout(10_000) { published.await() }
        if (status != "NetStream.Publish.Start") error("the server answered $status")
    }

    /** The stream's description, sent once before the first media. */
    fun metadata(width: Int, height: Int, frameRate: Int, codec: BroadcastCodec, audio: AacFormat?) {
        val meta = linkedMapOf<String, Any?>(
            "width" to width.toDouble(), "height" to height.toDouble(), "framerate" to frameRate.toDouble(),
            "videocodecid" to if (codec == BroadcastCodec.HEVC) fourCc("hvc1").toDouble() else 7.0,
            "encoder" to "Visor",
        )
        if (audio != null) {
            meta["audiocodecid"] = 10.0
            meta["audiosamplerate"] = audio.sampleRate.toDouble()
            meta["stereo"] = audio.channels > 1
        }
        val body = ByteArrayOutputStream().apply {
            amf(this, "@setDataFrame")
            amf(this, "onMetaData")
            amfEcmaArray(this, meta)
        }.toByteArray()
        send(4, 0, MSG_DATA_AMF0, streamId, body)
    }

    /** The decoder configuration: an hvcC record for HEVC, an avcC one for H.264. */
    fun videoHeader(codec: BroadcastCodec, record: ByteArray) {
        val body = if (codec == BroadcastCodec.HEVC) {
            byteArrayOf((EX_HEADER or (FRAME_KEY shl 4) or PACKET_SEQUENCE_START).toByte()) + fourCcBytes("hvc1") + record
        } else {
            byteArrayOf(0x17, 0, 0, 0, 0) + record
        }
        send(6, 0, MSG_VIDEO, streamId, body)
    }

    /** One frame, length-prefixed NAL units, at [ms] from the start. */
    fun video(codec: BroadcastCodec, ms: Long, keyFrame: Boolean, units: ByteArray) {
        val body = if (codec == BroadcastCodec.HEVC) {
            val frame = if (keyFrame) FRAME_KEY else FRAME_INTER
            byteArrayOf((EX_HEADER or (frame shl 4) or PACKET_CODED_FRAMES_X).toByte()) + fourCcBytes("hvc1") + units
        } else {
            byteArrayOf(if (keyFrame) 0x17 else 0x27, 1, 0, 0, 0) + units
        }
        send(6, ms, MSG_VIDEO, streamId, body)
    }

    fun audioHeader(format: AacFormat) = send(4, 0, MSG_AUDIO, streamId, byteArrayOf(0xAF.toByte(), 0) + format.asc)

    fun audio(ms: Long, frame: ByteArray) = send(4, ms, MSG_AUDIO, streamId, byteArrayOf(0xAF.toByte(), 1) + frame)

    fun close() {
        // A writer stuck on a stalled server holds the lock; then the goodbye is skipped.
        runCatching {
            if (streamId != 0 && failure == null && writeLock.tryLock(500, TimeUnit.MILLISECONDS)) {
                try {
                    notify("FCUnpublish", null, key)
                    sendCommand(0, "deleteStream", 0.0, null, streamId.toDouble())
                } finally {
                    writeLock.unlock()
                }
            }
        }
        reader?.cancel()
        runCatching { socket.close() }
        abort()
    }

    /** Closes the connection from any thread, under a blocked read or write. */
    fun abort() {
        runCatching { tcp?.close() }
    }

    private fun handshake() {
        val c1 = ByteArray(1536).also { Random.nextBytes(it) }
        for (i in 0 until 8) c1[i] = 0
        output.write(3)
        output.write(c1)
        output.flush()
        val version = input.readUnsignedByte()
        if (version != 3) throw IOException("not an RTMP server (version $version)")
        val s1 = ByteArray(1536).also(input::readFully)
        output.write(s1)
        output.flush()
        input.readFully(ByteArray(1536))
    }

    private suspend fun call(name: String, vararg args: Any?): List<Any?> {
        val id = txn++
        val answer = CompletableDeferred<List<Any?>>().also { waiting[id] = it }
        sendCommand(0, name, id, *args)
        return answer.await()
    }

    private fun notify(name: String, vararg args: Any?) = sendCommand(0, name, txn++, *args)

    private fun sendCommand(stream: Int, name: String, id: Double, vararg args: Any?) {
        val body = ByteArrayOutputStream().apply {
            amf(this, name)
            amf(this, id)
            for (a in args) amf(this, a)
        }.toByteArray()
        send(if (stream == 0) 3 else 5, 0, MSG_COMMAND_AMF0, stream, body)
    }

    /** One message in chunks: a type-0 header first, type-3 headers after. */
    private fun send(csid: Int, ms: Long, type: Int, stream: Int, body: ByteArray) = writeLock.withLock {
        val extended = ms >= 0xFFFFFF
        val ts = if (extended) 0xFFFFFF else ms.toInt()
        output.write(csid and 0x3F)
        output.write(ts ushr 16); output.write(ts ushr 8); output.write(ts)
        output.write(body.size ushr 16); output.write(body.size ushr 8); output.write(body.size)
        output.write(type)
        output.write(stream); output.write(stream ushr 8); output.write(stream ushr 16); output.write(stream ushr 24)
        if (extended) output.write(int32(ms.toInt()))
        var off = 0
        while (off < body.size) {
            if (off > 0) {
                output.write(0xC0 or (csid and 0x3F))
                if (extended) output.write(int32(ms.toInt()))
            }
            val n = minOf(outChunk, body.size - off)
            output.write(body, off, n)
            off += n
        }
        output.flush()
    }

    private class Partial(var type: Int = 0, var length: Int = 0) {
        var body = ByteArrayOutputStream()
        var extended = false
    }

    private fun readLoop() {
        val partial = HashMap<Int, Partial>()
        try {
            while (true) {
                val b0 = input.readUnsignedByte()
                val fmt = b0 ushr 6
                val csid = when (b0 and 0x3F) {
                    0 -> 64 + input.readUnsignedByte()
                    1 -> 64 + input.readUnsignedByte() + input.readUnsignedByte() * 256
                    else -> b0 and 0x3F
                }
                val p = partial.getOrPut(csid) { Partial() }
                // Server timestamps are read past; type 3 repeats the extended one whenever the last header had it.
                if (fmt <= 2) p.extended = read24() == 0xFFFFFFL
                if (fmt <= 1) {
                    p.length = read24().toInt()
                    p.type = input.readUnsignedByte()
                }
                if (fmt == 0) input.readInt()
                if (p.extended) input.readInt()
                val n = minOf(inChunk, p.length - p.body.size())
                val chunk = ByteArray(n).also(input::readFully)
                p.body.write(chunk)
                countIn(n + 12)
                if (p.body.size() >= p.length) {
                    val body = p.body.toByteArray()
                    p.body = ByteArrayOutputStream()
                    onMessage(p.type, body)
                }
            }
        } catch (e: Exception) {
            failure = failure ?: (e.message ?: "the connection closed")
            waiting.values.forEach { it.completeExceptionally(IOException(failure)) }
            published.completeExceptionally(IOException(failure))
        }
    }

    private fun countIn(n: Int) {
        received += n
        if (received - acked >= window / 2) {
            acked = received
            send(2, 0, MSG_ACK, 0, int32(received.toInt()))
        }
    }

    private fun onMessage(type: Int, body: ByteArray) {
        when (type) {
            MSG_SET_CHUNK_SIZE -> inChunk = readInt(body, 0) and 0x7FFFFFFF
            MSG_WINDOW_ACK -> window = readInt(body, 0).toLong()
            MSG_USER_CONTROL -> if (body.size >= 6 && body[1].toInt() == 6) {
                // Ping: answered with a pong carrying the same time.
                send(2, 0, MSG_USER_CONTROL, 0, byteArrayOf(0, 7) + body.copyOfRange(2, 6))
            }
            MSG_COMMAND_AMF0 -> {
                val values = readAmf(body)
                val name = values.getOrNull(0) as? String
                val id = values.getOrNull(1) as? Double ?: 0.0
                when (name) {
                    "_result" -> waiting.remove(id)?.complete(values.drop(2))
                    "_error" -> {
                        val info = values.getOrNull(3) as? Map<*, *>
                        failure = (info?.get("description") ?: info?.get("code") ?: "refused").toString()
                        waiting.remove(id)?.completeExceptionally(IOException(failure))
                    }
                    "onStatus" -> {
                        val info = values.getOrNull(3) as? Map<*, *>
                        val code = info?.get("code")?.toString().orEmpty()
                        if (code.startsWith("NetStream.Publish")) {
                            if (code != "NetStream.Publish.Start") failure = (info?.get("description") ?: code).toString()
                            published.complete(code)
                        }
                    }
                }
            }
        }
    }

    private fun read24(): Long = ((input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong()

    private companion object {
        const val MSG_SET_CHUNK_SIZE = 1
        const val MSG_ACK = 3
        const val MSG_USER_CONTROL = 4
        const val MSG_WINDOW_ACK = 5
        const val MSG_AUDIO = 8
        const val MSG_VIDEO = 9
        const val MSG_DATA_AMF0 = 18
        const val MSG_COMMAND_AMF0 = 20
        const val OUT_CHUNK = 4096

        // Enhanced RTMP video header: IsExHeader, frame type, packet type.
        const val EX_HEADER = 0x80
        const val FRAME_KEY = 1
        const val FRAME_INTER = 2
        const val PACKET_SEQUENCE_START = 0
        const val PACKET_CODED_FRAMES_X = 3
    }
}

/** An AVCDecoderConfigurationRecord from Annex-B SPS and PPS. */
fun avcC(config: ByteArray): ByteArray? {
    val units = annexBUnits(config)
    val sps = units.firstOrNull { (it[0].toInt() and 0x1F) == 7 } ?: return null
    val pps = units.filter { (it[0].toInt() and 0x1F) == 8 }
    if (pps.isEmpty() || sps.size < 4) return null
    val out = ByteArrayOutputStream()
    out.write(1)
    out.write(sps[1].toInt()); out.write(sps[2].toInt()); out.write(sps[3].toInt())
    out.write(0xFF) // lengthSizeMinusOne 3
    out.write(0xE1)
    out.write(sps.size ushr 8); out.write(sps.size)
    out.write(sps)
    out.write(pps.size)
    for (p in pps) {
        out.write(p.size ushr 8); out.write(p.size)
        out.write(p)
    }
    return out.toByteArray()
}

private fun fourCc(s: String): Int = s.fold(0) { acc, c -> (acc shl 8) or c.code }

private fun fourCcBytes(s: String) = s.toByteArray(Charsets.US_ASCII)

private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

private fun readInt(b: ByteArray, at: Int) =
    ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

private fun amf(out: ByteArrayOutputStream, value: Any?) {
    when (value) {
        null -> out.write(5)
        is Double -> {
            out.write(0)
            val bits = java.lang.Double.doubleToLongBits(value)
            for (i in 7 downTo 0) out.write((bits ushr (i * 8)).toInt())
        }
        is Boolean -> {
            out.write(1)
            out.write(if (value) 1 else 0)
        }
        is String -> {
            out.write(2)
            amfKey(out, value)
        }
        is Map<*, *> -> {
            out.write(3)
            for ((k, v) in value) {
                amfKey(out, k.toString())
                amf(out, v)
            }
            out.write(0); out.write(0); out.write(9)
        }
        is List<*> -> {
            out.write(10)
            out.write(int32(value.size))
            for (v in value) amf(out, v)
        }
        is Number -> amf(out, value.toDouble())
        else -> amf(out, value.toString())
    }
}

private fun amfEcmaArray(out: ByteArrayOutputStream, values: Map<String, Any?>) {
    out.write(8)
    out.write(int32(values.size))
    for ((k, v) in values) {
        amfKey(out, k)
        amf(out, v)
    }
    out.write(0); out.write(0); out.write(9)
}

private fun amfKey(out: ByteArrayOutputStream, key: String) {
    val bytes = key.toByteArray()
    out.write(bytes.size ushr 8); out.write(bytes.size)
    out.write(bytes)
}

/** The AMF0 values in a command body; objects and ECMA arrays come back as maps. */
private fun readAmf(body: ByteArray): List<Any?> {
    val input = DataInputStream(body.inputStream())
    val out = ArrayList<Any?>()
    while (input.available() > 0) out += runCatching { readValue(input) }.getOrElse { return out }
    return out
}

private fun readValue(input: DataInputStream): Any? = when (val marker = input.readUnsignedByte()) {
    0 -> input.readDouble()
    1 -> input.readUnsignedByte() != 0
    2 -> readKey(input)
    3 -> readObject(input)
    5, 6 -> null
    8 -> {
        input.readInt()
        readObject(input)
    }
    10 -> List(input.readInt()) { readValue(input) }
    12 -> String(ByteArray(input.readInt()).also(input::readFully))
    else -> throw IOException("AMF0 marker $marker")
}

private fun readObject(input: DataInputStream): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    while (true) {
        val key = readKey(input)
        if (key.isEmpty()) {
            input.readUnsignedByte()
            return map
        }
        map[key] = readValue(input)
    }
}

private fun readKey(input: DataInputStream): String = String(ByteArray(input.readUnsignedShort()).also(input::readFully))
