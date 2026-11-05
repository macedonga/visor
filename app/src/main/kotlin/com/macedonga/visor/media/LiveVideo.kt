package com.macedonga.visor.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.macedonga.visor.core.media.LiveDecoder
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.Playout
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stream's picture in a TextureView; [onShown] runs on the main thread for each frame shown. */
class LiveVideo(private val live: LiveStream) {
    private var view: TextureView? = null
    private var feeder: Feeder? = null
    var onShown: () -> Unit = {}

    fun attach(textureView: TextureView) {
        view = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                feeder?.stop()
                feeder = Feeder(live, Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                feeder?.stop()
                feeder = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = onShown()
        }
    }

    fun release() {
        feeder?.stop()
        feeder = null
        view = null
    }

    /** The picture shrunk to [w]×[h], for its colours. Main thread. */
    fun sample(w: Int, h: Int): Bitmap? = view?.takeIf { it.isAvailable }?.getBitmap(w, h)

    /** The picture at the size it was decoded, as a JPEG. Null when nothing is showing. Call on the main thread. */
    suspend fun grab(): ByteArray? {
        val size = live.decodedSize.value
        val bitmap = view?.takeIf { it.isAvailable }?.let { v ->
            if (size != null) v.getBitmap(size.width, size.height) else v.bitmap
        } ?: return null
        return withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                bitmap.recycle()
            }.toByteArray()
        }
    }
}

@Composable
fun LiveVideoView(video: LiveVideo, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { TextureView(it).apply { isOpaque = true }.also(video::attach) })
    DisposableEffect(video) { onDispose(video::release) }
}

@Composable
fun rememberLiveVideo(live: LiveStream): LiveVideo = remember(live) { LiveVideo(live) }

// Later than this past its turn, a frame is decoded but not shown, so the decoder catches up without a keyframe.
private const val CATCH_UP_MS = 250L

// Waiting this long for a keyframe, configure the stream again: that restarts the encoder on one.
private const val KEY_WAIT_MS = 2_000L

/** Feeds each frame at its turn to a decoder rebuilt on a new stream, size or error. */
private class Feeder(private val live: LiveStream, private val surface: Surface) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val job = scope.launch {
        var decoder: LiveDecoder? = null
        var playout = Playout()
        var generation = -1
        var lastSeq = -1L
        var needKey = true
        var needKeySince = System.nanoTime()
        var fedConfig: ByteArray? = null
        var shownBase = 0L
        var shownAt = System.nanoTime()

        fun wantKey() {
            if (!needKey) needKeySince = System.nanoTime()
            needKey = true
        }

        try {
            live.receiver.frames.collect { frame ->
                val current = decoder
                if (current == null || live.generation.value != generation || current.failed) {
                    if (current?.failed == true) live.note("The decoder failed; starting a new one")
                    current?.release()
                    val q = live.requested
                    decoder = LiveDecoder(surface, q.width, q.height) { w, h -> live.decodedSize.value = Size(w, h) }
                    playout = Playout()
                    shownBase = 0
                    generation = live.generation.value
                    fedConfig = live.receiver.codecConfig.value
                    wantKey()
                }
                val d = decoder!!

                val config = live.receiver.codecConfig.value
                if (config != null && !config.contentEquals(fedConfig)) {
                    if (d.started) d.feedConfig(config)
                    fedConfig = config
                    wantKey()
                }

                if (lastSeq >= 0 && frame.seq != lastSeq + 1) wantKey()
                lastSeq = frame.seq
                val due = playout.due(frame.ptsUs, frame.arrivedNs)

                if (needKey && !frame.isKey) {
                    live.skipped.value += 1
                    val now = System.nanoTime()
                    if ((now - needKeySince) / 1_000_000 > KEY_WAIT_MS) {
                        live.requestKeyframe()
                        needKeySince = now
                    }
                    return@collect
                }
                val early = (due - System.nanoTime()) / 1_000_000
                if (early > 0) delay(early)
                val now = System.nanoTime()
                live.waitMs.value = (now - frame.arrivedNs) / 1_000_000
                val used = runCatching { d.feed(frame, fedConfig, show = (now - due) / 1_000_000 <= CATCH_UP_MS) }.getOrDefault(false)
                if (used) needKey = false else wantKey()

                if (now - shownAt >= 1_000_000_000L) {
                    live.shownFps.value = ((d.shown - shownBase) * 1_000_000_000L / (now - shownAt)).toInt()
                    shownBase = d.shown
                    shownAt = now
                }
            }
        } finally {
            decoder?.release()
        }
    }

    /** The surface goes only after the decoder drawing into it is released. */
    fun stop() {
        job.invokeOnCompletion { surface.release() }
        scope.cancel()
    }
}
