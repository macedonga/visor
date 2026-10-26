package com.macedonga.visor.log

import android.content.Context
import android.util.Log
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.connection.words
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.Timers
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesWifi
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.transport.LeKeeper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
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
class EventLog @Inject constructor(
    @ApplicationContext context: Context,
    connection: GlassesConnection,
    keeper: LeKeeper,
    media: MediaImport,
    settings: GlassesSettings,
    wifi: GlassesWifi,
    live: LiveStream,
    assistant: Assistant,
    tools: com.macedonga.visor.core.assistant.mcp.McpHub,
    timers: Timers,
    hearing: Hearing,
    shutter: com.macedonga.visor.core.connection.Shutter,
    phone: com.macedonga.visor.phone.PhoneActions,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // Debug builds keep the log in a file too: the logcat buffer is gone in minutes.
    private val file = if (BuildConfig.DEBUG) File(context.filesDir, "events.log") else null

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
                // The assistant says what its svc15 traffic means itself; the internet tunnel and the data fetcher go to
                // the Requests page, a packet or a request at a time being far too many for this log.
                if (data.serviceId in QUIET) return@collect
                if (data.serviceId != 15) add("Message from service ${data.serviceId}, type ${data.messageType}, ${data.payload.size} bytes")
                // Debug builds also print the bytes, so a service Visor does not answer can be read over adb.
                if (BuildConfig.DEBUG && data.serviceId !in setOf(15, 20)) {
                    Log.i("VisorSvc", "svc ${data.serviceId} type ${data.messageType} " + data.payload.take(400).joinToString("") { "%02x".format(it) })
                }
            }
        }
        scope.launch {
            connection.warnings.collect { add("Warning: $it") }
        }
        scope.launch {
            media.notes.collect { add("Media: $it") }
        }
        scope.launch {
            settings.notes.collect { add("Settings: $it") }
        }
        scope.launch {
            wifi.notes.collect { add("Wi-Fi networks: $it") }
        }
        scope.launch {
            live.notes.collect { add("Live: $it") }
        }
        scope.launch {
            assistant.notes.collect { add("Assistant: $it") }
        }
        scope.launch {
            tools.notes.collect { add("Tools: $it") }
        }
        scope.launch {
            keeper.notes.collect { add(it) }
        }
        scope.launch {
            timers.notes.collect { add("Timers: $it") }
        }
        scope.launch {
            hearing.notes.collect { add("Hearing: $it") }
        }
        scope.launch {
            shutter.notes.collect { add("Capture: $it") }
        }
        scope.launch {
            phone.notes.collect { add("Phone: $it") }
        }
        scope.launch {
            assistant.wakeWord.notes.collect { add(it) }
        }
    }

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

    private fun describeChange(before: GlassesState, now: GlassesState) {
        if (now.target != before.target && now.target != null) {
            add("Glasses: ${now.target?.serial} at ${now.target?.link}")
        }
        if (now.link != before.link) add("Link: ${now.link.words()} [${now.link}]")
        if (now.reason != before.reason && now.reason.isNotBlank()) add("Reason: ${now.reason}")
        if (now.failure != before.failure) now.failure?.let { add("Connect failed: ${it.words()} [$it]") }
        if (now.diagnosis != before.diagnosis) now.diagnosis?.let { add("Verdict: ${it.words()} [$it]") }
        if (now.info != before.info) now.info?.let { add("Glasses build: ${it.build}, model ${it.model}") }
        if (before.device == null && now.device != null) add("Device status arrived")
        val old = before.device
        val d = now.device
        if (d != null) {
            if (d.hinge != old?.hinge) add("Hinge: ${d.hinge}")
            if (d.battery?.charging != old?.battery?.charging) add("Charging: ${d.battery?.charging} (${d.battery?.percent}%)")
            if (d.deepSleepPending != old?.deepSleepPending || d.inDeepSleep != old?.inDeepSleep) {
                add("Power: deep sleep pending ${d.deepSleepPending}, in deep sleep ${d.inDeepSleep}")
            }
        }
    }

    private companion object {
        const val LIMIT = 1000
        val QUIET = setOf(com.macedonga.visor.connection.DataFetcher.SVC, com.macedonga.visor.connection.PhoneInternet.SVC)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
