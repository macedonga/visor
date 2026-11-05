package com.macedonga.visor.band

import android.content.Context
import android.util.Base64
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.objects
import com.macedonga.visor.core.connection.parseBuild
import com.macedonga.visor.core.identity.firmware.FirmwareOffer
import com.macedonga.visor.core.identity.firmware.fetchBandKek
import com.macedonga.visor.core.identity.firmware.fetchFirmwareOffer
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.identity.token.Token
import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.language.downloadPack
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed interface BandWork {
    data object Idle : BandWork
    data object Checking : BandWork
    /** [percent] is null until the band acks the first chunk. */
    data class Installing(val percent: Int? = null) : BandWork
    data object Installed : BandWork
    data class Failed(val why: Why) : BandWork

    enum class Why { NO_GLASSES, NO_BAND, NO_ANSWER, LOW_BATTERY, FAILED }
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
    private val _offer = MutableStateFlow<FirmwareOffer?>(null)
    val offer: StateFlow<FirmwareOffer?> = _offer.asStateFlow()
    private val _work = MutableStateFlow<BandWork>(BandWork.Idle)
    val work: StateFlow<BandWork> = _work.asStateFlow()
    private val transfers = AtomicInteger(1)
    private var job: Job? = null

    private class Stop(val why: BandWork.Why, message: String) : Exception(message)

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
                val display = connection.state.value.info ?: throw Stop(BandWork.Why.NO_GLASSES, "the glasses gave no build")
                val build = parseBuild(display.build)?.takeIf { it.firmware.isNotEmpty() } ?: error("the glasses' build is unknown")
                val firmware = link.holdFor { withSession { deviceInfo().firmware } }
                // Meta's server gets a version of at least 1 and the Display the band goes with.
                val found = login.source.withToken {
                    fetchFirmwareOffer(
                        it, serial, BAND_DEVICE_TYPE, firmware.coerceAtLeast(1).toString(), fullOnly = false,
                        constellationType = build.otaType, constellationFirmware = build.firmware, constellationSerial = display.serial,
                    )
                }?.takeUnless { it.targetVersion == firmware.toString() }
                _offer.value = found
                _checked.value = true
                _work.value = BandWork.Idle
                log.add("Band firmware: " + (found?.let { "${it.targetVersion} is offered" } ?: "no newer build is offered"))
            } catch (error: CancellationException) {
                _work.value = BandWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Band firmware: check failed: ${error.message}")
                _work.value = BandWork.Failed(why(error))
            }
        }
    }

    fun install() {
        val offer = _offer.value ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _work.value = BandWork.Installing()
            val zip = File(File(context.cacheDir, "band-update").apply { mkdirs() }, "${offer.targetVersion}.zip")
            try {
                val serial = serial()
                val pack = PackOffer(feature = "", resource = offer.targetVersion, version = offer.targetVersion, url = offer.url, size = offer.size, sha256 = offer.checksum)
                downloadPack(pack, zip, SPARE_BYTES) {}
                val blobs = unpack(zip)
                log.add("Band firmware: installing ${offer.targetVersion}")
                login.source.withToken { token -> link.holdFor { installOn(offer, serial, token, blobs) } }
                _offer.value = null
                _work.value = BandWork.Installed
                log.add("Band firmware: ${offer.targetVersion} is installed")
            } catch (error: CancellationException) {
                _work.value = BandWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Band firmware: install failed: ${error.message}")
                _work.value = BandWork.Failed(why(error))
            } finally {
                zip.delete()
                File(zip.path + ".part").delete()
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

    private fun serial(): String = bands.forGlasses(connection.pair.value)?.serial ?: throw Stop(BandWork.Why.NO_BAND, "no band is set up with these glasses")

    private suspend fun installOn(offer: FirmwareOffer, serial: String, token: Token, blobs: List<Blob>) = withSession {
        val target = offer.targetVersion.toLongOrNull() ?: error("the offer's version is not a number")
        var begun = false
        val committed = try {
            if (battery() < MIN_BATTERY) throw Stop(BandWork.Why.LOW_BATTERY, "the band's battery is below $MIN_BATTERY%")
            var info = deviceInfo()
            // An update under way, or one received for another build, is aborted; one received for the target goes on.
            if (info.state == Band.OTA_IN_PROGRESS || (info.state == Band.OTA_UPDATE_READY && info.latest != target)) {
                fire(Band.otaAbort(link.nextSeq()))
                delay(ABORT_MS)
                info = deviceInfo()
                if (info.state == Band.OTA_IN_PROGRESS) error("the band is still in an update")
            }
            if (info.state == Band.OTA_UNAVAILABLE) throw unavailable(info)
            // Recovery firmware takes an image whatever its version.
            val skipCheck = info.device == Band.OTA_DEVICE_RECOVERY

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
                false
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
                    expectOk(call { Band.otaStartItem(it, item.type, target, size, skipCheck) }, "the update")
                    try {
                        transfer(item.blob) { n ->
                            val now = done.addAndGet(n.toLong())
                            _work.value = BandWork.Installing((now * 100 / total).toInt())
                        }
                    } catch (failure: Exception) {
                        // A band that stops taking the file may be low on power: its device info says so.
                        if (failure !is CancellationException) {
                            val now = runCatching { deviceInfo() }.getOrNull()
                            if (now != null && now.state == Band.OTA_UNAVAILABLE && now.unavailable == Band.OTA_POWER_LIMITATION) throw unavailable(now)
                        }
                        throw failure
                    }
                    expectOk(call(VERIFY_MS) { Band.otaVerifyItem(it, item.type, target) }, "the image")
                }
                expectOk(call(VERIFY_MS) { Band.otaCommit(it) }, "the commit")
                true
            }
        } catch (error: Throwable) {
            if (begun) fire(Band.otaAbort(link.nextSeq()))
            throw error
        }
        if (committed) confirm(target)
    }

    /** After the commit the band restarts: wait for it to come back, then check it runs [target]. */
    private suspend fun Session.confirm(target: Long) {
        log.add("Band firmware: waiting for the band to restart")
        withTimeoutOrNull(RESTART_MS) { link.state.first { it.link != LinkState.READY } }
        withTimeoutOrNull(BACK_MS) { link.state.first { it.link == LinkState.READY } } ?: throw Stop(BandWork.Why.NO_ANSWER, "the band did not come back after its restart")
        val now = deviceInfo().firmware
        if (now != target) error("the band runs $now, not $target")
    }

    private fun unavailable(info: Band.OtaInfo): Exception =
        if (info.unavailable == Band.OTA_POWER_LIMITATION) Stop(BandWork.Why.LOW_BATTERY, "the band's battery is too low")
        else IllegalStateException("the band cannot update now (reason ${info.unavailable})")

    /** One image over the bulk transfer: the info request, then 950-byte chunks in the band's window. */
    private suspend fun Session.transfer(blob: Blob, sent: (Int) -> Unit) {
        val id = transfers.getAndIncrement()
        val size = blob.bytes.size.toLong()
        val info = Band.bulk(call { Band.bulkInfo(it, blob.md5, size, id) }) as? Band.Bulk.Info
            ?: error("the band did not answer the transfer")
        if (info.transferId != id) error("the band answered another transfer")
        when (info.state) {
            Band.BULK_FULL -> return sent(blob.bytes.size)
            Band.BULK_NEW, Band.BULK_PARTIAL -> Unit
            else -> error("the band would not take the file (state ${info.state})")
        }
        val window = (info.memory / Band.CHUNK_BYTES).toInt().coerceAtLeast(1)
        // What the band already holds counts as sent.
        if (info.resume > 0) sent(info.resume.toInt())
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
                if (!link.sendRaw(listOf(Band.bulkChunk(link.nextSeq(), id, at.toLong(), piece)))) throw BandLink.BandAway()
                if (withTimeoutOrNull(CHUNK_MS) { ack.await() } != null) return
            } finally {
                acks.remove(key)
            }
        }
        throw Stop(BandWork.Why.NO_ANSWER, "the band did not ack the chunk at $at")
    }

    /** The chunk acks of one held update, by transfer and offset. */
    private inner class Session {
        val acks = ConcurrentHashMap<Pair<Int, Int>, CompletableDeferred<Unit>>()

        /** Sends one request and waits for its answer. */
        suspend fun call(timeout: Long = REPLY_MS, build: (Int) -> ByteArray): ByteArray =
            link.request(timeout, build) ?: throw Stop(BandWork.Why.NO_ANSWER, "the band did not answer")

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
                else -> Unit
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

    /** The zip's images, each checked against the SHA-256 in its manifest. They stay encrypted; the band decrypts them. */
    private fun unpack(zip: File): List<Blob> = ZipFile(zip).use { z ->
        val manifest = JSONObject(z.read(z.getEntry("manifest.json") ?: error("the zip has no manifest")).decodeToString())
        val items = manifest.getJSONArray("items")
        items.objects().map { item ->
            val path = item.getString("path")
            val bytes = z.read(z.getEntry(path) ?: error("the zip has no $path"))
            if (sha256(bytes) != item.getString("digest").lowercase()) error("$path does not match its digest")
            Blob(item.getInt("item_type"), bytes, MessageDigest.getInstance("MD5").digest(bytes))
        }
    }

    private fun ZipFile.read(entry: ZipEntry): ByteArray = getInputStream(entry).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun why(error: Throwable) = when (error) {
        is Stop -> error.why
        is BandLink.BandAway -> BandWork.Why.NO_ANSWER
        else -> BandWork.Why.FAILED
    }

    /** An image from the zip; [manifestType] is its item type in the manifest (1 firmware, 2 ML model). */
    private class Blob(val manifestType: Int, val bytes: ByteArray, val md5: ByteArray)

    /** An image as the band's item: [type] is the wire's item type (0 firmware, 2 ML model). */
    private class Item(val type: Int, val blob: Blob)

    private companion object {
        const val BAND_DEVICE_TYPE = "ota.ceres.user"
        const val MANIFEST_FIRMWARE = 1
        const val MANIFEST_ML = 2
        const val MIN_BATTERY = 10
        const val MIN_ML_BYTES = 10240
        const val SPARE_BYTES = 50_000_000L
        // A chunk is sent once and at most three more times when unanswered.
        const val CHUNK_SENDS = 4
        // Guessed timeouts.
        const val CHUNK_MS = 3_000L
        const val REPLY_MS = 5_000L
        const val VERIFY_MS = 60_000L
        const val ABORT_MS = 2_000L
        const val RESTART_MS = 30_000L
        const val BACK_MS = 180_000L
    }
}
