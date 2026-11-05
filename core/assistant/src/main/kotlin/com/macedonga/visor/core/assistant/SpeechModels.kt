package com.macedonga.visor.core.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why the phone cannot hear or speak a phone language. */
enum class PhoneSpeech { OLD_ANDROID, NO_RECOGNITION, NO_RECOGNITION_FOR_LANGUAGE, NO_VOICE }

class PhoneSpeechException(val problem: PhoneSpeech, detail: String) : IllegalStateException(detail)

/**
 * The phone's on-device speech models. Visor hears what the glasses leave to the server with them, in the glasses'
 * language: at launch, and whenever the glasses say their language, the model is checked and fetched if missing.
 */
object SpeechModels {
    /** The languages the on-device recogniser holds; empty until read. */
    @Volatile
    var installed: List<String> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())

    private const val DESTROY_DELAY_MS = 5_000L

    /** True when a recogniser confirms [locale]; false when none could say; throws when none has it. */
    suspend fun supports(context: Context, locale: Locale): Boolean = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw PhoneSpeechException(PhoneSpeech.OLD_ANDROID, "hearing the glasses needs Android 13 or newer")
        val wanted = speechTag(locale)
        val services = buildList {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) add(true)
            if (SpeechRecognizer.isRecognitionAvailable(context)) add(false)
        }
        if (services.isEmpty()) throw PhoneSpeechException(PhoneSpeech.NO_RECOGNITION, "the phone has no speech recognition service")
        val intent = recognizeIntent(wanted)
        var unknown = false
        for (onDevice in services) {
            val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            val supported = try {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<Boolean?> { cont ->
                        r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (onDevice) installed = support.installedOnDeviceLanguages
                                val languages = support.installedOnDeviceLanguages + support.supportedOnDeviceLanguages +
                                    support.pendingOnDeviceLanguages + if (onDevice) emptyList() else support.onlineLanguages
                                val matches = languages.any { matchesSpeechLanguage(it, locale) }
                                if (cont.isActive) cont.resume(matches)
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) false else null)
                            }
                        })
                    }
                }
            } finally {
                r.destroy()
            }
            if (supported == true) return@withContext true
            if (supported == null) unknown = true
        }
        if (!unknown) throw PhoneSpeechException(PhoneSpeech.NO_RECOGNITION_FOR_LANGUAGE, "the phone has no speech recognition for $wanted")
        false
    }

    /** Read what is held and, if [locale]'s model is not, ask the phone to download it. Any thread. */
    fun prepare(context: Context, locale: Locale, note: (String) -> Unit) = main.post {
        // Asking which models the phone holds came with Android 13.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@post note("this Android cannot list its speech models")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@post note("this phone has no on-device speech recognition")
        val wanted = speechTag(locale)
        val intent = recognizeIntent(wanted)
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                installed = support.installedOnDeviceLanguages
                when {
                    installed.any { it.equals(wanted, ignoreCase = true) } -> {
                        note("speech model for $wanted is on the phone")
                        r.destroy()
                    }
                    support.pendingOnDeviceLanguages.any { it.equals(wanted, ignoreCase = true) } -> {
                        note("speech model for $wanted is downloading")
                        r.destroy()
                    }
                    else -> download(context, r, intent, wanted, note)
                }
            }

            override fun onError(error: Int) {
                note("could not read the phone's speech models (error $error)")
                r.destroy()
            }
        })
    }

    private fun download(context: Context, r: SpeechRecognizer, intent: Intent, wanted: String, note: (String) -> Unit) {
        note("downloading the phone's speech model for $wanted")
        // Android 13 starts the download but has no listener for how it goes (ModelDownloadListener is 14).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Destroyed before its queued download runs, the recogniser crashes the app, so it goes a little later.
            r.triggerModelDownload(intent)
            main.postDelayed({ r.destroy() }, DESTROY_DELAY_MS)
            return
        }
        r.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = Unit

            override fun onSuccess() {
                installed = installed + wanted
                note("speech model for $wanted downloaded")
                r.destroy()
            }

            override fun onScheduled() {
                note("speech model for $wanted scheduled; the phone fetches it when it can")
                r.destroy()
            }

            override fun onError(error: Int) {
                note("the phone would not download the speech model for $wanted (error $error)")
                r.destroy()
            }
        })
    }

    /** The model to hear [locale] with: its own if held, else a held one of the same language, else its own. */
    fun pick(locale: Locale): String {
        val wanted = speechTag(locale)
        return installed.firstOrNull { it.equals(wanted, ignoreCase = true) }
            ?: installed.firstOrNull { matchesSpeechLanguage(it, locale) }
            ?: wanted
    }
}

internal fun matchesSpeechLanguage(tag: String, locale: Locale): Boolean {
    val available = Locale.forLanguageTag(tag)
    return available.language == locale.language && (locale.language != "zh" || available.country == locale.country)
}
