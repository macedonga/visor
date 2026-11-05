package com.macedonga.visor.metaai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.date
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.clockTime
import com.macedonga.visor.gallery.dayTitle
import com.macedonga.visor.settings.BAR
import com.macedonga.visor.settings.BarShade
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.TopBar

/** One Live AI session in full: each thing asked, the frame the camera had then, and the answer. */
@Composable
fun LiveSessionScreen(conversation: String, onMemories: () -> Unit, model: MetaAiModel = hiltViewModel()) {
    val colors = Visor.colors
    val all by model.turns.collectAsStateWithLifecycle()
    val turns = remember(all, conversation) { all.filter { it.conversation == conversation } }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top + BAR, bottom = 40.dp)) {
            item(key = "head") {
                turns.firstOrNull()?.let {
                    Text(dayTitle(it.date) + ", " + clockTime(it.at), style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE).padding(top = 6.dp, bottom = 24.dp))
                }
            }
            itemsIndexed(turns, key = { _, t -> t.interactionId }) { i, turn ->
                if (i > 0) Spacer(Modifier.height(28.dp))
                Exchange(turn, Int.MAX_VALUE, onActions = null, onPhoto = { viewing = it }, onMemories = onMemories)
            }
            item(key = "foot") { Spacer(Modifier.navigationBarsPadding()) }
        }
        BarShade { if (list.canScrollBackward) 1f else 0f }
        TopBar(stringResource(R.string.meta_ai_live_ai))
    }
    viewing?.let { PhotoView(it) { viewing = null } }
}
