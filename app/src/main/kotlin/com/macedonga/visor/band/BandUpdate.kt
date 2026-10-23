package com.macedonga.visor.band

import com.macedonga.visor.core.identity.token.withToken
import android.content.Context
import android.util.Base64
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.firmware.BandOffer
import com.macedonga.visor.core.identity.firmware.fetchBandKek
import com.macedonga.visor.core.identity.firmware.fetchBandOffer
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed interface BandWork {
    data object Idle : BandWork
    data object Checking : BandWork
    /** [percent] is null until the band acks the first chunk. */
    data class Installing(val percent: Int? = null) : BandWork
    data object Installed : BandWork
    data class Failed(val reason: String) : BandWork
}

/**
 * The Neural Band's firmware from Meta. [check] asks Meta for the newest image; [install] downloads it with the user's
 * login, checks it against its manifest, and sends it over the band's RPC: Ota on RpcRequest 7 (answers 8) and the bulk
 * file transfer on 9 (answers 10). The band is held for the whole update, and any failure aborts it.
 */
@Singleton
class BandUpdate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val login: MetaLogin,
    private val connection: GlassesConnection,
    private val bands: Bands,
    private val link: BandLink,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _checked = MutableStateFlow(false)
    /** Meta answered for the band's current build, offer or none. */
    val checked: StateFlow<Boolean> = _checked.asStateFlow()
    private val _offer = MutableStateFlow<BandOffer?>(null)
    val offer: StateFlow<BandOffer?> = _offer.asStateFlow()
    private val _work = MutableStateFlow<BandWork>(BandWork.Idle)
    val work: StateFlow<BandWork> = _work.asStateFlow()
    private val transfers = AtomicInteger(1)
    private var job: Job? = null

    init {
        // An offer is for one band's build: another pair starts over.
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().drop(1).collect {
                _offer.value = null
                _checked.value = false
            }
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _work.value = BandWork.Checking
            try {
                val serial = serial()
                val firmware = link.holdFor { withSession { deviceInfo().firmware } }
                // The Meta app never sends a version below 1.
                val found = login.source.withToken { fetchBandOffer(it, serial, firmware.coerceAtLeast(1).toString()) }
                _offer.value = found
                _checked.value = true
                _work.value = BandWork.Idle
                log.add("Band firmware: " + (found?.let { "${it.targetVersion} is offered" } ?: "no newer build is offered"))
            } catch (error: kotlinx.coroutines.CancellationException) {
                _work.value = BandWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Band firmware: check failed: ${error.message}")
                _work.value = BandWork.Failed(reason(error))
            }
        }
    }

    fun install() {
        val offer = _offer.value ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            val zip = File(File(context.cacheDir, "band-update").apply { mkdirs() }, "band.zip")
            try {
                val serial = serial()
                download(offer, zip)
                val blobs = unpack(zip)
                _work.value = BandWork.Installing()
                log.add("Band firmware: installing ${offer.targetVersion}")
                login.source.withToken { token -> link.holdFor { installOn(offer, serial, token, blobs) } }
                _offer.value = null
                _work.value = BandWork.Installed
                log.add("Band firmware: ${offer.targetVersion} is installed")
            } catch (error: kotlinx.coroutines.CancellationException) {
                _work.value = BandWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Band firmware: install failed: ${error.message}")
                _work.value = BandWork.Failed(reason(error))
            } finally {
                zip.delete()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to the start after an install ended. */
    fun dismiss() {
        if (job?.isActive != true) _work.value = BandWork.Idle
    }

    private fun serial(): String = bands.forGlasses(connection.pair.value)?.serial ?: error("no band is set up with these glasses")

    private suspend fun installOn(offer: BandOffer, serial: String, token: Token, blobs: List<Blob>) = withSession {
        val target = offer.targetVersion.toLongOrNull() ?: error("the offer's version is not a number")
        var begun = false
        try {
            if (battery() < MIN_BATTERY) error("the band's battery is below $MIN_BATTERY%")
            var info = deviceInfo()
            if (info.unavailable != Band.OTA_NO_REASON) error("the band cannot update now (reason ${info.unavailable})")
            if (info.state == Band.OTA_IN_PROGRESS) {
                fire(Band.otaAbort(link.nextSeq()))
                delay(ABORT_MS)
                info = deviceInfo()
                if (info.state == Band.OTA_IN_PROGRESS) error("the band is still in an update")
            }
            if (info.state == Band.OTA_UNAVAILABLE) error("the band is not ready for an update")

            val items = ArrayList<Item>()
            if (info.firmware != target) {
                val firmware = blobs.firstOrNull { it.manifestType == MANIFEST_FIRMWARE } ?: error("the zip has no firmware")
                items += Item(Band.OTA_ITEM_FIRMWARE, firmware)
            }
            blobs.firstOrNull { it.manifestType == MANIFEST_ML }?.let { ml ->
                if (ml.bytes.size >= MIN_ML_BYTES && target > info.ml) items += Item(Band.OTA_ITEM_ML_MODEL, ml)
            }
            if (items.isEmpty()) {
                log.add("Band firmware: already on $target")
            } else {
                offer.kekId?.takeIf { it != info.kek }?.let { kekId ->
                    val wrapped = fetchBandKek(token, serial, kekId)
                    if (wrapped.algorithm != null && wrapped.algorithm != "AES_128") log.add("Band firmware: the key names ${wrapped.algorithm}")
                    begun = true
                    val answer = call { Band.otaInstallKek(it, kekId, Base64.decode(wrapped.encrypted, Base64.NO_WRAP)) }
                    expectOk(answer, "its key")
                }
                val total = items.sumOf { it.blob.bytes.size.toLong() }
                val done = AtomicLong()
                for (item in items) {
                    begun = true
                    val size = item.blob.bytes.size.toLong()
                    expectOk(call { Band.otaStartItem(it, item.type, target, size) }, "the update")
                    transfer(item.blob) { n ->
                        val now = done.addAndGet(n.toLong())
                        _work.value = BandWork.Installing((now * 100 / total).toInt())
                    }
                    expectOk(call(VERIFY_MS) { Band.otaVerifyItem(it, item.type, target) }, "the image")
                }
                expectOk(call(VERIFY_MS) { Band.otaCommit(it) }, "the commit")
            }
        } catch (error: Throwable) {
            if (begun) fire(Band.otaAbort(link.nextSeq()))
            throw error
        }
    }

    /** One image over the bulk transfer: the info request, then 950-byte chunks in the band's window. */
    private suspend fun Session.transfer(blob: Blob, sent: (Int) -> Unit) {
        val id = transfers.getAndIncrement()
        val size = blob.bytes.size.toLong()
        val info = Band.bulk(call { Band.bulkInfo(it, blob.md5, size, id) }) as? Band.Bulk.Info
            ?: error("the band did not answer the transfer")
        if (info.transferId != id) error("the band answered another transfer")
        when (info.state) {
            Band.TRANSFER_FULL -> return
            Band.TRANSFER_NEW, Band.TRANSFER_PARTIAL -> Unit
            else -> error("the band would not take the file (state ${info.state})")
        }
        val window = (info.memory / Band.CHUNK_BYTES).toInt().coerceAtLeast(1)
        sendChunks(id, blob.bytes, info.resume.toInt(), window, sent)
    }

    private suspend fun Session.sendChunks(id: Int, bytes: ByteArray, from: Int, window: Int, sent: (Int) -> Unit) = coroutineScope {
        val slots = Semaphore(window)
        var offset = from
        while (offset < bytes.size) {
            slots.acquire()
            val at = offset
            val end = minOf(bytes.size, at + Band.CHUNK_BYTES)
            val piece = bytes.copyOfRange(at, end)
            launch {
                try {
                    chunk(id, at, piece)
                    sent(piece.size)
                } finally {
                    slots.release()
                }
            }
            offset = end
        }
    }

    /** Sends one chunk and waits for its ack; unanswered, it goes again up to three times. */
    private suspend fun Session.chunk(id: Int, at: Int, piece: ByteArray) {
        repeat(CHUNK_SENDS) {
            val key = id to at
            val ack = CompletableDeferred<Unit>()
            acks[key] = ack
            try {
                if (!link.sendRaw(listOf(Band.bulkChunk(link.nextSeq(), id, at.toLong(), piece)))) error("the band is not up")
                if (withTimeoutOrNull(CHUNK_MS) { ack.await() } != null) return
            } finally {
                acks.remove(key)
            }
        }
        error("the band did not ack the chunk at $at")
    }

    /** The answers of one held update: each request's by seq, each chunk's ack by its transfer and offset. */
    private inner class Session {
        private val replies = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
        val acks = ConcurrentHashMap<Pair<Int, Int>, CompletableDeferred<Unit>>()

        /** Sends one request and waits for its answer. */
        suspend fun call(timeout: Long = REPLY_MS, build: (Int) -> ByteArray): ByteArray {
            val n = link.nextSeq()
            val answer = CompletableDeferred<ByteArray>()
            replies[n] = answer
            try {
                if (!link.sendRaw(listOf(build(n)))) error("the band is not up")
                return withTimeoutOrNull(timeout) { answer.await() } ?: error("the band did not answer")
            } finally {
                replies.remove(n)
            }
        }

        fun fire(request: ByteArray) {
            link.sendRaw(listOf(request))
        }

        fun route(payload: ByteArray) {
            when (val bulk = runCatching { Band.bulk(payload) }.getOrNull()) {
                is Band.Bulk.Ack -> acks.remove(bulk.transferId to bulk.offset.toInt())?.complete(Unit)
                is Band.Bulk.Nack -> {
                    val refused = IllegalStateException("the band refused a chunk: ${bulk.reason.ifEmpty { "no reason" }}")
                    acks.values.forEach { it.completeExceptionally(refused) }
                    acks.clear()
                }
                else -> runCatching { Band.seqOf(payload) }.getOrNull()?.let { replies.remove(it)?.complete(payload) }
            }
        }
    }

    private suspend fun <T> withSession(block: suspend Session.() -> T): T = coroutineScope {
        val session = Session()
        val router = launch(start = CoroutineStart.UNDISPATCHED) { link.answers.collect { session.route(it) } }
        try {
            session.block()
        } finally {
            router.cancel()
        }
    }

    private suspend fun Session.deviceInfo(): Band.OtaInfo =
        Band.otaInfo(call { Band.otaDeviceInfo(it) }) ?: error("the band gave no device info")

    private suspend fun Session.battery(): Int =
        Band.response(call { Band.batteryRequest(it) }).battery?.percent ?: error("the band gave no battery")

    private fun Session.expectOk(answer: ByteArray, what: String) {
        val code = Band.otaResult(answer) ?: error("the band gave no answer for $what")
        if (code != Band.OTA_RESULT_OK) error("the band refused $what (code $code)")
    }

    private suspend fun download(offer: BandOffer, file: File) = withContext(Dispatchers.IO) {
        val c = URL(offer.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            if (c.responseCode != 200) error("the download answered ${c.responseCode}")
            c.inputStream.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (offer.checksum.isNotEmpty() && sha256(file.readBytes()) != offer.checksum.lowercase()) error("the download did not match Meta's checksum")
    }

    /** The zip's images, each checked against the SHA-256 in its manifest. They stay encrypted; the band decrypts them. */
    private fun unpack(zip: File): List<Blob> = ZipFile(zip).use { z ->
        val manifest = JSONObject(z.read(z.getEntry("manifest.json") ?: error("the zip has no manifest")).decodeToString())
        val items = manifest.getJSONArray("items")
        (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val path = item.getString("path")
            val bytes = z.read(z.getEntry(path) ?: error("the zip has no $path"))
            if (sha256(bytes) != item.getString("digest").lowercase()) error("$path does not match its digest")
            Blob(item.getInt("item_type"), bytes, MessageDigest.getInstance("MD5").digest(bytes))
        }
    }

    private fun ZipFile.read(entry: ZipEntry): ByteArray = getInputStream(entry).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun reason(error: Throwable): String = error.message ?: error::class.java.simpleName

    /** An image from the zip; [manifestType] is its item type in the manifest (1 firmware, 2 ML model). */
    private class Blob(val manifestType: Int, val bytes: ByteArray, val md5: ByteArray)

    /** An image as the band's item: [type] is the wire's item type (0 firmware, 2 ML model). */
    private class Item(val type: Int, val blob: Blob)

    private companion object {
        const val MANIFEST_FIRMWARE = 1
        const val MANIFEST_ML = 2
        const val MIN_BATTERY = 10
        const val MIN_ML_BYTES = 10240
        // A chunk is sent once and at most three more times when unanswered.
        const val CHUNK_SENDS = 4
        // Guesses: nothing in the Meta app's code sets these times.
        const val CHUNK_MS = 3_000L
        const val REPLY_MS = 5_000L
        const val VERIFY_MS = 60_000L
        const val ABORT_MS = 2_000L
    }
}
