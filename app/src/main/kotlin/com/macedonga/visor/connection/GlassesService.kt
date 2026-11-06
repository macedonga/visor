package com.macedonga.visor.connection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.StringRes
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.media.BroadcastPhase
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.display.CaptionTranscripts
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.gallery.importOnItsOwn
import com.macedonga.visor.gallery.notePlaces
import com.macedonga.visor.home.moodWords
import com.macedonga.visor.language.keepAssistantConfig
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.notify.PhoneContacts
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.outside.Glimpse
import com.macedonga.visor.outside.Outside
import com.macedonga.visor.settings.PhoneSteady
import com.macedonga.visor.settings.keepMuseWithWakeWord
import com.macedonga.visor.ui.allows
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The foreground service holding the one session with the glasses. */
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
    lateinit var media: MediaImport

    @Inject
    lateinit var importChoice: ImportChoice

    @Inject
    lateinit var live: LiveStream

    @Inject
    lateinit var glassesConfig: GlassesConfig

    @Inject
    lateinit var assistantConfig: AssistantConfigStore

    @Inject
    lateinit var log: EventLog

    @Inject
    lateinit var developer: DeveloperMode

    @Inject
    lateinit var glassesSettings: GlassesSettings

    @Inject
    lateinit var steady: PhoneSteady

    // Injected so the widget and the Quick Settings tile follow the glasses while no screen is open.
    @Inject
    lateinit var outside: Outside

    // Injected so a Display's band is linked while the service runs.
    @Inject
    lateinit var band: BandLink

    // Injected so captions started from the glasses are kept while no screen is open.
    @Inject
    lateinit var captionTranscripts: CaptionTranscripts

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    /** The notification collector, held so it can be stopped before anything else is. */
    private var notifications: Job? = null

    /** What the collector last posted, for a later start intent to post again. */
    @Volatile
    private var posted: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel(this)
        if (intent?.action == ACTION_STOP_LIVE) {
            live.stop()
            return START_STICKY
        }

        // Without Bluetooth there is no link to hold.
        if (!bluetoothAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            posted ?: notification(Status(getString(R.string.mood_searching), null, false, null, null)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // One session only: later intents switch pair or retry.
        if (!started) {
            started = true
            connection.start(scope, targetFrom(intent))
            contacts.start(scope)
            sharing.start(scope)
            navigation.start(scope)
            moveToLe()
            holdLe()
            notePlaces(this, scope, connection, media.places)
            importOnItsOwn(this, scope, connection, media, importChoice)
            keepAssistantConfig(scope, connection, glassesConfig, glassesSettings, assistantConfig, log)
            developer.keep(scope)
            keepCameraInLine(scope, glassesSettings, assistantConfig, log)
            keepMuseWithWakeWord(scope, glassesSettings, log)
            keepLandscape(scope, connection, glassesSettings, glassesConfig, log)
            media.steadiedOnGlasses = steadiedOnGlasses(glassesSettings)
            media.steadyOnPhone = { steady.on.value }
            notifications = scope.launch {
                var shown: Shown? = null
                combine(connection.state, liveNow(), outside.glimpse, ::Triple).collect { (state, now, glimpse) ->
                    // The state changes every few seconds; the notification only when what it says does.
                    val shows: Shown = now ?: status(state, glimpse)
                    if (shows != shown) {
                        shown = shows
                        val n = when (shows) {
                            is Status -> notification(shows)
                            is LiveNow -> liveNotification(shows)
                        }
                        posted = n
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
                    }
                    if (state.noGlasses) stopSelf()
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
        if (live.usingWifi || media.importing.value) return
        // Asked twice in a row, the second finds this pair already chosen.
        scope.launch { connection.restart(scope, target) { it.target?.serial == target.serial } }
    }

    /** [reconnect]: a waiting pair is tried now, unless a new session would end the Live's or an import's on Wi-Fi. */
    private fun retry() {
        val state = connection.state.value
        if (state.link != LinkState.DISCONNECTED && state.link != LinkState.LOST) return
        if (live.usingWifi || media.importing.value) return
        scope.launch {
            // Tried again on Bluetooth: a Wi-Fi link that dropped is not one to wait for.
            connection.restart(scope, state.target?.takeIf { it.link is GlassesLink.Bluetooth }) {
                it.link != LinkState.DISCONNECTED && it.link != LinkState.LOST
            }
        }
    }

    override fun onDestroy() {
        // Blocking: a new session must not open while the glasses still hold this one.
        runBlocking {
            // The collector stops first, so nothing is posted once the connection ends.
            notifications?.cancelAndJoin()
            connection.stop()
        }
        keeper.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Moves an RFCOMM session onto LE once LE is up, as their sleep cuts RFCOMM; never mid-stream, at most once a minute. */
    private fun moveToLe() {
        scope.launch {
            var last = 0L
            combine(connection.state, kind.overLe, keeper.connected) { state, le, held ->
                // A session moved to Wi-Fi for a lease is not on LE either, and stays where it is.
                held && !le && state.link == LinkState.READY && state.device?.streamingActive != true && state.target?.link !is GlassesLink.Wifi
            }
                .distinctUntilChanged()
                .collect { move ->
                    val now = System.currentTimeMillis()
                    if (!move || now - last < MOVE_EVERY_MS) return@collect
                    last = now
                    connection.restart(scope, connection.state.value.target)
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

    /** What the ongoing notification shows: the connection, or the Live while it runs. */
    private sealed interface Shown

    /** What the Live is doing, for the notification. */
    private data class LiveNow(@StringRes val label: Int, val since: Long, val badge: String) : Shown

    private fun liveNow() = combine(live.status, live.since, live.outputs.recording, live.outputs.broadcast.state) { status, since, recording, broadcast ->
        when {
            status.phase == LivePhase.IDLE || status.phase == LivePhase.FAILED -> null
            recording != null -> LiveNow(R.string.live_notification_recording, recording.since, getString(R.string.live_rec))
            broadcast.phase == BroadcastPhase.LIVE -> LiveNow(R.string.live_notification_on_air, broadcast.since, getString(R.string.live_on_air))
            else -> LiveNow(R.string.live_notification_watching, since, getString(R.string.live_badge))
        }
    }.distinctUntilChanged()

    /** While the Live runs: what it is doing, how long for, a way back to it and one to stop it. On Android 16 a Live Update. */
    private fun liveNotification(now: LiveNow): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_LIVE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_STOP_LIVE), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, LIVE_CHANNEL_ID)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(now.label))
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(this, 4, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, getString(R.string.live_stop), stop).build())
        if (now.since > 0) builder.setWhen(now.since).setShowWhen(true).setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(now.badge)
            // setRequestPromotedOngoing exists from Android 16.1.
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

    /** The connection notification: the home's word for the pair and the batteries, which [text] spells out for TalkBack. */
    private data class Status(val title: String, val glasses: Int?, val charging: Boolean, val case: Int?, val text: String?) : Shown

    private fun status(state: GlassesState, glimpse: Glimpse): Status {
        val title = getString(if (state.diagnosis == Diagnosis.EVICTED) R.string.connection_key_refused else moodWords(glimpse.mood))
        val text = listOfNotNull(
            glimpse.glasses?.let { getString(if (glimpse.charging) R.string.notify_glasses_charging else R.string.notify_glasses, it) },
            glimpse.case?.let { getString(R.string.widget_case, it) },
        ).joinToString(" · ").ifEmpty { null }
        return Status(title, glimpse.glasses, glimpse.charging, glimpse.case, text)
    }

    private fun notification(status: Status): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setShowWhen(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(statusViews(status))
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

        /** The silent connection and Live channels. */
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

        /** The person disconnected: Home does not start the service again until a start or reconnect. */
        @Volatile
        var stoppedByUser = false
            private set

        fun start(context: Context, target: GlassesTarget? = null) {
            stoppedByUser = false
            if (!bluetoothAllowed(context)) return
            val intent = Intent(context, GlassesService::class.java)
            if (target != null) (target.link as? GlassesLink.Bluetooth)?.let { intent.putExtra(EXTRA_SERIAL, target.serial).putExtra(EXTRA_ADDRESS, it.address) }
            context.startForegroundService(intent)
        }

        /** Start the service, or have the running one try the glasses again now. */
        fun reconnect(context: Context) {
            stoppedByUser = false
            if (!bluetoothAllowed(context)) return
            context.startForegroundService(Intent(context, GlassesService::class.java).setAction(ACTION_RECONNECT))
        }

        // Checked before a foreground start: a service that stops before startForeground crashes the app.
        private fun bluetoothAllowed(context: Context) = context.allows(Manifest.permission.BLUETOOTH_CONNECT)

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesService::class.java))
        }

        fun disconnect(context: Context) {
            stoppedByUser = true
            stop(context)
        }
    }
}
