package com.macedonga.visor.language

import com.macedonga.visor.core.identity.token.withToken
import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.language.LANGUAGE_FEATURE
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.identity.language.fetchPackOffers
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.pairs.pairPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where an install is. [done] and [total] are bytes for the download and the transfer. */
sealed interface LanguageWork {
    data object Idle : LanguageWork
    data class Working(val language: GlassesLanguage, val step: Step, val done: Long = 0, val total: Long = 0) : LanguageWork
    data class Finished(val language: GlassesLanguage) : LanguageWork
    data class Failed(val language: GlassesLanguage, val reason: String) : LanguageWork

    enum class Step { ASKING, DOWNLOADING, SENDING, SWITCHING }
}

/**
 * Language packs for the glasses: Meta's zip for a language, fetched with the user's login, sent to the glasses
 * over Wi-Fi Direct on airship `language_resource`, then the assistant voice switched to it. The glasses unpack
 * it themselves.
 */
@Singleton
class LanguageInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val login: MetaLogin,
    private val connection: GlassesConnection,
    private val settings: GlassesSettings,
    private val live: LiveStream,
    private val airship: AirshipReceiver,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Installed on the glasses: each pair has its own.
    @Volatile
    private var prefs = pairPrefs(context, "languages", connection.pair.value)
    private val dir = File(context.filesDir, "packs")

    private val _work = MutableStateFlow<LanguageWork>(LanguageWork.Idle)
    val work: StateFlow<LanguageWork> = _work.asStateFlow()

    /** The languages on the glasses, as far as this phone knows: the built-in one, those installed from here, the one in use. */
    private val _installed = MutableStateFlow(prefs.getStringSet(KEY_INSTALLED, null).orEmpty() + "en_US")
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    init {
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().drop(1).collect { serial ->
                prefs = pairPrefs(context, "languages", serial)
                _installed.value = prefs.getStringSet(KEY_INSTALLED, null).orEmpty() + "en_US"
            }
        }
    }

    /** What Meta has, by pack suffix, and why it could not be asked. */
    private val _offers = MutableStateFlow<Map<String, PackOffer>>(emptyMap())
    val offers: StateFlow<Map<String, PackOffer>> = _offers.asStateFlow()
    private val _offersProblem = MutableStateFlow("")
    val offersProblem: StateFlow<String> = _offersProblem.asStateFlow()

    private var job: Job? = null

    init {
        // The voice in use says its language is there.
        scope.launch { settings.state.collect { languageOfVoice(settings.value(VOICE_KEY))?.let { markInstalled(it.locale) } } }
    }

    /** Ask Meta for every language's pack, for the sizes. Quietly does nothing until the glasses have said who they are. */
    fun refreshOffers() {
        scope.launch {
            runCatching { ask(GLASSES_LANGUAGES.mapNotNull { it.pack }) }
                .onSuccess { found ->
                    _offers.value = found.associateBy { it.feature.removePrefix(LANGUAGE_FEATURE) }
                    _offersProblem.value = ""
                }
                .onFailure { _offersProblem.value = it.message.orEmpty() }
        }
    }

    fun install(language: GlassesLanguage) {
        val pack = language.pack ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                step(language, LanguageWork.Step.ASKING)
                val offer = ask(listOf(pack)).firstOrNull() ?: error("Meta has no pack of this language for these glasses")
                dir.mkdirs()
                val zip = File(dir, offer.resource + ".zip")
                if (!zip.exists()) {
                    if (dir.usableSpace < offer.size + SPARE_BYTES) error("the phone needs ${mb(offer.size + SPARE_BYTES)} free")
                    log.add("Language: downloading ${offer.resource} ${offer.version}, ${mb(offer.size)}")
                    downloadPack(offer, zip) { done -> _work.value = LanguageWork.Working(language, LanguageWork.Step.DOWNLOADING, done, offer.size) }
                }
                if (connection.state.value.link != LinkState.READY) error("the glasses are not connected")
                step(language, LanguageWork.Step.SENDING, 0, zip.length())
                log.add("Language: sending ${zip.name} to the glasses over Wi-Fi")
                // The name as the Meta app sends it: it-language_resource becomes it_language_resource.zip.
                val why = live.overWifi { mux ->
                    airship.send(mux, NAMESPACE, offer.resource.replace('-', '_') + ".zip", zip) { done ->
                        _work.value = LanguageWork.Working(language, LanguageWork.Step.SENDING, done, zip.length())
                    }
                }
                if (why != null) error(why)
                markInstalled(language.locale)
                zip.delete()
                step(language, LanguageWork.Step.SWITCHING)
                use(language.voices.first { it.id == language.defaultVoice })
                log.add("Language: ${language.locale} is on the glasses")
                _work.value = LanguageWork.Finished(language)
            } catch (error: kotlinx.coroutines.CancellationException) {
                _work.value = LanguageWork.Idle
                throw error
            } catch (error: Throwable) {
                log.add("Language: ${language.locale} failed: ${error.message}")
                _work.value = LanguageWork.Failed(language, error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (job?.isActive != true) _work.value = LanguageWork.Idle
    }

    /** Switch the assistant to [voice], and with it to its language. False when the glasses are not there to hear it. */
    fun use(voice: GlassesVoice): Boolean = settings.write(VOICE_KEY, voice.id)

    private fun step(language: GlassesLanguage, step: LanguageWork.Step, done: Long = 0, total: Long = 0) {
        _work.value = LanguageWork.Working(language, step, done, total)
    }

    private fun markInstalled(locale: String) {
        if (locale in _installed.value) return
        _installed.update { it + locale }
        prefs.edit().putStringSet(KEY_INSTALLED, _installed.value - "en_US").apply()
    }

    private fun ask(packs: List<String>): List<PackOffer> = offersFor(packs.map { LANGUAGE_FEATURE + it })

    /** What Meta has for these glasses under the constellation [features], named in full. */
    fun offersFor(features: List<String>): List<PackOffer> {
        val info = connection.state.value.info ?: error("connect the glasses once, so they say which they are")
        // Meta/hammerhead/greathammerhead:14/UKQ1.250303.001/72387580077200100:user/release-keys
        val parts = info.build.split('/')
        val firmware = parts.getOrNull(4)?.substringBefore(':').orEmpty()
        val type = parts.getOrNull(4)?.substringAfter(':', "user").orEmpty()
        if (firmware.isEmpty()) error("the glasses' build is unknown")
        return login.source.withToken { token -> fetchPackOffers(token, info.serial, firmware, features, "ota.${parts.getOrElse(1) { "hammerhead" }}.$type") }
    }

    private companion object {
        const val VOICE_KEY = "assistant_voice_id"
        const val NAMESPACE = "language_resource"
        const val KEY_INSTALLED = "installed"
        const val SPARE_BYTES = 50_000_000L
    }
}

internal fun mb(bytes: Long): String = "%d MB".format((bytes + 500_000) / 1_000_000)
