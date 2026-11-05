package com.macedonga.visor.language

import com.macedonga.visor.core.identity.token.TokenError
import com.macedonga.visor.core.identity.token.withToken
import android.content.Context
import androidx.annotation.StringRes
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.PhoneSpeech
import com.macedonga.visor.core.assistant.PhoneSpeechException
import com.macedonga.visor.core.assistant.SpeechModels
import com.macedonga.visor.core.assistant.Voice
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.parseBuild
import com.macedonga.visor.core.identity.language.LANGUAGE_FEATURE
import com.macedonga.visor.core.identity.language.PackOffer
import com.macedonga.visor.core.identity.language.fetchPackOffers
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.sizeWords
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.pairs.pairPrefs
import com.macedonga.visor.ui.sizeWords
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where an install is. [done] and [total] are bytes for the download and the transfer. */
sealed interface LanguageWork {
    data object Idle : LanguageWork
    data class Working(val language: GlassesLanguage, val step: Step, val done: Long = 0, val total: Long = 0) : LanguageWork
    data class Finished(val language: GlassesLanguage, val recognitionChecked: Boolean = true) : LanguageWork
    /** [words] say why on the screen when the failure is one the screen knows, else the English [why]. */
    data class Failed(val language: GlassesLanguage, @StringRes val words: Int? = null, val args: List<Any> = emptyList(), val why: String = "") : LanguageWork

    enum class Step { CHECKING_PHONE, ASKING, DOWNLOADING, SENDING, SWITCHING }
}

/** A failure with its English [message] for the log and its [words] for the screen. */
class LanguageError(message: String, @StringRes val words: Int, vararg val args: Any) : IllegalStateException(message)

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
    private var prefsPair = connection.pair.value
    @Volatile
    private var prefs = pairPrefs(context, "languages", prefsPair)
    private val dir = File(context.filesDir, "packs")

    private val _work = MutableStateFlow<LanguageWork>(LanguageWork.Idle)
    val work: StateFlow<LanguageWork> = _work.asStateFlow()

    /** The languages on the glasses, as far as this phone knows: the built-in one, those installed from here, the one in use. */
    private val _installed = MutableStateFlow(prefs.getStringSet(KEY_INSTALLED, null).orEmpty() + "en_US")
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    init {
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().collect { serial ->
                if (serial == prefsPair) return@collect
                prefsPair = serial
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
        scope.launch { settings.state.collect { languageOfVoice(settings.value(VOICE_KEY))?.takeUnless { it.partner }?.let { markInstalled(it.locale) } } }
    }

    /** Ask Meta for every language's pack, for the sizes. */
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
        launchWork(language) {
            step(language, LanguageWork.Step.ASKING)
            val offer = ask(listOf(pack)).firstOrNull() ?: throw LanguageError("Meta has no pack of this language for these glasses", R.string.language_err_no_pack)
            dir.mkdirs()
            // Named by version: a zip left by a failed send is not sent for a newer one, and older ones go.
            val zip = File(dir, "${offer.resource}-${offer.version}.zip")
            dir.listFiles { f -> (f.name.startsWith(offer.resource + "-") || f.name.startsWith(offer.resource + ".zip")) && !f.name.startsWith(zip.name) }
                ?.forEach { it.delete() }
            if (!zip.exists()) {
                log.add("Language: downloading ${offer.resource} ${offer.version}, ${sizeWords(offer.size)}")
                downloadPack(offer, zip, SPARE_BYTES) { done -> _work.value = LanguageWork.Working(language, LanguageWork.Step.DOWNLOADING, done, offer.size) }
            }
            if (connection.state.value.link != LinkState.READY) throw LanguageError("the glasses are not connected", R.string.language_err_offline)
            step(language, LanguageWork.Step.SENDING, 0, zip.length())
            log.add("Language: sending ${zip.name} to the glasses over Wi-Fi")
            // The name as the Meta app sends it: it-language_resource becomes it_language_resource.zip.
            val why = live.overWifi { mux ->
                airship.send(mux, NAMESPACE, offer.resource.replace('-', '_') + ".zip", zip) { done ->
                    _work.value = LanguageWork.Working(language, LanguageWork.Step.SENDING, done, zip.length())
                }
            }
            if (why != null) throw LanguageError(why, R.string.language_err_wifi)
            markInstalled(language.locale)
            zip.delete()
            step(language, LanguageWork.Step.SWITCHING)
            switchVoice(language.defaultVoice)
            log.add("Language: ${language.locale} is on the glasses")
            _work.value = LanguageWork.Finished(language)
        }
    }

    /** Runs [block] as the one job: cancelled, the card goes; failed, it says why. */
    private fun launchWork(language: GlassesLanguage, block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                _work.value = LanguageWork.Idle
                throw error
            } catch (error: Exception) {
                log.add("Language: ${language.locale} failed: ${error.message}")
                val step = (_work.value as? LanguageWork.Working)?.step
                val offline = error is IOException || (error as? TokenError)?.unreachable == true
                val known: LanguageError? = when {
                    error is LanguageError -> error
                    error is NoSpace -> LanguageError(error.message.orEmpty(), R.string.language_err_space, sizeWords(context, error.bytes))
                    step == LanguageWork.Step.SENDING -> LanguageError(error.message.orEmpty(), R.string.language_err_wifi)
                    offline && (step == LanguageWork.Step.ASKING || step == LanguageWork.Step.DOWNLOADING) -> LanguageError(error.message.orEmpty(), R.string.language_err_network)
                    else -> null
                }
                _work.value = LanguageWork.Failed(language, known?.words, known?.args?.toList().orEmpty(), error.message.orEmpty())
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (job?.isActive != true) _work.value = LanguageWork.Idle
    }

    /** Switch to [voice]; a phone language is checked on the phone first, then confirmed by the glasses. */
    fun use(voice: GlassesVoice) {
        if (job?.isActive == true) return
        val language = languageOfVoice(voice.id) ?: return
        // A voice on the glasses: its row shows the switch and a refusal, as the glasses report them.
        if (!language.partner) {
            settings.write(VOICE_KEY, voice.id)
            return
        }
        launchWork(language) {
            if (connection.state.value.link != LinkState.READY) throw LanguageError("the glasses are not connected", R.string.language_err_offline)
            val locale = language.javaLocale
            step(language, LanguageWork.Step.CHECKING_PHONE)
            val recognitionChecked = checkPhone(voice.id)
            step(language, LanguageWork.Step.SWITCHING)
            switchVoice(voice.id)
            SpeechModels.prepare(context, locale) { log.add("Language: $it") }
            _work.value = LanguageWork.Finished(language, recognitionChecked)
        }
    }

    internal suspend fun checkPhone(voiceId: String): Boolean {
        if (!isPhoneVoice(voiceId)) return true
        val language = languageOfVoice(voiceId) ?: error("unknown phone voice: $voiceId")
        val locale = language.javaLocale
        val voice = Voice(context)
        return try {
            val recognitionChecked = SpeechModels.supports(context, locale)
            voice.checkLanguage(locale)
            recognitionChecked
        } catch (e: PhoneSpeechException) {
            throw LanguageError(e.message.orEmpty(), when (e.problem) {
                PhoneSpeech.OLD_ANDROID -> R.string.language_err_old_android
                PhoneSpeech.NO_RECOGNITION -> R.string.language_err_no_recognition
                PhoneSpeech.NO_RECOGNITION_FOR_LANGUAGE -> R.string.language_err_no_recognition_for
                PhoneSpeech.NO_VOICE -> R.string.language_err_no_voice
            })
        } finally {
            voice.close()
        }
    }

    internal suspend fun switchVoice(voiceId: String) {
        if (!settings.write(VOICE_KEY, voiceId)) throw LanguageError("the voice could not be sent to the glasses", R.string.language_err_send)
        val defaultVoice = settings.catalogue[VOICE_KEY]?.default
        val confirmed = withTimeoutOrNull(20_000) {
            settings.state.first { state ->
                VOICE_KEY !in state.pending && (state.stored[VOICE_KEY] ?: defaultVoice) == voiceId
            }
        }
        if (confirmed == null) {
            throw if (VOICE_KEY in settings.state.value.kept) LanguageError("the glasses kept their previous voice", R.string.language_err_kept)
            else LanguageError("the glasses did not confirm the voice", R.string.language_err_unconfirmed)
        }
    }

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
        val build = parseBuild(info.build)?.takeIf { it.firmware.isNotEmpty() } ?: error("the glasses' build is unknown")
        return login.source.withToken { token -> fetchPackOffers(token, info.serial, build.firmware, features, build.otaType) }
    }

    private companion object {
        const val NAMESPACE = "language_resource"
        const val KEY_INSTALLED = "installed"
        const val SPARE_BYTES = 50_000_000L
    }
}
