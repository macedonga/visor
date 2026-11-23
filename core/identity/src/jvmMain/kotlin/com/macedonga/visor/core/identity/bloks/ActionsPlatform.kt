package com.macedonga.visor.core.identity.bloks

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Logging, metrics and tracing calls: no-ops, counted in the trace rather than listed. */
internal val LOGGING = setOf(
    "bk.action.logging.LogEvent", "bk.action.qpl.MarkerStartV2", "bk.action.qpl.MarkerAnnotate", "bk.action.qpl.MarkerPoint",
    "bk.action.qpl.MarkerEndV2", "bk.action.qpl.MarkerDrop", "bk.action.qpl.userflow.StartFlowV2", "bk.action.qpl.userflow.AnnotateV2",
    "bk.action.qpl.userflow.MarkPointV2", "bk.action.qpl.userflow.MarkErrorV2", "bk.action.qpl.userflow.EndFlowSuccessV2",
    "bk.action.qpl.userflow.EndFlowFailureV2", "bk.action.qpl.userflow.EndFlowCancelV2", "bk.action.trace.BeginSection",
    "bk.action.trace.EndSection", "bk.action.ttrc.AddAnnotation", "bk.action.ttrc.AddPoint", "bk.action.ttrc.AddPointV2",
    "bk.action.ttrc.AddQuery", "bk.action.ttrc.AddStep", "bk.action.ttrc.CachedContentDisplayed", "bk.action.ttrc.CompleteStep",
    "bk.action.ttrc.InteractionFailed", "bk.action.ttrc.MarkerStart", "bk.action.ttrc.NetworkContentDisplayed",
    "bk.action.ttrc.SurfaceLeft", "bk.action.accessibility.SetFocus", "bk.action.accessibility.Announcement",
)

/** Device facts, timers, animations, links, and the services Visor lacks, answered as on a phone without them. */
internal fun Registry.platform() {
    // ---- Device facts ----
    on("bk.action.caa.login.GetUniqueDeviceId", "bk.fx.action.GetFamilyDeviceId") { session.deviceId }
    on("bk.action.caa.FetchMachineID") { BloksSession.MACHINE_ID }
    on("bk.action.mwa.payments.GetDeviceID") { session.deviceId }
    on("bk.action.device.GetDeviceOSVersion") { session.host.apiLevel }
    on("bk.action.mwa.IsDarkModeEnabled") { session.darkMode }
    on("bk.action.accessibility.GetFontScale") { session.host.fontScale.toDouble() }
    on("bk.action.accessibility.GetScreenDensity") { session.host.densityDpi }
    on("bk.action.accessibility.IsScreenReaderEnabled") { session.host.screenReader }
    on("bk.action.accessibility.IsReduceMotionEnabled") { session.host.reduceMotion }
    on("bk.action.accessibility.IsHighContrastEnabled", "bk.action.accessibility.IsGreyscaleEnabled") { false }
    on("bk.action.caa.GetWaterfallId") { "" }
    on("bk.action.caa.reg.GetAgeFromBirthdayTimestamp") {
        Period.between(Instant.ofEpochSecond(num(0).toLong()).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()).years
    }
    on("bk.action.age.GetOsAgeRange") {
        session.post { call(at(1), screen, "API_NOT_AVAILABLE") }
        null
    }
    on("bk.action.session_store.Get") { session.sessionStore }

    // ---- What the app keeps in its preferences ----
    on("bk.action.frl.SetIDForAYMH") { null }
    on("bk.action.frl.GetDBLData") { session.prefs["dbl_data"] as? String ?: "" }
    on("bk.action.frl.SetDBLData") {
        session.prefs["dbl_data"] = at(0)?.toString().orEmpty()
        null
    }
    on("bk.action.frl.GetCategoryCount") {
        val entry = session.prefs["category:${at(0)}"] as? Pair<*, *>
        if (entry == null || (entry.second as Long) < System.currentTimeMillis()) 0 else entry.first as Int
    }
    on("bk.action.frl.IncreaseCategoryCount") {
        val key = "category:${at(0)}"
        val entry = session.prefs[key] as? Pair<*, *>
        val count = if (entry == null || (entry.second as Long) < System.currentTimeMillis()) 1 else (entry.first as Int) + 1
        session.prefs[key] = count to num(1).toLong()
        count
    }

    // ---- Time and queues ----
    on("bk.action.core.Delay") {
        val fn = at(1)
        val s = screen
        session.thread.schedule({ session.post { call(fn, s) } }, num(0).toLong(), TimeUnit.MILLISECONDS)
        null
    }
    on("bk.action.dispatch.GetMainQueue") { MAIN_QUEUE }
    on("bk.action.dispatch.GetLowPriBackgroundQueue") { BACKGROUND_QUEUE }
    // Runs after the current script, as a post to the queue would.
    on("bk.action.DispatchAsync") {
        val fn = at(1)
        session.post { call(fn) }
        null
    }
    on("bk.action.timer.Start") {
        val id = at(4).toString()
        session.timers.remove(id)?.cancel()
        val timer = Timer(id, num(1).toLong().coerceAtLeast(1), Interpreter.truthy(at(2)), at(3), screen)
        session.timers[id] = timer
        schedule(timer)
        null
    }
    on("bk.action.timer.Stop") {
        session.timers[at(0).toString()]?.let {
            it.stopped = true
            it.future?.cancel(false)
        }
        null
    }
    on("bk.action.timer.Restart") {
        session.timers[at(0).toString()]?.takeIf { !it.cancelled }?.let {
            it.future?.cancel(false)
            it.stopped = false
            schedule(it)
        }
        null
    }
    on("bk.action.timer.Cancel") {
        val id = (at(1) ?: at(0)).toString()
        session.timers.remove(id)?.cancel()
        null
    }

    // ---- Animations: each runs to its end at once; only the last value matters to the scripts ----
    on("bk.action.animated.easing.CreateCubicBezier") { null }
    on("bk.action.animated.Create", "bk.action.animated.CreateDimension", "bk.action.animated.CreateColor") {
        ValueAnimation(at(0), at(1), at(2), at(3), num(4), linear = false)
    }
    on("bk.action.animated.CreateSpring") { ValueAnimation(at(0), null, 0, 1, 0.0, linear = false) }
    on("bk.action.animation.linear.CreateAnimation") { ValueAnimation(at(0), at(1), at(2), at(3), num(4), linear = true) }
    on("bk.action.animated.Build") { (at(0) as? Animation)?.also { session.animations[at(1).toString()] = it } }
    on("bk.action.animated.StartToken") {
        session.animations[at(0).toString()]?.let { finish(it) }
        null
    }
    on("bk.action.animated.Start", "bk.action.animation.linear.Start") {
        (at(0) as? Animation)?.let { finish(it) }
        null
    }
    on("bk.action.animated.StartWithToken") {
        (at(0) as? Animation)?.let {
            session.animations[at(1).toString()] = it
            finish(it)
        }
        null
    }
    on("bk.action.animation.linear.SetNewEndValue") {
        (at(0) as? ValueAnimation)?.let {
            it.to = at(1)
            it.done = false
            finish(it)
        }
        null
    }
    on("bk.action.animated.AddOnCompleteListener") {
        (at(0) as? Animation)?.also { it.listeners += at(1) }
    }
    on("bk.action.animated.Parallel") { AnimationSet((at(2) as? List<*>).orEmpty().filterIsInstance<Animation>()).also { set -> at(0)?.let { set.listeners += it } } }
    on("bk.action.animated.Sequence") { AnimationSet((at(0) as? List<*>).orEmpty().filterIsInstance<Animation>()) }
    on("bk.action.animated.Stagger") { AnimationSet((at(1) as? List<*>).orEmpty().filterIsInstance<Animation>()) }
    on("bk.action.animated.Loop") { at(1) as? Animation }
    on("bk.action.animated.GetCurrentValue", "bk.action.animation.linear.GetCurrentValue") { (at(0) as? ValueAnimation)?.value }
    on("bk.action.animated.GetCurrentDimensionValue", "bk.action.animated.GetCurrentColorValue") { (at(0) as? ValueAnimation)?.value?.toString() }
    on("bk.action.animated.ToAnimator") { at(0) as? Animation }
    on("bk.action.animated.IsInitialized") { session.animations.containsKey(at(0).toString()) }
    on("bk.action.animated.GetTotalDuration") { (session.animations[at(0).toString()] as? ValueAnimation)?.seconds }
    on("bk.action.animated.GetCurrentPlayTime") { (session.animations[at(0).toString()] as? ValueAnimation)?.seconds ?: 0.0 }
    on("bk.action.animated.Destroy") { session.animations.remove(at(0).toString()); null }
    // Finished animations have nothing left to cancel, pause or seek.
    on(
        "bk.action.animated.Cancel", "bk.action.animated.CancelToken", "bk.action.animated.CancelWithToken",
        "bk.action.animated.SetCurrentPlayTime", "bk.action.animation.linear.Cancel",
    ) { null }
    on("bk.action.animated.Pause", "bk.action.animated.Resume") { false }
    on("bk.action.animated.cds.UpdateBottomSheet", "bk.action.animated.CreateSharedElementV3") { AnimationSet(emptyList()) }

    // ---- Feedback ----
    on("bk.action.io.Toast") { toast(at(0)?.toString(), 3500) }
    on("bk.action.slv.FWAToast") { toast(node(0)?.get(45)?.toString(), (node(0)?.get(42) as? Number)?.toLong() ?: 4000) }
    on("bk.action.HapticFeedback") {
        session.host.haptic()
        null
    }
    on("bk.action.SetBlockScreenshot") {
        session.host.secure(Interpreter.truthy(at(0)))
        null
    }
    on("bk.action.SetAppCanSleep") {
        session.host.keepScreenOn(!Interpreter.truthy(at(0)))
        null
    }
    on("bk.action.io.clipboard.SetString") {
        str(0)?.takeIf { it.isNotEmpty() }?.let(session.host::copy)
        null
    }
    on("bk.action.errorreporting.ReportError") {
        session.log("Meta reported: ${at(0)}")
        null
    }
    on("bk.action.debug.internal.DeviceLogV2") {
        session.trace("device log: ${(at(0) as? List<*>).orEmpty().joinToString(", ")}")
        null
    }

    // ---- Links and other apps ----
    on("bk.action.OpenUniversalLink", "bk.action.acp.OpenUrlExternally", "bk.fx.action.OpenURLInIAB", "bk.action.frl.OpenUrlWithAuth") {
        str(0)?.let(session.host::openUrl)
        null
    }
    // Visor does not hand the user to other Meta apps; the store page is the link it can open.
    on("bk.action.DeeplinkToOtherAppOrDirectToAppStore") {
        str(1)?.let(session.host::openUrl)
        null
    }
    on("bk.action.bloks.IsAppInstalled") { false }
    on("bk.action.share.Text") {
        str(0)?.let(session.host::shareText)
        null
    }
    on("bk.action.mwa.DialPhoneNumber") {
        str(0)?.let(session.host::dial)
        null
    }
    on("bk.action.native.ShareMedia") { null }

    // ---- Permissions and SMS: no retriever, so the user types the code ----
    on("bk.action.mwa.EducationPermissionRequest") {
        call(at(1))
        null
    }
    on("bk.action.caa.FetchSMSCode") {
        call(at(1), null, "error", screen)
        null
    }
    on("bk.action.caa.StartSMSCodeRetriever", "bk.action.caa.ClearSMSCode") { null }
    on("bk.action.caa.reg.FetchSMSCodeFromRetriever") { "" }

    // ---- Around the login ----
    on("bk.action.caa.AcceptOxygenPreloadTos") { null }
    on("bk.action.caa.ShouldAcceptOxygenPreloadTos", "bk.action.caa.ShouldShowExplicitOxygenPreloadTos") { false }
    on("bk.action.caa.login.RemoveProfile") {
        session.prefs.remove("dbl_data")
        call(at(3), screen)
        null
    }
    on("bk.action.caa.reg.LogoutCurrentUser") {
        session.sessionStore.clear()
        call(at(1), "SUCCESS", "")
        null
    }
    on("bk.action.caa.FoaFetchOpenIdTokens") {
        call(at(0), LinkedHashMap<Any?, Any?>())
        null
    }
    on("bk.fx.action.FetchAllAvailableNativeAuthDataForCaller", "bk.fx.action.FetchAllAvailableNativeAuthData") {
        call(at(2), ArrayList<Any?>())
        null
    }
    on("bk.fx.action.FetchNativeAuthDataWithFeatureSupport") {
        call(at(4), ArrayList<Any?>())
        null
    }
    on("bk.action.caa.login.FetchCredentialManager") {
        call(at(1), "no_account_selected", "", "", "", "", "")
        null
    }
    on("bk.action.caa.login.SaveCredential") {
        call(at(4))
        null
    }
    on("bk.action.frl.FetchAndSaveAccountsCenterCookie") {
        call(at(1))
        null
    }
    on("bk.action.frl.checkpoint.ResumeSession") {
        session.log("Meta says the checkpoint is done")
        null
    }
    on("bk.action.mma.StartSSOLogin", "bk.action.mma.StartSSOLoginV2") {
        session.log("Meta asked for a Workplace single sign-on, which Visor does not do")
        null
    }
    on("bk.action.CompleteAllLoginDiagnosis", "bk.action.FRXEndFlowEvent") { null }
    on("bk.action.mwa.payments.CloseAllScreensAndRelaunch") {
        session.screens.clear()
        session.finish(BloksSession.Outcome.Cancelled)
        null
    }
    on("bk.action.mwa.quickpromotion.Dismiss", "bk.action.mwa.support.OpenFlyTrap") { null }

    // ---- Passkeys: Visor has no Credential Manager ----
    on("bk.action.fx.passkey.CheckDeviceEligibility") { "credential_manager_unavailable" }
    on("bk.action.fx.passkey.CreatePasskey") {
        val options = node(0)
        if (options?.get(42) != null) call(options[42], "Passkeys are not supported") else call(options?.get(41))
        null
    }
    on("bk.action.fx.passkey.UsePasskey") {
        val options = node(0)
        if (options?.get(44) != null) {
            if (options[43] != null) {
                call(
                    options[43],
                    linkedMapOf(
                        "error_type" to "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL",
                        "error_message" to "No credentials available",
                        "exception_class" to "androidx.credentials.exceptions.NoCredentialException",
                    ),
                )
                call(options[52])
            } else {
                call(options[41])
            }
        }
        null
    }
    on("bk.action.fx.passkey.SignalAllAcceptedCredentials", "bk.action.fx.passkey.SignalCurrentUserDetails", "bk.action.fx.passkey.SignalUnknownCredential") {
        node(0)?.get(38)?.let { call(it, 0, "Credential Manager unavailable") }
        null
    }

    // ---- Account linking (Accounts Center) ----
    on("bk.fx.action.ExitLinkingFlow", "bk.fx.action.TriggerLinkingFlowCallbackV2") {
        session.log("Meta ended a linking flow: ${at(0)}")
        session.screens.clear()
        session.finish(BloksSession.Outcome.Cancelled)
        null
    }
    on("bk.fx.action.HideInitialLoadingState") {
        call(at(0))
        null
    }
    on("bk.fx.action.LoadingOverlay") { null }
    on("bk.fx.action.UpdateClientLinkageCache", "bk.fx.action.UpdateClientServiceCache") {
        call(at(1))
        null
    }
    on("bk.fx.action.FetchWebAuthData") {
        val url = str(0)
        if (url != null) session.host.openUrl(url)
        call(at(2), null)
        null
    }
    on("bk.fx.action.Launch3POAuth", "bk.fx.action.media.OpenMediaPicker", "bk.action.fx.im.ProfilePictureEditorSave") { null }
    on("bk.action.fx.UnifiedLauncherCompletionHandler") {
        if (at(0) != "success") session.log("Meta's launcher ended: ${at(0)}")
        null
    }

    // ---- Auth tickets and local factors ----
    on("bk.action.mft.ModularCreateAuthenticationTicket") {
        call(at(6), linkedMapOf("error_message" to "unsupported", "exception" to "", "error_code" to 0))
        null
    }
    on("bk.action.mft.ModularGeneratePTT") {
        call(at(11), linkedMapOf("exception" to "", "error_code" to 0, "error_message" to "unsupported", "remaining_attempts" to null, "auth_factor_expired" to false))
        null
    }
    on("bk.action.mft.security.GetDeviceKeys") { ArrayList<Any?>() }
    on("bk.action.mft.security.mfa.IsFactorAvailable", "bk.action.mft.security.mfa.UseFactor") {
        val options = node(0)
        if (name.endsWith("IsFactorAvailable")) call(options?.get(36), false, "Factors not available") else call(options?.get(36), null, "Factors not available")
        null
    }

    // ---- WhatsApp ----
    on("bk.action.waffle.AcceptWaDisclosuresAndGenerateWaAuthProof") {
        call(at(2), "Missing required parameters")
        null
    }
    on("bk.action.waffle.FetchWaDataBundleV2") {
        call(at(2), "Invalid or missing use_case parameter")
        null
    }
    on("bk.action.waffle.HasMinimumWhatsAppVersionForIPCFeature") { false }
    on("bk.waffle.action.QueryFoaToWaffleEligibility") {
        val cb = at(1)
        session.post { call(cb, false) }
        null
    }

    // ---- Payments, media, uploads: none of it in a login ----
    on("bk.action.frontier.IsLuhnCompliant") {
        val digits = str(0).orEmpty().filter(Char::isDigit)
        digits.isNotEmpty() && digits.reversed().mapIndexed { i, c -> (c - '0').let { d -> if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d } }.sum() % 10 == 0
    }
    on("bk.action.frontier.CreateUPLSessionId") { "upl_${System.currentTimeMillis()}_${UUID.randomUUID()}" }
    on("bk.action.frontier.IsGooglePayAvailable") {
        call(at(3), screen, false)
        null
    }
    on("bk.action.frontier.LaunchGooglePaySheet") {
        call(at(5), screen, "NOT_SUPPORTED", "Google Pay cannot be launched.")
        null
    }
    on("bk.action.frontier.UpdateGooglePaySheet") { null }
    on("bk.action.commerce.GetPaymentToken") {
        call(at(3))
        null
    }
    on("commerce.action.GetCreditCardMetadata", "bk.action.payments.GetCreditCardMetaData") { linkedMapOf("cardType" to "UNKNOWN", "isValid" to false) }
    on("fbpay.action.DAGeneratePTT") {
        call(at(9))
        null
    }
    on("bk.action.inapppurchase.FetchPriceAndBuy", "bk.action.inapppurchase.FetchPriceAndBuyV2") { null }
    on("bk.action.media.LoadAlbums", "bk.action.media.LoadMediaV3") { null }
    on("bk.action.media.DownloadMultiple") {
        call(at(3), LinkedHashMap<Any?, Any?>())
        null
    }
    on("authenticity.action.OpenIdCapture", "authenticity.action.OpenSelfieCapture", "authenticity.action.Upload") {
        session.log("Meta asked for an identity check with the camera, which Visor cannot do")
        null
    }
    on("bk.action.UploadV2") {
        call(at(4), "Upload is not supported")
        null
    }
    on("bk.action.aistudio.ShareGuidedConversationResult") {
        node(1)?.get(41)?.let { call(it, false) }
        null
    }
    on("bk.action.survey.ResolveClientSideContext") {
        val cb = at(2)
        session.post { call(cb, null) }
        null
    }
    on(
        "bk.action.rlcp.mwa.ConsentSync", "bk.action.rlcp.mwa.OpenMediaHumanReviewSetting", "bk.action.rlcp.mwa.SetMediaHumanReviewEnabled",
        "bk.action.rlcp.mwa.CSOnConsentApproved", "bk.action.nmegai.UpdateAsteriaStatus", "bk.action.mwa.ScreenshotAndShare",
        "bk.action.mwa.SubmitActivityInsightsFeedback", "bk.action.mwa.SubmitActivityInsightsUpdate",
        "bk.action.internal.EditSandboxHostName", "bk.action.TakeAndSaveScreenshot", "bk.action.RequestPermission",
        "bk.action.internal.EngineDescription", "bk.fx.action.FetchFacebookAccountAuthData", "bk.fx.action.UpdateLinkedFBUser",
        "fx.action.crossposting.SetFeedAutoCrossposting", "fx.action.crossposting.SetStoryAutoCrossposting",
    ) { null }

    // ---- Logging, metrics, tracing: nothing to do ----
    on(*LOGGING.toTypedArray()) { null }
}

private val MAIN_QUEUE = Any()
private val BACKGROUND_QUEUE = Any()

internal fun ActionCall.toast(text: String?, millis: Long): Any? {
    if (!text.isNullOrBlank()) {
        session.toast = text
        session.toastMillis = millis
        session.toastCount++
    }
    return null
}

/** A timer of a screen: ticks `cb(id, ctx)` every interval, or once. */
internal class Timer(val id: String, val interval: Long, val repeat: Boolean, val callback: Any?, val screen: BloksSession.Screen?) {
    var future: ScheduledFuture<*>? = null
    var stopped = false
    var cancelled = false

    fun cancel() {
        cancelled = true
        future?.cancel(false)
    }
}

private fun ActionCall.schedule(timer: Timer) {
    val s = session
    timer.future = s.thread.schedule({
        s.post {
            if (timer.cancelled || timer.stopped) return@post
            // A timer of a screen that has gone ticks no more.
            if (timer.screen != null && timer.screen !in s.screens) {
                s.timers.remove(timer.id, timer)
                return@post
            }
            call(timer.callback, timer.id, timer.screen)
            if (timer.repeat && !timer.cancelled && !timer.stopped) schedule(timer) else if (!timer.repeat) s.timers.remove(timer.id, timer)
        }
    }, timer.interval, TimeUnit.MILLISECONDS)
}

/** An animation, run to its end the moment it starts. */
internal abstract class Animation {
    val listeners = ArrayList<Any?>()
    var done = false
}

internal class ValueAnimation(val onUpdate: Any?, onEnd: Any?, from: Any?, var to: Any?, val seconds: Double, val linear: Boolean) : Animation() {
    var value: Any? = from

    init {
        if (onEnd != null) listeners += onEnd
    }
}

internal class AnimationSet(val children: List<Animation>) : Animation()

/** Its last value, its update callback once, then its end listeners: `(animation, cancelled, ctx)`, or one arg for linear. */
internal fun ActionCall.finish(animation: Animation) {
    if (animation.done) return
    animation.done = true
    when (animation) {
        is ValueAnimation -> {
            animation.value = animation.to
            call(animation.onUpdate, animation)
        }
        is AnimationSet -> animation.children.forEach { finish(it) }
    }
    for (listener in animation.listeners) {
        if (animation is ValueAnimation && animation.linear) call(listener, animation) else call(listener, animation, false, screen)
    }
}
