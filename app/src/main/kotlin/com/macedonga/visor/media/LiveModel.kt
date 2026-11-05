package com.macedonga.visor.media

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.BroadcastCodec
import com.macedonga.visor.core.media.BroadcastTarget
import com.macedonga.visor.core.media.LADDER
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LiveSettings
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.RecordStart
import com.macedonga.visor.gallery.duration
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A word over the picture; [uri] can be opened, [glassesHost] offers the glasses' network. */
data class Notice(
    @StringRes val text: Int,
    val arg: String? = null,
    val uri: Uri? = null,
    val warning: Boolean = false,
    val glassesHost: Boolean = false,
    val icon: ImageVector? = null,
    val at: Long = System.currentTimeMillis(),
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    val live: LiveStream,
    val connection: GlassesConnection,
    val settings: LiveSettingsStore,
    val assistant: Assistant,
) : ViewModel() {
    val outputs = live.outputs

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    fun start() {
        live.start(settings.settings.value)
    }

    fun stop() = live.stop()

    /** Whether the Live has a reason to go on with nobody watching: something is being made of it. */
    fun keepsGoing(): Boolean = outputs.recording.value != null || outputs.broadcast.running

    fun say(@StringRes text: Int, arg: String? = null, uri: Uri? = null, warning: Boolean = false, glassesHost: Boolean = false, icon: ImageVector? = null) {
        _notice.value = Notice(text, arg, uri, warning, glassesHost, icon)
    }

    /** From now on the glasses host the Wi-Fi, and the stream running now moves there. */
    fun useGlassesHost() {
        settings.update { it.copy(glassesHost = true) }
        live.useGlassesHost()
        _notice.value = null
    }

    fun dismiss(notice: Notice) {
        if (_notice.value == notice) _notice.value = null
    }

    fun toggleRecording() {
        viewModelScope.launch {
            if (outputs.recording.value != null) {
                val uri = outputs.stopRecording()
                if (uri == null) say(R.string.live_nothing_yet) else say(R.string.live_saved_video, uri = uri)
            } else when (outputs.startRecording()) {
                RecordStart.NOT_READY -> say(R.string.live_nothing_yet)
                RecordStart.FAILED -> say(R.string.live_save_failed, warning = true)
                RecordStart.STARTED -> Unit
            }
        }
    }

    fun saveReplay() {
        viewModelScope.launch {
            val held = outputs.replay.heldUs
            val uri = try {
                outputs.saveReplay()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                return@launch say(R.string.live_save_failed, warning = true)
            }
            if (uri == null) say(R.string.live_nothing_yet) else say(R.string.live_saved_replay, duration(held / 1000), uri)
        }
    }

    /** The picture on screen saved as a photo; from the replay ring when nothing is on screen. */
    fun snapshot(video: LiveVideo?) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            val jpeg = video?.grab()
            val uri = if (jpeg != null) outputs.savePhoto(jpeg, now) else outputs.saveFrame()
            if (uri == null) say(R.string.live_nothing_yet) else say(R.string.live_saved_photo, uri = uri)
        }
    }

    /** A real photo from the glasses' camera, at their capture size, while the stream goes on. */
    fun glassesPhoto() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val heic = live.capturePhoto()
            val uri = heic?.let { outputs.savePhoto(it, now, heic = true) }
            if (uri == null) say(R.string.live_photo_failed, warning = true, icon = VisorIcons.Camera) else say(R.string.live_saved_glasses_photo, uri = uri)
        }
    }

    fun startBroadcast() {
        val s = settings.settings.value
        val config = outputs.config ?: return say(R.string.live_nothing_yet)
        val rung = live.rung.value ?: LADDER.first()
        // H.264 needs about half as much again for the same picture.
        val bitrate = if (s.rtmpCodec == BroadcastCodec.H264) minOf(6_000_000, rung.bitrate * 3 / 2) else rung.bitrate
        outputs.broadcast.start(BroadcastTarget(s.rtmpUrl, s.rtmpKey, s.rtmpCodec), config, rung.frameRate, bitrate) { outputs.aac }
    }

    fun stopBroadcast() = outputs.broadcast.stop()

    fun update(change: (LiveSettings) -> LiveSettings) = settings.update(change)

    fun setOverlay(overlay: LiveOverlay, on: Boolean) = settings.update { it.copy(overlays = if (on) it.overlays + overlay else it.overlays - overlay) }

    init {
        // The replay ring follows the setting, whoever changes it.
        viewModelScope.launch {
            settings.settings.collect { outputs.replay.windowUs = it.replaySeconds * 1_000_000L }
        }
        // Notices go after a few seconds, warnings after longer, a switch offer after longer still.
        viewModelScope.launch {
            notice.collectLatest { n ->
                if (n == null) return@collectLatest
                delay(if (n.glassesHost) 12_000 else if (n.warning) 6_000 else 3_500)
                dismiss(n)
            }
        }
        // A stream on the phone's group that keeps stopping: the glasses' own network may hold.
        viewModelScope.launch {
            live.suggestGlassesHost.collect { if (it) say(R.string.live_drops, warning = true, glassesHost = true, icon = VisorIcons.Wifi) }
        }
    }
}
