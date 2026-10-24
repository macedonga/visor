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
// What the camera follows and marks each capture with: 1 landscape, 0 portrait.
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
 * Media orientation is the one landscape control. The camera follows capture_aspect_ratio and marks each capture with
 * it, still taking the whole portrait frame; Visor crops the landscape ones as it brings them over. The older
 * landscape_mode_enabled follows it, and with landscape Meta's landscape switch in the glasses' configuration goes on
 * too, which Meta's server turns off.
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
        combine(
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
            settings.state.map { s ->
                // Unknown until the first read; never stored, the camera takes portrait.
                if (s.readAt == null) null else same(Kind.INT, s.pending[ASPECT] ?: s.stored[ASPECT] ?: settings.catalogue[ASPECT]?.default, "1")
            }.distinctUntilChanged(),
        ) { ready, on -> on.takeIf { ready } }.collectLatest { on ->
            on ?: return@collectLatest
            val legacy = settings.state.value.pending[LANDSCAPE_MODE] ?: settings.value(LANDSCAPE_MODE)
            if (!same(Kind.BOOL, legacy, on.toString()) && settings.write(LANDSCAPE_MODE, on.toString())) {
                log.add("Camera: landscape mode follows Media orientation, ${if (on) "on" else "off"}")
            }
            if (!on) return@collectLatest
            if (config.state.value.params.isEmpty()) {
                // The configuration comes in a bug report: never on top of photos to bring over.
                combine(media.importing, media.captures) { importing, captures -> !importing && captures.none { it.isNew && it.isMedia } }
                    .first { it }
                log.add("Camera: reading the glasses' configuration for landscape")
                config.read()
                withTimeoutOrNull(READ_MS) { config.state.first { it.params.isNotEmpty() } } ?: return@collectLatest
            }
            val state = config.state.first { it.busy.isEmpty() }
            if (LANDSCAPE_FLAG !in state.params || state.values[LANDSCAPE_FLAG] == true) return@collectLatest
            log.add("Camera: turning on Meta's landscape switch")
            val why = config.send(mapOf(LANDSCAPE_FLAG to true))
            log.add(if (why == null) "Camera: Meta's landscape switch is on; it applies once the glasses restart" else "Camera: Meta's landscape switch did not go: $why")
        }
    }
}

/**
 * Whether a video this size came out of the glasses' own two-stage stabilization, from the output sizes in their
 * settings (1080p, and 3K and 60 fps on firmware that has those). Any other video is steadied on the phone.
 */
fun steadiedOnGlasses(settings: GlassesSettings): (Int, Int) -> Boolean = { w, h ->
    listOf("", "_3k", "_60fps").any { suffix ->
        val ow = settings.value("two_stage_eis_output_width$suffix")?.trim()?.toIntOrNull()
        val oh = settings.value("two_stage_eis_output_height$suffix")?.trim()?.toIntOrNull()
        ow != null && oh != null && minOf(w, h) == minOf(ow, oh) && maxOf(w, h) == maxOf(ow, oh)
    }
}
