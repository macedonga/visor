package com.macedonga.visor.core.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The phone's on-device speech models. Visor hears what the glasses leave to the server with them, in the glasses'
 * language: at launch, and whenever the glasses say their language, the model is checked and fetched if missing.
 */
object SpeechModels {
    /** The languages the on-device recogniser holds; null until read. */
    @Volatile
    var installed: List<String> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())

    private const val DESTROY_DELAY_MS = 5_000L

    /** Read what is held and, if [locale]'s model is not, ask the phone to download it. Any thread. */
    fun prepare(context: Context, locale: Locale, note: (String) -> Unit) = main.post {
        // Asking which models the phone holds came with Android 13.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@post note("this Android cannot list its speech models")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@post note("this phone has no on-device speech recognition")
        val wanted = speechTag(locale)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, wanted)
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
            // The recogniser queues the download on the main thread; destroyed before it runs, it calls a service
            // already let go and the app crashes (issue #36). It goes once the download has been handed over.
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
            ?: installed.firstOrNull { it.substringBefore('-').equals(wanted.substringBefore('-'), ignoreCase = true) }
            ?: wanted
    }
}
