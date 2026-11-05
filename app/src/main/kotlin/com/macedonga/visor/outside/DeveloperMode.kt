package com.macedonga.visor.outside

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import com.macedonga.visor.R
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.CAMERA_VIDEO_STARTED
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.connection.VideoKind
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.Kept
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.outside.DeveloperIntents as I
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Meta's Robusta demo switch: with it on, the glasses take a photo for the phone. */
const val ROBUSTA = "wearables_robusta.demo_enabled"

/**
 * Developer mode: other apps work the glasses' camera and import through [DeveloperIntents] and hear of each capture.
 * The glasses take a photo for the phone only with Meta's Robusta demo switch on, so the mode turns it on and, when
 * it ends, off. Voice photos need the same switch, so it stays on while either wants it.
 */
@Singleton
class DeveloperMode @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val config: GlassesConfig,
    private val media: MediaImport,
    private val shutter: Shutter,
    private val log: EventLog,
    private val assistant: AssistantConfigStore,
) {
    private val prefs = context.getSharedPreferences("developer", Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean(ON, false))
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** What an app shows to get Live frames, and to send actions while [tokenRequired]. */
    private val _token = MutableStateFlow(prefs.getString(TOKEN, null) ?: newToken())
    val token: StateFlow<String> = _token.asStateFlow()
    private val _tokenRequired = MutableStateFlow(prefs.getBoolean(TOKEN_REQUIRED, false))
    val tokenRequired: StateFlow<Boolean> = _tokenRequired.asStateFlow()

    /** Where the intents run: they outlive the receiver and the activity that took them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Imports already said to have failed, until the next import starts.
    private val failed = Collections.synchronizedSet(HashSet<String>())

    // The video shortcut reads Stop recording.
    @Volatile private var stopShown = false

    init {
        open(_on.value)
    }

    fun set(on: Boolean) {
        prefs.edit().putBoolean(ON, on).apply()
        _on.value = on
        open(on)
    }

    fun requireToken(required: Boolean) {
        prefs.edit().putBoolean(TOKEN_REQUIRED, required).apply()
        _tokenRequired.value = required
    }

    /** A new token: apps holding the old one lose their frames and, while it is required, their actions. */
    fun renewToken() {
        _token.value = newToken()
        log.add("Developer mode: a new token")
        // The launcher's shortcuts carry it.
        if (on.value) open(true)
    }

    /** Whether [given] is the token. */
    fun holds(given: String?): Boolean = given != null && MessageDigest.isEqual(given.toByteArray(), token.value.toByteArray())

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { prefs.edit().putString(TOKEN, it).apply() }
    }

    /** The intents and the frames reach Visor, and the launcher offers the shutter, only while the mode is on. */
    private fun open(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        for (component in listOf(DeveloperActivity::class.java, DeveloperReceiver::class.java, FrameService::class.java)) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, component), state, PackageManager.DONT_KILL_APP)
        }
        val shortcuts = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            if (!on) {
                shortcuts.removeDynamicShortcuts(SHORTCUTS)
                shortcuts.disableShortcuts(SHORTCUTS)
                return@runCatching
            }
            shortcuts.enableShortcuts(SHORTCUTS)
            shortcuts.addDynamicShortcuts(
                listOf(
                    shortcut(PHOTO, R.string.shutter_take_photo, R.mipmap.ic_shortcut_photo, I.TAKE_PHOTO),
                    videoShortcut(),
                ),
            )
        }
    }

    /** The video shortcut toggles: it says Stop recording while a video runs. */
    private fun videoShortcut(): ShortcutInfo {
        stopShown = connection.state.value.device?.camera == CAMERA_VIDEO_STARTED
        val label = if (stopShown) R.string.live_stop_recording else R.string.shutter_record_video
        return shortcut(VIDEO, label, R.mipmap.ic_shortcut_video, I.TOGGLE_VIDEO)
    }

    private fun shortcut(id: String, label: Int, icon: Int, action: String) = ShortcutInfo.Builder(context, id)
        .setShortLabel(context.getString(label))
        .setIcon(Icon.createWithResource(context, icon))
        .setIntent(Intent(action).setClass(context, DeveloperActivity::class.java).putExtra(I.EXTRA_TOKEN, token.value))
        .build()

    /** Do what [intent] asks, waking the connection first when the glasses are not there yet. */
    suspend fun handle(intent: Intent) {
        val action = intent.action ?: return
        if (!on.value || action !in I.ACTIONS) return
        if (tokenRequired.value && !holds(intent.getStringExtra(I.EXTRA_TOKEN))) {
            log.add("Developer mode: ignored ${action.substringAfterLast('.')} without the token")
            return
        }
        if (connection.state.value.link != LinkState.READY) {
            runCatching { GlassesService.reconnect(context) }
            withTimeoutOrNull(WAKE_MS) { connection.state.first { it.link == LinkState.READY } }
        }
        val kind = VideoKind.of(intent.getStringExtra(I.EXTRA_CAPTURE_TYPE))
        when (action) {
            I.TAKE_PHOTO -> shutter.photo()
            I.START_VIDEO -> shutter.video(kind)
            I.STOP_VIDEO -> shutter.stop()
            I.TOGGLE_VIDEO -> shutter.toggle(kind)
            I.IMPORT_MEDIA -> import(intent.getStringExtra(I.EXTRA_ID) ?: return)
        }
    }

    private suspend fun import(id: String) {
        val listed = media.captures.value.firstOrNull { it.handle == id } ?: run {
            // Just connected, or just taken: the list may not have it yet.
            media.refresh()
            withTimeoutOrNull(LIST_MS) { media.captures.map { list -> list.firstOrNull { it.handle == id } }.first { it != null } }
        }
        val kept = media.index.entries.value[id]
        // Already here: say where again.
        if (kept != null && listed?.isNew != true) return newMedia(kept)
        if (listed == null || !listed.isMedia) return tell(I.IMPORT_FAILED, I.EXTRA_ID to id, I.EXTRA_REASON to NOT_FOUND)
        failed -= id
        log.add("Developer mode: importing ${id.take(8)}")
        media.import(listOf(listed))
    }

    /**
     * For the glasses' service: puts the demo switch where the mode wants it each time the glasses connect or the mode
     * changes, and while the mode is on, broadcasts the events [DeveloperIntents] lists.
     */
    fun keep(scope: CoroutineScope) {
        scope.launch {
            var upAt = 0L
            val ready = connection.state.map { it.link == LinkState.READY }.distinctUntilChanged()
                .onEach { if (it) upAt = SystemClock.elapsedRealtime() }
            val photos = assistant.config.map { it.voicePhotos }.distinctUntilChanged()
            combine(ready, on, photos) { ready, on, photos -> (on || photos).takeIf { ready } }.collectLatest { on ->
                on ?: return@collectLatest
                // A link just up settles first; the configuration comes in a bug report.
                delay(upAt + SETTLE_MS - SystemClock.elapsedRealtime())
                keepSwitch(on)
            }
        }
        scope.launch {
            var was: String? = null
            connection.state.map { it.device?.camera }.distinctUntilChanged().collect { camera ->
                val event = when {
                    camera == CAMERA_VIDEO_STARTED -> I.VIDEO_STARTED.takeIf { was != CAMERA_VIDEO_STARTED }
                    // No state at all is a link gone, not a video ended.
                    was == CAMERA_VIDEO_STARTED && camera != null -> I.VIDEO_STOPPED
                    else -> null
                }
                if (camera != null) was = camera
                if (camera != null && on.value && (camera == CAMERA_VIDEO_STARTED) != stopShown) {
                    runCatching { context.getSystemService(ShortcutManager::class.java)?.updateShortcuts(listOf(videoShortcut())) }
                }
                if (event != null && on.value) tell(event, I.EXTRA_TIME to System.currentTimeMillis())
            }
        }
        scope.launch {
            val told = HashSet<String>()
            combine(media.captures, on) { list, on -> list.takeIf { on } }.collect { list ->
                for (c in list ?: return@collect) {
                    if (!c.isNew || !c.isMedia || !told.add(c.handle)) continue
                    tell(
                        I.MEDIA_AVAILABLE, I.EXTRA_ID to c.handle, I.EXTRA_TYPE to word(c.type),
                        I.EXTRA_TIME to (c.takenAtMillis ?: 0L), I.EXTRA_SIZE to (c.main?.size ?: 0L),
                    )
                }
            }
        }
        scope.launch {
            val shown = HashMap<String, Int>()
            combine(media.transfers, on) { transfers, on -> transfers.takeIf { on } }.collect { transfers ->
                for (t in transfers ?: return@collect) {
                    // The photo or the video itself; the pieces that come with it are small.
                    val capture = media.captures.value.firstOrNull { it.main?.id == t.uuid } ?: continue
                    val percent = if (t.size > 0) (t.received * 100 / t.size).toInt() else 0
                    val before = shown.put(capture.handle, percent)
                    if (before == percent) continue
                    tell(
                        I.IMPORT_PROGRESS, I.EXTRA_ID to capture.handle, I.EXTRA_RECEIVED to t.received,
                        I.EXTRA_SIZE to t.size, I.EXTRA_PERCENT to percent,
                        // Every step would fill the log: a line each quarter.
                        logged = before == null || before / 25 != percent / 25,
                    )
                }
            }
        }
        scope.launch {
            media.imported.collect { handle -> if (on.value) media.index.entries.value[handle]?.let(::newMedia) }
        }
        scope.launch {
            combine(media.problem, on) { problem, on -> problem.takeIf { on } }.collect { problem ->
                // Each import starts without a problem: what fails in it is new.
                if (problem == null) return@collect failed.clear()
                for (handle in problem.handles) {
                    if (failed.add(handle)) tell(I.IMPORT_FAILED, I.EXTRA_ID to handle, I.EXTRA_REASON to problem.kind.name)
                }
            }
        }
    }

    private fun newMedia(kept: Kept) = tell(
        I.NEW_MEDIA, I.EXTRA_ID to kept.handle, I.EXTRA_TYPE to word(kept.type), I.EXTRA_TIME to kept.takenAt,
        I.EXTRA_URI to kept.uri, I.EXTRA_PATH to pathOf(Uri.parse(kept.uri)),
    )

    /** Broadcast [event] with [extras] (strings, longs and ints; nulls are left out), and say so in the log. */
    private fun tell(event: String, vararg extras: Pair<String, Any?>, logged: Boolean = true) {
        val intent = Intent(event)
        for ((key, value) in extras) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
            }
        }
        context.sendBroadcast(intent)
        if (logged) log.add("Developer mode: sent ${event.substringAfterLast('.')} " + extras.filter { it.second != null }.joinToString(" ") { (k, v) -> "$k=$v" })
    }

    private fun word(type: CaptureType) = type.name.lowercase()

    /** The file behind a gallery item, for apps that want a path rather than a content URI. */
    @Suppress("DEPRECATION")
    private fun pathOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private suspend fun keepSwitch(on: Boolean) {
        if (config.state.value.params.isEmpty()) {
            // A switch Visor never read, it never turned on.
            if (!on) return
            if (!config.readOnce(READ_MS) { log.add("Developer mode: reading the glasses' configuration once") }) return
        }
        val state = config.state.first { it.busy == null }
        if (ROBUSTA !in state.params || (state.values[ROBUSTA] == true) == on) return
        log.add(if (on) "Developer mode: letting the glasses take photos for the phone" else "Developer mode: the glasses no longer take photos for the phone")
        config.apply(mapOf(ROBUSTA to on))
        withTimeoutOrNull(WRITE_MS) { config.state.first { s -> s.busy == null && s.values[ROBUSTA] == on } }
            ?: log.add("Developer mode: the glasses did not take the change: ${config.state.value.note?.why.orEmpty()}")
    }

    private companion object {
        const val ON = "on"
        const val TOKEN = "token"
        const val TOKEN_REQUIRED = "token_required"
        const val PHOTO = "shutter_photo"
        const val VIDEO = "shutter_video"
        val SHORTCUTS = listOf(PHOTO, VIDEO)
        const val NOT_FOUND = "NOT_FOUND"
        const val WAKE_MS = 20_000L
        const val LIST_MS = 15_000L
        const val SETTLE_MS = 20_000L
        const val READ_MS = 5 * 60_000L
        const val WRITE_MS = 2 * 60_000L
    }
}
