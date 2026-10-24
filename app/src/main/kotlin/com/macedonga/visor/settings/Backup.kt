package com.macedonga.visor.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.mcp.AuthKind
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.McpStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.BroadcastCodec
import com.macedonga.visor.core.media.LiveOverlay
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.gallery.AutoImport
import com.macedonga.visor.gallery.ImportChoice
import com.macedonga.visor.notify.NotifyApps
import com.macedonga.visor.notify.NotifyChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every setting in one file and back: the glasses' own (those set away from their default) and Visor's. The API key,
 * the Meta login and the stream key stay out of it.
 */
@Singleton
class Backups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: GlassesSettings,
    private val connection: GlassesConnection,
    private val assistant: AssistantConfigStore,
    private val notify: NotifyChoice,
    private val apps: NotifyApps,
    private val import: ImportChoice,
    private val live: LiveSettingsStore,
    private val pins: Pins,
    private val mcp: McpStore,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L).takeIf { it > 0 })

    /** When a backup was last saved, if ever. */
    val last: StateFlow<Long?> = _last.asStateFlow()

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
            .put("glasses", glasses)
            .put(
                "assistant",
                JSONObject()
                    .put("enabled", a.enabled).put("provider", a.provider.name).put("model", a.model).put("linesModel", a.linesModel)
                    .put("baseUrl", a.baseUrl).put("webSearch", a.webSearch).put("vision", a.vision).put("glassesVoice", a.glassesVoice).put("speechLanguage", a.speechLanguage)
                    .put("instructions", a.instructions).put("memoryMinutes", a.memoryMinutes)
                    .put("answerWaitSeconds", a.answerWaitSeconds),
            )
            .put("notifications", JSONObject().put("enabled", notify.enabled.value).put("muted", JSONArray(apps.muted.value.sorted())))
            .put("import", import.mode.value.name)
            .put(
                "live",
                JSONObject()
                    .put("manual", l.manual).put("quality", l.quality.name).put("bitrate", l.bitrate).put("frameRate", l.frameRate)
                    .put("overlays", JSONArray(l.overlays.map { it.name })).put("replaySeconds", l.replaySeconds)
                    .put("listen", l.listen).put("rtmpUrl", l.rtmpUrl).put("rtmpCodec", l.rtmpCodec.name),
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
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(build().toString(2).toByteArray()) }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(LAST, now).apply()
        _last.value = now
    }

    suspend fun read(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
    }

    /** Puts Visor's part back at once, and the glasses' part one write at a time; the glasses' part needs them connected. */
    suspend fun restore(backup: JSONObject, onProgress: (Int, Int) -> Unit) {
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
                    glassesVoice = o.optBoolean("glassesVoice", it.glassesVoice),
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
                        off = o.optJSONArray("off")?.let { f -> (0 until f.length()).map { f.getString(it) }.toSet() }.orEmpty(),
                        clientId = o.optString("clientId"), redirect = o.optString("redirect"),
                    ),
                )
            }
        }
        backup.optJSONObject("notifications")?.let { o ->
            notify.set(o.optBoolean("enabled", true))
            o.optJSONArray("muted")?.let { a -> apps.restore((0 until a.length()).map { a.getString(it) }.toSet()) }
        }
        backup.optString("import").takeIf { it.isNotEmpty() }?.let { m -> runCatching { AutoImport.valueOf(m) }.getOrNull()?.let(import::set) }
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
                )
            }
        }
        backup.optJSONArray("pins")?.let { a -> pins.set((0 until a.length()).map { a.getString(it) }) }

        val glasses = backup.optJSONObject("glasses") ?: return
        if (connection.state.value.link != LinkState.READY) return
        val keys = glasses.keys().asSequence().filter { settings.catalogue[it]?.writable == true }.toList()
        for ((i, key) in keys.withIndex()) {
            onProgress(i, keys.size)
            val want = glasses.getString(key)
            if (settings.value(key) != want) {
                settings.write(key, want)
                delay(WRITE_GAP_MS)
            }
        }
        onProgress(keys.size, keys.size)
    }

    private companion object {
        const val LAST = "last"
        const val WRITE_GAP_MS = 250L
    }
}

@HiltViewModel
class BackupModel @Inject constructor(val backups: Backups, val connection: GlassesConnection) : ViewModel()

/** Save every setting to a file, or put a saved file back. */
@Composable
fun BackupScreen(model: BackupModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val last by model.backups.last.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val ready = glasses.link == LinkState.READY
    val wordsSaved = stringResource(R.string.backup_saved)
    val wordsDone = stringResource(R.string.backup_restored)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            saving = true
            runCatching { model.backups.save(uri) }.onSuccess { progress = wordsSaved; problem = null }.onFailure { problem = it.message }
            saving = false
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { model.backups.read(uri) }.onSuccess { if (it.has("visor")) pending = it else problem = context.getString(R.string.backup_not_ours) }
                .onFailure { problem = it.message }
        }
    }

    SettingsPage(stringResource(R.string.backup_title), stringResource(R.string.backup_lede), light = 0.35f) {
        item(key = "save") {
            Rows(
                {
                    ValueRow(
                        stringResource(R.string.backup_save), null, onClick = if (saving) null else ({ create.launch("visor-settings-${LocalDate.now()}.json") }), busy = saving,
                        sub = last?.let { stringResource(R.string.backup_sum_last, dateWords(it)) } ?: stringResource(R.string.backup_sum_never),
                    )
                },
                {
                    ValueRow(
                        stringResource(R.string.backup_restore), null, onClick = if (restoring) null else ({ openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }),
                        sub = if (restoring) progress else stringResource(R.string.backup_restore_sub), busy = restoring,
                    )
                },
            )
        }
        item(key = "state") {
            Column {
                progress?.let { Footnote(it) }
                problem?.let { Footnote(it, color = com.macedonga.visor.core.design.Visor.colors.signal) }
                Footnote(stringResource(R.string.backup_foot))
            }
        }
    }

    pending?.let { backup ->
        val count = backup.optJSONObject("glasses")?.length() ?: 0
        VisorSheet({ pending = null }) {
            SheetTitle(
                stringResource(R.string.backup_restore_title, backup.optString("saved").take(10)),
                if (ready) stringResource(R.string.backup_restore_text, count) else stringResource(R.string.backup_restore_away, count),
            )
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(stringResource(R.string.backup_restore), {
                    pending = null
                    scope.launch {
                        restoring = true
                        model.backups.restore(backup) { done, total -> progress = context.getString(R.string.backup_restoring, done, total) }
                        restoring = false
                        progress = wordsDone
                    }
                })
                ActionPill(stringResource(R.string.language_cancel), { pending = null }, quiet = true)
            }
        }
    }
}
