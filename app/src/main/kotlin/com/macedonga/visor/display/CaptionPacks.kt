package com.macedonga.visor.display

import android.content.Context
import com.macedonga.visor.core.connection.CaptionsMode
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.language.LanguageInstaller
import com.macedonga.visor.language.downloadPack
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.pairs.pairPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PackStep { DOWNLOADING, SENDING, INSTALLING }

sealed interface PackWork {
    data object Idle : PackWork
    data class Working(val feature: String, val step: PackStep, val done: Long = 0, val total: Long = 0) : PackWork
    data class Failed(val feature: String, val reason: String) : PackWork
}

/**
 * The Display's captions and translation run only with Meta's pack for the language, a constellation feature
 * (`air.live_captions_en`, `air.translation_en_it`) the glasses' feature manager must have on. Installed as the Meta
 * app installs it, through the glasses' OTA service (svc29): StartApp for the OTA app, the install request naming the
 * pack, the zip on airship `ota` under the id the request gave it, then a feature change that turns it on. The
 * glasses do not say which they have, so this phone remembers what it installed.
 */
@Singleton
class CaptionPacks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languages: LanguageInstaller,
    private val connection: GlassesConnection,
    private val live: LiveStream,
    private val airship: AirshipReceiver,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var prefs = pairPrefs(context, "caption_packs", connection.pair.value)
    private val dir = File(context.filesDir, "packs")

    private val _installed = MutableStateFlow(prefs.getStringSet(INSTALLED, null).orEmpty())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()
    private val _offers = MutableStateFlow<Map<String, PackOffer>>(emptyMap())
    val offers: StateFlow<Map<String, PackOffer>> = _offers.asStateFlow()
    private val _work = MutableStateFlow<PackWork>(PackWork.Idle)
    val work: StateFlow<PackWork> = _work.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().drop(1).collect { serial ->
                prefs = pairPrefs(context, "caption_packs", serial)
                _installed.value = prefs.getStringSet(INSTALLED, null).orEmpty()
                _offers.value = emptyMap()
            }
        }
    }

    /** Ask Meta about [feature]'s pack, for its size. */
    fun look(feature: String) {
        if (feature in _offers.value) return
        scope.launch {
            runCatching { languages.offersFor(listOf(FEATURE + feature)) }.getOrNull()?.firstOrNull()
                ?.let { offer -> _offers.update { it + (feature to offer) } }
        }
    }

    fun install(feature: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                _work.value = PackWork.Working(feature, PackStep.DOWNLOADING)
                val offer = _offers.value[feature] ?: languages.offersFor(listOf(FEATURE + feature)).firstOrNull()
                    ?: error("Meta has no pack for these glasses")
                dir.mkdirs()
                val zip = File(dir, offer.resource + ".zip")
                if (!zip.exists()) {
                    if (dir.usableSpace < offer.size + SPARE_BYTES) error("the phone needs ${(offer.size + SPARE_BYTES) / 1_000_000} MB free")
                    log.add("Captions: downloading ${offer.resource} ${offer.version}")
                    downloadPack(offer, zip) { _work.value = PackWork.Working(feature, PackStep.DOWNLOADING, it, offer.size) }
                }
                if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                log.add("Captions: installing ${zip.name} on the glasses over Wi-Fi")
                val why = live.overWifi { mux -> runCatching { installOn(mux, feature, offer, zip) }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName } }
                if (why != null) error(why)
                zip.delete()
                _installed.update { it + feature }
                prefs.edit().putStringSet(INSTALLED, _installed.value).apply()
                log.add("Captions: ${offer.resource} is on the glasses")
                _work.value = PackWork.Idle
            } catch (error: kotlinx.coroutines.CancellationException) {
                _work.value = PackWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Captions: $feature failed: ${error.message}")
                _work.value = PackWork.Failed(feature, error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun installOn(mux: DataXConnection, feature: String, offer: PackOffer, zip: File) = coroutineScope {
        val fromGlasses = Channel<ServiceData>(Channel.UNLIMITED)
        val listening = launch { live.wifiData.collect { if (it.serviceId == SVC_OTA) fromGlasses.send(it) } }
        suspend fun next(type: Int, ms: Long): ServiceData? = withTimeoutOrNull(ms) {
            var m: ServiceData
            do {
                m = fromGlasses.receive()
                if (m.messageType == MT_ERROR) error("the glasses stopped the install: ${errorWords(m.payload)}")
            } while (m.messageType != type)
            m
        }
        try {
            val artifact = UUID.randomUUID()
            val raw = ByteBuffer.allocate(16).putLong(artifact.mostSignificantBits).putLong(artifact.leastSignificantBits).array()
            val session = UUID.randomUUID().toString()
            val key = Wire.fieldBytes(1, str(UUID.randomUUID().toString())) + Wire.fieldBytes(2, str(session)) + Wire.fieldBytes(3, str(artifact.toString()))

            // The OTA app first: its workflow waits for the install request once started.
            val megabytes = (offer.size / 1_000_000 + 1) * 2
            val start = Wire.fieldVarint(1, APP_OTA) + Wire.fieldBytes(2, Wire.fieldBytes(1, Wire.fieldVarint(1, megabytes))) +
                intent("ota_type", Wire.fieldBytes(5, str("resource"))) + intent("attempt_id", Wire.fieldBytes(5, str(artifact.toString()))) +
                intent("app_transfer_auto", Wire.fieldVarint(2, 1))
            mux.sendOnService(SVC_OTA, MT_START_APP, start)
            val started = next(MT_START_APP_RESPONSE, ANSWER_MS) ?: error("the glasses' OTA service did not answer")
            val code = runCatching { Wire.parse(started.payload).varint(1) }.getOrDefault(0)
            // Already started: its workflow is up, and still takes the request.
            if (code != SNAPP_SUCCESS && code != SNAPP_ALREADY_STARTED) error("the glasses would not start an install (code $code)")

            val pack = Wire.fieldVarint(1, APP_RESOURCE) + Wire.fieldBytes(2, raw) + Wire.fieldBytes(3, str(offer.version)) +
                Wire.fieldBytes(4, str(offer.appId)) + Wire.fieldBytes(6, str(offer.feature)) + Wire.fieldBytes(7, str("$artifact.zip"))
            val request = Wire.fieldBytes(1, key) + Wire.fieldBytes(2, raw) + Wire.fieldVarint(5, zip.length()) +
                Wire.fieldBytes(8, pack) + Wire.fieldVarint(9, 1)
            mux.sendOnService(SVC_OTA, MT_INSTALL, request)
            // They ask for the file once their receiver is ready for it; earlier it is dropped.
            next(MT_FILE_STATUS, ANSWER_MS) ?: error("the glasses did not ask for the file")

            _work.value = PackWork.Working(feature, PackStep.SENDING, 0, zip.length())
            airship.send(mux, NAMESPACE, "$artifact.zip", zip, uuid = raw) {
                _work.value = PackWork.Working(feature, PackStep.SENDING, it, zip.length())
            }?.let { error(it) }

            _work.value = PackWork.Working(feature, PackStep.INSTALLING)
            next(MT_INSTALL_COMPLETE, INSTALL_MS) ?: error("the glasses did not finish installing it")

            val change = Wire.fieldBytes(1, Wire.fieldBytes(2, str(session))) +
                Wire.fieldBytes(2, Wire.fieldVarint(1, APP_RESOURCE) + Wire.fieldBytes(2, str(offer.feature)) + Wire.fieldVarint(3, ENABLE))
            mux.sendOnService(SVC_OTA, MT_FEATURE_CHANGE, change)
            val changed = next(MT_FEATURE_CHANGE_RESPONSE, ANSWER_MS)
            log.add("Captions: feature change answered " + (changed?.payload?.joinToString("") { "%02x".format(it) } ?: "nothing"))
        } finally {
            listening.cancel()
        }
    }

    private fun errorWords(payload: ByteArray): String = runCatching {
        val f = Wire.parse(payload)
        val text = f.bytes(5).decodeToString()
        "code ${f.varint(3)}" + if (text.isNotBlank()) ", $text" else ""
    }.getOrDefault(payload.joinToString("") { "%02x".format(it) })

    private fun str(s: String) = s.encodeToByteArray()

    private fun intent(key: String, value: ByteArray) = Wire.fieldBytes(3, Wire.fieldBytes(1, str(key)) + value)

    companion object {
        /** The feature a choice needs: one language for captions; for translation, theirs into the wearer's. */
        fun featureFor(mode: CaptionsMode, self: String, other: String): String =
            if (mode == CaptionsMode.CAPTIONS) "live_captions_$self" else "translation_${other}_$self"

        private const val FEATURE = "com.meta.constellation.feature.air."
        private const val NAMESPACE = "ota"
        private const val INSTALLED = "installed"
        private const val SPARE_BYTES = 50_000_000L
        private const val SVC_OTA = 29
        private const val MT_INSTALL = 1
        private const val MT_ERROR = 3
        private const val MT_FILE_STATUS = 4
        private const val MT_INSTALL_COMPLETE = 7
        private const val MT_FEATURE_CHANGE = 8
        private const val MT_FEATURE_CHANGE_RESPONSE = 9
        private const val MT_START_APP = 2000
        private const val MT_START_APP_RESPONSE = 2001
        private const val APP_OTA = 1L
        // RESOURCE_PACK, as the Meta app numbers the package it installs.
        private const val APP_RESOURCE = 1L
        private const val ENABLE = 1L
        private const val SNAPP_SUCCESS = 1L
        private const val SNAPP_ALREADY_STARTED = 12L
        private const val ANSWER_MS = 15_000L
        private const val INSTALL_MS = 240_000L
    }
}
