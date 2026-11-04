package com.macedonga.visor.language

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.log.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Meta's switch for its session ("SCI") assistant path, on for English. There the glasses never speak Visor's text. */
const val SESSION_PATH_FLAG = "wearables_ai_phone_call.session_centric_air_infra"

/** How long the glasses wait for an answer before they speak their own error; 20 s from the factory. */
const val PROCESSING_TIMEOUT = "assistant_on_sn.default_processing_timeout_s"

private const val CAPTION_VOICE = "live_caption_voice_id"

/**
 * What Visor's assistant needs in the glasses' configuration, kept each time they connect or it changes: with the
 * assistant on, the processing timeout chosen in the app; with answers in their own voice, Meta's session flag off,
 * since only the classic path speaks Visor's text in every language. The configuration is read once if it never was.
 * The flag takes a restart of their assistant; the timeout applies at once.
 */
fun keepAssistantConfig(
    scope: CoroutineScope,
    connection: GlassesConnection,
    config: GlassesConfig,
    media: com.macedonga.visor.core.media.MediaImport,
    settings: GlassesSettings,
    store: AssistantConfigStore,
    log: EventLog,
) {
    scope.launch {
        combine(
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
            store.config.map { Triple(it.enabled, it.glassesVoice, it.answerWaitSeconds.toLong()) }.distinctUntilChanged(),
            // A phone language has no glasses voice to keep. Settings come in on connect, well inside the settle below.
            settings.state.map { settings.value("assistant_voice_id")?.startsWith("3p_") == true }.distinctUntilChanged(),
        ) { ready, wanted, partner ->
            Triple(wanted.first, wanted.second && !partner, wanted.third).takeIf { ready }
        }.collectLatest { wanted ->
            val (enabled, own, wait) = wanted ?: return@collectLatest
            if (!(enabled || own)) return@collectLatest
            // Let the link settle; the configuration comes in a bug report, a minute over Bluetooth.
            delay(SETTLE_MS)
            if (config.state.value.params.isEmpty()) {
                // The configuration comes in a bug report of several megabytes: never on top of photos to bring over.
                combine(media.importing, media.captures) { importing, captures -> !importing && captures.none { it.isNew && it.isMedia } }
                    .first { it }
                log.add("Assistant: reading the glasses' configuration once")
                config.read()
                withTimeoutOrNull(READ_MS) { config.state.first { it.params.isNotEmpty() } } ?: return@collectLatest
            }
            val state = config.state.first { it.busy.isEmpty() }
            val changes = buildMap<String, Any> {
                if (enabled && PROCESSING_TIMEOUT in state.params && (state.values[PROCESSING_TIMEOUT] as? Number)?.toLong() != wait) put(PROCESSING_TIMEOUT, wait)
                if (own && SESSION_PATH_FLAG in state.params && state.values[SESSION_PATH_FLAG] == true) put(SESSION_PATH_FLAG, false)
            }
            if (changes.isEmpty()) return@collectLatest
            if (PROCESSING_TIMEOUT in changes) log.add("Assistant: letting the glasses wait $wait s for an answer, not ${state.values[PROCESSING_TIMEOUT]}")
            if (SESSION_PATH_FLAG in changes) log.add("Own voice: turning Meta's session path off, so the glasses speak answers in every language")
            config.apply(changes)
            val written = withTimeoutOrNull(WRITE_MS) {
                config.state.first { s -> s.busy.isEmpty() && changes.all { (key, value) -> s.values[key] == value } }
            }
            if (written == null) return@collectLatest log.add("Assistant: the glasses did not take the change: ${config.state.value.note}")
            if (SESSION_PATH_FLAG !in changes) return@collectLatest
            log.add(if (restartGlassesAssistant(settings)) "Own voice: restarting the glasses' assistant to apply it" else "Own voice: restart the glasses to apply it")
        }
    }
}

/**
 * Restart only the glasses' assistant, which reads MobileConfig afresh when it starts. It exits on any change of
 * live_caption_voice_id, so that voice goes to another value and straight back.
 */
suspend fun restartGlassesAssistant(settings: GlassesSettings): Boolean {
    // The value to put back must be the glasses' own, so read them first if this run has not.
    if (CAPTION_VOICE !in settings.state.value.stored) {
        settings.read()
        withTimeoutOrNull(SETTINGS_MS) { settings.state.first { CAPTION_VOICE in it.stored } } ?: return false
    }
    val was = settings.state.value.stored[CAPTION_VOICE].orEmpty()
    val other = if (was == "en_US.mai_03") "en_US.mai_01" else "en_US.mai_03"
    if (!settings.write(CAPTION_VOICE, other)) return false
    delay(1_500)
    return settings.write(CAPTION_VOICE, was)
}

private const val SETTLE_MS = 20_000L
private const val READ_MS = 5 * 60_000L
private const val WRITE_MS = 2 * 60_000L
private const val SETTINGS_MS = 20_000L
