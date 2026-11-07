@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.macedonga.visor.core.media.GlassesApp
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveProblem
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.SessionApp
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.media.videoSize
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.LiveConfig
import com.macedonga.visor.kit.protocol.LiveHeader
import com.macedonga.visor.kit.protocol.LiveKind
import com.macedonga.visor.kit.protocol.LivePhoto
import com.macedonga.visor.kit.protocol.LiveQuality
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Live for VDK apps. The first app to watch starts it, unless it runs already for Visor's own Live view; it stops
 * after the last app, if an app started it. Each watcher gets the codec configs first, then video from a keyframe.
 */
@Singleton
class KitLive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val live: LiveStream,
    private val log: EventLog,
) {
    private var watchers = 0
    private var startedHere = false

    fun watch(caller: Caller, config: LiveConfig): Flow<Pair<LiveHeader, ByteArray>> {
        val quality = QUALITIES[config.quality]
            ?: throw KitException(ErrorCode.INVALID_PARAMS, "The Live comes in ${QUALITIES.keys.joinToString()}, not ${config.quality}.")
        if (config.frameRate !in LiveConfig.FRAME_RATES) {
            throw KitException(ErrorCode.INVALID_PARAMS, "The Live runs at ${LiveConfig.FRAME_RATES.joinToString()} frames a second, not ${config.frameRate}.")
        }
        return channelFlow {
            join(caller, quality, config.frameRate)
            try {
                running()
                launch { video().collect(::send) }
                if (config.audio) launch { audio().collect(::send) }
                val end = live.status.first { it.phase == LivePhase.FAILED || it.phase == LivePhase.IDLE }
                throw ended(end.problem?.name?.lowercase() ?: "stopped")
            } finally {
                leave(caller)
            }
        }.buffer(BUFFER, BufferOverflow.SUSPEND)
    }

    /** A photo from the running Live: a content URI the app may read, or the photo itself for a computer. */
    suspend fun photo(caller: Caller): LivePhoto {
        if (!live.running.value) throw KitException(ErrorCode.BUSY, "The Live is not running.")
        val heic = live.capturePhoto() ?: throw KitException(ErrorCode.TIMEOUT, "The glasses sent no photo.")
        if (caller.shell || caller.network) return LivePhoto(HEIC, data = Base64.getEncoder().encodeToString(heic))
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        val now = System.currentTimeMillis()
        // An app has a few minutes to read its photo.
        folder.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach(File::delete)
        val file = File(folder, "live-$now.heic").apply { writeBytes(heic) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.grantUriPermission(caller.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return LivePhoto(HEIC, uri = uri.toString())
    }

    @Synchronized
    private fun join(caller: Caller, quality: StreamQuality, frameRate: Int) {
        watchers++
        val runs = live.running.value || live.status.value.phase !in setOf(LivePhase.IDLE, LivePhase.FAILED)
        if (!runs) {
            live.start(GlassesApp.LIVE_STREAMING, SessionApp.FAIR_DEMO, quality, BITRATES.getValue(quality), frameRate, overWifi = true)
            startedHere = true
        } else {
            // The watcher joins mid-stream: a keyframe now beats waiting for the next one.
            live.requestKeyframe()
        }
        log.add("Kit: ${caller.packageName} watches the camera live")
    }

    @Synchronized
    private fun leave(caller: Caller) {
        log.add("Kit: ${caller.packageName} stopped watching the camera live")
        if (--watchers > 0 || !startedHere) return
        startedHere = false
        live.stop()
    }

    /** Returns once the Live runs; fails as the Live fails. */
    private suspend fun running() {
        val now = withTimeoutOrNull(START_MS) {
            live.status.first { it.phase == LivePhase.RUNNING || it.phase == LivePhase.RESTARTING || it.phase == LivePhase.FAILED }
        } ?: throw KitException(ErrorCode.TIMEOUT, "The Live did not start in time.")
        val problem = now.problem
        if (now.phase == LivePhase.FAILED && problem != null) {
            if (problem == LiveProblem.NOT_CONNECTED) throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
            throw ended(problem.name.lowercase())
        }
    }

    private fun ended(reason: String) = KitException(ErrorCode.FAILED, "The Live ended: $reason.", reason = reason)

    /** The video from a keyframe, each new config first; after a gap, from the next keyframe again. */
    private fun video(): Flow<Pair<LiveHeader, ByteArray>> = flow {
        var sentConfig: ByteArray? = null
        var lastSeq = -1L
        var waiting = true
        live.receiver.frames.collect { frame ->
            val config = live.receiver.codecConfig.value ?: return@collect
            if (config !== sentConfig) {
                val size = videoSize(config)
                emit(LiveHeader(LiveKind.VIDEO, frame.codec.mime, 0, config = true, width = size?.first, height = size?.second) to config)
                sentConfig = config
                waiting = true
            }
            // Frames lost on the way, or by a watcher too slow for the receiver's buffer: the picture breaks until a keyframe.
            if (lastSeq >= 0 && frame.seq != lastSeq + 1) {
                waiting = true
                live.requestKeyframe()
            }
            lastSeq = frame.seq
            if (waiting && !frame.isKey) return@collect
            waiting = false
            emit(LiveHeader(LiveKind.VIDEO, frame.codec.mime, frame.ptsUs, key = frame.isKey) to frame.data)
        }
    }

    private fun audio(): Flow<Pair<LiveHeader, ByteArray>> = flow {
        var sentFormat: Any? = null
        live.receiver.audio.collect { packet ->
            val format = live.receiver.aac.value ?: return@collect
            if (format !== sentFormat) {
                emit(LiveHeader(LiveKind.AUDIO, AAC, 0, config = true, sampleRate = format.sampleRate, channels = format.channels) to format.asc)
                sentFormat = format
            }
            emit(LiveHeader(LiveKind.AUDIO, AAC, packet.ptsUs) to packet.data)
        }
    }

    private companion object {
        val QUALITIES = mapOf(
            LiveQuality.LOW to StreamQuality.VERY_LOW,
            LiveQuality.MEDIUM to StreamQuality.MEDIUM,
            LiveQuality.HIGH to StreamQuality.HIGH,
            LiveQuality.FULL_HD to StreamQuality.FULL_HD,
        )

        // The Live view's own rungs for each size.
        val BITRATES = mapOf(
            StreamQuality.VERY_LOW to 600_000,
            StreamQuality.MEDIUM to 1_500_000,
            StreamQuality.HIGH to 2_500_000,
            StreamQuality.FULL_HD to 4_000_000,
        )

        const val AAC = "audio/mp4a-latm"
        const val HEIC = "image/heic"
        const val FOLDER = "kit"
        const val KEEP_MS = 10 * 60_000L

        // Wi-Fi Direct alone can take half a minute to come up.
        const val START_MS = 90_000L

        // Records on their way to the app's socket; past it, Visor waits for the app.
        const val BUFFER = 64
    }
}
