package com.macedonga.visor.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val REPO_URL = "https://github.com/macedonga/visor"
const val NEW_ISSUE_URL = "$REPO_URL/issues/new/choose"
const val NEW_DISCUSSION_URL = "$REPO_URL/discussions/new/choose"

fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Asks for a star on GitHub a day after Visor was first used; Later asks again a week later. */
@Singleton
class StarAsk @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("star", Context.MODE_PRIVATE)
    private val _due = MutableStateFlow(false)
    val due: StateFlow<Boolean> = _due.asStateFlow()

    /** Home was opened with a pair set up: see whether the ask is due. */
    fun opened() {
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_FIRST)) prefs.edit().putLong(KEY_FIRST, now).apply()
        _due.value = !prefs.getBoolean(KEY_NEVER, false) && now - prefs.getLong(KEY_FIRST, now) >= DAY_MS &&
            now >= prefs.getLong(KEY_NEXT, 0)
    }

    fun later() {
        prefs.edit().putLong(KEY_NEXT, System.currentTimeMillis() + LATER_MS).apply()
        _due.value = false
    }

    fun never() {
        prefs.edit().putBoolean(KEY_NEVER, true).apply()
        _due.value = false
    }

    private companion object {
        const val KEY_FIRST = "first"
        const val KEY_NEXT = "next"
        const val KEY_NEVER = "never"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val LATER_MS = 7 * DAY_MS
    }
}

/** The ask: a star and three answers. Swiping it away counts as Later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarSheet(onOk: () -> Unit, onLater: () -> Unit, onNever: () -> Unit) {
    val colors = Visor.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The sheet slides away before the answer takes it out.
    fun answer(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { then() }
    }
    VisorSheet(onLater, state = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = SIDE), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(bottom = 16.dp).size(68.dp).clip(RoundedCornerShape(22.dp)).background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(VisorIcons.Star, null, Modifier.size(32.dp), tint = colors.textPrimary)
            }
            Text(
                stringResource(R.string.star_title), style = Visor.type.title, color = colors.textPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 28.dp).asHeading(),
            )
            WidePill(stringResource(R.string.star_ok), { answer(onOk) }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WidePill(stringResource(R.string.star_never), { answer(onNever) }, Modifier.weight(1f), quiet = true)
                WidePill(stringResource(R.string.star_no), { answer(onLater) }, Modifier.weight(1f), quiet = true)
            }
        }
    }
}
