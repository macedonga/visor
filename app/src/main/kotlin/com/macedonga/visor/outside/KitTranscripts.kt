@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import com.macedonga.visor.core.assistant.GlassesEars
import com.macedonga.visor.core.assistant.headsetListen
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Transcript
import com.macedonga.visor.kit.protocol.TranscriptRequest
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * What the glasses hear, as text, for VDK apps: the glasses' microphone as a Bluetooth headset, into the phone's
 * speech recogniser. One recording serves every app that listens, all in one language.
 */
@Singleton
class KitTranscripts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.add("Kit: transcripts failed: $e") },
    )
    private var language: String? = null
    private var listeners = 0
    private var shared: SharedFlow<Result<Transcript>>? = null

    fun transcripts(caller: Caller, request: TranscriptRequest): Flow<Transcript> {
        val locale = request.language?.let { tag ->
            Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() }
                ?: throw KitException(ErrorCode.INVALID_PARAMS, "$tag is not a BCP 47 language tag.")
        } ?: Locale.getDefault()
        return flow {
            val heard = join(caller, locale.toLanguageTag())
            try {
                // A failure is the last thing a session sends, and ends the stream here.
                heard.collect { emit(it.getOrThrow()) }
            } finally {
                leave(caller)
            }
        }
    }

    @Synchronized
    private fun join(caller: Caller, tag: String): SharedFlow<Result<Transcript>> {
        val running = language
        if (listeners > 0 && running != tag) {
            throw KitException(ErrorCode.BUSY, "Visor hears in $running for another app.")
        }
        listeners++
        language = tag
        log.add("Kit: ${caller.packageName} hears the glasses' microphone in $tag")
        return shared ?: hear(Locale.forLanguageTag(tag)).shareIn(scope, SharingStarted.WhileSubscribed(), 0).also { shared = it }
    }

    @Synchronized
    private fun leave(caller: Caller) {
        log.add("Kit: ${caller.packageName} stopped hearing the glasses' microphone")
        if (--listeners > 0) return
        language = null
        shared = null
    }

    /** Never throws: under shareIn a failure would have nowhere to go, so it is sent as the last result. */
    private fun hear(locale: Locale): Flow<Result<Transcript>> = channelFlow {
        val tag = locale.toLanguageTag()
        val fail = { e: KitException -> trySend(Result.failure(e)).also { close() } }
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) {
            fail(KitException(ErrorCode.UNSUPPORTED, "This phone cannot hand recorded speech to its recogniser.", reason = "no_recognizer"))
            return@channelFlow
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(KitException(ErrorCode.UNSUPPORTED, "Visor may not record audio. Allow the microphone in Visor's settings.", reason = "no_permission"))
            return@channelFlow
        }
        val address = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
        if (address == null) {
            fail(KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected over Bluetooth."))
            return@channelFlow
        }
        var problem: String? = null
        val ears = GlassesEars(
            context, locale,
            onUtterance = { text, _, _ -> trySend(Result.success(Transcript(text, tag, System.currentTimeMillis()))) },
            onProblem = {
                problem = it
                log.add("Kit: transcripts: $it")
            },
            onDone = { fail(KitException(ErrorCode.FAILED, "Speech recognition stopped: $problem", reason = "recognizer_failed")) },
        )
        ears.start()
        val mic = launch {
            val why = headsetListen(context, address, ears::hearPcm)
            fail(KitException(ErrorCode.FAILED, "The glasses' microphone stopped: $why", reason = "no_headset"))
        }
        awaitClose {
            mic.cancel()
            ears.stop()
        }
    }
}
