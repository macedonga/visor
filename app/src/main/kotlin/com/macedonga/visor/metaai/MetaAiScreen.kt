package com.macedonga.visor.metaai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Provider
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.core.assistant.date
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.Pill
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.gallery.clockTime
import com.macedonga.visor.gallery.dayTitle
import com.macedonga.visor.home.rememberNow
import com.macedonga.visor.stage.AnimateLight
import com.macedonga.visor.stage.LightFrame
import com.macedonga.visor.stage.LightPool
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import com.macedonga.visor.ui.rememberHaptics
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What a long press was on. */
private sealed interface Pressed {
    data class OnTurn(val turn: TurnRecord, val conversation: DiaryConversation) : Pressed
    data class OnDay(val day: DiaryDay) : Pressed
}

/**
 * Meta AI as a diary: every day something was asked, newest first, each with its lines, its photos and its
 * conversations, under the assistant's own light; at the foot, the phone's way into the conversation.
 */
@Composable
fun MetaAiScreen(onSettings: () -> Unit, onMemories: () -> Unit, onSession: (String) -> Unit, model: MetaAiModel = hiltViewModel()) {
    val colors = Visor.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val days by model.days.collectAsStateWithLifecycle()
    val turns by model.turns.collectAsStateWithLifecycle()
    val config by model.store.config.collectAsStateWithLifecycle()
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val phase by model.assistant.phase.collectAsStateWithLifecycle()
    val livePhase by model.assistant.liveAiPhase.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()

    // The assistant's light: what it is doing, and the colours of a photo it has just been shown.
    val light = remember { LightFrame() }
    val attention = rememberUpdatedState(glasses.device?.assistantAttention)
    val speech = rememberSpeech(turns, { attention.value }, light)
    var phoneListening by remember { mutableStateOf(false) }
    val configured = config.apiKey.isNotBlank() || config.provider != Provider.CLAUDE
    val now = rememberNow()
    val newest = turns.lastOrNull()
    val waiting = turns.takeLast(4).any { it.answer.isEmpty() && it.problem.isEmpty() && System.currentTimeMillis() - it.at < WAITING_MS }
    val mood = assistantMood(attention.value, phase, livePhase, phoneListening, waiting, speech.active, configured)
    val tintPhoto by rememberDiaryPhoto(newest?.image?.takeIf { it.isNotEmpty() && max(now, System.currentTimeMillis()) - newest.at < TINT_MS }, 64)
    val tint = remember(tintPhoto) { tintPhoto?.let(::averageColor) }
    AnimateLight(light, lookOf(mood, colors, tint))

    // Which conversation the composer would add to: one picked, a new one, or the one still open.
    var continuing by rememberSaveable { mutableStateOf<String?>(null) }
    var fresh by rememberSaveable { mutableStateOf(false) }
    val open = remember(turns, now) { model.assistant.openConversation() }
    val target = continuing ?: open.takeUnless { fresh }
    val targetLabel = target?.let { id ->
        turns.lastOrNull { it.conversation == id }?.let { t -> if (t.date == LocalDate.now()) clockTime(t.at) else dayTitle(t.date) + ", " + clockTime(t.at) }
    }

    var calendar by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<Pressed?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }

    val showSetUp = !configured
    val keys = remember(days, showSetUp) {
        buildList {
            if (showSetUp) add(SET_UP)
            if (days.isEmpty()) add(EMPTY)
            for (day in days) {
                add("day-${day.date}")
                for (c in day.conversations) add("c-${day.date}-${c.id}")
            }
        }
    }
    val list = rememberLazyListState()
    fun scrollTo(key: String) {
        val i = keys.indexOf(key)
        if (i >= 0) scope.launch { list.animateScrollToItem(i) }
    }
    fun keyOf(turn: TurnRecord) = "c-${turn.date}-${turn.conversation.ifEmpty { turn.interactionId }}"
    val shownDay by remember(keys) {
        derivedStateOf {
            keys.getOrNull(list.firstVisibleItemIndex)?.let { k ->
                when {
                    k.startsWith("day-") -> LocalDate.parse(k.removePrefix("day-"))
                    k.startsWith("c-") -> LocalDate.parse(k.substring(2, 12))
                    else -> null
                }
            } ?: days.firstOrNull()?.date
        }
    }

    // A new question while the top is in view: the page follows it.
    LaunchedEffect(newest?.interactionId) {
        if (newest != null && System.currentTimeMillis() - newest.at < FOLLOW_MS && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0)
    }
    // Days come into view with their lines written, or written again after a deletion.
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { k -> k.startsWith("day-") } } }
            .distinctUntilChanged()
            .collect { visible -> visible.forEach { model.writeLines(LocalDate.parse(it.removePrefix("day-"))) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.canvas)) {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val fadePx = with(density) { LIGHT_FADE.toPx() }
        val scrolled by remember { derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f) } }
        val awake by animateFloatAsState(if (mood == AssistantMood.REST || mood == AssistantMood.UNSET) 0f else 1f, tween(600), label = "awake")

        LightPool(
            light, Offset(0.5f, 0.2f), Size(0.62f, 0.4f), Size(0.001f, 0.001f),
            Modifier
                .fillMaxWidth()
                .height(maxWidth * 1.25f)
                .graphicsLayer { alpha = max(1f - scrolled * 0.9f, awake) },
        )

        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + BAR + 8.dp, bottom = 180.dp),
        ) {
            if (showSetUp) {
                item(key = SET_UP) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(bottom = 28.dp)) {
                        Text(stringResource(R.string.meta_ai_no_model), style = Visor.type.body, color = colors.textSecondary)
                        Spacer(Modifier.height(14.dp))
                        Pill(stringResource(R.string.meta_ai_set_up), onSettings)
                    }
                }
            }
            if (days.isEmpty()) {
                item(key = EMPTY) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 12.dp)) {
                        Text(stringResource(R.string.meta_ai_empty_title), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
                        Text(stringResource(R.string.meta_ai_empty), style = ledeStyle(), color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            for ((d, day) in days.withIndex()) {
                item(key = "day-${day.date}") {
                    DayHead(
                        day, first = d == 0,
                        onCalendar = {
                            haptics.tick(0.4f)
                            calendar = true
                        },
                        onPhoto = { id -> day.photos.firstOrNull { it.interactionId == id }?.let { scrollTo(keyOf(it)) } },
                        onLongPress = {
                            haptics.tick(0.6f)
                            pressed = Pressed.OnDay(day)
                        },
                    )
                }
                for ((i, c) in day.conversations.withIndex()) {
                    item(key = "c-${day.date}-${c.id}") {
                        Column(Modifier.animateItem()) {
                            if (i > 0) Box(Modifier.padding(horizontal = SIDE).fillMaxWidth().height(1.dp).background(colors.hairline))
                            if (c.live) {
                                LiveBlock(c) { onSession(c.id) }
                            } else {
                                ConversationBlock(
                                    c, speech,
                                    onLongPress = { turn ->
                                        haptics.tick(0.6f)
                                        pressed = Pressed.OnTurn(turn, c)
                                    },
                                    onPhoto = { viewing = it },
                                    onMemories = onMemories,
                                )
                            }
                        }
                    }
                }
            }
        }

        // The bar: the page's name and its memories, over a shade that comes as the diary scrolls under it.
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(Brush.verticalGradient(listOf(colors.canvas.copy(alpha = scrolled * 0.95f), colors.canvas.copy(alpha = 0f)))) }
                .statusBarsPadding()
                .height(BAR)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.meta_ai_title), style = Visor.type.title, color = colors.textPrimary, modifier = Modifier.asHeading().padding(start = 12.dp).weight(1f))
            RoundIcon(VisorIcons.Memory, stringResource(R.string.meta_ai_memories), onMemories)
        }

        AnimatedVisibility(
            visible = deleted.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp),
            enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it / 2 },
            exit = fadeOut(tween(160)),
        ) {
            UndoPill(pluralStringResource(R.plurals.meta_ai_deleted, deleted.size, deleted.size)) {
                haptics.toggle()
                model.undoDelete()
            }
        }

        Composer(
            continuing = targetLabel,
            onLetGo = {
                haptics.tick(0.4f)
                continuing = null
                fresh = true
            },
            onSend = { question ->
                val conversation = continuing ?: if (fresh) UUID.randomUUID().toString() else null
                model.ask(question, conversation)
                continuing = null
                fresh = false
                scope.launch { list.animateScrollToItem(0) }
            },
            onListening = { phoneListening = it },
            onLevel = { light.pulse(it * 0.14f) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (calendar) {
        CalendarSheet(
            turns, shownDay,
            onDay = {
                calendar = false
                scrollTo("day-$it")
            },
            onTurn = {
                calendar = false
                scrollTo(keyOf(it))
            },
            onDismiss = { calendar = false },
        )
    }
    pressed?.let { what ->
        Actions(
            what,
            onDismiss = { pressed = null },
            onCopy = { turn ->
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText(turn.question, turn.answer))
            },
            onShare = { share(context, it) },
            onAgain = { model.askAgain(it.interactionId) },
            onContinue = { c ->
                continuing = c.id
                fresh = false
            },
            onDelete = { ids ->
                haptics.toggle()
                model.delete(ids)
            },
        )
    }
    viewing?.let { PhotoView(it) { viewing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Actions(
    pressed: Pressed,
    onDismiss: () -> Unit,
    onCopy: (TurnRecord) -> Unit,
    onShare: (TurnRecord) -> Unit,
    onAgain: (TurnRecord) -> Unit,
    onContinue: (DiaryConversation) -> Unit,
    onDelete: (Set<String>) -> Unit,
) {
    val colors = Visor.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            fun then(action: () -> Unit) {
                action()
                onDismiss()
            }
            when (pressed) {
                is Pressed.OnTurn -> {
                    val turn = pressed.turn
                    Text(
                        turn.question,
                        style = Visor.type.body,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = SIDE).padding(bottom = 10.dp),
                    )
                    val answered = turn.answer.isNotEmpty()
                    if (answered) ActionRow(VisorIcons.Copy, stringResource(R.string.meta_ai_copy)) { then { onCopy(turn) } }
                    if (answered) ActionRow(VisorIcons.Share, stringResource(R.string.meta_ai_share)) { then { onShare(turn) } }
                    if (answered || turn.problem.isNotEmpty()) ActionRow(VisorIcons.Replay, stringResource(R.string.meta_ai_again)) { then { onAgain(turn) } }
                    ActionRow(VisorIcons.Reply, stringResource(R.string.meta_ai_continue)) { then { onContinue(pressed.conversation) } }
                    ActionRow(VisorIcons.Trash, stringResource(R.string.meta_ai_delete)) { then { onDelete(setOf(turn.interactionId)) } }
                    if (pressed.conversation.turns.size > 1) {
                        ActionRow(VisorIcons.Trash, stringResource(R.string.meta_ai_delete_conversation)) {
                            then { onDelete(pressed.conversation.turns.map { it.interactionId }.toSet()) }
                        }
                    }
                }
                is Pressed.OnDay -> {
                    Text(dayTitle(pressed.day.date), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading().padding(horizontal = SIDE).padding(bottom = 10.dp))
                    ActionRow(VisorIcons.Trash, stringResource(R.string.meta_ai_delete_day)) {
                        then { onDelete(pressed.day.conversations.flatMap { it.turns }.map { it.interactionId }.toSet()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = SIDE, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Visor.colors.textPrimary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = Visor.type.body, color = Visor.colors.textPrimary)
    }
}

@Composable
internal fun UndoPill(text: String, onUndo: () -> Unit) {
    val colors = Visor.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.raised).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Visor.type.label, color = colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.gallery_undo),
            style = Visor.type.label,
            color = colors.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onUndo).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The question and its answer as text, with the photo when there is one. */
private fun share(context: Context, turn: TurnRecord) {
    val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, turn.question + "\n\n" + turn.answer)
    val photo = turn.image.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
    if (photo != null) {
        intent.type = "image/jpeg"
        intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.packageName + ".files", photo))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        intent.type = "text/plain"
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private val BAR = 56.dp
private val LIGHT_FADE = 420.dp
private const val SET_UP = "set-up"
private const val EMPTY = "empty"
private const val WAITING_MS = 120_000L
private const val TINT_MS = 120_000L
private const val FOLLOW_MS = 15_000L
