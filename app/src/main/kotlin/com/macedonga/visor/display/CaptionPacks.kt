package com.macedonga.visor.display

import android.content.Context
import com.macedonga.visor.core.connection.CaptionsMode
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.SnApp
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.protocol.SVC_OTA
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.toBytes
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.firmware.Ota
import com.macedonga.visor.language.LanguageInstaller
import com.macedonga.visor.language.NoSpace
import com.macedonga.visor.language.downloadPack
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.pairs.pairPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PackStep { DOWNLOADING, SENDING, INSTALLING }

/** Why an install stopped, for the screen; the event log keeps the details. */
enum class PackTrouble { NO_PACK, NO_SPACE, DOWNLOAD, GLASSES }

sealed interface PackWork {
    data object Idle : PackWork
    data class Working(val feature: String, val step: PackStep, val done: Long = 0, val total: Long = 0) : PackWork
    /** [bytes]: the free space a NO_SPACE install needs. */
    data class Failed(val feature: String, val trouble: PackTrouble, val bytes: Long = 0) : PackWork
}

private class PackFailure(val trouble: PackTrouble, message: String) : Exception(message)

/** Installs Meta's captions and translation packs over svc29 and remembers which this phone installed. */
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
    private var prefsFor = connection.pair.value
    @Volatile
    private var prefs = pairPrefs(context, "caption_packs", prefsFor)
    private val dir = File(context.filesDir, "caption_packs")

    private val _installed = MutableStateFlow(prefs.getStringSet(INSTALLED, null).orEmpty())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()
    private val _offers = MutableStateFlow<Map<String, PackOffer>>(emptyMap())
    val offers: StateFlow<Map<String, PackOffer>> = _offers.asStateFlow()
    private val _work = MutableStateFlow<PackWork>(PackWork.Idle)
    val work: StateFlow<PackWork> = _work.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().collect { serial ->
                if (serial == prefsFor) return@collect
                prefsFor = serial
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
            // What any other failure is: the download until the pack is on the phone, then the glasses.
            var stage = PackTrouble.DOWNLOAD
            try {
                if (connection.state.value.link != LinkState.READY) throw PackFailure(PackTrouble.GLASSES, "the glasses are not connected")
                _work.value = PackWork.Working(feature, PackStep.DOWNLOADING)
                val offer = _offers.value[feature] ?: languages.offersFor(listOf(FEATURE + feature)).firstOrNull()
                    ?: throw PackFailure(PackTrouble.NO_PACK, "Meta has no pack for these glasses")
                dir.mkdirs()
                // Only this pack is kept, so a retry still resumes it.
                val keep = setOf(offer.resource + ".zip", offer.resource + ".zip.part")
                dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                val zip = File(dir, offer.resource + ".zip")
                if (!zip.exists()) {
                    log.add("Captions: downloading ${offer.resource} ${offer.version}")
                    downloadPack(offer, zip, SPARE_BYTES) { _work.value = PackWork.Working(feature, PackStep.DOWNLOADING, it, offer.size) }
                }
                stage = PackTrouble.GLASSES
                if (connection.state.value.link != LinkState.READY) throw PackFailure(PackTrouble.GLASSES, "the glasses are not connected")
                log.add("Captions: installing ${zip.name} on the glasses over Wi-Fi")
                val why = live.overWifi { mux ->
                    try {
                        installOn(mux, feature, offer, zip)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e::class.java.simpleName
                    }
                }
                if (why != null) error(why)
                zip.delete()
                _installed.update { it + feature }
                prefs.edit().putStringSet(INSTALLED, _installed.value).apply()
                log.add("Captions: ${offer.resource} is on the glasses")
                _work.value = PackWork.Idle
            } catch (error: CancellationException) {
                _work.value = PackWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Captions: $feature failed: ${error.message}")
                _work.value = when (error) {
                    is NoSpace -> PackWork.Failed(feature, PackTrouble.NO_SPACE, error.bytes)
                    is PackFailure -> PackWork.Failed(feature, error.trouble)
                    else -> PackWork.Failed(feature, stage)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun installOn(mux: DataXConnection, feature: String, offer: PackOffer, zip: File) = coroutineScope {
        val fromGlasses = Channel<ServiceData>(Channel.UNLIMITED)
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { live.wifiData.collect { if (it.serviceId == SVC_OTA) fromGlasses.send(it) } }
        suspend fun next(type: Int, ms: Long): ServiceData? = withTimeoutOrNull(ms) {
            var m: ServiceData
            do {
                m = fromGlasses.receive()
                if (m.messageType == Ota.MT_ERROR) error("the glasses stopped the install: ${errorWords(m.payload)}")
            } while (m.messageType != type)
            m
        }
        try {
            val artifact = UUID.randomUUID()
            val raw = artifact.toBytes()
            val session = UUID.randomUUID().toString()
            val key = Wire.fieldString(1, UUID.randomUUID().toString()) + Wire.fieldString(2, session) + Wire.fieldString(3, artifact.toString())

            // The OTA app first: its workflow waits for the install request once started.
            val megabytes = (offer.size / 1_000_000 + 1) * 2
            mux.sendOnService(SVC_OTA, SnApp.MT_START_APP, Ota.start("resource", artifact.toString(), megabytes, autoTransfer = true))
            val started = next(SnApp.MT_START_APP_RESPONSE, ANSWER_MS) ?: error("the glasses' OTA service did not answer")
            Ota.checkStarted(started)

            val pack = Wire.fieldVarint(1, APP_RESOURCE) + Wire.fieldBytes(2, raw) + Wire.fieldString(3, offer.version) +
                Wire.fieldString(4, offer.appId) + Wire.fieldString(6, offer.feature) + Wire.fieldString(7, "$artifact.zip")
            val request = Wire.fieldBytes(1, key) + Wire.fieldBytes(2, raw) + Wire.fieldVarint(5, zip.length()) +
                Wire.fieldBytes(8, pack) + Wire.fieldVarint(9, 1)
            mux.sendOnService(SVC_OTA, Ota.MT_INSTALL, request)
            // They ask for the file once their receiver is ready for it; earlier it is dropped.
            next(Ota.MT_FILE_STATUS, ANSWER_MS) ?: error("the glasses did not ask for the file")

            _work.value = PackWork.Working(feature, PackStep.SENDING, 0, zip.length())
            airship.send(mux, NAMESPACE, "$artifact.zip", zip, uuid = raw) {
                _work.value = PackWork.Working(feature, PackStep.SENDING, it, zip.length())
            }?.let { error(it) }

            _work.value = PackWork.Working(feature, PackStep.INSTALLING)
            next(Ota.MT_INSTALL_COMPLETE, INSTALL_MS) ?: error("the glasses did not finish installing it")

            val change = Wire.fieldBytes(1, Wire.fieldString(2, session)) +
                Wire.fieldBytes(2, Wire.fieldVarint(1, APP_RESOURCE) + Wire.fieldString(2, offer.feature) + Wire.fieldVarint(3, ENABLE))
            mux.sendOnService(SVC_OTA, MT_FEATURE_CHANGE, change)
            val changed = next(MT_FEATURE_CHANGE_RESPONSE, ANSWER_MS)
            log.add("Captions: feature change answered " + (changed?.payload?.toHexString() ?: "nothing"))
        } finally {
            listening.cancel()
        }
    }

    private fun errorWords(payload: ByteArray): String = runCatching {
        val f = Wire.parse(payload)
        val text = f.bytes(5).decodeToString()
        "code ${f.varint(3)}" + if (text.isNotBlank()) ", $text" else ""
    }.getOrDefault(payload.toHexString())

    companion object {
        /** The feature a choice needs: one language for captions; for translation, theirs into the wearer's. */
        fun featureFor(mode: CaptionsMode, self: String, other: String): String =
            if (mode == CaptionsMode.CAPTIONS) "live_captions_$self" else "translation_${other}_$self"

        private const val FEATURE = "com.meta.constellation.feature.air."
        private const val NAMESPACE = "ota"
        private const val INSTALLED = "installed"
        private const val SPARE_BYTES = 50_000_000L
        private const val MT_FEATURE_CHANGE = 8
        private const val MT_FEATURE_CHANGE_RESPONSE = 9
        // RESOURCE_PACK, as the Meta app numbers the package it installs.
        private const val APP_RESOURCE = 1L
        private const val ENABLE = 1L
        private const val ANSWER_MS = 15_000L
        private const val INSTALL_MS = 240_000L
    }
}
