package com.macedonga.visor.phone

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.support.v4.media.MediaBrowserCompat
import android.view.KeyEvent
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.MediaControl
import com.macedonga.visor.core.assistant.NowPlaying
import com.macedonga.visor.core.assistant.Phone
import com.macedonga.visor.core.assistant.PhoneDone
import com.macedonga.visor.notify.MirrorListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's side of directions and music by voice. The map app gets a directions link; the music app is asked through
 * its media session or media browser service, the way the Meta app plays partner music. An app in the background may
 * open another only as a companion app ([companionAssociated]); otherwise, and while the phone is locked, it waits in a
 * notification.
 */
@Singleton
class PhoneActions @Inject constructor(@ApplicationContext private val context: Context) : Phone {
    private val pm = context.packageManager
    private val listener = ComponentName(context, MirrorListener::class.java)
    private val prefs = context.getSharedPreferences("phone_actions", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    override suspend fun directions(destination: String, mode: String): PhoneDone {
        val link = Uri.parse(DIRECTIONS).buildUpon().appendQueryParameter("api", "1").appendQueryParameter("destination", destination)
            .apply { if (mode in MODES) appendQueryParameter("travelmode", mode) }.build()
        val route = Intent(Intent.ACTION_VIEW, link)
        // A map app that opens the link shows the route; without one, the place in whichever map app the phone has.
        val maps = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=x")), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
        val intent = if (maps.isEmpty() || handler(route) in maps) route else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
        val app = handler(intent)?.let(::label).orEmpty()
        note("Directions to \"$destination\"${if (mode.isNotEmpty()) " by $mode" else ""} in ${app.ifEmpty { "the app the phone picks" }}: $intent")
        return open(intent, DIRECTIONS_ID, context.getString(R.string.phone_directions, destination), app)
    }

    override suspend fun play(query: String, app: String): PhoneDone {
        val sessions = sessions()
        val pkg = (if (app.isNotEmpty()) named(app, sessions) else chosen(sessions))
            ?: return PhoneDone("", if (app.isNotEmpty()) "there is no app called $app on the phone" else "no app was named and none played lately; ask which one")
        val label = label(pkg)
        note("Playing \"$query\" in $label")
        // The step that did not play it, for the answer (#45).
        var gaveUp = ""
        // Its session when it has one; then its media browser service, which an app may refuse to an app it does not know.
        sessions?.firstOrNull { it.packageName == pkg }?.takeIf { it.takes(PlaybackState.ACTION_PLAY_FROM_SEARCH) }?.let { c ->
            if (playIn(c, query)) return played(pkg, label, c)
            gaveUp = "$label's session took the search and played nothing"
            note("$label's session did not play it")
        }
        val browsed = browse(pkg)
        if (browsed == null && browserService(pkg) != null) gaveUp = "$label's media browser did not let Visor in"
        browsed?.let { (browser, c) ->
            try {
                // An app may say its session takes a search and do nothing with it (YouTube Music, issue #37); its
                // browser still finds the song, and playing that by its id works.
                if (query.isNotEmpty()) gaveUp = playFound(pkg, c, query) ?: return played(pkg, label, c)
                if (playIn(c, query)) return played(pkg, label, c)
                if (gaveUp.isEmpty()) gaveUp = "$label's media browser took the search and played nothing"
                note("$label's media browser did not play it")
            } finally {
                main.post { browser.disconnect() }
            }
        }
        // Then the app itself, opened on the search.
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtra(SearchManager.QUERY, query).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (pm.resolveActivity(search, 0) == null) return PhoneDone(label, "$label does not take searches from other apps", gaveUp = gaveUp)
        prefs.edit().putString(LAST, pkg).apply()
        return open(search, PLAY_ID, context.getString(R.string.phone_play, query.ifEmpty { label }), label).copy(gaveUp = gaveUp)
    }

    override suspend fun control(control: MediaControl): PhoneDone {
        val sessions = sessions()
        val c = sessions?.let { s -> s.firstOrNull { it.playing() } ?: s.firstOrNull() }
        if (c == null) {
            // Without a session to see, the phone still sends media keys to the app that played last.
            val key = KEYS[control] ?: return PhoneDone("", if (sessions == null) "Visor has no notification access" else "nothing is playing")
            val audio = context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            note("${control.what}: media key sent")
            return PhoneDone("")
        }
        val t = c.transportControls
        when (control) {
            MediaControl.PAUSE -> t.pause()
            MediaControl.RESUME -> t.play()
            MediaControl.NEXT -> t.skipToNext()
            MediaControl.PREVIOUS -> t.skipToPrevious()
            MediaControl.STOP -> t.stop()
            MediaControl.RESTART -> t.seekTo(0)
        }
        note("${control.what} in ${c.packageName}")
        // An app may take play and stay paused (one that plays only on screen): then it did not work.
        if (control == MediaControl.RESUME && withTimeoutOrNull(RESUME_WAIT_MS) { while (!c.playing()) delay(POLL_MS) } == null) {
            note("${c.packageName} took play and stayed ${c.playbackState?.state}")
            return PhoneDone(label(c.packageName), "${label(c.packageName)} did not start playing")
        }
        return PhoneDone(label(c.packageName))
    }

    override fun nowPlaying(): NowPlaying {
        val sessions = sessions() ?: return NowPlaying(unknown = "Visor has no notification access, so it cannot see what plays")
        val c = sessions.firstOrNull { it.playing() } ?: sessions.firstOrNull() ?: return NowPlaying(unknown = "nothing is playing")
        val m = c.metadata
        return NowPlaying(
            label(c.packageName),
            m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            c.playing(),
        )
    }

    private fun played(pkg: String, label: String, c: MediaController): PhoneDone {
        prefs.edit().putString(LAST, pkg).apply()
        val d = c.metadata?.description
        val what = listOfNotNull(d?.title, d?.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        note("$label plays ${what.ifEmpty { "something" }}")
        return PhoneDone(label, playing = what)
    }

    /** Asks [c] to play [query]; true once it plays, and plays something else than before if it was playing. */
    private suspend fun playIn(c: MediaController, query: String): Boolean {
        if (query.isEmpty() && c.playing()) return true
        val before = c.item()
        val was = c.playing()
        c.transportControls.playFromSearch(query, Bundle())
        return withTimeoutOrNull(PLAY_WAIT_MS) {
            while (!(c.playing() && (!was || c.item() != before))) delay(POLL_MS)
            true
        } == true
    }

    /**
     * Searches [pkg]'s media browser for [query] and plays the first playable item it finds by its id, on [c]; null once
     * it plays, else the step that did not work. Only the compat browser asks a service to search, and the music apps'
     * services are compat ones.
     */
    private suspend fun playFound(pkg: String, c: MediaController, query: String): String? {
        val label = label(pkg)
        val service = browserService(pkg) ?: return "$label has no media browser"
        var browser: MediaBrowserCompat? = null
        var connected = false
        val id = try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(BROWSE_MS) {
                    suspendCancellableCoroutine<String?> { call ->
                        lateinit var b: MediaBrowserCompat
                        b = MediaBrowserCompat(context, service, object : MediaBrowserCompat.ConnectionCallback() {
                            override fun onConnected() {
                                connected = true
                                b.search(query, null, object : MediaBrowserCompat.SearchCallback() {
                                    override fun onSearchResult(query: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                                        if (call.isActive) call.resume(items.firstOrNull { it.isPlayable }?.mediaId)
                                    }

                                    override fun onError(query: String, extras: Bundle?) {
                                        if (call.isActive) call.resume(null)
                                    }
                                })
                            }

                            override fun onConnectionFailed() {
                                if (call.isActive) call.resume(null)
                            }
                        }, null)
                        browser = b
                        b.connect()
                    }
                }
            }
        } finally {
            browser?.let { b -> main.post { b.disconnect() } }
        }
        if (id == null) {
            val why = if (connected) "$label's media browser found nothing to play for \"$query\"" else "$label's media browser did not let Visor search"
            return why.also(::note)
        }
        val before = c.item()
        val was = c.playing()
        c.transportControls.playFromMediaId(id, Bundle())
        val plays = withTimeoutOrNull(PLAY_WAIT_MS) {
            while (!(c.playing() && (!was || c.item() != before))) delay(POLL_MS)
            true
        } == true
        return if (plays) null else "$label took the song its search found and did not start it".also(::note)
    }

    private fun browserService(pkg: String): ComponentName? =
        pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0).firstOrNull()?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }

    /** [pkg]'s media browser service, connected, and a controller for its session; null when there is none or it refuses. */
    private suspend fun browse(pkg: String): Pair<MediaBrowser, MediaController>? {
        val service = browserService(pkg) ?: return null
        // Its callbacks come on the thread that connects, which needs a looper.
        val connected = withContext(Dispatchers.Main) {
            withTimeoutOrNull(BROWSE_MS) {
                suspendCancellableCoroutine<Pair<MediaBrowser, MediaController>?> { call ->
                    lateinit var browser: MediaBrowser
                    browser = MediaBrowser(context, service, object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            if (call.isActive) call.resume(browser to MediaController(context, browser.sessionToken)) else browser.disconnect()
                        }

                        override fun onConnectionFailed() {
                            if (call.isActive) call.resume(null)
                        }
                    }, null)
                    call.invokeOnCancellation { main.post { browser.disconnect() } }
                    browser.connect()
                }
            }
        }
        if (connected == null) note("${label(pkg)}'s media browser did not let Visor in")
        return connected
    }

    /** The media sessions of the phone's apps, newest first, without the system's own; null without notification access. */
    private fun sessions(): List<MediaController>? = runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener)
            .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
    }.getOrNull()

    /** The installed app called [name], among the ones that play media. */
    private fun named(name: String, sessions: List<MediaController>?): String? {
        val want = simple(name)
        val apps = mediaApps(sessions).associateWith { simple(label(it)) }
        return apps.entries.firstOrNull { it.value == want }?.key
            ?: apps.entries.filter { it.value.isNotEmpty() && (want.contains(it.value) || it.value.contains(want)) }.maxByOrNull { it.value.length }?.key
    }

    /** With no app named: the one playing or played last, else the only one there is. */
    private fun chosen(sessions: List<MediaController>?): String? {
        val apps = mediaApps(sessions)
        return sessions?.firstOrNull { it.playing() }?.packageName
            ?: sessions?.firstOrNull()?.packageName
            ?: prefs.getString(LAST, null)?.takeIf { it in apps }
            ?: apps.singleOrNull()
    }

    /** Apps with a launcher that browse media, play from a search, or have a session now. */
    private fun mediaApps(sessions: List<MediaController>?): Set<String> =
        (pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0).map { it.serviceInfo.packageName } +
            pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0).map { it.activityInfo.packageName } +
            sessions.orEmpty().map { it.packageName })
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }.toSet()

    /** Starts [implicit] when Visor may, and puts it in a notification when it may not or the phone is locked. */
    private fun open(implicit: Intent, id: Int, title: String, named: String): PhoneDone {
        // Explicit, so it opens the app it names and nothing else; with no default, the first that takes it.
        val pkg = implicit.`package` ?: handler(implicit)
            ?: pm.queryIntentActivities(implicit, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()?.activityInfo?.packageName
            ?: return PhoneDone(named, "no app on the phone opens it")
        val intent = Intent(implicit).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = named.ifEmpty { label(pkg) }
        val started = (onScreen() || companionAssociated(context)) && runCatching { context.startActivity(intent) }.isSuccess
        if (started && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return PhoneDone(app, opened = true)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return PhoneDone(app, "Visor may not post notifications, and it cannot open $app from the background")
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.meta_ai_title), NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_visor)
                .setContentTitle(title)
                .setContentText(app)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
        note("Waiting in a notification: $title")
        return PhoneDone(app, tapToOpen = true)
    }

    /** Whether an activity of Visor's is in front: the only time it may open another app itself. */
    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo()
        .also(ActivityManager::getMyMemoryState).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /** The app [intent] opens; null when the phone would ask which one. */
    private fun handler(intent: Intent): String? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }

    private fun label(pkg: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun MediaController.playing() = playbackState?.state == PlaybackState.STATE_PLAYING

    /** What the session holds now, to tell a new item from the old one. */
    private fun MediaController.item(): String? = metadata?.description?.let { it.mediaId ?: it.title?.toString() }

    /** Whether the session takes [action]; one that has not said what it takes is tried anyway. */
    private fun MediaController.takes(action: Long): Boolean = playbackState?.actions?.let { it == 0L || it and action != 0L } ?: true

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    private companion object {
        const val DIRECTIONS = "https://www.google.com/maps/dir/"
        val MODES = setOf("driving", "walking", "bicycling", "transit")
        val KEYS = mapOf(
            MediaControl.PAUSE to KeyEvent.KEYCODE_MEDIA_PAUSE,
            MediaControl.RESUME to KeyEvent.KEYCODE_MEDIA_PLAY,
            MediaControl.NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
            MediaControl.PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            MediaControl.STOP to KeyEvent.KEYCODE_MEDIA_STOP,
        )
        const val CHANNEL = "assistant_actions"
        const val DIRECTIONS_ID = 11
        const val PLAY_ID = 12
        const val LAST = "last_player"
        const val PLAY_WAIT_MS = 6_000L
        const val POLL_MS = 250L
        const val RESUME_WAIT_MS = 3_000L
        const val BROWSE_MS = 5_000L

        fun simple(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
