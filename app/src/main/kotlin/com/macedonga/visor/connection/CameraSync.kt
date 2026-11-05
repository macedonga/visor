package com.macedonga.visor.connection

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.GlassesConfig
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

/** The glasses' own camera switches for Meta AI. With either off, they refuse every question about what the wearer sees. */
val GLASSES_CAMERA_KEYS = setOf("cloud_ai_understanding_enabled", "camera_use_enabled")

/** Meta's server switch for landscape capture, which the older landscape path needs with landscape_mode_enabled. */
private const val LANDSCAPE_FLAG = "mwa_ios_landscape_mode.enabled"

private const val LANDSCAPE_MODE = "landscape_mode_enabled"
// What the camera follows and marks each capture with: 1 landscape, 0 portrait.
private const val ASPECT = "capture_aspect_ratio"
private const val READ_MS = 5 * 60_000L

/** Visor's "Use the camera" is the one switch: whenever it changes, and after every read, the glasses' two follow it. */
fun keepCameraInLine(scope: CoroutineScope, settings: GlassesSettings, store: AssistantConfigStore, log: EventLog) {
    scope.launch {
        var lastVision: Boolean? = null
        combine(
            store.config.map { it.vision }.distinctUntilChanged(),
            settings.state.map { it.readAt }.distinctUntilChanged(),
        ) { vision, readAt -> vision to readAt }.collect { (vision, readAt) ->
            if (readAt == null) return@collect
            val flipped = vision != lastVision
            lastVision = vision
            val state = settings.state.value
            val want = vision.toString()
            // After a read, not one on its way or one the glasses kept; a flip of the switch writes again.
            val due = GLASSES_CAMERA_KEYS.filter { it !in state.pending && (flipped || it !in state.kept) && !same(settings.catalogue[it]?.kind, settings.value(it), want) }
            if (due.count { settings.write(it, want) } > 0) log.add("Camera: the glasses' own camera use follows Visor's switch, ${if (vision) "on" else "off"}")
        }
    }
}

/** Keeps landscape_mode_enabled and Meta's landscape flag, which Meta's server turns off, in line with capture_aspect_ratio. */
fun keepLandscape(
    scope: CoroutineScope,
    connection: GlassesConnection,
    settings: GlassesSettings,
    config: GlassesConfig,
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
            if (!config.readOnce(READ_MS) { log.add("Camera: reading the glasses' configuration for landscape") }) return@collectLatest
            val state = config.state.first { it.busy == null }
            if (LANDSCAPE_FLAG !in state.params || state.values[LANDSCAPE_FLAG] == true) return@collectLatest
            log.add("Camera: turning on Meta's landscape switch")
            val why = config.send(mapOf(LANDSCAPE_FLAG to true))
            log.add(if (why == null) "Camera: Meta's landscape switch is on; it applies once the glasses restart" else "Camera: Meta's landscape switch did not go: $why")
        }
    }
}

/** Whether a video this size was steadied on the glasses: one of their two-stage EIS output sizes. */
fun steadiedOnGlasses(settings: GlassesSettings): (Int, Int) -> Boolean = { w, h ->
    listOf("", "_3k", "_60fps").any { suffix ->
        val ow = settings.value("two_stage_eis_output_width$suffix")?.trim()?.toIntOrNull()
        val oh = settings.value("two_stage_eis_output_height$suffix")?.trim()?.toIntOrNull()
        ow != null && oh != null && minOf(w, h) == minOf(ow, oh) && maxOf(w, h) == maxOf(ow, oh)
    }
}
