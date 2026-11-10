package com.macedonga.visor.metaai

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.ANSWER_TOO_LATE
import com.macedonga.visor.core.assistant.CameraAction
import com.macedonga.visor.core.assistant.MediaControl
import com.macedonga.visor.core.assistant.ON_GLASSES
import com.macedonga.visor.core.assistant.TurnRecord
import com.macedonga.visor.core.assistant.TurnSource
import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.PhotoLight
import com.macedonga.visor.gallery.clockTime
import com.macedonga.visor.gallery.dayTitle
import com.macedonga.visor.home.rememberNow
import com.macedonga.visor.settings.CARD_RADIUS
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil

private val LIGHT_OVERHANG = 96.dp
private const val WAITING_MS = 120_000L

/** Still waiting for its answer: asked moments ago, or asked again ([asking]). */
internal fun TurnRecord.awaiting(asking: Set<String> = emptySet(), now: Long = System.currentTimeMillis()) =
    answer.isEmpty() && problem.isEmpty() && (now - at < WAITING_MS || interactionId in asking)

/** As tall as the parent and [amount] more above and below it. */
private fun Modifier.overhang(amount: Dp) = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight + 2 * extra, maxHeight = constraints.maxHeight + 2 * extra))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -extra) }
}

/** A day's lines: the diary's own voice, a step larger than the exchanges under it. */
@Composable
internal fun ledeStyle(): TextStyle = Visor.type.body.copy(fontSize = 19.sp, lineHeight = 27.sp, letterSpacing = (-0.012).em)

/** A day's title, which opens the calendar, its lines and its photos. */
@Composable
internal fun DayHead(day: DiaryDay, first: Boolean, onCalendar: () -> Unit, onPhoto: (String) -> Unit, onActions: () -> Unit) {
    val colors = Visor.colors
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val relative = day.date == today || day.date == today.minusDays(1)
    val full = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(day.date).replaceFirstChar { it.titlecase(locale) }
    val sub = listOfNotNull(full.takeIf { relative }, pluralStringResource(R.plurals.meta_ai_questions, day.questions, day.questions)).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(top = if (first) 4.dp else 44.dp).animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(interactionSource = null, indication = null, hapticFeedbackEnabled = false, onClick = onCalendar, onLongClick = onActions)
                .padding(horizontal = SIDE, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(dayTitle(day.date), style = Visor.type.heading, color = colors.textPrimary, modifier = Modifier.asHeading())
                Text(sub, style = Visor.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(VisorIcons.ChevronDown, stringResource(R.string.meta_ai_pick_day), Modifier.size(18.dp), tint = colors.textTertiary)
        }
        day.lines?.let { lines ->
            Text(lines.text, style = ledeStyle(), color = colors.textPrimary, modifier = Modifier.padding(start = SIDE, end = SIDE, top = 10.dp))
        }
        val photos = day.photos
        if (photos.isNotEmpty()) PhotoStack(photos, onPhoto, Modifier.padding(start = SIDE, top = 16.dp))
    }
}

/** Up to four photos laid over each other like prints; each opens its exchange. */
@Composable
private fun PhotoStack(photos: List<TurnRecord>, onPhoto: (String) -> Unit, modifier: Modifier) {
    val shown = photos.take(4)
    Box(modifier.height(48.dp).width((48 + (shown.size - 1) * 34).dp)) {
        for ((i, turn) in shown.withIndex()) {
            key(turn.interactionId) {
                val image by rememberDiaryPhoto(turn.image, 192)
                Box(
                    Modifier
                        .offset(x = (i * 34).dp)
                        .size(48.dp)
                        .graphicsLayer { rotationZ = if (i % 2 == 0) -3f else 3f }
                        .clip(RoundedCornerShape(11.dp))
                        .border(2.dp, Visor.colors.canvas, RoundedCornerShape(11.dp))
                        .background(Visor.colors.raised)
                        .clickable { onPhoto(turn.interactionId) },
                ) {
                    image?.let { Image(it, stringResource(R.string.gallery_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
    }
}

/** One conversation of a day: its exchanges in order, and where it began if not today. */
@Composable
internal fun ConversationBlock(
    conversation: DiaryConversation,
    speech: Speech,
    asking: Set<String>,
    onActions: (TurnRecord) -> Unit,
    onPhoto: (String) -> Unit,
    onMemories: () -> Unit,
) {
    val now = rememberNow()
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        conversation.startedOn?.let {
            Text(
                stringResource(R.string.meta_ai_continues, dayTitle(it)),
                style = Visor.type.label,
                color = Visor.colors.textTertiary,
                modifier = Modifier.padding(start = SIDE, end = SIDE, bottom = 12.dp),
            )
        }
        for ((i, turn) in conversation.turns.withIndex()) {
            key(turn.interactionId) {
                if (i > 0) Spacer(Modifier.height(24.dp))
                Exchange(turn, speech.shownOf(turn), { onActions(turn) }, onPhoto, onMemories, waiting = turn.awaiting(asking, maxOf(now, System.currentTimeMillis())))
            }
        }
    }
}

/** One question, its photo and its answer, revealed word by word while it is spoken. */
@Composable
internal fun Exchange(
    turn: TurnRecord,
    shown: Int,
    onActions: (() -> Unit)?,
    onPhoto: (String) -> Unit,
    onMemories: () -> Unit,
    waiting: Boolean = turn.awaiting(),
) {
    val colors = Visor.colors
    val photo = turn.image.takeIf { it.isNotEmpty() }
    val image by rememberDiaryPhoto(photo)
    Box(Modifier.fillMaxWidth()) {
        // Overhangs the exchange so the light fades out instead of ending at a hard edge.
        if (photo != null) PhotoLight(image, Modifier.matchParentSize().overhang(LIGHT_OVERHANG), level = 0.48f, radius = 0.5f)
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onActions == null) Modifier
                    else Modifier.combinedClickable(interactionSource = null, indication = null, hapticFeedbackEnabled = false, onClick = onActions, onLongClick = onActions),
                )
                .padding(horizontal = SIDE),
        ) {
            val where = if (turn.source == TurnSource.PHONE) " · " + stringResource(R.string.meta_ai_phone) else ""
            Text(clockTime(turn.at) + where, style = Visor.type.label, color = colors.textTertiary)
            Text(turn.question.ifBlank { "…" }, style = Visor.type.body, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
            if (photo != null) {
                Box(
                    Modifier
                        .padding(vertical = 14.dp)
                        .fillMaxWidth()
                        .aspectRatio(4f / 5f)
                        .clip(RoundedCornerShape(CARD_RADIUS))
                        .background(colors.raised)
                        .clickable { onPhoto(photo) },
                ) {
                    image?.let { Image(it, stringResource(R.string.gallery_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            Answer(turn, shown, waiting)
            for (fact in turn.remembered) {
                Row(
                    Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onMemories).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(VisorIcons.Memory, null, Modifier.size(15.dp), tint = colors.textTertiary)
                    Text(
                        stringResource(R.string.meta_ai_remembered, fact),
                        style = Visor.type.label,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            // What the assistant did with the person's integrations, as quietly as what it remembered.
            for (action in turn.actions) {
                val tint = if (action.ok) colors.textTertiary else colors.signal
                val (by, did) = action.words()
                Row(Modifier.padding(top = 10.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VisorIcons.Plug, null, Modifier.size(15.dp), tint = tint)
                    Text(
                        stringResource(if (action.ok) R.string.meta_ai_action else R.string.meta_ai_action_failed, by, did),
                        style = Visor.type.label,
                        color = tint,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Answer(turn: TurnRecord, shown: Int, waiting: Boolean) {
    val colors = Visor.colors
    when {
        turn.problem == ON_GLASSES -> Text(stringResource(R.string.meta_ai_on_glasses), style = Visor.type.label, color = colors.textTertiary)
        (turn.problem.isNotEmpty() && turn.problem != ANSWER_TOO_LATE) || (turn.answer.isEmpty() && !waiting) ->
            Text(stringResource(R.string.meta_ai_unanswered), style = Visor.type.label, color = colors.textTertiary)
        turn.answer.isEmpty() -> Thinking()
        else -> {
            // A turn that only acted keeps its actions' names as its answer.
            val silent = turn.actions.isNotEmpty() && turn.answer == turn.actions.joinToString { it.what }
            val text = if (silent) turn.actions.map { it.words().second }.joinToString(", ") else turn.answer
            // The words not yet spoken hold their place, so nothing moves as they come.
            val annotated = if (shown >= text.length) AnnotatedString(text) else buildAnnotatedString {
                append(text.substring(0, shown))
                withStyle(SpanStyle(color = Color.Transparent)) { append(text.substring(shown)) }
            }
            Text(annotated, style = Visor.type.body, color = colors.textPrimary)
            if (turn.problem == ANSWER_TOO_LATE) {
                Text(stringResource(R.string.meta_ai_too_late), style = Visor.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Who did [ToolAction] and what, in the app's language for Visor's own tools; a server's words as it gave them. */
@Composable
private fun ToolAction.words(): Pair<String, String> {
    val camera = CameraAction.entries.firstOrNull { it.tool == kind }
    val control = MediaControl.entries.firstOrNull { it.name.lowercase() == kind }
    val by = if (camera != null) stringResource(R.string.glasses_title) else server.ifEmpty { stringResource(R.string.meta_ai_phone) }
    val did = when {
        kind == "directions" -> stringResource(R.string.phone_directions, subject)
        kind == "play_media" -> if (subject.isEmpty()) stringResource(R.string.gallery_play) else stringResource(R.string.phone_play, subject)
        kind == "call" -> stringResource(R.string.meta_ai_call, subject)
        kind == "send_text" -> stringResource(R.string.meta_ai_text, subject)
        kind == "reply_message" -> stringResource(R.string.meta_ai_reply, subject)
        control != null -> stringResource(
            when (control) {
                MediaControl.PAUSE -> R.string.meta_ai_pause
                MediaControl.RESUME -> R.string.meta_ai_resume
                MediaControl.NEXT -> R.string.meta_ai_next
                MediaControl.PREVIOUS -> R.string.meta_ai_previous
                MediaControl.STOP -> R.string.meta_ai_stop
                MediaControl.RESTART -> R.string.meta_ai_restart
            },
        )
        camera != null -> stringResource(
            when (camera) {
                CameraAction.TAKE_PHOTO -> R.string.shutter_take_photo
                CameraAction.RECORD_VIDEO -> R.string.shutter_record_video
                CameraAction.RECORD_HYPERLAPSE -> R.string.meta_ai_hyperlapse
                CameraAction.STOP_RECORDING -> R.string.live_stop_recording
            },
        )
        else -> what
    }
    return by to did
}

/** Three dots breathing one after the other: the model is on it. */
@Composable
private fun Thinking() {
    val beat = rememberInfiniteTransition(label = "thinking")
    val t by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "t")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        val phase = ((t - i * 0.18f) % 1f + 1f) % 1f
                        alpha = 0.25f + 0.75f * (1f - abs(phase * 2f - 1f))
                    }
                    .clip(CircleShape)
                    .background(Visor.colors.textSecondary),
            )
        }
    }
}

/** A Live AI session, folded into one card: how long, a strip of what the camera saw, what was asked. */
@Composable
internal fun LiveBlock(conversation: DiaryConversation, onOpen: () -> Unit, onActions: () -> Unit) {
    val colors = Visor.colors
    val turns = conversation.turns
    val first = turns.first()
    val last = turns.last()
    val minutes = ceil((last.at + last.modelMs + SPOKEN_TAIL_MS - first.at) / 60_000.0).toInt().coerceAtLeast(1)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(colors.raised)
            .combinedClickable(hapticFeedbackEnabled = false, onClick = onOpen, onLongClick = onActions)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VisorIcons.Live, null, Modifier.size(18.dp), tint = colors.textPrimary)
            Text(
                stringResource(R.string.meta_ai_live_length, minutes),
                style = Visor.type.label,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(clockTime(first.at), style = Visor.type.label, color = colors.textSecondary)
        }
        val frames = turns.filter { it.image.isNotEmpty() }.take(5)
        if (frames.isNotEmpty()) {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (frame in frames) {
                    key(frame.interactionId) {
                        val image by rememberDiaryPhoto(frame.image, 160)
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.control)) {
                            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                }
            }
        }
        Text(
            turns.joinToString(" · ") { it.question.trim() },
            style = Visor.type.body,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A photo on its own, lit by itself; a tap anywhere puts it back. */
@Composable
internal fun PhotoView(path: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val image by rememberDiaryPhoto(path, 2048)
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PhotoLight(image, Modifier.fillMaxSize(), level = 0.7f)
            image?.let {
                Image(it, stringResource(R.string.gallery_photo), Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
            }
        }
    }
}

// How long the last answer of a session is taken to have gone on after it was ready.
private const val SPOKEN_TAIL_MS = 5_000L
