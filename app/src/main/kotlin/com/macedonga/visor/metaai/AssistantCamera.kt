package com.macedonga.visor.metaai

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.CameraAction
import com.macedonga.visor.core.assistant.LiveAiCamera
import com.macedonga.visor.core.assistant.VOICE_PHOTOS_OFF
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.connection.VideoKind
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.outside.ROBUSTA
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The glasses' camera for the assistant: the Live AI stream, and the photos and videos asked by voice. */
@Singleton
class AssistantCamera @Inject constructor(
    private val live: LiveStream,
    private val shutter: Shutter,
    private val config: AssistantConfigStore,
    private val glassesConfig: GlassesConfig,
    private val connection: GlassesConnection,
) : LiveAiCamera {

    // A Live view already running is the camera; only a stream Live AI opened is Live AI's to close.
    override fun on() = live.startLiveAi()

    override fun off() {
        if (live.forLiveAi) live.stop()
    }

    override val frames: Flow<Boolean> = live.status.map { it.phase == LivePhase.RUNNING }.distinctUntilChanged()

    suspend fun act(action: CameraAction): Boolean = when (action) {
        CameraAction.TAKE_PHOTO -> {
            // The switch as the wearer left it, and the glasses told so: a value from before it went off does not count.
            check(config.config.value.voicePhotos) { VOICE_PHOTOS_OFF }
            check(glassesConfig.state.value.values[ROBUSTA] == true) { "the glasses are not set up for voice photos yet; try again in a minute" }
            shutter.photo()
        }
        CameraAction.RECORD_VIDEO, CameraAction.RECORD_HYPERLAPSE -> {
            check(!shutter.recording.first()) { "a video is already recording; stop it before starting another" }
            shutter.video(if (action == CameraAction.RECORD_HYPERLAPSE) VideoKind.HYPERLAPSE else VideoKind.VIDEO)
        }
        CameraAction.STOP_RECORDING -> {
            // Away, or before the glasses said what the camera does, stop() would answer "stopped" unasked.
            val state = connection.state.value
            check(state.link == LinkState.READY) { "the glasses are not connected" }
            check(state.device?.camera != null) { "the glasses have not said whether they are recording" }
            shutter.stop()
        }
    }
}
