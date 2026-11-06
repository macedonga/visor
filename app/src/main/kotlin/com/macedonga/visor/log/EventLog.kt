package com.macedonga.visor.log

import android.content.Context
import android.util.Log
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.connection.DataFetcher
import com.macedonga.visor.connection.PhoneInternet
import com.macedonga.visor.connection.words
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.SVC_ASSISTANT
import com.macedonga.visor.core.assistant.Timers
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.SVC_HN_SCREENCAST
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.phone.PhoneActions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What happened, in order, for as long as the process lives. */
@Singleton
class EventLog @Inject constructor(@ApplicationContext context: Context) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // Debug builds keep the log in a file too: the logcat buffer is gone in minutes.
    private val file = if (BuildConfig.DEBUG) File(context.filesDir, "events.log") else null

    fun add(text: String) {
        // Debug builds mirror the log to logcat, so a run can be followed over adb.
        if (BuildConfig.DEBUG) Log.i("Visor", text)
        val line = LocalTime.now().format(TIME) + "  " + text
        _lines.update { (it + line).takeLast(LIMIT) }
        file?.let { synchronized(it) { runCatching { it.appendText("${LocalDate.now()} $line\n") } } }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun text(): String = _lines.value.joinToString("\n")

    private companion object {
        const val LIMIT = 1000
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

/** A responder's scope: a failed job leaves the others running and logs "[name]: failed". */
fun responderScope(log: EventLog, name: String, dispatcher: CoroutineDispatcher = Dispatchers.IO) =
    CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log.add("$name: failed: $e") })

/** Puts the notes of the connection, media, settings, assistant and phone parts in the event log. */
@Singleton
class LogFeed @Inject constructor(
    private val log: EventLog,
    private val connection: GlassesConnection,
    keeper: LeKeeper,
    media: MediaImport,
    albums: Albums,
    settings: GlassesSettings,
    wifi: GlassesWifi,
    live: LiveStream,
    assistant: Assistant,
    tools: McpHub,
    timers: Timers,
    hearing: Hearing,
    shutter: Shutter,
    phone: PhoneActions,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            var last = GlassesState()
            connection.state.collect { state ->
                describeChange(last, state)
                last = state
            }
        }
        scope.launch {
            connection.serviceData.collect { data ->
                // svc15 logs itself; the tunnel, the fetcher and screencast chunks are too many to list.
                if (data.serviceId in QUIET) return@collect
                add("Message from service ${data.serviceId}, type ${data.messageType}, ${data.payload.size} bytes")
                // Debug builds also print the bytes, so a service Visor does not answer can be read over adb.
                if (BuildConfig.DEBUG) {
                    Log.i("VisorSvc", "svc ${data.serviceId} type ${data.messageType} " + data.payload.toHexString(0, minOf(400, data.payload.size)))
                }
            }
        }
        listOf(
            "Warning: " to connection.warnings,
            "Media: " to media.notes,
            "Albums: " to albums.notes,
            "Settings: " to settings.notes,
            "Wi-Fi networks: " to wifi.notes,
            "Live: " to live.notes,
            "Assistant: " to assistant.notes,
            "Tools: " to tools.notes,
            "" to keeper.notes,
            "Timers: " to timers.notes,
            "Hearing: " to hearing.notes,
            "Capture: " to shutter.notes,
            "Phone: " to phone.notes,
            "Custom wake word: " to assistant.wakeWord.notes,
        ).forEach { (tag, notes) -> scope.launch { notes.collect { add(tag + it) } } }
    }

    private fun add(text: String) = log.add(text)

    private fun describeChange(before: GlassesState, now: GlassesState) {
        val t = now.target
        if (t != null && t != before.target) add("Glasses: ${t.serial} at ${t.link}")
        if (now.noGlasses && !before.noGlasses) add("No glasses set up")
        if (now.link != before.link) add("Link: ${now.link.words()} [${now.link}]")
        if (now.link == LinkState.READY && before.link != LinkState.READY) connection.handshake?.let { add("Link handshake: $it") }
        if (now.reason != before.reason && now.reason.isNotBlank()) add("Reason: ${now.reason}")
        if (now.failure != before.failure) now.failure?.let { add("Connect failed: ${it.words()} [$it]") }
        if (now.diagnosis != before.diagnosis) now.diagnosis?.let { add("Verdict: ${it.words()} [$it]") }
        if (now.info != before.info) now.info?.let { add("Glasses build: ${it.build}, model ${it.model}") }
        if (before.device == null && now.device != null) add("Device status arrived")
        val old = before.device
        val d = now.device
        if (d != null) {
            if (d.hinge != old?.hinge) add("Hinge: ${d.hinge}")
            d.battery?.let { b -> if (b.charging != old?.battery?.charging) add("Charging: ${b.charging} (${b.percent}%)") }
            if (d.deepSleepPending != old?.deepSleepPending || d.inDeepSleep != old?.inDeepSleep) {
                add("Power: deep sleep pending ${d.deepSleepPending}, in deep sleep ${d.inDeepSleep}")
            }
        }
    }

    private companion object {
        val QUIET = setOf(SVC_ASSISTANT, DataFetcher.SVC, PhoneInternet.SVC, SVC_HN_SCREENCAST)
    }
}
