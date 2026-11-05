package com.macedonga.visor.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.macedonga.visor.connection.RequestSource
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.ConfirmSheet
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.MONO
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.cardItems
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.copy
import com.macedonga.visor.ui.sizeWords
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
    var clearing by remember { mutableStateOf(false) }
    SettingsPage(
        stringResource(R.string.requests_title),
        light = 0.3f,
        actions = { RoundIcon(VisorIcons.Trash, stringResource(R.string.event_log_clear), { clearing = true }) },
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
        if (shown.isEmpty()) item(key = "empty") { Footnote(stringResource(R.string.requests_empty)) }
        cardItems(shown, { it.id }) { RequestRow(it) }
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
            copy(context, r.url, "url")
            host.notice.show(copied)
        }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(place(r.url), style = Visor.type.label, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val outcome = r.outcome ?: stringResource(R.string.requests_running)
        val parts = listOfNotNull(
            TIME.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())),
            r.times.takeIf { it > 1 }?.let { "×$it" },
            outcome,
            r.bytes.takeIf { it > 0 }?.let { sizeWords(context, it) },
            r.millis.takeIf { r.outcome != null }?.let { "$it ms" },
            when (r.source) {
                RequestSource.TUNNEL -> stringResource(R.string.requests_tunnel)
                RequestSource.META -> stringResource(R.string.requests_meta)
                RequestSource.LOCATION -> stringResource(R.string.requests_location)
                RequestSource.SMS -> stringResource(R.string.requests_sms)
                RequestSource.CALLS -> stringResource(R.string.requests_calls)
                RequestSource.MAPS -> stringResource(R.string.requests_maps)
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
    return if (url.startsWith("http")) noQuery.substringAfter("://") else noQuery
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
