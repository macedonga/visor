package com.macedonga.visor.settings

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.Kind
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.log.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The glasses' own camera switches for Meta AI. With either off, they refuse every question about what the wearer sees. */
val GLASSES_CAMERA_KEYS = setOf("cloud_ai_understanding_enabled", "camera_use_enabled")

/** Meta's server switch for landscape capture. The glasses shoot landscape only with it and their own switch both on. */
const val LANDSCAPE_FLAG = "mwa_ios_landscape_mode.enabled"

private const val LANDSCAPE_MODE = "landscape_mode_enabled"
private const val READ_MS = 5 * 60_000L

/** Visor's "Use the camera" is the one switch: whenever it changes, and after every read, the glasses' two follow it. */
fun keepCameraInLine(scope: CoroutineScope, settings: GlassesSettings, store: AssistantConfigStore, log: EventLog) {
    scope.launch {
        combine(
            store.config.map { it.vision }.distinctUntilChanged(),
            settings.state.map { it.readAt }.distinctUntilChanged(),
        ) { vision, readAt -> vision to readAt }.collect { (vision, readAt) ->
            if (readAt == null) return@collect
            val state = settings.state.value
            val want = vision.toString()
            // Not one already on its way, nor one the glasses have refused: that would write it after every read.
            val sent = GLASSES_CAMERA_KEYS
                .filter { it !in state.pending && it !in state.kept && !same(settings.catalogue[it]?.kind, settings.value(it), want) }
                .filter { settings.write(it, want) }
            if (sent.isNotEmpty()) log.add("Camera: the glasses' own camera use follows Visor's switch, ${if (vision) "on" else "off"}")
        }
    }
}

/**
 * With "Landscape mode" on, Meta's landscape switch in the glasses' configuration goes on too: Meta's server turns it
 * off, and the glasses' own switch then does nothing. The camera reads it at boot, so it takes a restart.
 */
fun keepLandscapeFlag(
    scope: CoroutineScope,
    connection: GlassesConnection,
    settings: GlassesSettings,
    config: GlassesConfig,
    media: MediaImport,
    log: EventLog,
) {
    scope.launch {
        combine(
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
            settings.state.map { s -> same(Kind.BOOL, s.pending[LANDSCAPE_MODE] ?: s.stored[LANDSCAPE_MODE], "true") }.distinctUntilChanged(),
        ) { ready, on -> ready && on }.collectLatest { wanted ->
            if (!wanted) return@collectLatest
            if (config.state.value.params.isEmpty()) {
                // The configuration comes in a bug report: never on top of photos to bring over.
                combine(media.importing, media.captures) { importing, captures -> !importing && captures.none { it.isNew && it.isMedia } }
                    .first { it }
                log.add("Camera: reading the glasses' configuration for landscape mode")
                config.read()
                withTimeoutOrNull(READ_MS) { config.state.first { it.params.isNotEmpty() } } ?: return@collectLatest
            }
            val state = config.state.first { it.busy.isEmpty() }
            if (LANDSCAPE_FLAG !in state.params || state.values[LANDSCAPE_FLAG] == true) return@collectLatest
            log.add("Camera: turning on Meta's landscape switch for landscape mode")
            val why = config.send(mapOf(LANDSCAPE_FLAG to true))
            log.add(if (why == null) "Camera: landscape mode is on; restart the glasses to use it" else "Camera: Meta's landscape switch did not go: $why")
        }
    }
}
