package com.macedonga.visor.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.bloks.BloksHost
import com.macedonga.visor.core.identity.bloks.BloksSession
import com.macedonga.visor.core.identity.bloks.Button
import com.macedonga.visor.core.identity.bloks.Event
import com.macedonga.visor.core.identity.bloks.Field
import com.macedonga.visor.core.identity.bloks.Flat
import com.macedonga.visor.core.identity.bloks.Heading
import com.macedonga.visor.core.identity.bloks.Page
import com.macedonga.visor.core.identity.bloks.Paragraph
import com.macedonga.visor.core.identity.token.AuthorizeRequest
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.ProgressRing
import com.macedonga.visor.settings.RoundIcon
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SheetAnswers
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.WidePill
import com.macedonga.visor.ui.VisorIcons
import com.macedonga.visor.ui.asHeading
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class NativeLoginViewModel @Inject constructor(val login: MetaLogin, @ApplicationContext private val context: Context) : ViewModel() {
    private var session: BloksSession? = null
    private var startedFor: AuthorizeRequest? = null
    private val _state = MutableStateFlow<BloksSession.State?>(null)
    val state: StateFlow<BloksSession.State?> = _state.asStateFlow()

    /** What a screen asked of the window (no screenshots, screen kept on) and a date it asked for. */
    val secure = MutableStateFlow(false)
    val keepOn = MutableStateFlow(false)
    val dateAsk = MutableStateFlow<DateAsk?>(null)

    class DateAsk(val initialSeconds: Long?, val done: (Long?) -> Unit)

    private val host = object : BloksHost {
        override fun openUrl(url: String) {
            runCatching {
                val tab = CustomTabsIntent.Builder().build()
                tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(context, Uri.parse(url))
            }
        }

        override fun haptic() {
            runCatching {
                val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        }

        override fun shareText(text: String) {
            runCatching { com.macedonga.visor.ui.shareText(context, text) }
        }

        override fun dial(number: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        override fun copy(text: String) = com.macedonga.visor.ui.copy(context, text)

        override fun secure(on: Boolean) {
            secure.value = on
        }

        override fun keepScreenOn(on: Boolean) {
            keepOn.value = on
        }

        override fun pickDate(initialSeconds: Long?, withTime: Boolean, done: (Long?) -> Unit) {
            dateAsk.value = DateAsk(initialSeconds, done)
        }

        override val apiLevel: Int get() = Build.VERSION.SDK_INT
        override val fontScale: Float get() = context.resources.configuration.fontScale
        override val densityDpi: Int get() = context.resources.displayMetrics.densityDpi
        override val screenReader: Boolean
            get() = context.getSystemService(AccessibilityManager::class.java)?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
        override val reduceMotion: Boolean
            get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    /** Starts once per login request; the session outlives recomposition and rotation with this model. */
    fun start(onDone: () -> Unit): BloksSession? {
        val request = login.pending ?: return null
        session?.takeIf { startedFor === request }?.let { return it }
        session?.close()
        startedFor = request
        _state.value = null
        secure.value = false
        keepOn.value = false
        dateAsk.value = null
        return login.nativeSession(darkMode = true, host = host).also { s ->
            session = s
            s.onState = { _state.value = it }
            s.onOutcome = { outcome ->
                login.finishNative(outcome)
                Handler(Looper.getMainLooper()).post(onDone)
            }
            s.start(request.url)
        }
    }

    override fun onCleared() {
        session?.close()
    }
}

/** Meta's login screens, each reduced to its title, lines, fields and buttons. */
@Composable
fun NativeLoginPage(onDone: () -> Unit, model: NativeLoginViewModel = hiltViewModel()) {
    val session = remember { model.start(onDone) }
    if (session == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val state by model.state.collectAsStateWithLifecycle()
    val s = state
    WindowFlags(model)
    DateAsked(model)
    val screen = remember(s?.version) { Flat.of(s?.ui, s?.title) }
    val back = { screen.back?.let(session::run) ?: session.back() }
    BackHandler(onBack = back)
    Column(
        Modifier
            .fillMaxSize()
            .background(Visor.colors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            RoundIcon(VisorIcons.ChevronLeft, stringResource(R.string.settings_back), back)
        }
        if (s == null || screen.items.isEmpty()) {
            // Nothing to draw and nothing on its way is a step Visor cannot show: say so instead of spinning forever.
            var stuck by remember(s?.version) { mutableStateOf(false) }
            LaunchedEffect(s?.version, s?.busy) {
                if (s != null && !s.busy) {
                    delay(STUCK_MS)
                    stuck = true
                    session.stuck()
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (stuck) {
                    Text(stringResource(R.string.native_login_stuck), style = Visor.type.body, color = Visor.colors.textSecondary, textAlign = TextAlign.Center)
                } else {
                    ProgressRing(null, Modifier.size(28.dp), color = Visor.colors.textSecondary)
                }
            }
        } else {
            Content(screen, s, session)
        }
    }
    s?.dialog?.let { d ->
        VisorSheet({ session.dismissDialog() }) {
            val message = if (d.failed) stringResource(R.string.native_login_request_failed) else d.message
            (d.title ?: message)?.let { SheetTitle(it, message.takeIf { d.title != null }) }
            FlowRow(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Meta lists the main answer last; it goes first and white, as in every other sheet.
                d.buttons.asReversed().forEachIndexed { i, (label, handler) ->
                    ActionPill(label ?: stringResource(android.R.string.ok), {
                        session.dismissDialog()
                        handler?.let { session.invoke(it, listOf(null, null)) }
                    }, quiet = i > 0)
                }
            }
        }
    }
}

@Composable
private fun Content(screen: Page, s: BloksSession.State, session: BloksSession) {
    val primary = screen.items.filterIsInstance<Button>().firstOrNull { it.primary }
    val others = screen.items.filterIsInstance<Button>().filter { it !== primary }
    val fields = screen.items.filterIsInstance<Field>()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            screen.items.forEach { item ->
                when (item) {
                    is Heading -> Text(item.text, style = Visor.type.title, color = Visor.colors.textPrimary, modifier = Modifier.asHeading().padding(bottom = 12.dp))
                    is Paragraph -> ParagraphText(item, session)
                    is Field -> FieldWell(item, session, index = fields.indexOf(item), depth = s.depth, last = item === fields.last()) {
                        primary?.takeIf { it.enabled }?.let { session.run(it.event) }
                    }
                    is Button -> {}
                }
            }
            // The screen's other ways on (another code, a password instead): quiet pills under what it asks.
            if (others.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { action -> ActionPill(action.label, { session.run(action.event) }, quiet = true, enabled = action.enabled && !s.busy) }
                }
            }
        }
        Toast(s)
        if (primary != null) {
            WidePill(primary.label, { session.run(primary.event) }, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = primary.enabled && !s.busy, busy = s.busy)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ParagraphText(item: Paragraph, session: BloksSession) {
    val text = buildAnnotatedString {
        item.spans.forEach { span ->
            val click = span.onClick
            if (click != null) {
                withLink(LinkAnnotation.Clickable("bloks") { session.run(click) }) {
                    withStyle(SpanStyle(color = Visor.colors.textPrimary, textDecoration = TextDecoration.Underline)) { append(span.text) }
                }
            } else {
                append(span.text)
            }
        }
    }
    Text(
        text,
        style = Visor.type.body,
        color = if (item.error) Visor.colors.signal else Visor.colors.textSecondary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/** A screen's window wishes: no screenshots while it shows a secret, the screen kept on while it waits. */
@Composable
private fun WindowFlags(model: NativeLoginViewModel) {
    val secure by model.secure.collectAsStateWithLifecycle()
    val keepOn by model.keepOn.collectAsStateWithLifecycle()
    val window = (LocalActivity.current as? ComponentActivity)?.window ?: return
    DisposableEffect(secure, keepOn) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** A date a screen asks for (a birthday), answered in unix seconds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateAsked(model: NativeLoginViewModel) {
    val ask by model.dateAsk.collectAsStateWithLifecycle()
    val current = ask ?: return
    val picker = rememberDatePickerState(initialSelectedDateMillis = current.initialSeconds?.times(1000))
    val answer = { seconds: Long? ->
        model.dateAsk.value = null
        current.done(seconds)
    }
    VisorSheet({ answer(null) }, full = true) {
        DatePicker(picker, title = null, headline = null, showModeToggle = false, colors = DatePickerDefaults.colors(containerColor = Visor.colors.surface))
        SheetAnswers(stringResource(R.string.settings_save), { answer(picker.selectedDateMillis?.div(1000)) }, stringResource(R.string.cancel), { answer(null) })
    }
}

/** Meta's toast ("code sent"): a quiet line above the button for as long as Meta asks. */
@Composable
private fun ColumnScope.Toast(s: BloksSession.State) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.toastCount) {
        if (s.toastCount == 0 || s.toast == null) return@LaunchedEffect
        shown = s.toast
        delay(s.toastMillis.coerceIn(1500L, 8000L))
        shown = null
    }
    AnimatedVisibility(shown != null, Modifier.align(Alignment.CenterHorizontally), enter = fadeIn(), exit = fadeOut()) {
        Text(
            shown.orEmpty(),
            style = Visor.type.label,
            color = Visor.colors.textPrimary,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Visor.colors.control)
                .padding(horizontal = 18.dp, vertical = 11.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A field in the setup's look: a rounded well, its name inside while empty, the text in white as it is typed. */
@Composable
private fun FieldWell(item: Field, session: BloksSession, index: Int, depth: Int, last: Boolean, onGo: () -> Unit) {
    val input = item.input
    val focus = LocalFocusManager.current
    // Go on the last field or one with its own submit; Next moves on to the field below.
    val go = last || input.onSubmit != null
    // Meta redraws the screen on every keystroke and may hand a new node each time: the field is its place on the
    // screen, and it keeps its own text and cursor.
    var value by remember(depth, index) { mutableStateOf(TextFieldValue(input.text, TextRange(input.text.length))) }
    val text = value.text
    val requester = remember(depth, index) { FocusRequester() }
    if (index == 0) LaunchedEffect(depth) { runCatching { requester.requestFocus() } }
    val code = input.keyboard == "passcode"
    val label = item.label.ifEmpty { if (code) stringResource(R.string.login_code) else input.hint }
    val style = if (code) {
        Visor.type.readoutSmall.copy(color = Visor.colors.textPrimary, fontSize = 28.sp, letterSpacing = 10.sp, textAlign = TextAlign.Center)
    } else {
        Visor.type.body.copy(color = Visor.colors.textPrimary)
    }
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Visor.colors.raised)
            .padding(horizontal = 16.dp, vertical = if (code) 18.dp else 16.dp),
        contentAlignment = if (code) Alignment.Center else Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text(label, style = Visor.type.body, color = Visor.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val clipped = if (input.maxLength > 0 && next.text.length > input.maxLength) {
                    next.copy(text = next.text.take(input.maxLength), selection = TextRange(input.maxLength))
                } else {
                    next
                }
                val changed = clipped.text != value.text
                value = clipped
                if (changed) {
                    session.setText(input.node, clipped.text)
                    input.onChange?.let(session::run)
                }
            },
            enabled = input.enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            // Meta masks a code field; here the digits show, as they do in the Meta app's own boxes.
            visualTransformation = if (input.secret && !code) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (input.keyboard) {
                    "email" -> KeyboardType.Email
                    "password" -> KeyboardType.Password
                    "passcode", "number", "amount" -> KeyboardType.Number
                    "phone" -> KeyboardType.Phone
                    else -> KeyboardType.Text
                },
                imeAction = if (go) ImeAction.Go else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onAny = { if (go) input.onSubmit?.let(session::run) ?: onGo() else focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { f -> (if (f.isFocused) input.onFocus else input.onBlur)?.let(session::run) },
        )
    }
}

private fun BloksSession.run(event: Event) = invoke(event.handler, event.args)

// How long a screen may show nothing, with nothing on its way, before the page says it cannot show it.
private const val STUCK_MS = 20_000L
