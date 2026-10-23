package com.macedonga.visor.firmware

import com.macedonga.visor.core.identity.token.withToken
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.firmware.FirmwareOffer
import com.macedonga.visor.core.identity.firmware.fetchFirmwareOffer
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface FirmwareWork {
    data object Idle : FirmwareWork
    /** [percent] is null until the glasses report some progress. */
    data class Installing(val percent: Int? = null) : FirmwareWork
    data object Installed : FirmwareWork
    data class Failed(val reason: String) : FirmwareWork
}

/**
 * The glasses' firmware from Meta. [check] asks Meta for the newest full image; [install] sends its address to the
 * glasses over svc29 (the OTA service), and the glasses fetch the image over their own Wi-Fi. If they ask for the
 * image over Bluetooth instead, the install stops: that fallback is not built.
 */
@Singleton
class FirmwareUpdate @Inject constructor(
    private val login: MetaLogin,
    private val connection: GlassesConnection,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    init {
        // An offer is for one pair's build: a different pair starts over.
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().drop(1).collect {
                _offer.value = null
                _checked.value = false
                _problem.value = ""
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
                val firmware = parts.getOrNull(4)?.substringBefore(':').orEmpty()
                val type = parts.getOrNull(4)?.substringAfter(':', "user").orEmpty()
                val product = parts.getOrNull(1).orEmpty()
                if (firmware.isEmpty() || product.isEmpty()) error("the glasses' build is unknown")
                val found = login.source.withToken { fetchFirmwareOffer(it, info.serial, "ota.$product.$type", firmware) }
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
            try {
                if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                _work.value = FirmwareWork.Installing()
                log.add("Firmware: installing ${offer.targetVersion} on the glasses")
                val why = runCatching { installOn(offer) }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
                if (why != null) error(why)
                _offer.value = null
                _work.value = FirmwareWork.Installed
                log.add("Firmware: ${offer.targetVersion} is installed, the glasses restart")
            } catch (error: kotlinx.coroutines.CancellationException) {
                _work.value = FirmwareWork.Idle
                throw error
            } catch (error: Throwable) {
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

    private suspend fun installOn(offer: FirmwareOffer) = coroutineScope {
        val fromGlasses = Channel<ServiceData>(Channel.UNLIMITED)
        // Subscribed before the first message goes out, so no answer is missed.
        val listening = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.serviceData.collect { if (it.serviceId == SVC_OTA) fromGlasses.send(it) }
        }
        suspend fun next(type: Int, ms: Long): ServiceData? = withTimeoutOrNull(ms) {
            var m: ServiceData
            do {
                m = fromGlasses.receive()
                when (m.messageType) {
                    MT_ERROR -> error(errorWords(m.payload))
                    MT_FILE_STATUS -> error("the glasses asked for the image over Bluetooth, not over their Wi-Fi")
                    MT_PROGRESS -> progress(m.payload)
                }
            } while (m.messageType != type)
            m
        }
        try {
            val install = UUID.randomUUID().toString()
            val megabytes = (offer.size / 1_000_000 + 1) * 2
            val start = Wire.fieldVarint(1, APP_OTA) + Wire.fieldBytes(2, Wire.fieldBytes(1, Wire.fieldVarint(1, megabytes))) +
                intent("ota_type", Wire.fieldBytes(5, str("firmware"))) + intent("attempt_id", Wire.fieldBytes(5, str(install)))
            connection.submit { it.sendOnService(SVC_OTA, MT_START_APP, start) }
            val started = next(MT_START_APP_RESPONSE, ANSWER_MS) ?: error("the glasses' OTA service did not answer")
            val code = runCatching { Wire.parse(started.payload).varint(1) }.getOrDefault(0)
            // Already started: its workflow is up, and still takes the request.
            if (code != SNAPP_SUCCESS && code != SNAPP_ALREADY_STARTED) error("the glasses would not start an install (code $code)")

            val key = Wire.fieldBytes(1, str(offer.sessionId)) + Wire.fieldBytes(2, str(install))
            val request = Wire.fieldBytes(1, key) +
                Wire.fieldBytes(3, str(offer.baseVersion)) +
                Wire.fieldBytes(4, str(offer.targetVersion)) +
                Wire.fieldVarint(5, payloadSize(offer)) +
                Wire.fieldVarint(6, offer.payloadOffset) +
                (if (offer.metaHeaders.isNotEmpty()) Wire.fieldBytes(7, str(offer.metaHeaders)) else ByteArray(0)) +
                Wire.fieldBytes(10, str(offer.url)) +
                (offer.kekId?.let { Wire.fieldVarint(11, it) } ?: ByteArray(0)) +
                (if (offer.kekHash.isNotEmpty()) Wire.fieldBytes(12, str(offer.kekHash)) else ByteArray(0))
            connection.submit { it.sendOnService(SVC_OTA, MT_INSTALL, request) }
            _work.value = FirmwareWork.Installing()
            next(MT_INSTALL_COMPLETE, INSTALL_MS) ?: error("the glasses did not finish installing it")
        } finally {
            listening.cancel()
        }
    }

    // The payload's size is the FILE_SIZE line of the install headers, as the Meta app reads it; else the image's size.
    private fun payloadSize(offer: FirmwareOffer): Long =
        offer.metaHeaders.lineSequence().firstOrNull { it.startsWith("FILE_SIZE") }?.substringAfter('=')?.trim()?.toLongOrNull() ?: offer.size

    /** Progress for the sheet: the percentage the glasses give, when it is in range. */
    private fun progress(payload: ByteArray) {
        val f = runCatching { Wire.parse(payload) }.getOrNull() ?: return
        if (!f.has(3)) return
        val percent = f.float(3).toInt()
        if (percent in 0..100) _work.value = FirmwareWork.Installing(percent)
    }

    private fun errorWords(payload: ByteArray): String = runCatching {
        val f = Wire.parse(payload)
        val stage = STAGES.getOrElse(f.varint(2).toInt() - 1) { "stage ${f.varint(2)}" }
        "the glasses stopped the install at $stage, code ${f.varint(3)}, error ${f.varint(5)}" +
            if (f.varint(4) == 1L) " (retry)" else ""
    }.getOrDefault("the glasses stopped the install")

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
        // The OTA app, as the Meta app numbers it for svc29 starts.
        const val APP_OTA = 1L
        const val SNAPP_SUCCESS = 1L
        const val SNAPP_ALREADY_STARTED = 12L
        const val ANSWER_MS = 15_000L
        // A download over the glasses' Wi-Fi and the A/B apply: a guess, no timing was read.
        const val INSTALL_MS = 60 * 60_000L
        // OtaUpdateInstallStage, in the order the Meta app numbers them from 1.
        val STAGES = listOf("transfer", "apply", "reboot", "stopped", "startup", "complete", "download")
    }
}
