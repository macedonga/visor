package com.macedonga.visor.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.connection.GlassesRequest
import com.macedonga.visor.connection.RequestLog
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.Hairline
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.MONO
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class RequestLogViewModel @Inject constructor(val log: RequestLog) : ViewModel()

/** What the glasses' apps asked the phone to fetch, newest first; a tap copies the whole link. */
@Composable
fun RequestLogScreen(model: RequestLogViewModel = hiltViewModel()) {
    val items by model.log.items.collectAsStateWithLifecycle()
    val shown = items.asReversed()
    SettingsPage(
        stringResource(R.string.requests_title),
        light = 0.3f,
        actions = { RoundIcon(VisorIcons.Trash, stringResource(R.string.event_log_clear), model.log::clear) },
    ) {
        if (shown.isEmpty()) item(key = "empty") { Footnote(stringResource(R.string.requests_empty)) }
        itemsIndexed(shown, key = { _, r -> r.id }) { i, r ->
            val shape = when {
                shown.size == 1 -> RoundedCornerShape(18.dp)
                i == 0 -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                i == shown.lastIndex -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
                else -> RectangleShape
            }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(Visor.colors.raised)) {
                if (i > 0) Hairline()
                RequestRow(r)
            }
        }
    }
}

@Composable
private fun RequestRow(r: GlassesRequest) {
    val colors = Visor.colors
    val context = LocalContext.current
    val host = LocalHost.current
    val copied = stringResource(R.string.requests_copied)
    Column(
        Modifier.fillMaxWidth().clickable {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("url", r.url))
            host.notice.show(copied)
        }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(place(r.url), style = Visor.type.label, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val outcome = r.outcome ?: stringResource(R.string.requests_running)
        val parts = listOfNotNull(
            TIME.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())),
            r.times.takeIf { it > 1 }?.let { "×$it" },
            outcome,
            r.bytes.takeIf { it > 0 }?.let(::size),
            r.millis.takeIf { r.outcome != null }?.let { "$it ms" },
            when (r.source) {
                -1 -> stringResource(R.string.requests_tunnel)
                -2 -> stringResource(R.string.requests_meta)
                -3 -> stringResource(R.string.requests_location)
                -4 -> stringResource(R.string.requests_sms)
                -5 -> stringResource(R.string.requests_calls)
                -6 -> stringResource(R.string.navigation_requests_maps)
                else -> stringResource(R.string.requests_source, r.source)
            },
            r.options.ifEmpty { null },
        )
        Text(parts.joinToString(" · "), style = MONO, color = if (r.failed) colors.signal else colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Host and path; the query, often a long signature, is left to the copied link. */
private fun place(url: String): String {
    val noQuery = url.substringBefore('?')
    return noQuery.substringAfter("://").ifEmpty { url }.let { if (url.startsWith("http")) it else noQuery }
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
