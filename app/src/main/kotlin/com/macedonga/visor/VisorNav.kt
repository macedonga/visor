package com.macedonga.visor

import android.content.Context
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.device.DeviceScreen
import com.macedonga.visor.features.ConversationFocusScreen
import com.macedonga.visor.features.HearingCorrectionScreen
import com.macedonga.visor.features.HearingTestScreen
import com.macedonga.visor.gallery.GalleryScreen
import com.macedonga.visor.home.HomeScreen
import com.macedonga.visor.log.EventLogScreen
import com.macedonga.visor.login.NativeLoginPage
import com.macedonga.visor.media.LiveScreen
import com.macedonga.visor.media.LiveSettingsScreen
import com.macedonga.visor.media.MirrorScreen
import com.macedonga.visor.metaai.LiveSessionScreen
import com.macedonga.visor.metaai.MemoriesScreen
import com.macedonga.visor.metaai.MetaAiScreen
import com.macedonga.visor.settings.AboutScreen
import com.macedonga.visor.settings.AdvancedScreen
import com.macedonga.visor.settings.AssistantSettingsScreen
import com.macedonga.visor.settings.BackupScreen
import com.macedonga.visor.settings.FlagsScreen
import com.macedonga.visor.settings.GlassesPage
import com.macedonga.visor.settings.LocalBack
import com.macedonga.visor.settings.NotifyAppsScreen
import com.macedonga.visor.settings.PhoneAccessScreen
import com.macedonga.visor.settings.SettingsGroupScreen
import com.macedonga.visor.settings.SettingsScreen
import com.macedonga.visor.settings.TopicScreen
import com.macedonga.visor.setup.OnboardingScreen
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.MarkOverlay
import com.macedonga.visor.stage.SharedStage
import com.macedonga.visor.stage.rememberStageHolder
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data object DeviceKey : NavKey

/** The setup: from the welcome on the first run, otherwise straight to what is left to do. */
@Serializable
data class OnboardingKey(val welcome: Boolean = false, val login: Boolean = false, val address: String? = null) : NavKey

@Serializable
data object MetaLoginPageKey : NavKey

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
data object WebAppsKey : NavKey

/** The app's graph: the home, and every screen it opens. */
@Composable
fun VisorNav(opens: kotlinx.coroutines.flow.Flow<String>, markAtLaunch: Boolean) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(*remember { if (firstRun(context)) arrayOf(HomeKey, OnboardingKey(welcome = true)) else arrayOf(HomeKey) })
    val stage = rememberStageHolder(markAtLaunch)
    val haptics = com.macedonga.visor.ui.rememberHaptics()
    androidx.compose.runtime.LaunchedEffect(stage) { if (stage.intro.showing) stage.intro.play { haptics.tick(0.4f) } }
    // A notification, a launcher shortcut or the tile opens its place over whatever is showing.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        opens.collect { place ->
            if (place == MainActivity.OPEN_GALLERY && backStack.lastOrNull() != MediaKey) backStack.add(MediaKey)
            if (place == MainActivity.OPEN_META_AI && backStack.lastOrNull() != MetaAiKey) backStack.add(MetaAiKey)
            if (place == MainActivity.OPEN_ONBOARDING && backStack.lastOrNull() !is OnboardingKey) backStack.add(OnboardingKey(welcome = true))
            if (place == MainActivity.OPEN_ACCOUNTS && backStack.lastOrNull() != AccountsKey) backStack.add(AccountsKey)
            if (place == MainActivity.OPEN_LIVE && backStack.lastOrNull() != LiveKey) {
                stage.homeToTop = true
                backStack.add(LiveKey)
            }
        }
    }

    val open: (NavKey) -> Unit = { backStack.add(it) }
    CompositionLocalProvider(LocalStage provides stage, LocalBack provides { backStack.removeLastOrNull() }) {
      // The one stage under every screen: those that show the pair leave it a hole and say where it goes.
      Box(Modifier.fillMaxSize().background(Visor.colors.canvas)) {
        SharedStage(stage, shown = backStack.lastOrNull().let { it == HomeKey || it == SettingsKey || it == LiveKey || it == HearingTestKey || it == WakeWordKey || it is OnboardingKey })
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // Screens dissolve into one another: the outgoing one leaves first, the incoming settles in from a hair smaller.
            transitionSpec = {
                (fadeIn(tween(ENTER_MS, delayMillis = OVERLAP_MS, easing = SETTLE)) + scaleIn(tween(ENTER_MS, delayMillis = OVERLAP_MS, easing = SETTLE), initialScale = 0.96f))
                    .togetherWith(fadeOut(tween(LEAVE_MS)) + scaleOut(tween(LEAVE_MS), targetScale = 1.02f))
            },
            // Going back, the screen underneath is simply there: only the one being left dissolves, above it.
            popTransitionSpec = {
                (EnterTransition.None togetherWith fadeOut(tween(LEAVE_MS * 2, easing = SETTLE)) + scaleOut(tween(LEAVE_MS * 2, easing = SETTLE), targetScale = 0.97f))
                    .apply { targetContentZIndex = -1f }
            },
            predictivePopTransitionSpec = {
                (EnterTransition.None togetherWith fadeOut(tween(ENTER_MS)) + scaleOut(tween(ENTER_MS), targetScale = 0.9f))
                    .apply { targetContentZIndex = -1f }
            },
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        onSetUp = { backStack.add(OnboardingKey()) },
                        onChangeOwner = { backStack.add(OnboardingKey(login = true, address = it)) },
                        onMedia = { backStack.add(MediaKey) },
                        onLive = {
                            stage.homeToTop = true
                            backStack.add(LiveKey)
                        },
                        onMetaAi = { backStack.add(MetaAiKey) },
                        onSettings = { backStack.add(SettingsKey) },
                        onDisplayTool = { tool ->
                            when (tool) {
                                com.macedonga.visor.display.DisplayTool.TELEPROMPTER -> backStack.add(PrompterKey)
                                com.macedonga.visor.display.DisplayTool.CAPTIONS -> backStack.add(CaptionsKey)
                                com.macedonga.visor.display.DisplayTool.GESTURES -> backStack.add(GesturesKey)
                                com.macedonga.visor.display.DisplayTool.MIRROR -> backStack.add(MirrorKey)
                            }
                        },
                    )
                }
                entry<DeviceKey> {
                    DeviceScreen(
                        onSetUp = { backStack.add(OnboardingKey()) },
                        onEventLog = { backStack.add(EventLogKey) },
                        onMedia = { backStack.add(MediaKey) },
                        onSettings = { backStack.add(SettingsKey) },
                        onMetaAi = { backStack.add(MetaAiKey) },
                        onLive = { backStack.add(LiveKey) },
                    )
                }
                entry<OnboardingKey> { key ->
                    OnboardingScreen(
                        welcome = key.welcome,
                        login = key.login,
                        address = key.address,
                        onLogin = { backStack.add(MetaLoginPageKey) },
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
                entry<MetaLoginPageKey> {
                    NativeLoginPage(onDone = { backStack.removeLastOrNull() })
                }
                entry<EventLogKey> { EventLogScreen(onRequests = { backStack.add(RequestLogKey) }) }
                entry<RequestLogKey> { com.macedonga.visor.log.RequestLogScreen() }
                entry<MediaKey> { GalleryScreen() }
                // The pair travels between the home and Settings on the one stage, so the screens only cross-fade, both ways.
                entry<SettingsKey>(
                    metadata = NavDisplay.transitionSpec { fadeIn(tween(SETTINGS_FADE_MS)) togetherWith fadeOut(tween(SETTINGS_FADE_MS)) } +
                        NavDisplay.popTransitionSpec {
                            (EnterTransition.None togetherWith fadeOut(tween(SETTINGS_FADE_MS))).apply { targetContentZIndex = -1f }
                        } +
                        NavDisplay.predictivePopTransitionSpec {
                            (EnterTransition.None togetherWith fadeOut(tween(SETTINGS_FADE_MS))).apply { targetContentZIndex = -1f }
                        },
                ) { SettingsScreen(open) }
                entry<TopicKey> { TopicScreen(it.name, open) }
                entry<AdvancedKey> { AdvancedScreen(open) }
                entry<SettingsGroupKey> { SettingsGroupScreen(it.group) }
                entry<GlassesKey> { GlassesPage(open) }
                entry<BackupKey> { BackupScreen() }
                entry<AboutKey> { AboutScreen() }
                entry<PhoneAccessKey> { PhoneAccessScreen() }
                entry<NotifyAppsKey> { NotifyAppsScreen() }
                entry<PrompterKey> { com.macedonga.visor.display.PrompterScreen() }
                entry<GesturesKey> { com.macedonga.visor.display.GesturesScreen() }
                entry<CaptionsKey> { com.macedonga.visor.display.CaptionsScreen() }
                entry<WifiNetworksKey> { com.macedonga.visor.settings.WifiNetworksScreen() }
                entry<AccountsKey> { com.macedonga.visor.settings.AccountsScreen() }
                entry<WebAppsKey> { com.macedonga.visor.settings.WebAppsScreen() }
                entry<MetaAiKey> {
                    MetaAiScreen(
                        onSettings = { backStack.add(AssistantSettingsKey) },
                        onMemories = { backStack.add(MemoriesKey) },
                        onSession = { backStack.add(LiveSessionKey(it)) },
                    )
                }
                entry<MemoriesKey> { MemoriesScreen() }
                entry<LiveSessionKey> { key -> LiveSessionScreen(key.conversation, onMemories = { backStack.add(MemoriesKey) }) }
                // The same for the Live.
                entry<LiveKey>(
                    metadata = NavDisplay.transitionSpec { fadeIn(tween(LIVE_FADE_MS)) togetherWith fadeOut(tween(LIVE_FADE_MS)) } +
                        NavDisplay.popTransitionSpec {
                            (EnterTransition.None togetherWith fadeOut(tween(LIVE_FADE_MS))).apply { targetContentZIndex = -1f }
                        } +
                        NavDisplay.predictivePopTransitionSpec {
                            (EnterTransition.None togetherWith fadeOut(tween(LIVE_FADE_MS))).apply { targetContentZIndex = -1f }
                        },
                ) {
                    LiveScreen(
                        onClose = { backStack.removeLastOrNull() },
                        onSettings = { backStack.add(LiveSettingsKey) },
                        onOpen = { backStack.add(MediaKey) },
                    )
                }
                entry<LiveSettingsKey> { LiveSettingsScreen() }
                entry<MirrorKey> { MirrorScreen() }
                entry<LanguageKey> { com.macedonga.visor.language.LanguageScreen() }
                entry<FlagsKey> { FlagsScreen() }
                entry<ConversationFocusKey> { ConversationFocusScreen() }
                entry<HearingCorrectionKey> { HearingCorrectionScreen(open) }
                entry<HearingTestKey> { HearingTestScreen() }
                entry<WakeWordKey> { com.macedonga.visor.settings.WakeWordScreen() }
                entry<AssistantSettingsKey> { AssistantSettingsScreen(open) }
                entry<IntegrationsKey> { com.macedonga.visor.settings.IntegrationsScreen(open) }
                entry<IntegrationKey> { key ->
                    com.macedonga.visor.settings.IntegrationScreen(key.id, onGone = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() })
                }
            },
        )
        MarkOverlay(stage)
      }
    }
}

private val SETTLE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val LIVE_FADE_MS = 260
private const val SETTINGS_FADE_MS = 220
private const val ENTER_MS = 420
private const val LEAVE_MS = 180
private const val OVERLAP_MS = 90

/** The first launch, before any glasses are set up or the setup was ever closed: it opens on the welcome. */
private fun firstRun(context: Context): Boolean =
    !context.getSharedPreferences(ONBOARDING, Context.MODE_PRIVATE).getBoolean(DONE, false) &&
        java.io.File(context.filesDir, "identity").list().isNullOrEmpty()

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
