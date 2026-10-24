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

/** Meta's server switch for landscape capture, which the older landscape path needs with landscape_mode_enabled. */
const val LANDSCAPE_FLAG = "mwa_ios_landscape_mode.enabled"

private const val LANDSCAPE_MODE = "landscape_mode_enabled"
// What the camera follows since 7238: 1 landscape, 0 portrait.
private const val ASPECT = "capture_aspect_ratio"
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
 * "Landscape mode" sets landscape the way the firmware reads it now. The 7238 camera takes the orientation from
 * capture_aspect_ratio, which stays portrait with only landscape_mode_enabled on: the switch sets it to landscape, and
 * back to portrait when turned off. Meta's landscape switch in the glasses' configuration goes on too, for the older
 * path; Meta's server turns it off. The camera reads that one at boot.
 */
fun keepLandscape(
    scope: CoroutineScope,
    connection: GlassesConnection,
    settings: GlassesSettings,
    config: GlassesConfig,
    media: MediaImport,
    log: EventLog,
) {
    scope.launch {
        var was: Boolean? = null
        combine(
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
            settings.state.map { s -> same(Kind.BOOL, s.pending[LANDSCAPE_MODE] ?: s.stored[LANDSCAPE_MODE], "true") }.distinctUntilChanged(),
        ) { ready, on -> on.takeIf { ready } }.collectLatest { on ->
            on ?: return@collectLatest
            val turnedOff = was == true && !on
            was = on
            val aspect = settings.state.value.pending[ASPECT] ?: settings.value(ASPECT)
            // Off at start leaves the orientation as it is: it has its own row, Media orientation.
            val want = if (on) "1" else "0".takeIf { turnedOff }
            if (want != null && !same(Kind.INT, aspect, want) && settings.write(ASPECT, want)) {
                log.add("Camera: media orientation follows Landscape mode, ${if (on) "landscape" else "portrait"}")
            }
            if (!on) return@collectLatest
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
            log.add(if (why == null) "Camera: Meta's landscape switch is on; it applies once the glasses restart" else "Camera: Meta's landscape switch did not go: $why")
        }
    }
}
