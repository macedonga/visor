package com.macedonga.visor.connection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.home.moodWords
import com.macedonga.visor.outside.Glimpse
import dagger.hilt.android.AndroidEntryPoint
import com.macedonga.visor.notify.PhoneContacts
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.macedonga.visor.core.transport.LeKeeper
import kotlinx.coroutines.flow.map
import com.macedonga.visor.core.session.LinkState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine

/** The foreground service the design asks for: one session, for the life of the connection. */
@AndroidEntryPoint
class GlassesService : Service() {

    @Inject
    lateinit var connection: GlassesConnection

    // Injected so it exists from the first link: it registers as the glasses' assistant on READY.
    @Inject
    lateinit var assistant: Assistant

    @Inject
    lateinit var contacts: PhoneContacts

    @Inject
    lateinit var sharing: PhoneSharing

    @Inject
    lateinit var navigation: Navigation

    @Inject
    lateinit var kind: LinkKind

    @Inject
    lateinit var keeper: LeKeeper

    @Inject
    lateinit var media: com.macedonga.visor.core.media.MediaImport

    @Inject
    lateinit var importChoice: com.macedonga.visor.gallery.ImportChoice

    @Inject
    lateinit var live: com.macedonga.visor.core.media.LiveStream

    @Inject
    lateinit var glassesConfig: com.macedonga.visor.core.media.GlassesConfig

    @Inject
    lateinit var assistantConfig: com.macedonga.visor.core.assistant.AssistantConfigStore

    @Inject
    lateinit var log: com.macedonga.visor.log.EventLog

    @Inject
    lateinit var developer: com.macedonga.visor.outside.DeveloperMode

    @Inject
    lateinit var glassesSettings: com.macedonga.visor.core.settings.GlassesSettings

    // Injected so the widget and the Quick Settings tile follow the glasses while no screen is open.
    @Inject
    lateinit var outside: com.macedonga.visor.outside.Outside

    // Injected so a Display's band is linked while the service runs.
    @Inject
    lateinit var band: com.macedonga.visor.band.BandLink

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    /** The notification collector, held so it can be stopped before anything else is. */
    private var notifications: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel(this)
        if (intent?.action == ACTION_STOP_LIVE) {
            live.stop()
            return START_STICKY
        }

        // Checked before startForeground and not after.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            notification(Status(getString(R.string.connection_reaching), null, false, null, null)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // A second start intent must not open a second session. The design's rule is one
        // session for the life of the connection, and the glasses hold one RFCOMM channel.
        if (!started) {
            started = true
            connection.start(scope, targetFrom(intent))
            contacts.start(scope)
            sharing.start(scope)
            navigation.start(scope)
            moveToLe()
            holdLe()
            com.macedonga.visor.gallery.notePlaces(this, scope, connection, media.places)
            com.macedonga.visor.gallery.importOnItsOwn(this, scope, connection, media, importChoice)
            com.macedonga.visor.language.keepAssistantConfig(scope, connection, glassesConfig, media, glassesSettings, assistantConfig, log)
            developer.keep(scope)
            com.macedonga.visor.settings.keepCameraInLine(scope, glassesSettings, assistantConfig, log)
            notifications = scope.launch {
                var shown: Any? = null
                combine(connection.state, liveNow(), outside.glimpse, ::Triple).collect { (state, now, glimpse) ->
                    // The state changes every few seconds; the notification only when what it says does.
                    val shows: Any = now ?: status(state, glimpse)
                    if (shows != shown) {
                        shown = shows
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, if (shows is Status) notification(shows) else liveNotification(shows as LiveNow))
                    }
                    // target == null alone is also the state before the store read finishes, so
                    // checking it without reason would stop the service on every launch before
                    // GlassesConnection.start ever gets to read the store.
                    if (state.target == null && state.reason.isNotBlank()) {
                        stopSelf()
                    }
                }
            }
        } else if (intent?.action == ACTION_RECONNECT) {
            retry()
        } else {
            targetFrom(intent)?.let(::switchTo)
        }
        return START_STICKY
    }

    /** Another pair asked for: the session with this one ends first, as the glasses hold one each. */
    private fun switchTo(target: GlassesTarget) {
        if (target.serial == connection.state.value.target?.serial) return
        if (live.running.value || media.importing.value) return
        scope.launch {
            connection.stop()
            connection.start(scope, target)
        }
    }

    /**
     * Asked from the tile: a pair the ladder is waiting to try again is tried now. Never while the
     * Live or an import has the glasses over Wi-Fi: Bluetooth is paused for them, and a second session ends theirs.
     */
    private fun retry() {
        val state = connection.state.value
        if (state.link != LinkState.DISCONNECTED && state.link != LinkState.LOST) return
        if (live.running.value || media.importing.value) return
        scope.launch {
            connection.stop()
            connection.start(scope, state.target)
        }
    }

    override fun onDestroy() {
        // runBlocking because onDestroy cannot suspend, and because returning before the socket
        // closes would let a second session open onto glasses that still hold the first one.
        runBlocking {
            // Joined, and before the connection is stopped rather than after. This is the only
            // thing that guarantees no further notification is posted: see [notifications].
            notifications?.cancelAndJoin()
            connection.stop()
        }
        keeper.release()
        notifications = null
        scope.cancel()
        started = false
        super.onDestroy()
    }

    /**
     * A session that had to start on RFCOMM, before the LE link was up, moves onto LE once it is: on RFCOMM their
     * sleep would cut it. Not during a live stream, and at most once a minute.
     */
    private fun moveToLe() {
        scope.launch {
            var last = 0L
            combine(connection.state, kind.overLe, keeper.connected) { state, le, held ->
                held && !le && state.link == LinkState.READY && state.device?.streamingActive != true
            }
                .distinctUntilChanged()
                .collect { move ->
                    val now = System.currentTimeMillis()
                    if (!move || now - last < MOVE_EVERY_MS) return@collect
                    last = now
                    val target = connection.state.value.target
                    connection.stop()
                    connection.start(scope, target)
                }
        }
    }

    /** Hold an LE link to the paired glasses for as long as the service runs, as the Meta app does. */
    private fun holdLe() {
        scope.launch {
            connection.state.map { (it.target?.link as? GlassesLink.Bluetooth)?.address }.distinctUntilChanged().collect { address ->
                if (address != null) keeper.hold(address)
            }
        }
        // Fast while they are open and talking, relaxed while folded.
        scope.launch {
            connection.state.map { it.link == LinkState.READY && it.device?.hinge == "OPEN" }.distinctUntilChanged().collect(keeper::fast)
        }
    }

    /** What the Live is doing while it runs, for the notification; null when it does not. */
    private data class LiveNow(val words: Int, val since: Long, val short: String)

    private fun liveNow() = combine(live.status, live.since, live.outputs.recording, live.outputs.broadcast.state) { status, since, recording, broadcast ->
        when {
            status.phase == com.macedonga.visor.core.media.LivePhase.IDLE || status.phase == com.macedonga.visor.core.media.LivePhase.FAILED -> null
            recording != null -> LiveNow(R.string.live_notification_recording, recording.since, getString(R.string.live_rec))
            broadcast.phase == com.macedonga.visor.core.media.BroadcastPhase.LIVE -> LiveNow(R.string.live_notification_on_air, broadcast.since, getString(R.string.live_on_air))
            else -> LiveNow(R.string.live_notification_watching, since, getString(R.string.live_badge))
        }
    }.distinctUntilChanged()

    /** While the Live runs: what it is doing, how long for, a way back to it and one to stop it. On Android 16 a Live Update. */
    private fun liveNotification(now: LiveNow): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_LIVE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_STOP_LIVE), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, LIVE_CHANNEL_ID)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(now.words))
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, getString(R.string.live_stop), stop).build())
        if (now.since > 0) builder.setWhen(now.since).setShowWhen(true).setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(now.short)
            // Asking to be promoted came with Android 16.1; on 16.0 the call is missing and Live crashed.
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    /** The target from the intent, or null to let the store decide. */
    private fun targetFrom(intent: Intent?): GlassesTarget? {
        if (intent == null) return null
        val serial = intent.getStringExtra(EXTRA_SERIAL) ?: return null
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return null
        return GlassesTarget(GlassesLink.Bluetooth(address), serial)
    }

    /**
     * What the connection notification says: the home's word for the pair, and their battery and the case's. [text] is
     * the same in words, for TalkBack and plain notifications.
     */
    private data class Status(val title: String, val glasses: Int?, val charging: Boolean, val case: Int?, val text: String?)

    private fun status(state: GlassesState, glimpse: Glimpse): Status {
        val title = getString(if (state.diagnosis == Diagnosis.EVICTED) R.string.connection_key_refused else moodWords(glimpse.mood))
        val batteries = listOfNotNull(
            glimpse.glasses?.let { getString(if (glimpse.charging) R.string.notify_glasses_charging else R.string.notify_glasses, it) },
            glimpse.case?.let { getString(R.string.widget_case, it) },
        ).joinToString(" · ").ifEmpty { null }
        val text = batteries ?: getString(connectionWords(state)).takeIf { it != title }
        return Status(title, glimpse.glasses, glimpse.charging, glimpse.case, text)
    }

    private fun notification(status: Status): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    style = Notification.DecoratedCustomViewStyle()
                    setCustomContentView(statusViews(status))
                }
            }
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    /** The widget's look in the shade: icons and numbers, tinted with the shade's own text colour. */
    @RequiresApi(31)
    private fun statusViews(status: Status): RemoteViews {
        val v = RemoteViews(packageName, R.layout.notification_glasses)
        v.setTextViewText(R.id.title, status.title)
        val numbers = status.glasses != null || status.case != null
        v.setViewVisibility(R.id.readouts, if (numbers) View.VISIBLE else View.GONE)
        if (numbers) {
            v.setContentDescription(R.id.readouts, status.text)
            for (icon in listOf(R.id.glasses_icon, R.id.bolt, R.id.case_icon)) {
                v.setColorStateListAttr(icon, "setImageTintList", android.R.attr.textColorSecondary)
            }
            val glasses = if (status.glasses != null) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.glasses_icon, glasses)
            v.setViewVisibility(R.id.glasses, glasses)
            status.glasses?.let { v.setTextViewText(R.id.glasses, "$it%") }
            v.setViewVisibility(R.id.bolt, if (status.charging && status.glasses != null) View.VISIBLE else View.GONE)
            val case = if (status.case != null) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.case_icon, case)
            v.setViewVisibility(R.id.case_level, case)
            status.case?.let { v.setTextViewText(R.id.case_level, "$it%") }
        }
        v.setViewVisibility(R.id.text, if (!numbers && status.text != null) View.VISIBLE else View.GONE)
        status.text?.let { v.setTextViewText(R.id.text, it) }
        return v
    }

    companion object {
        private const val MOVE_EVERY_MS = 60_000L
        const val CHANNEL_ID = "connection"
        const val LIVE_CHANNEL_ID = "live"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_LIVE = "com.macedonga.visor.action.STOP_LIVE"
        const val ACTION_RECONNECT = "com.macedonga.visor.action.RECONNECT"

        const val EXTRA_ADDRESS = "com.macedonga.visor.extra.ADDRESS"
        const val EXTRA_SERIAL = "com.macedonga.visor.extra.SERIAL"

        /** Low importance, no vibration, no light, deliberately. */
        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
            // A Live Update needs more than low importance; it stays silent all the same.
            val live = NotificationChannel(LIVE_CHANNEL_ID, context.getString(R.string.live_notification_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(live)
        }

        fun start(context: Context, target: GlassesTarget? = null) {
            val intent = Intent(context, GlassesService::class.java)
            if (target != null) {
                intent.putExtra(EXTRA_SERIAL, target.serial)
                when (val link = target.link) {
                    is GlassesLink.Bluetooth -> intent.putExtra(EXTRA_ADDRESS, link.address)
                    // Only media import uses Wi-Fi, on a session of its own.
                    is GlassesLink.Wifi -> Unit
                }
            }
            context.startForegroundService(intent)
        }

        /** Start the service, or have the running one try the glasses again now. */
        fun reconnect(context: Context) {
            context.startForegroundService(Intent(context, GlassesService::class.java).setAction(ACTION_RECONNECT))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesService::class.java))
        }
    }
}
