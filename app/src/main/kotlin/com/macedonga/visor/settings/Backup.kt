package com.macedonga.visor.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.TtsPath
import com.macedonga.visor.core.assistant.mcp.AuthKind
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.McpStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.strings
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.BroadcastCodec
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.gallery.AutoImport
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.language.LanguageInstaller
import com.macedonga.visor.language.VOICE_KEY
import com.macedonga.visor.notify.NotifyApps
import com.macedonga.visor.notify.NotifyChoice
import com.macedonga.visor.update.Updates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Every setting in one file and back, without the API key, the Meta login and the stream key. */
@Singleton
class Backups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: GlassesSettings,
    private val connection: GlassesConnection,
    private val assistant: AssistantConfigStore,
    private val notify: NotifyChoice,
    private val apps: NotifyApps,
    private val import: ImportChoice,
    private val albums: Albums,
    private val live: LiveSettingsStore,
    private val pins: Pins,
    private val mcp: McpStore,
    private val languages: LanguageInstaller,
    private val updates: Updates,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L).takeIf { it > 0 })

    /** When a backup was last saved, if ever. */
    val last: StateFlow<Long?> = _last.asStateFlow()

    private val _restoring = MutableStateFlow<Pair<Int, Int>?>(null)

    /** The glasses' settings written and to write while a restore runs, null when none does. */
    val restoring: StateFlow<Pair<Int, Int>?> = _restoring.asStateFlow()

    private val _restored = MutableStateFlow<Result<Restored>?>(null)

    /** How the last restore of this run ended. */
    val restored: StateFlow<Result<Restored>?> = _restored.asStateFlow()

    /** The glasses' settings a restore could not write, and whether the phone could check the voice's language. */
    data class Restored(val notWritten: Int, val voiceChecked: Boolean)

    fun build(): JSONObject {
        val state = settings.state.value
        val glasses = JSONObject()
        for ((key, value) in state.stored) {
            val info = settings.catalogue[key] ?: continue
            if (info.writable && state.changed(key, info)) glasses.put(key, value)
        }
        val a = assistant.config.value
        val l = live.settings.value
        return JSONObject()
            .put("visor", 1)
            .put("saved", Instant.now().toString())
            .put("serial", connection.state.value.target?.serial ?: connection.state.value.info?.serial ?: "")
            .put("read", state.readAt?.let { Instant.ofEpochMilli(it).toString() } ?: "")
            // Unread this run, the glasses' values are not known: their part stays out.
            .putOpt("glasses", glasses.takeIf { state.readAt != null })
            .put("updates", updates.enabled.value)
            .put(
                "assistant",
                JSONObject()
                    .put("enabled", a.enabled).put("provider", a.provider.name).put("model", a.model).put("linesModel", a.linesModel)
                    .put("baseUrl", a.baseUrl).put("webSearch", a.webSearch).put("vision", a.vision).put("voicePhotos", a.voicePhotos).put("glassesVoice", a.tts == TtsPath.GLASSES).put("quietAfterActions", a.quietAfterActions).put("speechLanguage", a.speechLanguage)
                    .put("instructions", a.instructions).put("memoryMinutes", a.memoryMinutes)
                    .put("answerWaitSeconds", a.answerWaitSeconds)
                    // The speech service's key stays out, like the model's.
                    .put("tts", a.tts.name).put("ttsBaseUrl", a.ttsBaseUrl).put("ttsModel", a.ttsModel).put("ttsVoice", a.ttsVoice),
            )
            .put("notifications", JSONObject().put("enabled", notify.enabled.value).put("muted", JSONArray(apps.muted.value.sorted())))
            .put("import", import.mode.value.name)
            .put("albums", JSONObject().put("photos", albums.photos.value).put("videos", albums.videos.value).put("together", albums.together.value))
            .put(
                "live",
                JSONObject()
                    .put("manual", l.manual).put("quality", l.quality.name).put("bitrate", l.bitrate).put("frameRate", l.frameRate)
                    .put("overlays", JSONArray(l.overlays.map { it.name })).put("replaySeconds", l.replaySeconds)
                    .put("listen", l.listen).put("rtmpUrl", l.rtmpUrl).put("rtmpCodec", l.rtmpCodec.name).put("glassesHost", l.glassesHost),
            )
            .put("pins", JSONArray(pins.ids.value))
            // The servers without their keys and sign-ins, which stay on this phone.
            .put(
                "integrations",
                JSONArray(
                    mcp.servers.value.map {
                        JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled).put("auth", it.auth.name)
                            .put("header", it.header).put("off", JSONArray(it.off.sorted())).put("clientId", it.clientId).put("redirect", it.redirect)
                    },
                ),
            )
    }

    suspend fun save(uri: Uri) = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")).use { it.write(build().toString(2).toByteArray()) }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(LAST, now).apply()
        _last.value = now
    }

    suspend fun read(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        JSONObject((context.contentResolver.openInputStream(uri) ?: error("no stream")).use { it.readBytes().decodeToString() })
    }

    /** Restores [backup] in Visor's own scope, so leaving the page does not stop it; one restore at a time. */
    fun startRestore(backup: JSONObject) {
        if (!_restoring.compareAndSet(null, 0 to 0)) return
        _restored.value = null
        scope.launch {
            try {
                _restored.value = Result.success(restore(backup))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _restored.value = Result.failure(error)
            } finally {
                _restoring.value = null
            }
        }
    }

    /** Puts Visor's part back at once, and the glasses' part one write at a time; the glasses' part needs them connected. */
    private suspend fun restore(backup: JSONObject): Restored {
        backup.optJSONObject("assistant")?.let { o ->
            assistant.update {
                it.copy(
                    enabled = o.optBoolean("enabled", it.enabled),
                    provider = runCatching { Provider.valueOf(o.getString("provider")) }.getOrDefault(it.provider),
                    model = o.optString("model", it.model),
                    linesModel = o.optString("linesModel", it.linesModel),
                    baseUrl = o.optString("baseUrl", it.baseUrl),
                    webSearch = o.optBoolean("webSearch", it.webSearch),
                    vision = o.optBoolean("vision", it.vision),
                    voicePhotos = o.optBoolean("voicePhotos", it.voicePhotos),
                    // Older backups carry only the glassesVoice boolean.
                    tts = TtsPath.entries.firstOrNull { p -> p.name == o.optString("tts") }
                        ?: if (!o.has("glassesVoice")) it.tts else if (o.getBoolean("glassesVoice")) TtsPath.GLASSES else TtsPath.PHONE,
                    ttsBaseUrl = o.optString("ttsBaseUrl", it.ttsBaseUrl),
                    ttsModel = o.optString("ttsModel", it.ttsModel),
                    ttsVoice = o.optString("ttsVoice", it.ttsVoice),
                    quietAfterActions = o.optBoolean("quietAfterActions", it.quietAfterActions),
                    speechLanguage = o.optString("speechLanguage", it.speechLanguage),
                    instructions = o.optString("instructions", it.instructions),
                    memoryMinutes = o.optInt("memoryMinutes", it.memoryMinutes),
                    answerWaitSeconds = o.optInt("answerWaitSeconds", it.answerWaitSeconds),
                )
            }
        }
        backup.optJSONArray("integrations")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (mcp.servers.value.any { it.url == url }) continue
                mcp.add(
                    McpServer(
                        name = o.optString("name").ifBlank { url }, url = url, enabled = o.optBoolean("enabled", true),
                        auth = runCatching { AuthKind.valueOf(o.optString("auth")) }.getOrDefault(AuthKind.NONE),
                        header = o.optString("header"),
                        off = o.optJSONArray("off")?.strings()?.toSet().orEmpty(),
                        clientId = o.optString("clientId"), redirect = o.optString("redirect"),
                    ),
                )
            }
        }
        backup.optJSONObject("notifications")?.let { o ->
            notify.set(o.optBoolean("enabled", true))
            o.optJSONArray("muted")?.let { a -> apps.restore(a.strings().toSet()) }
        }
        backup.optString("import").takeIf { it.isNotEmpty() }?.let { m -> runCatching { AutoImport.valueOf(m) }.getOrNull()?.let(import::set) }
        backup.optJSONObject("albums")?.let { o ->
            o.optString("photos").takeIf { it.isNotEmpty() }?.let(albums::setPhotos)
            o.optString("videos").takeIf { it.isNotEmpty() }?.let(albums::setVideos)
            if (o.has("together")) albums.setTogether(o.optBoolean("together"))
        }
        backup.optJSONObject("live")?.let { o ->
            live.update {
                it.copy(
                    manual = o.optBoolean("manual", it.manual),
                    quality = runCatching { StreamQuality.valueOf(o.getString("quality")) }.getOrDefault(it.quality),
                    bitrate = o.optInt("bitrate", it.bitrate),
                    frameRate = o.optInt("frameRate", it.frameRate),
                    overlays = o.optJSONArray("overlays")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { LiveOverlay.valueOf(a.getString(i)) }.getOrNull() }.toSet() } ?: it.overlays,
                    replaySeconds = o.optInt("replaySeconds", it.replaySeconds),
                    listen = o.optBoolean("listen", it.listen),
                    rtmpUrl = o.optString("rtmpUrl", it.rtmpUrl),
                    rtmpCodec = runCatching { BroadcastCodec.valueOf(o.getString("rtmpCodec")) }.getOrDefault(it.rtmpCodec),
                    glassesHost = o.optBoolean("glassesHost", it.glassesHost),
                )
            }
        }
        backup.optJSONArray("pins")?.let { a -> pins.set(a.strings()) }
        if (backup.has("updates") && backup.optBoolean("updates") != updates.enabled.value) updates.setEnabled(backup.optBoolean("updates"))

        val glasses = backup.optJSONObject("glasses") ?: return Restored(0, true)
        // The voice last: a phone that cannot speak it, or glasses that keep theirs, stop the restore with a reason.
        val keys = glasses.keys().asSequence().filter { settings.catalogue[it]?.writable == true }.sortedBy { it == VOICE_KEY }.toList()
        var notWritten = 0
        var voiceChecked = true
        for ((i, key) in keys.withIndex()) {
            _restoring.value = i to keys.size
            // Away, nothing more reaches the glasses.
            if (connection.state.value.link != LinkState.READY) {
                notWritten += keys.size - i
                break
            }
            val want = glasses.getString(key)
            if (same(settings.catalogue[key]?.kind, settings.value(key), want)) continue
            if (key == VOICE_KEY) {
                voiceChecked = languages.checkPhone(want)
                languages.switchVoice(want)
            } else {
                if (!settings.write(key, want)) notWritten++
                delay(WRITE_GAP_MS)
            }
        }
        _restoring.value = keys.size to keys.size
        return Restored(notWritten, voiceChecked)
    }

    private companion object {
        const val LAST = "last"
        const val WRITE_GAP_MS = 250L
    }
}

@HiltViewModel
class BackupModel @Inject constructor(val backups: Backups, val connection: GlassesConnection, val settings: GlassesSettings) : ViewModel()

/** Save every setting to a file, or put a saved file back. */
@Composable
fun BackupScreen(model: BackupModel = hiltViewModel()) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val settings by model.settings.state.collectAsStateWithLifecycle()
    val last by model.backups.last.collectAsStateWithLifecycle()
    val restoring by model.backups.restoring.collectAsStateWithLifecycle()
    val restored by model.backups.restored.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val ready = glasses.link == LinkState.READY
    val busy = restoring != null
    val steps = restoring?.takeIf { it.second > 0 }?.let { (done, total) -> stringResource(R.string.backup_restoring, done, total) }
    // The last restore's outcome, until this page says something newer.
    val outcome = restored?.takeIf { !busy && problem == null }
    val done = outcome?.getOrNull()

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            saving = true
            try {
                model.backups.save(uri)
                problem = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = resources.getString(R.string.backup_save_failed)
            } finally {
                saving = false
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val backup = try {
                model.backups.read(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            if (backup != null && backup.has("visor")) pending = backup else problem = resources.getString(R.string.backup_not_ours)
        }
    }

    SettingsPage(stringResource(R.string.backup_title), light = 0.35f) {
        item(key = "save") {
            Rows(
                {
                    ValueRow(
                        stringResource(R.string.backup_save), if (settings.readAt == null) stringResource(R.string.backup_visor_only) else null,
                        onClick = if (saving) null else ({ create.launch("visor-settings-${LocalDate.now()}.json") }), busy = saving,
                        sub = last?.let { stringResource(R.string.backup_sum_last, dateWords(it)) } ?: stringResource(R.string.backup_sum_never),
                    )
                },
                {
                    ValueRow(
                        stringResource(R.string.backup_restore), null, onClick = if (busy) null else ({ openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }),
                        sub = if (busy) steps else if (done?.notWritten == 0) stringResource(R.string.backup_restored) else null, busy = busy,
                    )
                },
            )
        }
        item(key = "state") {
            Column {
                problem?.let { Footnote(it, color = Visor.colors.signal) }
                if (done != null && done.notWritten > 0) Footnote(pluralStringResource(R.plurals.backup_settings_not_restored, done.notWritten, done.notWritten), color = Visor.colors.signal)
                if (done != null && !done.voiceChecked) Footnote(stringResource(R.string.language_recognition_unknown), color = Visor.colors.signal)
                if (outcome?.isFailure == true) Footnote(stringResource(R.string.backup_restore_failed), color = Visor.colors.signal)
            }
        }
    }

    pending?.let { backup ->
        val count = backup.optJSONObject("glasses")?.length() ?: 0
        val savedAt = runCatching { Instant.parse(backup.optString("saved")).toEpochMilli() }.getOrNull()
        ConfirmSheet(
            savedAt?.let { stringResource(R.string.backup_restore_title, dateWords(it)) } ?: stringResource(R.string.backup_restore),
            when {
                count == 0 -> null
                ready -> stringResource(R.string.backup_restore_text, count)
                else -> stringResource(R.string.backup_restore_away, count)
            },
            stringResource(R.string.backup_restore),
            onConfirm = {
                problem = null
                model.backups.startRestore(backup)
            },
            onDismiss = { pending = null },
        )
    }
}
