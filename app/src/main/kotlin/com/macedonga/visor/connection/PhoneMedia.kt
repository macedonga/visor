package com.macedonga.visor.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.macedonga.visor.R
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.log.responderScope
import com.macedonga.visor.notify.MirrorListener
import com.macedonga.visor.phone.mayOpenApps
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * svc9012, partner_ecosystem, for what the phone plays. The Display subscribes (type 5) on connect and takes, on that
 * channel, MediaPlayerInfo {1 player, 2 content type, 3 liked, 4 package} (type 100) and the cover art as a JPEG in
 * 1024-byte pieces (101, the last one 102; an empty 102 clears it), again whenever the playing session changes. With an
 * Android phone the Display's music screen takes the cover only from here; title and artist come over AVRCP.
 * SystemUI's music screen asks for playlists (8, 12), answered empty (9, 13), likes (15, the heart or thumb rating or
 * the app's like action; no reply, the new state goes in the next 100) and asks for notification access (11). The
 * native music handler launches apps (1, and 7 with a package) on a channel it closes at once, so they get no reply;
 * an app starts from the foreground or as a companion only. Other requests are logged once and not answered.
 */
@Singleton
class PhoneMedia @Inject constructor(@ApplicationContext private val context: Context, private val log: EventLog) : Responder {
    private val scope = responderScope(log, "Media", Dispatchers.Main)
    private val sending = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val unserved = mutableSetOf<Int>()

    @Volatile private var subscriber: Pair<DataXConnection, Int>? = null
    private var sessions: MediaSessionManager? = null
    @Volatile private var controller: MediaController? = null
    private var sentInfo: ByteArray? = null
    private var sentArt: Bitmap? = null

    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }
    private val onChange = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = sessions?.let { follow(active(it)) } ?: Unit
        override fun onSessionDestroyed() = sessions?.let { follow(active(it)) } ?: Unit
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val reply = DataXConnection.mirror(message.info.channelId)
        if (message.info.flags.isClosed) return
        when (message.info.messageType) {
            MT_SUBSCRIBE -> scope.launch {
                sending.withLock {
                    subscriber = connection to reply
                    sentInfo = null
                    sentArt = null
                }
                watch()
                push()
            }
            MT_INFO_REQUEST -> {
                val (player, name) = player(controller)
                connection.send(reply, Wire.fieldVarint(1, player) + Wire.fieldString(2, name), messageType = MT_INFO_RESPONSE)
            }
            // TryLaunchAppMessage {1 package, 2 payload, 3 metadata}.
            MT_TRY_LAUNCH -> {
                val f = fields(message)
                val pkg = f?.bytes(1)?.decodeToString().orEmpty()
                val payload = f?.bytes(2)?.decodeToString().orEmpty()
                scope.launch { tryLaunch(pkg, payload) }
            }
            // Empty from the music screen's connect button; AIActionsLaunchApp {2 Android package} from the native handler.
            MT_CONNECT_AUDIO_APP -> {
                val pkg = fields(message)?.bytes(2)?.decodeToString().orEmpty()
                if (pkg.isEmpty()) log.add("Partner ecosystem: the glasses asked to connect a music app")
                else scope.launch { tryLaunch(pkg, "") }
            }
            MT_LIKE -> scope.launch { setLiked(fields(message)?.varint(1) == 1L) }
            MT_NOTIFICATION_ACCESS -> scope.launch {
                if (!startFromHere(listenerPage()) && !askAccess()) {
                    log.add("Notification access: Visor may not open settings from the background")
                }
            }
            MT_SPOTIFY_PLAYLISTS_REQUEST -> connection.send(reply, EMPTY, messageType = MT_SPOTIFY_PLAYLISTS_RESPONSE)
            MT_PLAYLIST_REQUEST -> {
                val partner = fields(message)?.bytes(1) ?: EMPTY
                connection.send(reply, Wire.fieldBytes(3, partner), messageType = MT_PLAYLIST_RESPONSE)
            }
            else -> if (unserved.add(message.info.messageType)) log.add("Partner ecosystem: request ${message.info.messageType} not served")
        }
    }

    override suspend fun closed(channelId: Int, connection: DataXConnection) {
        if (subscriber?.let { it.first === connection && it.second == DataXConnection.mirror(channelId) } == true) subscriber = null
    }

    private fun fields(message: DataXMessage) = runCatching { Wire.parse(message.payload) }.getOrNull()

    /** From the first subscription on, follows the playing session; without notification access there is none. */
    private fun watch() {
        if (sessions != null) return
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            manager.addOnActiveSessionsChangedListener(onSessions, listener(), main)
            sessions = manager
            follow(active(manager))
        }
    }

    private fun listener() = ComponentName(context, MirrorListener::class.java)

    // Visor's own page among the notification listeners, else the list on phones without it.
    private fun listenerPage(): Intent {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener().flattenToString())
        return if (detail.resolveActivity(context.packageManager) != null) detail else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    private fun active(manager: MediaSessionManager): List<MediaController> =
        runCatching { manager.getActiveSessions(listener()) }.getOrDefault(emptyList())

    // The playing session, or else the first one Android lists.
    private fun follow(controllers: List<MediaController>) {
        val next = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(onChange)
            controller = next
            next?.registerCallback(onChange, main)
        }
        push()
    }

    // Sends what changed since the last push: the player, the cover, or both.
    private fun push() {
        val session = controller
        scope.launch(Dispatchers.Default) {
            sending.withLock {
                val (mux, reply) = subscriber ?: return@withLock
                val (player, name) = player(session)
                val info = Wire.fieldVarint(1, player) + Wire.fieldVarint(2, CONTENT_UNKNOWN) + Wire.fieldVarint(3, liked(session)) +
                    Wire.fieldString(4, name)
                val art = session?.metadata?.let { art(it) }
                val newInfo = !info.contentEquals(sentInfo)
                val newArt = !(art === sentArt || (art != null && sentArt?.let { art.sameAs(it) } == true))
                runCatching {
                    if (newInfo) mux.send(reply, info, messageType = MT_PLAYER_INFO)
                    if (newArt) sendArt(mux, reply, art)
                    sentInfo = info
                    sentArt = art
                }.onFailure { if (subscriber?.first === mux) subscriber = null }
            }
        }
    }

    private suspend fun sendArt(mux: DataXConnection, reply: Int, art: Bitmap?) {
        if (art == null) return mux.send(reply, EMPTY, messageType = MT_ART_LAST)
        val jpeg = ByteArrayOutputStream().also { fit(art).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        for (at in jpeg.indices step CHUNK) {
            val end = minOf(at + CHUNK, jpeg.size)
            mux.send(reply, Wire.fieldBytes(1, jpeg.copyOfRange(at, end)), messageType = if (end == jpeg.size) MT_ART_LAST else MT_ART_PIECE)
        }
    }

    // At most 300 x 300, as the Meta app sends it.
    private fun fit(art: Bitmap): Bitmap {
        if (art.width <= MAX_SIDE && art.height <= MAX_SIDE) return art
        val scale = MAX_SIDE.toFloat() / maxOf(art.width, art.height)
        return Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun art(metadata: MediaMetadata): Bitmap? {
        for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
            metadata.getBitmap(key)?.let { return it }
        }
        for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
            val uri = metadata.getString(key)?.takeIf { it.isNotBlank() } ?: continue
            runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()?.let { return it }
        }
        return null
    }

    // MediaPlayer: the partner apps the Display knows by name, any other player as 0 with its package.
    private fun player(session: MediaController?): Pair<Long, String> {
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return NO_ACCESS to ""
        val pkg = session?.packageName ?: return 0L to ""
        return (PLAYERS[pkg] ?: 0L) to pkg
    }

    // The track's heart or thumb rating; for Spotify its like action, CHECK_FILL when the track is liked, ADD_TO when not.
    private fun liked(session: MediaController?): Long {
        if (session?.packageName == SPOTIFY) {
            val actions = session.playbackState?.customActions.orEmpty().map { it.action }
            if (SPOTIFY_LIKED in actions) return LIKED_YES
            if (SPOTIFY_NOT_LIKED in actions) return LIKED_NO
        }
        val rating = session?.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return LIKED_UNKNOWN
        if (!rating.isRated) return LIKED_UNKNOWN
        return when (rating.ratingStyle) {
            Rating.RATING_HEART -> if (rating.hasHeart()) LIKED_YES else LIKED_NO
            Rating.RATING_THUMB_UP_DOWN -> if (rating.isThumbUp) LIKED_YES else LIKED_NO
            else -> LIKED_UNKNOWN
        }
    }

    // The heart or thumb rating when the session takes one, else its like action; the glasses get only the new state.
    private fun setLiked(wanted: Boolean) {
        val session = controller ?: return
        val actions = session.playbackState?.customActions.orEmpty()
        val action = actions.firstOrNull { it.action == SPOTIFY_LIKED || it.action == SPOTIFY_NOT_LIKED } ?: actions.firstOrNull { a ->
            val words = a.action + " " + a.name
            LIKE_WORDS.any { words.contains(it, ignoreCase = true) } && !NOT_LIKE.containsMatchIn(words)
        }
        when {
            session.ratingType == Rating.RATING_HEART -> session.transportControls.setRating(Rating.newHeartRating(wanted))
            session.ratingType == Rating.RATING_THUMB_UP_DOWN -> session.transportControls.setRating(Rating.newThumbRating(wanted))
            action == null -> log.add("Like: ${session.packageName} has no like control")
            liked(session) != (if (wanted) LIKED_YES else LIKED_NO) -> session.transportControls.sendCustomAction(action.action, null)
        }
        push()
    }

    // Tap and hold on a music tile asks for the app and its music: its session plays when it has one.
    private fun tryLaunch(pkg: String, payload: String) {
        if (pkg.isEmpty() || pkg == BE_MY_EYES) return
        if (!launch(pkg)) return log.add("Partner app $pkg: not started from the background")
        if (payload == TAP_AND_HOLD) sessions?.let { active(it) }?.firstOrNull { it.packageName == pkg }?.transportControls?.play()
    }

    // Starts the partner app as the Meta app would: only from the foreground or as a companion, else it stays put.
    private fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return startFromHere(intent)
    }

    // Android keeps Visor from opening the settings from the background, so a notification opens them.
    private fun askAccess(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.access_listener_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, ACCESS_ID,
            listenerPage().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setContentTitle(context.getString(R.string.access_listener_prompt))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(ACCESS_ID, notification) }.isSuccess
    }

    private fun startFromHere(intent: Intent): Boolean =
        mayOpenApps(context) && runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    companion object {
        const val SVC = 9012
        private const val MT_TRY_LAUNCH = 1
        private const val MT_INFO_REQUEST = 3
        private const val MT_INFO_RESPONSE = 4
        private const val MT_SUBSCRIBE = 5
        private const val MT_CONNECT_AUDIO_APP = 7
        private const val MT_SPOTIFY_PLAYLISTS_REQUEST = 8
        private const val MT_SPOTIFY_PLAYLISTS_RESPONSE = 9
        private const val MT_NOTIFICATION_ACCESS = 11
        private const val MT_PLAYLIST_REQUEST = 12
        private const val MT_PLAYLIST_RESPONSE = 13
        private const val MT_LIKE = 15
        private const val MT_PLAYER_INFO = 100
        private const val MT_ART_PIECE = 101
        private const val MT_ART_LAST = 102
        private const val CONTENT_UNKNOWN = 0L
        private const val LIKED_UNKNOWN = 0L
        private const val LIKED_YES = 1L
        private const val LIKED_NO = 2L
        private const val NO_ACCESS = 7L
        private const val CHUNK = 1024
        private const val MAX_SIDE = 300
        private const val JPEG_QUALITY = 70
        private val EMPTY = ByteArray(0)
        private const val SPOTIFY = "com.spotify.music"
        private const val SPOTIFY_LIKED = "CHECK_FILL"
        private const val SPOTIFY_NOT_LIKED = "ADD_TO"
        // The Meta app does not launch it either.
        private const val BE_MY_EYES = "com.bemyeyes.bemyeyes"
        private const val TAP_AND_HOLD = "EXECUTE_TAP_AND_HOLD"
        private const val CHANNEL = "glasses_requests"
        private const val ACCESS_ID = 9012
        private val LIKE_WORDS = listOf("like", "thumbs", "heart", "favorite", "favourite")
        // Thumbs down and dislike sit beside the like action in many players.
        private val NOT_LIKE = Regex("dislike|unlike|down|remove", RegexOption.IGNORE_CASE)
        private val PLAYERS = mapOf(
            SPOTIFY to 1L, "com.apple.android.music" to 2L, "com.amazon.mp3" to 3L,
            "com.audible.application" to 4L, "com.clearchannel.iheartradio.controller" to 5L, "com.calm.android" to 6L,
        )
    }
}
