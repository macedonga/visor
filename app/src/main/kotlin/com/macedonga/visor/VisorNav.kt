package com.macedonga.visor

import android.content.Context
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.device.DeviceScreen
import com.macedonga.visor.display.CaptionsScreen
import com.macedonga.visor.display.DisplayTool
import com.macedonga.visor.display.GesturesScreen
import com.macedonga.visor.display.PrompterScreen
import com.macedonga.visor.features.ConversationFocusScreen
import com.macedonga.visor.features.HearingCorrectionScreen
import com.macedonga.visor.features.HearingTestScreen
import com.macedonga.visor.gallery.GalleryScreen
import com.macedonga.visor.home.HomeReturn
import com.macedonga.visor.home.HomeScreen
import com.macedonga.visor.language.LanguageScreen
import com.macedonga.visor.log.EventLogScreen
import com.macedonga.visor.log.RequestLogScreen
import com.macedonga.visor.login.NativeLoginPage
import com.macedonga.visor.media.LiveScreen
import com.macedonga.visor.media.LiveSettingsScreen
import com.macedonga.visor.media.MirrorScreen
import com.macedonga.visor.metaai.LiveSessionScreen
import com.macedonga.visor.metaai.MemoriesScreen
import com.macedonga.visor.metaai.MetaAiScreen
import com.macedonga.visor.settings.AboutScreen
import com.macedonga.visor.settings.AccountsScreen
import com.macedonga.visor.settings.AdvancedScreen
import com.macedonga.visor.settings.AssistantSettingsScreen
import com.macedonga.visor.settings.BackupScreen
import com.macedonga.visor.settings.FlagsScreen
import com.macedonga.visor.settings.GlassesPage
import com.macedonga.visor.settings.IntegrationScreen
import com.macedonga.visor.settings.IntegrationsScreen
import com.macedonga.visor.settings.LocalBack
import com.macedonga.visor.settings.NotifyAppsScreen
import com.macedonga.visor.settings.PhoneAccessScreen
import com.macedonga.visor.settings.SettingsGroupScreen
import com.macedonga.visor.settings.SettingsScreen
import com.macedonga.visor.settings.TopicScreen
import com.macedonga.visor.settings.WakeWordScreen
import com.macedonga.visor.settings.WebAppsScreen
import com.macedonga.visor.settings.WifiNetworksScreen
import com.macedonga.visor.setup.OnboardingScreen
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.MarkOverlay
import com.macedonga.visor.stage.SETTLE_EASE
import com.macedonga.visor.stage.SharedStage
import com.macedonga.visor.stage.rememberStageHolder
import com.macedonga.visor.ui.rememberHaptics
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data object DeviceKey : NavKey

/** The setup: from the welcome on the first run, otherwise straight to what is left to do. */
@Serializable
data class OnboardingKey(val welcome: Boolean = false, val login: Boolean = false, val address: String? = null) : NavKey

@Serializable
data object LoginKey : NavKey

@Serializable
data object EventLogKey : NavKey

@Serializable
data object MediaKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data class SettingsGroupKey(val group: String) : NavKey

@Serializable
data object MetaAiKey : NavKey

@Serializable
data object LiveKey : NavKey

@Serializable
data object MirrorKey : NavKey

@Serializable
data object FlagsKey : NavKey

@Serializable
data object ConversationFocusKey : NavKey

@Serializable
data object HearingCorrectionKey : NavKey

@Serializable
data object HearingTestKey : NavKey

@Serializable
data object WakeWordKey : NavKey

@Serializable
data object LiveSettingsKey : NavKey

@Serializable
data object LanguageKey : NavKey

@Serializable
data object AssistantSettingsKey : NavKey

@Serializable
data object MemoriesKey : NavKey

@Serializable
data object IntegrationsKey : NavKey

@Serializable
data class IntegrationKey(val id: String) : NavKey

@Serializable
data class LiveSessionKey(val conversation: String) : NavKey

@Serializable
data class TopicKey(val name: String) : NavKey

@Serializable
data object GlassesKey : NavKey

@Serializable
data object AdvancedKey : NavKey

@Serializable
data object BackupKey : NavKey

@Serializable
data object AboutKey : NavKey

@Serializable
data object PhoneAccessKey : NavKey

@Serializable
data object NotifyAppsKey : NavKey

@Serializable
data object PrompterKey : NavKey

@Serializable
data object GesturesKey : NavKey

@Serializable
data object CaptionsKey : NavKey

@Serializable
data object RequestLogKey : NavKey

@Serializable
data object WifiNetworksKey : NavKey

@Serializable
data object AccountsKey : NavKey

@Serializable
data object WebAppsKey : NavKey

/** The app's graph: the home, and every screen it opens. */
@Composable
fun VisorNav(opens: Flow<String>, markAtLaunch: Boolean) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(*remember { if (firstRun(context)) arrayOf(HomeKey, OnboardingKey(welcome = true)) else arrayOf(HomeKey) })
    val stage = rememberStageHolder(markAtLaunch)
    val homeReturn = remember { HomeReturn() }
    val haptics = rememberHaptics()
    LaunchedEffect(stage) { if (stage.intro.showing) stage.intro.play { haptics.tick(0.4f) } }
    // A notification, a launcher shortcut or the tile opens its place over whatever is showing.
    LaunchedEffect(Unit) {
        opens.collect { place ->
            val key: NavKey = when (place) {
                MainActivity.OPEN_GALLERY -> MediaKey
                MainActivity.OPEN_META_AI -> MetaAiKey
                MainActivity.OPEN_ACCOUNTS -> AccountsKey
                MainActivity.OPEN_LIVE -> LiveKey
                else -> return@collect
            }
            if (backStack.lastOrNull() == key) return@collect
            if (key == LiveKey) homeReturn.toTop = true
            backStack.add(key)
        }
    }

    val open: (NavKey) -> Unit = { backStack.add(it) }
    // Keeps the last screen: a second tap on back during the pop fade must not empty the stack.
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    CompositionLocalProvider(LocalStage provides stage, LocalBack provides back) {
        // The one stage under every screen: those that show the pair leave it a hole and say where it goes.
        Box(Modifier.fillMaxSize().background(Visor.colors.canvas)) {
            SharedStage(stage, shown = backStack.lastOrNull().let { it == HomeKey || it == SettingsKey || it == LiveKey || it == HearingTestKey || it == WakeWordKey || it is OnboardingKey })
            NavDisplay(
                backStack = backStack,
                onBack = back,
                // Screens dissolve into one another: the outgoing one leaves first, the incoming settles in from a hair smaller.
                transitionSpec = {
                    (fadeIn(tween(ENTER_MS, delayMillis = OVERLAP_MS, easing = SETTLE_EASE)) + scaleIn(tween(ENTER_MS, delayMillis = OVERLAP_MS, easing = SETTLE_EASE), initialScale = 0.96f))
                        .togetherWith(fadeOut(tween(LEAVE_MS)) + scaleOut(tween(LEAVE_MS), targetScale = 1.02f))
                },
                // Going back, the screen underneath is simply there: only the one being left dissolves, above it.
                popTransitionSpec = {
                    (EnterTransition.None togetherWith fadeOut(tween(LEAVE_MS * 2, easing = SETTLE_EASE)) + scaleOut(tween(LEAVE_MS * 2, easing = SETTLE_EASE), targetScale = 0.97f))
                        .apply { targetContentZIndex = -1f }
                },
                predictivePopTransitionSpec = {
                    (EnterTransition.None togetherWith fadeOut(tween(ENTER_MS)) + scaleOut(tween(ENTER_MS), targetScale = 0.9f))
                        .apply { targetContentZIndex = -1f }
                },
                entryProvider = entryProvider {
                    entry<HomeKey> {
                        HomeScreen(
                            returning = homeReturn,
                            onSetUp = { backStack.add(OnboardingKey()) },
                            onChangeOwner = { backStack.add(OnboardingKey(login = true, address = it)) },
                            onMedia = { backStack.add(MediaKey) },
                            onLive = {
                                homeReturn.toTop = true
                                backStack.add(LiveKey)
                            },
                            onMetaAi = { backStack.add(MetaAiKey) },
                            onSettings = { backStack.add(SettingsKey) },
                            onDisplayTool = { tool ->
                                when (tool) {
                                    DisplayTool.TELEPROMPTER -> backStack.add(PrompterKey)
                                    DisplayTool.CAPTIONS -> backStack.add(CaptionsKey)
                                    DisplayTool.GESTURES -> backStack.add(GesturesKey)
                                    DisplayTool.MIRROR -> backStack.add(MirrorKey)
                                }
                            },
                        )
                    }
                    entry<DeviceKey> { DeviceScreen() }
                    entry<OnboardingKey> { key ->
                        OnboardingScreen(
                            welcome = key.welcome,
                            login = key.login,
                            address = key.address,
                            onLogin = { backStack.add(LoginKey) },
                            onEventLog = { backStack.add(EventLogKey) },
                            onDone = { target ->
                                onboarded(context)
                                finishSetUp(context, backStack, target)
                            },
                            onLeave = {
                                onboarded(context)
                                backStack.removeLastOrNull()
                                if (backStack.isEmpty()) backStack.add(HomeKey)
                            },
                        )
                    }
                    entry<LoginKey> { NativeLoginPage(onDone = back) }
                    entry<EventLogKey> { EventLogScreen() }
                    entry<RequestLogKey> { RequestLogScreen() }
                    entry<MediaKey> { GalleryScreen() }
                    entry<SettingsKey>(metadata = crossFade(SETTINGS_FADE_MS)) { SettingsScreen(open) }
                    entry<TopicKey> { TopicScreen(it.name, open) }
                    entry<AdvancedKey> { AdvancedScreen(open) }
                    entry<SettingsGroupKey> { SettingsGroupScreen(it.group) }
                    entry<GlassesKey> { GlassesPage(open) }
                    entry<BackupKey> { BackupScreen() }
                    entry<AboutKey> { AboutScreen() }
                    entry<PhoneAccessKey> { PhoneAccessScreen() }
                    entry<NotifyAppsKey> { NotifyAppsScreen() }
                    entry<PrompterKey> { PrompterScreen() }
                    entry<GesturesKey> { GesturesScreen(open) }
                    entry<CaptionsKey> { CaptionsScreen() }
                    entry<WifiNetworksKey> { WifiNetworksScreen() }
                    entry<AccountsKey> { AccountsScreen() }
                    entry<WebAppsKey> { WebAppsScreen() }
                    entry<MetaAiKey> {
                        MetaAiScreen(
                            onSettings = { backStack.add(AssistantSettingsKey) },
                            onMemories = { backStack.add(MemoriesKey) },
                            onSession = { backStack.add(LiveSessionKey(it)) },
                        )
                    }
                    entry<MemoriesKey> { MemoriesScreen() }
                    entry<LiveSessionKey> { key -> LiveSessionScreen(key.conversation, onMemories = { backStack.add(MemoriesKey) }) }
                    entry<LiveKey>(metadata = crossFade(LIVE_FADE_MS)) {
                        LiveScreen(
                            onClose = { streamed ->
                                if (streamed) homeReturn.fromLive = true
                                back()
                            },
                            onSettings = { backStack.add(LiveSettingsKey) },
                            onOpen = { backStack.add(MediaKey) },
                        )
                    }
                    entry<LiveSettingsKey> { LiveSettingsScreen() }
                    entry<MirrorKey> { MirrorScreen() }
                    entry<LanguageKey> { LanguageScreen() }
                    entry<FlagsKey> { FlagsScreen() }
                    entry<ConversationFocusKey> { ConversationFocusScreen() }
                    entry<HearingCorrectionKey> { HearingCorrectionScreen(open) }
                    entry<HearingTestKey> { HearingTestScreen() }
                    entry<WakeWordKey> { WakeWordScreen() }
                    entry<AssistantSettingsKey> { AssistantSettingsScreen(open) }
                    entry<IntegrationsKey> { IntegrationsScreen(open) }
                    entry<IntegrationKey> { key ->
                        IntegrationScreen(key.id, onGone = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() })
                    }
                },
            )
            MarkOverlay(stage)
        }
    }
}

private const val LIVE_FADE_MS = 260
private const val SETTINGS_FADE_MS = 220
private const val ENTER_MS = 420
private const val LEAVE_MS = 180
private const val OVERLAP_MS = 90

/** Screens the pair travels to on the one stage only cross-fade, both ways. */
private fun crossFade(ms: Int) =
    NavDisplay.transitionSpec { fadeIn(tween(ms)) togetherWith fadeOut(tween(ms)) } +
        NavDisplay.popTransitionSpec { (EnterTransition.None togetherWith fadeOut(tween(ms))).apply { targetContentZIndex = -1f } } +
        NavDisplay.predictivePopTransitionSpec { (EnterTransition.None togetherWith fadeOut(tween(ms))).apply { targetContentZIndex = -1f } }

/** The first launch, before any glasses are set up or the setup was ever closed: it opens on the welcome. */
private fun firstRun(context: Context): Boolean =
    !context.getSharedPreferences(ONBOARDING, Context.MODE_PRIVATE).getBoolean(DONE, false) &&
        File(context.filesDir, IdentityStore.DIRECTORY).list().isNullOrEmpty()

private fun onboarded(context: Context) {
    context.getSharedPreferences(ONBOARDING, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
}

private const val ONBOARDING = "onboarding"
private const val DONE = "done"

/** Back home with nothing above it, connecting to the glasses just set up. */
private fun finishSetUp(context: Context, backStack: NavBackStack<NavKey>, target: GlassesTarget) {
    backStack.retainAll { it == HomeKey }
    if (backStack.isEmpty()) backStack.add(HomeKey)
    GlassesService.start(context, target)
}
