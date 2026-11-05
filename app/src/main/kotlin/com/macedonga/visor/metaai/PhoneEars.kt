package com.macedonga.visor.metaai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** The phone's speech recogniser while the mic is held, on the phone when it has the language. Main thread only. */
class PhoneEars(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun ready()
        fun heard(text: String)
        fun level(value: Float)
        /** The words when the mic was let go; null when nothing was understood. */
        fun done(text: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var last = ""
    // The words of the sessions a pause ended while the mic was still held.
    private var kept = ""
    private var resumed = false
    private val giveUp = Runnable { finish(last.ifBlank { null }) }

    fun start(locale: Locale) {
        cancel()
        listen(locale.toLanguageTag(), SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
    }

    /** The mic was let go: the last words come back through [Listener.done]. */
    fun stop() {
        val r = recognizer ?: return
        stopping = true
        r.stopListening()
        main.postDelayed(giveUp, GIVE_UP_MS)
    }

    fun cancel() {
        main.removeCallbacks(giveUp)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        stopping = false
        last = ""
        kept = ""
        resumed = false
    }

    private fun finish(text: String?) {
        if (recognizer == null) return
        cancel()
        listener.done(text)
    }

    /** The recogniser stopped on its own with the mic still held: a new one goes on from [words]. */
    private fun resume(r: SpeechRecognizer, tag: String, onDevice: Boolean, words: String) {
        kept = words
        last = words
        resumed = true
        r.destroy()
        if (recognizer === r) listen(tag, onDevice)
    }

    private fun listen(tag: String, onDevice: Boolean) {
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!resumed) listener.ready()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    last = join(kept, text)
                    listener.heard(last)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                val words = text?.let { join(kept, it) } ?: last
                if (!stopping) return resume(r, tag, onDevice, words)
                finish(words.ifBlank { null })
            }

            override fun onError(error: Int) {
                // The phone does not hold this language: its usual recogniser instead, which works while Visor is in front.
                if (onDevice && !stopping && error in LANGUAGE_MISSING) {
                    r.destroy()
                    if (recognizer === r) listen(tag, onDevice = false)
                    return
                }
                if (!stopping && error in PAUSED) return resume(r, tag, onDevice, last)
                finish(last.ifBlank { null })
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Held down, a pause is not the end.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, HELD_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, HELD_SILENCE_MS)
            },
        )
    }

    private companion object {
        const val GIVE_UP_MS = 3_000L
        const val HELD_SILENCE_MS = 10_000L
        val LANGUAGE_MISSING = setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        val PAUSED = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")
    }
}
