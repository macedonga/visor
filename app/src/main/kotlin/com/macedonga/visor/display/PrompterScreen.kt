package com.macedonga.visor.display

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.PrompterLook
import com.macedonga.visor.core.connection.PrompterPhase
import com.macedonga.visor.core.connection.Teleprompter
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.settings.row
import com.macedonga.visor.ui.paste
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one script and how it shows, kept on the phone. */
@Singleton
class PrompterScript @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
    private val _text = MutableStateFlow(prefs.getString(TEXT, "").orEmpty())
    val text: StateFlow<String> = _text.asStateFlow()
    private val _look = MutableStateFlow(
        PrompterLook().let { d ->
            PrompterLook(
                sizeSp = prefs.getFloat(SIZE, d.sizeSp.toFloat()).toDouble(),
                speed = prefs.getFloat(SPEED, d.speed.toFloat()).toDouble(),
                paged = prefs.getBoolean(PAGED, d.paged),
            )
        },
    )
    val look: StateFlow<PrompterLook> = _look.asStateFlow()

    fun setText(text: String) {
        _text.value = text
        prefs.edit().putString(TEXT, text).apply()
    }

    fun setLook(look: PrompterLook) {
        _look.value = look
        prefs.edit().putFloat(SIZE, look.sizeSp.toFloat()).putFloat(SPEED, look.speed.toFloat())
            .putBoolean(PAGED, look.paged).apply()
    }

    private companion object {
        const val TEXT = "text"
        const val SIZE = "size"
        const val SPEED = "speed"
        const val PAGED = "paged"
    }
}

@HiltViewModel
class PrompterModel @Inject constructor(
    val script: PrompterScript,
    val prompter: Teleprompter,
    val connection: GlassesConnection,
) : ViewModel()

/** The teleprompter: preview, script, size, speed and layout, and the button for the glasses. */
@Composable
fun PrompterScreen(model: PrompterModel = hiltViewModel()) {
    val text by model.script.text.collectAsStateWithLifecycle()
    val look by model.script.look.collectAsStateWithLifecycle()
    val state by model.prompter.state.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val ready = glasses.link == LinkState.READY
    var editing by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    SettingsPage(
        stringResource(R.string.prompter_title),
        sheet = {
            PrompterBar(
                ready = ready,
                empty = text.isBlank(),
                phase = state.phase,
                changed = state.shown != text || state.look != look,
                refusal = state.refusal,
                onShow = { haptics.done(); model.prompter.show(text, look) },
                onClose = { haptics.toggle(); model.prompter.close() },
            )
            if (editing) ScriptSheet(text, model.script::setText) { editing = false }
        },
    ) {
        item(key = "square") {
            ScriptPreview(text, look, Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), onClick = { editing = true })
        }
        item(key = "script") {
            val words = remember(text) { wordCount(text) }
            Rows(
                row {
                    ValueRow(
                        stringResource(R.string.prompter_script),
                        if (words == 0) stringResource(R.string.prompter_card_empty) else pluralStringResource(R.plurals.prompter_words, words, words),
                        onClick = { editing = true },
                    )
                },
            )
        }
        item(key = "look") {
            Spacer(Modifier.height(16.dp))
            Rows(
                row {
                    PillsRow(
                        stringResource(R.string.prompter_size), SIZES.map { it to sizeName(it) }, SIZES.minByOrNull { abs(it - look.sizeSp) },
                        { model.script.setLook(look.copy(sizeSp = it)) },
                    )
                },
                row {
                    PillsRow(
                        stringResource(R.string.prompter_speed), SPEEDS.map { it to "%s×".format(speedName(it)) },
                        SPEEDS.minByOrNull { abs(it - look.speed) }, { model.script.setLook(look.copy(speed = it)) },
                    )
                },
                row {
                    PillsRow(
                        stringResource(R.string.prompter_layout),
                        listOf(false to stringResource(R.string.prompter_scroll), true to stringResource(R.string.prompter_pages)),
                        look.paged, { model.script.setLook(look.copy(paged = it)) },
                    )
                },
            )
        }
        item(key = "foot") { Spacer(Modifier.height(BAR_ROOM)) }
    }
}

/** The script at the glasses' scale: scrolled starts mid-square, paged fills from the top. */
@Composable
private fun ScriptPreview(text: String, look: PrompterLook, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    DisplaySquare(modifier, onClick) { px ->
        val inset = Modifier.padding(glassesDp(PROMPTER_INSET, px))
        val style = Visor.type.heading.copy(
            fontSize = glassesSp(look.sizeSp.toFloat(), px),
            lineHeight = 1.3.em,
            color = Color.White,
        )
        if (text.isBlank()) {
            Text(stringResource(R.string.prompter_empty), style = style.copy(color = Visor.colors.textTertiary), modifier = Modifier.align(Alignment.Center).then(inset))
        } else if (look.paged) {
            Text(text, style = style, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxSize().then(inset))
        } else {
            Column(Modifier.fillMaxSize().then(inset)) {
                Spacer(Modifier.weight(1f))
                Text(text, style = style, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f).wrapContentHeight(Alignment.Top, unbounded = true))
            }
        }
    }
}

private val SPACES = Regex("\\s+")

internal fun wordCount(text: String): Int = text.split(SPACES).count { it.isNotEmpty() }

/** At the foot of the page, over a fade: what the glasses are doing with the script, and the button for it. */
@Composable
private fun PrompterBar(
    ready: Boolean,
    empty: Boolean,
    phase: PrompterPhase,
    changed: Boolean,
    refusal: Long?,
    onShow: () -> Unit,
    onClose: () -> Unit,
) {
    val line = when {
        !ready -> stringResource(R.string.connect_first)
        refusal != null -> refusalWords(refusal)
        phase == PrompterPhase.SHOWING -> stringResource(R.string.prompter_on_glasses)
        else -> null
    }
    ToolBar(line, bad = refusal != null && ready, busy = ready && (phase == PrompterPhase.STARTING || phase == PrompterPhase.CLOSING)) {
        when {
            !ready -> Unit
            phase == PrompterPhase.SHOWING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WidePill(stringResource(R.string.close), onClose, Modifier.weight(1f), quiet = true)
                WidePill(stringResource(R.string.prompter_update), onShow, Modifier.weight(1f), enabled = changed && !empty)
            }
            else -> WidePill(stringResource(R.string.prompter_show), onShow, Modifier.fillMaxWidth(), enabled = !empty)
        }
    }
}

/** The script, full height, with Paste and Clear; every keystroke is kept. */
@Composable
private fun ScriptSheet(text: String, onText: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // What Clear took, until the next edit.
    var cleared by remember { mutableStateOf<String?>(null) }
    VisorSheet(onDismiss, full = true) {
        SheetTitle(stringResource(R.string.prompter_script))
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 160.dp, max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Visor.colors.raised)
                .padding(16.dp),
        ) {
            if (text.isEmpty()) Text(stringResource(R.string.prompter_empty), style = Visor.type.body, color = Visor.colors.textTertiary)
            BasicTextField(
                value = text,
                onValueChange = { cleared = null; onText(it) },
                textStyle = Visor.type.body.copy(color = Visor.colors.textPrimary),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionPill(stringResource(R.string.ai_paste), { paste(context)?.let { cleared = null; onText(if (text.isBlank()) it else text + "\n" + it) } }, quiet = true)
            val undo = cleared
            if (undo != null) {
                ActionPill(stringResource(R.string.undo), { onText(undo); cleared = null }, quiet = true)
            } else if (text.isNotEmpty()) {
                ActionPill(stringResource(R.string.settings_clear), { cleared = text; onText("") }, quiet = true)
            }
            Spacer(Modifier.weight(1f))
            ActionPill(stringResource(R.string.done), onDismiss)
        }
    }
}

@Composable
private fun sizeName(sp: Double): String = stringResource(
    when (sp) {
        SIZES[0] -> R.string.prompter_size_small
        SIZES[1] -> R.string.prompter_size_medium
        SIZES[2] -> R.string.prompter_size_large
        else -> R.string.prompter_size_huge
    },
)

/** As a multiple of the glasses' own default, the one thing the number means for sure. */
private fun speedName(speed: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(speed / DEFAULT_SPEED)

// The glasses' default size is 35 sp; the Display's own body text runs 24 to 32.
private val SIZES = listOf(28.0, 35.0, 44.0, 56.0)
private val DEFAULT_SPEED = PrompterLook().speed
private val SPEEDS = listOf(16.0, 24.0, 32.0, 48.0, 64.0)
// The glasses' own teleprompter layout is not known; this keeps the preview's text off the square's edge.
private const val PROMPTER_INSET = 28f
