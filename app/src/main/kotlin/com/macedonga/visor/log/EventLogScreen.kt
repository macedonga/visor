package com.macedonga.visor.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.ConfirmSheet
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.MONO
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.cardItems
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import com.macedonga.visor.login.MetaLogin
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

@HiltViewModel
class EventLogViewModel @Inject constructor(val log: EventLog) : ViewModel()

/** The log as one card of lines, opened at the end: what matters is what just happened. */
@Composable
fun EventLogScreen(model: EventLogViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lines by model.log.lines.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (lines.isNotEmpty()) list.scrollToItem(lines.size + 1) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching { save(context, uri, model.log.text()) }.onFailure { model.log.add("Could not save the log: ${it.message}") }
        }
    }

    SettingsPage(
        stringResource(R.string.settings_event_log),
        light = 0.3f,
        list = list,
        actions = {
            RoundIcon(VisorIcons.Download, stringResource(R.string.event_log_save), { create.launch("visor-log-${LocalDateTime.now().format(FILE_TIME)}.txt") })
            RoundIcon(VisorIcons.Share, stringResource(R.string.event_log_share), {
                scope.launch {
                    runCatching { share(context, model.log.text()) }.onFailure { model.log.add("Could not share the log: ${it.message}") }
                }
            })
            RoundIcon(VisorIcons.Trash, stringResource(R.string.event_log_clear), { clearing = true })
        },
        sheet = {
            if (clearing) {
                ConfirmSheet(
                    stringResource(R.string.event_log_clear_title), null, stringResource(R.string.event_log_clear),
                    onConfirm = { model.log.clear() },
                    onDismiss = { clearing = false },
                )
            }
        },
    ) {
        if (lines.isEmpty()) item(key = "empty") { Footnote(stringResource(R.string.event_log_empty)) }
        // Keyed by place: the same line can come twice.
        cardItems(lines.withIndex().toList(), { it.index }) { LogRow(it.value) }
    }
}

/** `HH:mm:ss.SSS  what happened`: the time in the margin, a failure in the signal colour. */
@Composable
private fun LogRow(line: String) {
    val colors = Visor.colors
    val time = line.substringBefore("  ", "")
    val text = line.substringAfter("  ", line)
    val failed = text.startsWith("Warning") || text.contains("failed", ignoreCase = true) || text.contains("crashed")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(time.dropLast(4), style = MONO, color = colors.textTertiary, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 56.dp).padding(top = 3.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Visor.type.label, color = if (failed) colors.signal else colors.textPrimary)
    }
}

/** What the log came from: the first line of the file. */
private fun head(): String {
    val webView = WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    // A work profile or Samsung's Secure Folder is another user: what Visor saves there, the main gallery never shows.
    val user = android.os.Process.myUid() / 100_000
    return "Visor ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, WebView $webView" +
        if (user != 0) ", user $user" else ""
}

/** The log as a text file, headed by what it came from. */
private fun report(context: Context, text: String): String {
    // The last Meta login's Bloks trace rides along: every call, request and failure of it.
    val trace = MetaLogin.traceFile(context).takeIf { it.exists() && it.length() > 0 }?.readText()
    return head() + "\n\n" + text + "\n" + (trace?.let { "\n--- Bloks trace of the last Meta login ---\n$it" } ?: "")
}

/** The log for mail or chat. */
private suspend fun share(context: Context, text: String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "log/visor-log.txt").apply {
            parentFile?.mkdirs()
            writeText(report(context, text))
        }
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_event_log))
        .putExtra(Intent.EXTRA_TEXT, head())
        .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.packageName + ".files", file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

/** The log into the file the save window made. */
private suspend fun save(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    checkNotNull(context.contentResolver.openOutputStream(uri)) { "the file could not be opened" }.use { it.write(report(context, text).toByteArray()) }
}
