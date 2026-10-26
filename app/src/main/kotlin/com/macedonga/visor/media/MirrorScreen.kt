package com.macedonga.visor.media

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.Screencast
import com.macedonga.visor.core.media.ScreencastFault
import com.macedonga.visor.core.media.ScreencastPhase
import com.macedonga.visor.core.media.ScreencastState
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.SettingsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltViewModel
class MirrorModel @Inject constructor(val screencast: Screencast, private val log: EventLog) : ViewModel() {
    // The decoder is touched on this one thread only.
    private val decoding = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var decoder: MirrorDecoder? = null

    private val feed = decoding.launch { screencast.frames.collect { decoder?.feed(it) } }

    init {
        viewModelScope.launch {
            screencast.state.collect { s ->
                when (s.phase) {
                    ScreencastPhase.FAILED -> log.add("Mirror failed: ${s.fault}")
                    ScreencastPhase.LIVE -> log.add("Mirror live")
                    else -> Unit
                }
            }
        }
    }

    /** The surface the picture goes to, or null when it is gone. */
    fun attach(surface: Surface?) {
        decoding.launch {
            decoder?.release()
            decoder = surface?.let { MirrorDecoder(it) }
        }
    }

    override fun onCleared() {
        decoding.launch { decoder?.release(); decoder = null }
        feed.cancel()
        super.onCleared()
    }
}

/** The Display's screen, on the phone. Closing the page stops the stream. */
@Composable
fun MirrorScreen(model: MirrorModel = hiltViewModel()) {
    val state by model.screencast.state.collectAsStateWithLifecycle()
    val active = state.phase == ScreencastPhase.STARTING || state.phase == ScreencastPhase.LIVE
    DisposableEffect(model) { onDispose { model.screencast.stop() } }

    SettingsPage(title = stringResource(R.string.mirror_title)) {
        item(key = "picture") {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = model.attach(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = model.attach(null)
                        })
                    }
                },
            )
        }
        item(key = "controls") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(stringResource(R.string.mirror_start), onClick = { model.screencast.start() }, enabled = !active)
                ActionPill(stringResource(R.string.mirror_stop), onClick = { model.screencast.stop() }, quiet = true, enabled = active)
            }
        }
        item(key = "state") {
            Text(
                phraseOf(state),
                style = Visor.type.label,
                color = Visor.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun phraseOf(s: ScreencastState): String = when (s.phase) {
    ScreencastPhase.IDLE -> stringResource(R.string.mirror_idle)
    ScreencastPhase.STARTING -> stringResource(R.string.mirror_starting)
    ScreencastPhase.LIVE -> stringResource(R.string.mirror_live)
    ScreencastPhase.FAILED -> when (s.fault) {
        ScreencastFault.NOT_CONNECTED -> stringResource(R.string.mirror_not_connected)
        ScreencastFault.NOT_READ -> stringResource(R.string.mirror_not_read)
        ScreencastFault.NO_FLAG -> stringResource(R.string.mirror_no_flag)
        ScreencastFault.FLAG_NOT_SENT -> stringResource(R.string.mirror_flag_not_sent, s.detail)
        ScreencastFault.NEEDS_RESTART -> stringResource(R.string.mirror_needs_restart)
        ScreencastFault.NO_WIFI -> stringResource(R.string.mirror_no_wifi, s.detail)
        ScreencastFault.NO_ANSWER -> stringResource(R.string.mirror_no_answer)
        ScreencastFault.ASLEEP -> stringResource(R.string.mirror_asleep)
        ScreencastFault.GLASSES_TIMEOUT -> stringResource(R.string.mirror_timeout)
        ScreencastFault.UNKNOWN, null -> stringResource(R.string.mirror_unknown)
    }
}
