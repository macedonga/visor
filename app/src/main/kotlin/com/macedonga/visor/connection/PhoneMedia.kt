package com.macedonga.visor.connection

import android.app.ActivityManager
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
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.notify.MirrorListener
import com.macedonga.visor.phone.companionAssociated
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * svc9012, partner_ecosystem, for what the phone plays. The Display subscribes (type 5) on connect and takes, on that
 * channel, MediaPlayerInfo {1 player, 2 content type, 3 liked, 4 package} (type 100) and the cover art as a JPEG in
 * 1024-byte pieces (101, the last one 102; an empty 102 clears it), again whenever the playing session changes. With an
 * Android phone the Display's music screen takes the cover only from here; title and artist come over AVRCP.
 * Playlists are answered empty (9, 13). Like (15) sets the heart rating or the app's like action. Launch (1) starts an
 * app from the foreground or as a companion only. Other requests are logged once and not answered.
 */
@Singleton
class PhoneMedia @Inject constructor(@ApplicationContext private val context: Context, private val log: Provider<EventLog>) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })
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
        if (message.info.flags.isClosed) {
            if (subscriber?.second == reply) subscriber = null
            return
        }
        when (message.info.messageType) {
            MT_SUBSCRIBE -> scope.launch {
                subscriber = connection to reply
                sentInfo = null
                sentArt = null
                watch()
                push()
                runCatching { sending.withLock { connection.send(reply, EMPTY, messageType = MT_SPOTIFY_PLAYLISTS_RESPONSE) } }
            }
            MT_INFO_REQUEST -> {
                val (player, name) = player(controller)
                connection.send(reply, Wire.fieldVarint(1, player) + Wire.fieldBytes(2, name.encodeToByteArray()), messageType = MT_INFO_RESPONSE)
            }
            MT_TRY_LAUNCH -> {
                val f = fields(message)
                val pkg = f?.bytes(2)?.decodeToString().orEmpty()
                val ok = pkg.isNotEmpty() && launch(pkg)
                if (!ok) log.get().add("Partner app $pkg: not started from the background")
                connection.send(reply, Wire.fieldVarint(1, if (ok) 1 else 0) + Wire.fieldBytes(4, f?.bytes(3) ?: EMPTY), messageType = MT_TRY_LAUNCH)
            }
            MT_LIKE -> scope.launch { setLiked(fields(message)?.varint(1) == 1L) }
            MT_NOTIFICATION_ACCESS -> scope.launch {
                if (!startFromHere(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) log.get().add("Notification access: Visor may not open settings from the background")
            }
            MT_SPOTIFY_PLAYLISTS_REQUEST -> connection.send(reply, EMPTY, messageType = MT_SPOTIFY_PLAYLISTS_RESPONSE)
            MT_PLAYLIST_REQUEST -> {
                val partner = fields(message)?.bytes(1) ?: EMPTY
                connection.send(reply, Wire.fieldBytes(3, partner), messageType = MT_PLAYLIST_RESPONSE)
            }
            else -> if (unserved.add(message.info.messageType)) log.get().add("Partner ecosystem: request ${message.info.messageType} not served")
        }
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
                    Wire.fieldBytes(4, name.encodeToByteArray())
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
        if (art == null) return mux.send(reply, ByteArray(0), messageType = MT_ART_LAST)
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

    // The heart rating of the track, when the session keeps one.
    private fun liked(session: MediaController?): Long {
        val rating = session?.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return LIKED_UNKNOWN
        if (rating.ratingStyle != Rating.RATING_HEART || !rating.isRated) return LIKED_UNKNOWN
        return if (rating.hasHeart()) LIKED_YES else LIKED_NO
    }

    // Heart rating when the session takes it, else its like action; the glasses get no reply, only the new state.
    private fun setLiked(wanted: Boolean) {
        val session = controller ?: return
        val action = session.playbackState?.customActions?.firstOrNull { a ->
            val words = a.action + " " + a.name
            LIKE_WORDS.any { words.contains(it, ignoreCase = true) } && !NOT_LIKE.containsMatchIn(words)
        }
        when {
            session.ratingType == Rating.RATING_HEART -> session.transportControls.setRating(Rating.newHeartRating(wanted))
            action == null -> log.get().add("Like: ${session.packageName} has no like control")
            liked(session) != (if (wanted) LIKED_YES else LIKED_NO) -> session.transportControls.sendCustomAction(action.action, null)
        }
        push()
    }

    // Starts the partner app as the Meta app would: only from the foreground or as a companion, else it stays put.
    private fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return startFromHere(intent)
    }

    private fun startFromHere(intent: Intent): Boolean =
        (onScreen() || companionAssociated(context)) && runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo()
        .also(ActivityManager::getMyMemoryState).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    companion object {
        const val SVC = 9012
        private const val MT_TRY_LAUNCH = 1
        private const val MT_INFO_REQUEST = 3
        private const val MT_INFO_RESPONSE = 4
        private const val MT_SUBSCRIBE = 5
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
        private val LIKE_WORDS = listOf("like", "thumbs", "heart", "favorite", "favourite")
        // Thumbs down and dislike sit beside the like action in many players.
        private val NOT_LIKE = Regex("dislike|unlike|down|remove", RegexOption.IGNORE_CASE)
        private val PLAYERS = mapOf(
            "com.spotify.music" to 1L, "com.apple.android.music" to 2L, "com.amazon.mp3" to 3L,
            "com.audible.application" to 4L, "com.clearchannel.iheartradio.controller" to 5L, "com.calm.android" to 6L,
        )
    }
}
