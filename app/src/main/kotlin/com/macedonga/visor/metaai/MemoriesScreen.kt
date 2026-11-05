package com.macedonga.visor.metaai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.dayTitle
import com.macedonga.visor.settings.BAR
import com.macedonga.visor.settings.BarShade
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.TopBar
import com.macedonga.visor.settings.UndoPill
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.rememberHaptics
import java.time.Instant
import java.time.ZoneId

/** What the assistant keeps about the wearer: read, dropped, or added by hand. */
@Composable
fun MemoriesScreen(model: MetaAiModel = hiltViewModel()) {
    val colors = Visor.colors
    val haptics = rememberHaptics()
    val memories by model.memories.collectAsStateWithLifecycle()
    val forgotten by model.forgotten.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf("") }

    fun add() {
        if (adding.isBlank()) return
        haptics.toggle()
        model.remember(adding.trim())
        adding = ""
    }

    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top + BAR + 12.dp, bottom = 140.dp)) {
            if (memories.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.memories_empty), style = Visor.type.body, color = colors.textTertiary, modifier = Modifier.padding(horizontal = SIDE, vertical = 12.dp))
                }
            }
            items(memories.asReversed(), key = { it.id }) { memory ->
                Row(
                    Modifier.animateItem().fillMaxWidth().padding(start = SIDE, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(memory.text, style = ledeStyle(), color = colors.textPrimary)
                        val day = Instant.ofEpochMilli(memory.at).atZone(ZoneId.systemDefault()).toLocalDate()
                        Text(dayTitle(day), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
                    }
                    RoundIcon(VisorIcons.Close, stringResource(R.string.memories_forget), {
                        haptics.toggle()
                        model.forget(memory)
                    }, tint = colors.textSecondary)
                }
            }
        }
        BarShade { if (list.canScrollBackward) 1f else 0f }
        TopBar(stringResource(R.string.meta_ai_memories))

        UndoPill(
            forgotten?.let { stringResource(R.string.memories_deleted) },
            { haptics.toggle(); model.unforget() },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
        )

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to colors.canvas.copy(alpha = 0.92f), 1f to colors.canvas))
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            InputField(
                adding, { adding = it }, stringResource(R.string.memories_add), 4,
                KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                Modifier.weight(1f),
                KeyboardActions(onDone = { add() }),
            )
            Spacer(Modifier.width(10.dp))
            val can = adding.isNotBlank()
            RoundIcon(
                VisorIcons.Plus, stringResource(R.string.memories_add), { add() },
                size = 52.dp, background = if (can) colors.textPrimary else colors.control, tint = if (can) Color.Black else colors.textSecondary,
            )
        }
    }
}
