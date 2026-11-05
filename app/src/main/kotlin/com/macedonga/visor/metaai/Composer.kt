package com.macedonga.visor.metaai

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.settings.openAppPage
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.allows
import com.macedonga.visor.ui.rememberHaptics
import kotlinx.coroutines.delay

/** The phone's text field, and a hold-to-talk mic that turns into Send once there is text. */
@Composable
internal fun Composer(
    continuing: String?,
    onLetGo: () -> Unit,
    onSend: (String) -> Unit,
    onListening: (Boolean) -> Unit,
    onLevel: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Visor.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var text by rememberSaveable { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var hint by remember { mutableStateOf<Int?>(null) }
    var hintAt by remember { mutableIntStateOf(0) }
    val send by rememberUpdatedState(onSend)
    val levelOut by rememberUpdatedState(onLevel)
    val locale = LocalConfiguration.current.locales[0]

    fun say(res: Int) {
        hint = res
        hintAt++
    }

    val ears = remember {
        PhoneEars(context, object : PhoneEars.Listener {
            override fun ready() {
                ready = true
                haptics.tick(0.5f)
            }

            override fun heard(text: String) {
                heard = text
            }

            override fun level(value: Float) {
                level = value
                levelOut(value)
            }

            override fun done(text: String?) {
                if (text.isNullOrBlank()) say(R.string.meta_ai_not_heard) else send(text.trim())
                heard = ""
            }
        })
    }
    DisposableEffect(Unit) { onDispose { ears.cancel() } }
    LaunchedEffect(holding) { onListening(holding) }
    LaunchedEffect(hintAt) {
        if (hint == null) return@LaunchedEffect
        delay(HINT_MS)
        hint = null
    }
    val activity = LocalActivity.current
    var allowed by remember { mutableStateOf(context.allows(Manifest.permission.RECORD_AUDIO)) }
    var refused by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        if (!it) {
            refused = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            say(R.string.meta_ai_mic)
        }
    }
    // Refused for good: only Visor's app page can give it back.
    fun askForMic() {
        if (refused && !context.allows(Manifest.permission.RECORD_AUDIO)) openAppPage(context)
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val cancelPx = with(LocalDensity.current) { CANCEL_DISTANCE.toPx() }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to colors.canvas.copy(alpha = 0.92f), 1f to colors.canvas))
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
    ) {
        AnimatedVisibility(
            visible = hint != null || (continuing != null && !holding),
            enter = fadeIn(tween(180)) + expandVertically(),
            exit = fadeOut(tween(140)) + shrinkVertically(),
        ) {
            Crossfade(hint ?: continuing, label = "over") { what ->
                when (what) {
                    is Int -> Text(
                        stringResource(what),
                        style = Visor.type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is String -> Row(
                        Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(50)).background(colors.surface).clickable(onClick = onLetGo)
                            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.meta_ai_continuing, what), style = Visor.type.label, color = colors.textSecondary)
                        Icon(VisorIcons.Close, stringResource(R.string.meta_ai_new), Modifier.padding(start = 6.dp).size(14.dp), tint = colors.textSecondary)
                    }
                    else -> Spacer(Modifier.size(0.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (holding) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(colors.raised)
                        .padding(horizontal = 20.dp, vertical = 15.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val words = when {
                        armed -> stringResource(R.string.meta_ai_release_cancel)
                        heard.isNotEmpty() -> heard
                        else -> stringResource(R.string.meta_ai_listening)
                    }
                    Text(words, style = Visor.type.body, color = if (armed || heard.isEmpty()) colors.textSecondary else colors.textPrimary, maxLines = 4)
                }
            } else {
                InputField(
                    text, { text = it }, stringResource(R.string.meta_ai_ask), 6,
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(10.dp))
            val canSend = text.isNotBlank() && !holding
            val grow by animateFloatAsState(if (holding && ready) 1.22f + level * 0.18f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "grow")
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = grow
                        scaleY = grow
                    }
                    .clip(CircleShape)
                    .background(if (canSend || holding) colors.textPrimary else colors.control)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        onClick {
                            when {
                                canSend -> {
                                    haptics.toggle()
                                    send(text.trim())
                                    text = ""
                                }
                                !allowed -> askForMic()
                                else -> say(R.string.meta_ai_hold)
                            }
                            true
                        }
                    }
                    .pointerInput(canSend, allowed) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (canSend) {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    haptics.toggle()
                                    send(text.trim())
                                    text = ""
                                }
                                return@awaitEachGesture
                            }
                            if (!allowed) {
                                waitForUpOrCancellation()
                                askForMic()
                                return@awaitEachGesture
                            }
                            val pressedAt = down.uptimeMillis
                            heard = ""
                            armed = false
                            ready = false
                            holding = true
                            ears.start(locale)
                            var lastUp = pressedAt
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val drag = change.position - down.position
                                val cancel = drag.x < -cancelPx || drag.y < -cancelPx
                                if (cancel != armed) {
                                    armed = cancel
                                    haptics.tick(0.4f)
                                }
                                if (!change.pressed) {
                                    lastUp = change.uptimeMillis
                                    break
                                }
                                change.consume()
                            }
                            holding = false
                            levelOut(0f)
                            when {
                                lastUp - pressedAt < TAP_MS -> {
                                    ears.cancel()
                                    say(R.string.meta_ai_hold)
                                }
                                armed -> {
                                    ears.cancel()
                                    haptics.tick(0.3f)
                                }
                                else -> ears.stop()
                            }
                            armed = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (canSend) VisorIcons.Send else VisorIcons.Mic,
                    stringResource(if (canSend) R.string.meta_ai_send else R.string.meta_ai_hold),
                    Modifier.size(22.dp),
                    tint = if (canSend || holding) Color.Black else colors.textPrimary,
                )
            }
        }
    }
}

/** The raised pill the diary's fields share; a tap anywhere on it takes the keyboard. */
@Composable
internal fun InputField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    maxLines: Int,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = Visor.colors
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = Visor.type.body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.textPrimary),
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(colors.raised),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = Visor.type.body, color = colors.textSecondary)
                inner()
            }
        },
    )
}

private val CANCEL_DISTANCE = 96.dp
private const val TAP_MS = 300L
private const val HINT_MS = 2_400L
