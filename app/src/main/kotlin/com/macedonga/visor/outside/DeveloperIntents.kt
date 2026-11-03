package com.macedonga.visor.outside

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.session.LinkState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Visor for other apps, automations and the launcher, while [DeveloperMode] is on.
 *
 * In, as a broadcast to [DeveloperReceiver] or an activity start of [DeveloperActivity]: TAKE_PHOTO; START_VIDEO and
 * TOGGLE_VIDEO, both with an optional capture_type video|hyperlapse; STOP_VIDEO; IMPORT_MEDIA with the id a
 * MEDIA_AVAILABLE gave. With the token required, each carries token.
 *
 *     adb shell am broadcast -p com.macedonga.visor -a com.macedonga.visor.action.TAKE_PHOTO
 *     adb shell am broadcast -p com.macedonga.visor -a com.macedonga.visor.action.IMPORT_MEDIA --es id <id>
 *
 * Out, as broadcasts:
 * - MEDIA_AVAILABLE (id, type, time, size): a capture on the glasses this phone does not have yet.
 * - IMPORT_PROGRESS (id, received, size, percent), then NEW_MEDIA (id, type, time, uri, path) once it is in the
 *   gallery, or IMPORT_FAILED (id, reason).
 * - VIDEO_STARTED and VIDEO_STOPPED (time).
 *
 * type is photo, live or video; time is in milliseconds since the epoch, when the capture was taken.
 *
 * Live frames: bind [FrameService] with LIVE_FRAMES and send FRAMES_SUBSCRIBE (token, interval_ms) with a replyTo;
 * FRAMES_FRAME (jpeg, time) comes back while a Live runs, or FRAMES_REFUSED (reason OFF or TOKEN).
 */
object DeveloperIntents {
    const val TAKE_PHOTO = "com.macedonga.visor.action.TAKE_PHOTO"
    const val START_VIDEO = "com.macedonga.visor.action.START_VIDEO"
    const val STOP_VIDEO = "com.macedonga.visor.action.STOP_VIDEO"
    const val TOGGLE_VIDEO = "com.macedonga.visor.action.TOGGLE_VIDEO"
    const val IMPORT_MEDIA = "com.macedonga.visor.action.IMPORT_MEDIA"
    val ACTIONS = setOf(TAKE_PHOTO, START_VIDEO, STOP_VIDEO, TOGGLE_VIDEO, IMPORT_MEDIA)

    const val MEDIA_AVAILABLE = "com.macedonga.visor.event.MEDIA_AVAILABLE"
    const val IMPORT_PROGRESS = "com.macedonga.visor.event.IMPORT_PROGRESS"
    const val IMPORT_FAILED = "com.macedonga.visor.event.IMPORT_FAILED"
    const val NEW_MEDIA = "com.macedonga.visor.event.NEW_MEDIA"
    const val VIDEO_STARTED = "com.macedonga.visor.event.VIDEO_STARTED"
    const val VIDEO_STOPPED = "com.macedonga.visor.event.VIDEO_STOPPED"

    const val LIVE_FRAMES = "com.macedonga.visor.action.LIVE_FRAMES"
    const val FRAMES_SUBSCRIBE = 1
    const val FRAMES_UNSUBSCRIBE = 2
    const val FRAMES_FRAME = 3
    const val FRAMES_REFUSED = 4

    const val EXTRA_TOKEN = "token"
    const val EXTRA_INTERVAL_MS = "interval_ms"
    const val EXTRA_JPEG = "jpeg"
    const val EXTRA_CAPTURE_TYPE = "capture_type"
    const val EXTRA_ID = "id"
    const val EXTRA_TYPE = "type"
    const val EXTRA_TIME = "time"
    const val EXTRA_SIZE = "size"
    const val EXTRA_RECEIVED = "received"
    const val EXTRA_PERCENT = "percent"
    const val EXTRA_URI = "uri"
    const val EXTRA_PATH = "path"
    const val EXTRA_REASON = "reason"
}

@AndroidEntryPoint
class DeveloperReceiver : BroadcastReceiver() {
    @Inject lateinit var developer: DeveloperMode

    override fun onReceive(context: Context, intent: Intent) {
        developer.scope.launch { developer.handle(intent) }
    }
}

/** The same, for whatever starts activities: launcher shortcuts and automation apps. It shows nothing. */
@AndroidEntryPoint
class DeveloperActivity : ComponentActivity() {
    @Inject lateinit var connection: GlassesConnection
    @Inject lateinit var developer: DeveloperMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asked = intent
        // Woken from here, while the activity is in front, the connection may start from the background.
        if (connection.state.value.link != LinkState.READY) runCatching { GlassesService.reconnect(this) }
        developer.scope.launch { developer.handle(asked) }
        finish()
    }
}
