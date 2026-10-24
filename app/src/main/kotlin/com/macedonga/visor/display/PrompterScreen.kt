package com.macedonga.visor.display

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.row
import com.macedonga.visor.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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
        PrompterLook(
            sizeSp = prefs.getFloat(SIZE, 35f).toDouble(),
            speed = prefs.getFloat(SPEED, 32f).toDouble(),
            paged = prefs.getBoolean(PAGED, false),
        ),
    )
    val look: StateFlow<PrompterLook> = _look.asStateFlow()

    fun write(text: String) {
        _text.value = text
        prefs.edit().putString(TEXT, text).apply()
    }

    fun look(look: PrompterLook) {
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

/**
 * The teleprompter: the script as the glasses will draw it, in their square; the script itself, how big and how fast,
 * and the one button that puts it on the glasses, or updates or closes it there.
 */
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
            if (editing) ScriptSheet(text, model.script::write) { editing = false }
        },
    ) {
        item(key = "square") {
            ScriptPreview(text, look, Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).clickable { editing = true })
        }
        item(key = "script") { Rows(row { ScriptRow(text) { editing = true } }) }
        item(key = "look") {
            Spacer(Modifier.height(16.dp))
            Rows(
                row {
                    PillsRow(
                        stringResource(R.string.prompter_size), SIZES.map { it to sizeName(it) }, SIZES.minByOrNull { kotlin.math.abs(it - look.sizeSp) },
                        { model.script.look(look.copy(sizeSp = it)) },
                    )
                },
                row {
                    PillsRow(
                        stringResource(R.string.prompter_speed), SPEEDS.map { it to "%s×".format(speedName(it)) },
                        SPEEDS.minByOrNull { kotlin.math.abs(it - look.speed) }, { model.script.look(look.copy(speed = it)) },
                    )
                },
                row {
                    PillsRow(
                        stringResource(R.string.prompter_layout),
                        listOf(false to stringResource(R.string.prompter_scroll), true to stringResource(R.string.prompter_pages)),
                        look.paged, { model.script.look(look.copy(paged = it)) },
                    )
                },
            )
        }
        item(key = "foot") { Spacer(Modifier.height(BAR_ROOM)) }
    }
}

/**
 * The script in the glasses' square, at the size they will draw it: scrolled, the first line sits in the middle,
 * where they centre the line being read; paged, it fills from the top as their first card does.
 */
@Composable
fun ScriptPreview(text: String, look: PrompterLook, modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 14.dp) {
    DisplaySquare(modifier, radius) { px ->
        val inset = Modifier.padding(glassesDp(PROMPTER_INSET, px))
        val style = Visor.type.body.copy(
            fontSize = glassesSp(look.sizeSp, px),
            lineHeight = 1.3.em,
            fontWeight = FontWeight.Medium,
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

@Composable
private fun ScriptRow(text: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.prompter_script), style = Visor.type.body, color = Visor.colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        if (text.isBlank()) {
            Text(stringResource(R.string.prompter_empty), style = Visor.type.label, color = Visor.colors.textSecondary)
        } else {
            val words = remember(text) { text.split(Regex("\\s+")).count { it.isNotEmpty() } }
            Text(pluralStringResource(R.plurals.prompter_words, words, words), style = Visor.type.label, color = Visor.colors.textSecondary)
        }
    }
}

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
    val colors = Visor.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to colors.canvas))
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val line = when {
                !ready -> stringResource(R.string.prompter_not_connected)
                refusal != null -> refusalWords(refusal)
                phase == PrompterPhase.SHOWING -> stringResource(R.string.prompter_on_glasses)
                else -> null
            }
            if (line != null) {
                Text(line, style = Visor.type.label, color = if (refusal != null && ready) colors.signal else colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
            }
            when {
                !ready -> Unit
                phase == PrompterPhase.STARTING || phase == PrompterPhase.CLOSING -> Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) { Spinner() }
                phase == PrompterPhase.SHOWING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WidePill(stringResource(R.string.prompter_close), onClose, Modifier.weight(1f), quiet = true)
                    WidePill(stringResource(R.string.prompter_update), onShow, Modifier.weight(1f), enabled = changed && !empty)
                }
                else -> WidePill(stringResource(R.string.prompter_show), onShow, Modifier.fillMaxWidth(), enabled = !empty)
            }
        }
    }
}

@Composable
internal fun WidePill(text: String, onClick: () -> Unit, modifier: Modifier, quiet: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (quiet) Visor.colors.control else Color.White.copy(alpha = if (enabled) 1f else 0.25f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Visor.type.body, color = if (quiet) Visor.colors.textPrimary else Color.Black)
    }
}

/** The script, full height, with Paste and Clear; every keystroke is kept. */
@Composable
private fun ScriptSheet(text: String, onText: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    VisorSheet(onDismiss, full = true) {
        SheetTitle(stringResource(R.string.prompter_script))
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Visor.colors.raised)
                .padding(16.dp),
        ) {
            if (text.isEmpty()) Text(stringResource(R.string.prompter_empty), style = Visor.type.body, color = Visor.colors.textTertiary)
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = Visor.type.body.copy(color = Visor.colors.textPrimary),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionPill(stringResource(R.string.ai_paste), { pasted(context)?.let { onText(if (text.isBlank()) it else text + "\n" + it) } }, quiet = true)
            if (text.isNotEmpty()) ActionPill(stringResource(R.string.settings_clear), { onText("") }, quiet = true)
            Spacer(Modifier.weight(1f))
            ActionPill(stringResource(R.string.editor_done), onDismiss)
        }
    }
}

@Composable
internal fun refusalWords(code: Long): String = stringResource(
    when (code) {
        Teleprompter.SNAPP_NOT_ENOUGH_BATTERY -> R.string.prompter_refused_battery
        Teleprompter.SNAPP_THERMAL_THROTTLING -> R.string.prompter_refused_heat
        Teleprompter.SNAPP_DENIED_BY_PEAK_POWER -> R.string.prompter_refused_power
        else -> R.string.prompter_refused
    },
)

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
    java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(speed / DEFAULT_SPEED)

private fun pasted(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

// The glasses' default size is 35 sp; the Display's own body text runs 24 to 32.
private val SIZES = listOf(28.0, 35.0, 44.0, 56.0)
private const val DEFAULT_SPEED = 32.0
private val SPEEDS = listOf(16.0, 24.0, 32.0, 48.0, 64.0)
private val BAR_ROOM = 120.dp
// The glasses' own teleprompter layout is not known; this keeps the preview's text off the square's edge.
private const val PROMPTER_INSET = 28f
