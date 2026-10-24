package com.macedonga.visor.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.PrompterPhase
import com.macedonga.visor.core.connection.Teleprompter
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What the Display's cards on the home open. */
enum class DisplayTool { TELEPROMPTER, CAPTIONS, GESTURES, MIRROR }

@HiltViewModel
class ShelfModel @Inject constructor(
    val script: PrompterScript,
    val prompter: Teleprompter,
    val band: com.macedonga.visor.band.BandLink,
    val connection: com.macedonga.visor.core.connection.GlassesConnection,
    val captions: com.macedonga.visor.core.connection.Captions,
    val captionsChoices: CaptionsChoices,
    val screencast: com.macedonga.visor.core.media.Screencast,
) : ViewModel()

/** The Display's tools on the home: a card each, with what it holds now. */
@Composable
fun DisplayShelf(onOpen: (DisplayTool) -> Unit, modifier: Modifier = Modifier, model: ShelfModel = hiltViewModel()) {
    val text by model.script.text.collectAsStateWithLifecycle()
    val prompter by model.prompter.state.collectAsStateWithLifecycle()
    val band by model.band.state.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val bandBattery = glasses.device?.bandBattery ?: band.battery
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "prompter") {
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            ToolCard(
                VisorIcons.Lines,
                stringResource(R.string.prompter_title),
                when {
                    prompter.phase == PrompterPhase.SHOWING -> stringResource(R.string.prompter_on_glasses)
                    words == 0 -> stringResource(R.string.prompter_card_empty)
                    else -> pluralStringResource(R.plurals.prompter_words, words, words)
                },
                onClick = { onOpen(DisplayTool.TELEPROMPTER) },
            )
        }
        item(key = "captions") {
            val captions by model.captions.state.collectAsStateWithLifecycle()
            val choice by model.captionsChoices.choice.collectAsStateWithLifecycle()
            ToolCard(
                VisorIcons.Captions,
                stringResource(R.string.captions_title),
                if (captions.phase == com.macedonga.visor.core.connection.CaptionsPhase.LIVE) stringResource(R.string.captions_live) else captionsSummary(choice),
                onClick = { onOpen(DisplayTool.CAPTIONS) },
            )
        }
        item(key = "gestures") {
            ToolCard(
                VisorIcons.Band,
                stringResource(R.string.gestures_title),
                bandBattery?.let { stringResource(R.string.gestures_card_battery, it) } ?: stringResource(R.string.gestures_card),
                onClick = { onOpen(DisplayTool.GESTURES) },
            )
        }
        item(key = "mirror") {
            val mirror by model.screencast.state.collectAsStateWithLifecycle()
            ToolCard(
                VisorIcons.Mirror,
                stringResource(R.string.mirror_title),
                if (mirror.phase == com.macedonga.visor.core.media.ScreencastPhase.LIVE) stringResource(R.string.mirror_live) else stringResource(R.string.mirror_idle),
                onClick = { onOpen(DisplayTool.MIRROR) },
            )
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, status: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(CARD_WIDTH)
            .clip(RoundedCornerShape(18.dp))
            .background(Visor.colors.raised)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Visor.colors.textPrimary)
        Spacer(Modifier.height(28.dp))
        Text(title, style = Visor.type.heading, color = Visor.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(status, style = Visor.type.label, color = Visor.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val CARD_WIDTH = 160.dp
