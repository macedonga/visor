package com.macedonga.visor.connection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * svc9000, the data fetcher: the Display's apps (Instagram, messaging, the prompter) ask the phone for a URL and get
 * its bytes back on the same channel, a ResponseData {1 data, 2 compressed, 3 total} per chunk, then an OK status.
 * Plain http(s) streams; with an image transformation the whole image is scaled first. An `instagram://` link wraps a
 * web one: `target_url`, with `headers` (base64 JSON) inside it, and `bytes_to_skip`. `sgmessaging://` is message
 * media: `whatsapp` with `encrypted=true` is the encrypted file on a whatsapp.net host, which the Display decrypts;
 * `phone` is a content URI of the phone's own messages. Messenger's, Instagram's and plain WhatsApp media need those
 * apps' own logins and are refused, as are other schemes.
 */
@Singleton
class DataFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: EventLog,
    private val requests: RequestLog,
) : Responder {
    private val scope = responderScope(log, "Fetch")
    private val running = ConcurrentHashMap<Int, Job>()
    private val slots = Semaphore(PARALLEL)

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val channel = message.info.channelId
        if (message.info.flags.isClosed) {
            running.remove(channel)?.cancel()
            if (message.payload.isEmpty()) return
        }
        if (message.info.messageType != MT_REQUEST) return
        val query = runCatching { Query.decode(message.payload) }.getOrNull() ?: return
        val reply = DataXConnection.mirror(channel)
        // Off the reader: a download must not hold up everything else on the link.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit { serve(query, reply, connection) }
            } finally {
                running.remove(channel, coroutineContext.job)
            }
        }
        running[channel] = job
        job.start()
    }

    private suspend fun serve(q: Query, reply: Int, mux: DataXConnection) {
        val id = requests.start(q.url, q.source, q.options())
        suspend fun refuse(why: String, failed: Boolean = true, code: Int = INTERNAL_ERROR) {
            requests.finish(id, why, failed)
            mux.sendStatus(reply, code)
        }
        var sent = 0L
        var resized = false
        val scheme = q.url.substringBefore(':').lowercase()
        val target = when (scheme) {
            "http", "https" -> Target(q.url, emptyMap(), 0)
            // Instagram's bare check, every few seconds; the Meta app turns it down the same way.
            "instagram" -> instagramTarget(q.url) ?: return refuse("no target_url, turned down", failed = false)
            "sgmessaging" -> {
                val link = Uri.parse(q.url)
                val inner = link.getQueryParameter("uri")
                when (link.authority) {
                    "whatsapp" -> {
                        val host = inner?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (link.getQueryParameter("encrypted") != "true" || host?.scheme != "https" || host.host?.endsWith(".whatsapp.net") != true) {
                            return refuse("not served: needs WhatsApp's own login")
                        }
                        Target(inner, emptyMap(), 0)
                    }
                    "phone" -> {
                        if (inner == null) return refuse("no uri in the link")
                        requests.rename(id, inner)
                        return servePhone(Uri.parse(inner), link.getQueryParameter("mimeType").orEmpty(), id, reply, mux)
                    }
                    else -> return refuse("not served: needs ${link.authority}'s own login")
                }
            }
            else -> return refuse("not served: $scheme links", code = UNKNOWN_TYPE)
        }
        if (target.url != q.url) requests.rename(id, target.url)
        val chunk = (if (q.chunkSize > 0) q.chunkSize else DEFAULT_CHUNK).coerceAtMost(MAX_CHUNK)
        val c = runCatching { open(target) }.getOrElse {
            log.add("Fetch: ${host(target.url)} failed: ${it.message}")
            return refuse("failed: ${it.message}")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                log.add("Fetch: ${host(target.url)} answered $code")
                return refuse("HTTP $code")
            }
            if (q.scale == null) {
                // From a byte on: a ranged answer says the whole length after its slash; a full one is skipped through.
                val ranged = code == 206
                val total = if (ranged) c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1 else c.contentLengthLong
                sent = stream(c.inputStream, total, chunk, reply, mux, skip = if (ranged) 0 else target.skip)
            } else {
                var body = c.inputStream.use { it.readBytes() }
                scaled(body, q.scale)?.let { body = it; resized = true }
                val compressed = q.compress
                if (compressed) body = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(body) } }.toByteArray()
                sendAll(mux, reply, body, chunk, compressed)
                sent = body.size.toLong()
            }
            mux.sendStatus(reply, OK)
            requests.finish(id, "HTTP $code" + (if (resized) ", scaled" else "") + (if (scheme == "instagram") ", for instagram://" else ""), failed = false, bytes = sent)
        } catch (error: CancellationException) {
            requests.finish(id, "cancelled", failed = false, bytes = sent)
            throw error
        } catch (error: Exception) {
            log.add("Fetch: ${host(target.url)} broke off: ${error.message}")
            requests.finish(id, "broke off: ${error.message}", failed = true, bytes = sent)
            runCatching { mux.sendStatus(reply, INTERNAL_ERROR) }
        } finally {
            c.disconnect()
        }
    }

    /** A file of the phone's own messages: a video as its first frame, anything else as it is. */
    private suspend fun servePhone(uri: Uri, mime: String, id: Long, reply: Int, mux: DataXConnection) {
        try {
            val sent = if (mime.startsWith("video/")) {
                val frame = MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, uri)
                        getFrameAtTime(0)
                    } finally {
                        release()
                    }
                } ?: error("no frame in the video")
                val jpeg = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, DEFAULT_QUALITY, it) }.toByteArray()
                sendAll(mux, reply, jpeg, DEFAULT_CHUNK, false)
                jpeg.size.toLong()
            } else {
                val input = context.contentResolver.openInputStream(uri) ?: error("it could not be opened")
                stream(input, -1, DEFAULT_CHUNK, reply, mux)
            }
            mux.sendStatus(reply, OK)
            requests.finish(id, if (mime.startsWith("video/")) "first frame" else "read", failed = false, bytes = sent)
        } catch (error: CancellationException) {
            requests.finish(id, "cancelled", failed = false)
            throw error
        } catch (error: Exception) {
            requests.finish(id, "failed: ${error.message}", failed = true)
            runCatching { mux.sendStatus(reply, INTERNAL_ERROR) }
        }
    }

    /** The body as it comes after its first [skip] bytes, a chunk per message; how much went. */
    private suspend fun stream(input: InputStream, total: Long, chunk: Int, reply: Int, mux: DataXConnection, skip: Long = 0): Long {
        var sent = 0L
        input.use {
            var left = skip
            while (left > 0) {
                val n = it.skip(left)
                if (n <= 0) break
                left -= n
            }
            val buffer = ByteArray(chunk)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    send(mux, reply, buffer.copyOf(n), total, false)
                    sent += n
                }
            }
        }
        return sent
    }

    private suspend fun send(mux: DataXConnection, reply: Int, data: ByteArray, total: Long, compressed: Boolean) {
        // An unknown length goes as 0: the field is an int32, and a negative one does not encode here.
        val body = Wire.fieldBytes(1, data) + Wire.fieldVarint(2, if (compressed) 1 else 0) + Wire.fieldVarint(3, total.coerceIn(0, Int.MAX_VALUE.toLong()))
        mux.send(reply, body, messageType = MT_REQUEST)
    }

    private suspend fun sendAll(mux: DataXConnection, reply: Int, body: ByteArray, chunk: Int, compressed: Boolean) {
        for (at in body.indices step chunk) send(mux, reply, body.copyOfRange(at, minOf(body.size, at + chunk)), body.size.toLong(), compressed)
    }

    private fun open(t: Target): HttpURLConnection = (URL(t.url).openConnection() as HttpURLConnection).apply {
        for ((k, v) in t.headers) setRequestProperty(k, v)
        if (t.skip > 0) setRequestProperty("Range", "bytes=${t.skip}-")
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
    }

    /** The image fitted to the asked size and format; null to send it as it came, also when that is no bigger. */
    private fun scaled(image: ByteArray, s: Scale): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || s.width <= 0 || s.height <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= s.width && bounds.outHeight / (sample * 2) >= s.height) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (w, h) = if (s.keepAspect) {
            val k = minOf(s.width.toFloat() / decoded.width, s.height.toFloat() / decoded.height, 1f)
            max(1, (decoded.width * k).roundToInt()) to max(1, (decoded.height * k).roundToInt())
        } else s.width to s.height
        val fitted = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
        val format = when (s.format) {
            FORMAT_PNG -> Bitmap.CompressFormat.PNG
            FORMAT_WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val out = ByteArrayOutputStream().also { fitted.compress(format, if (s.quality in 1..100) s.quality else DEFAULT_QUALITY, it) }.toByteArray()
        return out.takeIf { it.size < image.size }
    }

    private fun host(url: String) = runCatching { URL(url).host }.getOrDefault("?")

    /** What to fetch, the headers to send with it, and from which byte. */
    private class Target(val url: String, val headers: Map<String, String>, val skip: Long)

    private fun instagramTarget(link: String): Target? {
        val outer = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val inner = outer.getQueryParameter("target_url")?.takeIf { it.isNotEmpty() } ?: return null
        val skip = outer.getQueryParameter("bytes_to_skip")?.toLongOrNull() ?: 0
        val innerUri = Uri.parse(inner)
        val headers = innerUri.getQueryParameter("headers")?.let { encoded ->
            runCatching {
                val json = JSONObject(String(Base64.decode(encoded, Base64.NO_WRAP)))
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrNull()
        }.orEmpty()
        val url = if (innerUri.getQueryParameter("headers") == null) inner else {
            val rebuilt = innerUri.buildUpon().clearQuery()
            for (name in innerUri.queryParameterNames) if (name != "headers") innerUri.getQueryParameter(name)?.let { rebuilt.appendQueryParameter(name, it) }
            rebuilt.build().toString()
        }
        return Target(url, headers, skip)
    }

    private class Scale(val width: Int, val height: Int, val format: Int, val keepAspect: Boolean, val quality: Int)

    /** RequestQuery: 1 url, 2 compress, 3 transformations (1 image scale), 4 chunk size, 6 who asked. */
    private class Query(val url: String, val compress: Boolean, val scale: Scale?, val chunkSize: Int, val source: Int) {
        fun options(): String = listOfNotNull(
            scale?.let { s -> "${s.width}x${s.height} ${listOf("jpeg", "png", "webp").getOrElse(s.format) { "format ${s.format}" }}" },
            "zlib".takeIf { compress },
            "chunk $chunkSize".takeIf { chunkSize > 0 },
        ).joinToString(", ")

        companion object {
            fun decode(payload: ByteArray): Query {
                val f = Wire.parse(payload)
                val scale = f.repeatedBytes(3).firstNotNullOfOrNull { t ->
                    Wire.parse(t).takeIf { it.has(1) }?.let { Wire.parse(it.bytes(1)) }?.let {
                        Scale(it.varint(1).toInt(), it.varint(2).toInt(), it.varint(3).toInt(), it.varint(5) != 0L, it.varint(6).toInt())
                    }
                }
                return Query(f.bytes(1).decodeToString(), f.varint(2) != 0L, scale, f.varint(4).toInt(), f.varint(6).toInt())
            }
        }
    }

    companion object {
        const val SVC = 9000
        private const val MT_REQUEST = 0
        private const val OK = 0
        private const val INTERNAL_ERROR = 0xC004
        private const val UNKNOWN_TYPE = 0xD001
        private const val DEFAULT_CHUNK = 1024
        // A DataX message holds at most 16383 bytes, header included.
        private const val MAX_CHUNK = 16_000
        private const val FORMAT_PNG = 1
        private const val FORMAT_WEBP = 2
        private const val DEFAULT_QUALITY = 85
        private const val PARALLEL = 4
        private const val TIMEOUT_MS = 20_000
    }
}
