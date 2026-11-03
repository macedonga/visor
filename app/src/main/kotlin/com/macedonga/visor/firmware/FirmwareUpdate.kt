package com.macedonga.visor.firmware

import android.content.Context
import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.identity.firmware.FirmwareOffer
import com.macedonga.visor.core.identity.firmware.fetchFirmwareOffer
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.language.downloadPack
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface FirmwareWork {
    data object Idle : FirmwareWork
    /** The image coming to the phone: bytes so far of [total]. */
    data class Downloading(val done: Long, val total: Long) : FirmwareWork
    /** The image going to the glasses over the link: bytes so far of [total]. */
    data class Sending(val done: Long, val total: Long) : FirmwareWork
    /** [percent] is null until the glasses report some progress. */
    data class Installing(val percent: Int? = null) : FirmwareWork
    data object Installed : FirmwareWork
    data class Failed(val reason: String) : FirmwareWork
}

/**
 * The glasses' firmware from Meta, through the glasses' OTA service (svc29). [check] asks Meta for the newest image.
 * [install]: other glasses get its address and fetch it over their own Wi-Fi. The Display gets it as the Meta app sends
 * it without Wi-Fi: the phone downloads the zip, starts the OTA app, sends the install request with the zip's id, and
 * when the glasses ask for it sends the zip on airship `ota`; the glasses check it, apply it and restart.
 */
@Singleton
class FirmwareUpdate @Inject constructor(
    @ApplicationContext context: Context,
    private val login: MetaLogin,
    private val connection: GlassesConnection,
    private val airship: AirshipReceiver,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "firmware")

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    /** Meta answered for the glasses' current build, offer or none. */
    private val _checked = MutableStateFlow(false)
    val checked: StateFlow<Boolean> = _checked.asStateFlow()
    private val _offer = MutableStateFlow<FirmwareOffer?>(null)
    val offer: StateFlow<FirmwareOffer?> = _offer.asStateFlow()
    private val _problem = MutableStateFlow("")
    val problem: StateFlow<String> = _problem.asStateFlow()
    private val _work = MutableStateFlow<FirmwareWork>(FirmwareWork.Idle)
    val work: StateFlow<FirmwareWork> = _work.asStateFlow()
    private var job: Job? = null

    // A delta that failed to apply: the next check asks for the full image.
    @Volatile
    private var fullNext = false

    // The build a Display was restarting into, to compare with the one it comes back on.
    @Volatile
    private var rebooting: String? = null

    // Whether the offer is for a Display, as its check found it.
    @Volatile
    private var forDisplay = false

    init {
        // An offer is for one pair's build: a different pair starts over.
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().drop(1).collect {
                _offer.value = null
                _checked.value = false
                _problem.value = ""
                fullNext = false
                rebooting = null
            }
        }
        // Each connection says the build afresh, so the one after the restart comes through here.
        scope.launch {
            connection.state.map { it.info?.build }.distinctUntilChanged().filterNotNull().collect { build ->
                val target = rebooting ?: return@collect
                rebooting = null
                val now = firmwareOf(build)
                log.add(if (now == target) "Firmware: the glasses run $target" else "Firmware: the glasses came back on $now, not $target")
                if (now != target && _work.value == FirmwareWork.Installed) _work.value = FirmwareWork.Failed("the glasses came back on $now")
            }
        }
    }

    fun check() {
        if (_checking.value || job?.isActive == true) return
        scope.launch {
            _checking.value = true
            try {
                val info = connection.state.value.info ?: error("connect the glasses once, so they say which they are")
                // Meta/greatwhite/greatwhite:14/UKQ1.250303.001/69951180085500080:user/release-keys
                val parts = info.build.split('/')
                val firmware = firmwareOf(info.build)
                val type = parts.getOrNull(4)?.substringAfter(':', "user").orEmpty()
                val product = parts.getOrNull(1).orEmpty()
                if (firmware.isEmpty() || product.isEmpty()) error("the glasses' build is unknown")
                val display = isDisplayBuild(info.build)
                // The Display asks for a delta as the Meta app does; other glasses keep asking for the full image.
                val found = login.source.withToken { fetchFirmwareOffer(it, info.serial, "ota.$product.$type", firmware, fullOnly = !display || fullNext) }
                    // Meta also offers the Display the build it already runs, which the glasses would refuse.
                    ?.takeUnless { display && it.targetVersion == firmware }
                forDisplay = display
                _offer.value = found
                _problem.value = ""
                _checked.value = true
                log.add("Firmware: " + (found?.let { "${it.targetVersion} is offered" } ?: "no newer build is offered"))
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _problem.value = error.message ?: error::class.java.simpleName
                log.add("Firmware: check failed: ${error.message}")
            } finally {
                _checking.value = false
            }
        }
    }

    fun install() {
        val offer = _offer.value ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            var started = false
            try {
                if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                if (forDisplay) {
                    val zip = download(offer)
                    val transfer = transferId(zip)
                    log.add("Firmware: installing ${offer.targetVersion} on the glasses over the link")
                    var drops = 0
                    while (true) {
                        if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                        started = true
                        val failure = runCatching { installOn(offer, zip to transfer) }.exceptionOrNull() ?: break
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        // A drop ends the glasses' side too; they take the same zip again from the start.
                        if (connection.state.value.link == LinkState.READY || ++drops > RETRIES) throw failure
                        log.add("Firmware: the link dropped (${failure.message}); starting again when the glasses are back")
                        _work.value = FirmwareWork.Installing()
                        withTimeoutOrNull(RECONNECT_MS) { connection.state.first { it.link == LinkState.READY } } ?: throw failure
                    }
                    zip.delete()
                    idFile(zip).delete()
                    rebooting = offer.targetVersion
                } else {
                    _work.value = FirmwareWork.Installing()
                    log.add("Firmware: installing ${offer.targetVersion} on the glasses")
                    val why = runCatching { installOn(offer, null) }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
                    if (why != null) error(why)
                }
                _offer.value = null
                _work.value = FirmwareWork.Installed
                log.add("Firmware: ${offer.targetVersion} is installed, the glasses restart")
            } catch (error: kotlinx.coroutines.CancellationException) {
                // The OTA app on the glasses stops too, or it keeps waiting for the image.
                if (started) connection.submit { it.sendOnService(SVC_OTA, MT_STOP_APP, Wire.fieldVarint(1, APP_OTA)) }
                _work.value = FirmwareWork.Idle
                throw error
            } catch (error: Throwable) {
                if (error is InstallError && error.stage == STAGE_APPLY) fullNext = true
                log.add("Firmware: install failed: ${error.message}")
                _work.value = FirmwareWork.Failed(error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to the start after an install ended. */
    fun dismiss() {
        if (job?.isActive != true) _work.value = FirmwareWork.Idle
    }

    /** The zip in the app's files, resumed where an earlier try stopped and checked against Meta's SHA-256. */
    private suspend fun download(offer: FirmwareOffer): File {
        dir.mkdirs()
        val zip = File(dir, "${offer.targetVersion}.zip")
        // Another build's zip is of no use any more.
        dir.listFiles()?.filterNot { it.name.startsWith("${offer.targetVersion}.") }?.forEach { it.delete() }
        if (zip.exists()) return zip
        val part = File(zip.path + ".part")
        val need = offer.size - (if (part.exists()) part.length() else 0L)
        if (dir.usableSpace < need + SPARE_BYTES) error("the phone needs ${(need + SPARE_BYTES) / 1_000_000} MB free")
        log.add("Firmware: downloading ${offer.targetVersion}, ${offer.size / 1_000_000} MB")
        _work.value = FirmwareWork.Downloading(offer.size - need, offer.size)
        val pack = PackOffer(feature = "", resource = offer.targetVersion, version = offer.targetVersion, url = offer.url, size = offer.size, sha256 = offer.checksum)
        downloadPack(pack, zip) { _work.value = FirmwareWork.Downloading(it, offer.size) }
        return zip
    }

    // The id the zip goes to the glasses under, kept beside it until the install ends: a retry names the same file.
    private fun transferId(zip: File): UUID {
        val file = idFile(zip)
        runCatching { UUID.fromString(file.readText().trim()) }.getOrNull()?.let { return it }
        return UUID.randomUUID().also { file.writeText(it.toString()) }
    }

    private fun idFile(zip: File) = File(zip.path.removeSuffix(".zip") + ".id")

    /** One install. [image] is the Display's zip and its id, sent over the link; without it the glasses fetch the URL. */
    private suspend fun installOn(offer: FirmwareOffer, image: Pair<File, UUID>?) = coroutineScope {
        // The Display's whole exchange goes on one session, so a drop shows as a failure, not as a mix of two.
        val link = if (image == null) null else {
            val held = CompletableDeferred<DataXConnection>()
            connection.submit { held.complete(it) }
            withTimeoutOrNull(ANSWER_MS) { held.await() } ?: error("the glasses are not connected")
        }
        suspend fun tell(type: Int, payload: ByteArray) {
            if (link != null) link.sendOnService(SVC_OTA, type, payload) else connection.submit { it.sendOnService(SVC_OTA, type, payload) }
        }
        val fromGlasses = Channel<ServiceData>(Channel.UNLIMITED)
        // Subscribed before the first message goes out, so no answer is missed.
        val listening = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.serviceData.collect { if (it.serviceId == SVC_OTA) fromGlasses.send(it) }
        }
        suspend fun next(ms: Long, wanted: (ServiceData) -> Boolean): ServiceData? = withTimeoutOrNull(ms) {
            var m: ServiceData
            do {
                m = fromGlasses.receive()
                when (m.messageType) {
                    MT_ERROR -> throw installError(m.payload)
                    MT_FILE_STATUS -> if (image == null) error("the glasses asked for the image over Bluetooth, not over their Wi-Fi")
                    MT_PROGRESS -> progress(m.payload)
                }
            } while (!wanted(m))
            m
        }
        try {
            val install = UUID.randomUUID().toString()
            val megabytes = if (image == null) (offer.size / 1_000_000 + 1) * 2 else (offer.payloadSize + MIB - 1) / MIB + SPACE_MARGIN_MB
            val start = Wire.fieldVarint(1, APP_OTA) + Wire.fieldBytes(2, Wire.fieldBytes(1, Wire.fieldVarint(1, megabytes))) +
                intent("ota_type", Wire.fieldBytes(5, str("firmware"))) + intent("attempt_id", Wire.fieldBytes(5, str(install)))
            tell(MT_START_APP, start)
            val started = next(ANSWER_MS) { it.messageType == MT_START_APP_RESPONSE } ?: error("the glasses' OTA service did not answer")
            val code = runCatching { Wire.parse(started.payload).varint(1) }.getOrDefault(0)
            // Already started: its workflow is up, and still takes the request.
            if (code != SNAPP_SUCCESS && code != SNAPP_ALREADY_STARTED) error("the glasses would not start an install (code $code)")

            val key = Wire.fieldBytes(1, str(offer.sessionId)) + Wire.fieldBytes(2, str(install))
            val raw = image?.second?.let { ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() }
            val request = Wire.fieldBytes(1, key) +
                (raw?.let { Wire.fieldBytes(2, it) } ?: ByteArray(0)) +
                Wire.fieldBytes(3, str(offer.baseVersion)) +
                Wire.fieldBytes(4, str(offer.targetVersion)) +
                Wire.fieldVarint(5, offer.payloadSize) +
                Wire.fieldVarint(6, offer.payloadOffset) +
                (if (offer.metaHeaders.isNotEmpty()) Wire.fieldBytes(7, str(offer.metaHeaders)) else ByteArray(0)) +
                (if (image == null) Wire.fieldBytes(10, str(offer.url)) else ByteArray(0)) +
                (offer.kekId?.let { Wire.fieldVarint(11, it) } ?: ByteArray(0)) +
                (if (offer.kekHash.isNotEmpty()) Wire.fieldBytes(12, str(offer.kekHash)) else ByteArray(0)) +
                (if (image != null && offer.releaseChannelId.isNotEmpty()) Wire.fieldBytes(14, str(offer.releaseChannelId)) else ByteArray(0))
            tell(MT_INSTALL, request)
            _work.value = FirmwareWork.Installing()

            // One reader from here on, beside the transfer: an error the glasses send meanwhile stops it.
            val asked = CompletableDeferred<ServiceData>()
            val end = async {
                next(Long.MAX_VALUE) { m ->
                    if (m.messageType == MT_FILE_STATUS) asked.complete(m)
                    m.messageType == MT_INSTALL_COMPLETE || m.messageType == MT_PROGRESS && stage(m.payload) == STAGE_REBOOT
                }
            }
            if (image != null && link != null) {
                val status = withTimeoutOrNull(FILE_ASK_MS) { asked.await() }?.let { runCatching { Wire.parse(it.payload).varint(2) }.getOrDefault(0L) }
                    ?: if (end.isCompleted) FILE_HAVE else error("the glasses did not ask for the image")
                when (status) {
                    FILE_HAVE -> log.add("Firmware: the glasses already have the image")
                    FILE_SEND, FILE_WAITING -> {
                        val (zip, id) = image
                        _work.value = FirmwareWork.Sending(0, zip.length())
                        airship.send(link, NAMESPACE, "$id.zip", zip, uuid = raw) { _work.value = FirmwareWork.Sending(it, zip.length()) }
                            ?.let { error("the image did not reach the glasses: $it") }
                        _work.value = FirmwareWork.Installing()
                    }
                    else -> error("the glasses gave file status $status")
                }
            }
            withTimeoutOrNull(INSTALL_MS) { end.await() } ?: error("the glasses did not finish installing it")
        } finally {
            listening.cancel()
        }
    }

    /** Progress for the sheet: the glasses' fraction (a double from 0 to 1) while they apply or download it. */
    private fun progress(payload: ByteArray) {
        val f = runCatching { Wire.parse(payload) }.getOrNull() ?: return
        if (!f.has(3) || f.varint(2) == STAGE_TRANSFER) return
        val fraction = f.double(3, f.float(3).toDouble())
        val percent = (fraction * 100).toInt()
        if (percent in 0..100) _work.value = FirmwareWork.Installing(percent)
    }

    private fun stage(payload: ByteArray): Long = runCatching { Wire.parse(payload).varint(2) }.getOrDefault(0L)

    private fun installError(payload: ByteArray): InstallError = runCatching {
        val f = Wire.parse(payload)
        val stage = STAGES.getOrElse(f.varint(2).toInt() - 1) { "stage ${f.varint(2)}" }
        InstallError(
            f.varint(2),
            "the glasses stopped the install at $stage, code ${f.varint(3)}, error ${f.varint(5)}" + if (f.varint(4) == 1L) " (retry)" else "",
        )
    }.getOrDefault(InstallError(0, "the glasses stopped the install"))

    private class InstallError(val stage: Long, message: String) : Exception(message)

    private fun firmwareOf(build: String): String = build.split('/').getOrNull(4)?.substringBefore(':').orEmpty()

    private fun str(s: String) = s.encodeToByteArray()

    private fun intent(key: String, value: ByteArray) = Wire.fieldBytes(3, Wire.fieldBytes(1, str(key)) + value)

    private companion object {
        const val SVC_OTA = 29
        const val MT_INSTALL = 1
        const val MT_PROGRESS = 2
        const val MT_ERROR = 3
        const val MT_FILE_STATUS = 4
        const val MT_INSTALL_COMPLETE = 7
        const val MT_START_APP = 2000
        const val MT_START_APP_RESPONSE = 2001
        const val MT_STOP_APP = 2002
        // The OTA app, as the Meta app numbers it for svc29 starts.
        const val APP_OTA = 1L
        const val SNAPP_SUCCESS = 1L
        const val SNAPP_ALREADY_STARTED = 12L
        // OtaUpdateFileStatus: the glasses hold it, want it now, or will want it.
        const val FILE_HAVE = 1L
        const val FILE_SEND = 2L
        const val FILE_WAITING = 3L
        const val STAGE_TRANSFER = 1L
        const val STAGE_APPLY = 2L
        const val STAGE_REBOOT = 3L
        const val NAMESPACE = "ota"
        const val MIB = 1_048_576L
        // Over the payload's size for the glasses' storage check; Meta's own margin is a MobileConfig value not read.
        const val SPACE_MARGIN_MB = 100L
        const val SPARE_BYTES = 200_000_000L
        const val ANSWER_MS = 15_000L
        const val FILE_ASK_MS = 30_000L
        // After the image is there: the check and the A/B apply. A guess, no timing was read.
        const val INSTALL_MS = 60 * 60_000L
        const val RETRIES = 3
        const val RECONNECT_MS = 5 * 60_000L
        // OtaUpdateInstallStage, in the order the Meta app numbers them from 1.
        val STAGES = listOf("transfer", "apply", "reboot", "stopped", "startup", "complete", "download")
    }
}
