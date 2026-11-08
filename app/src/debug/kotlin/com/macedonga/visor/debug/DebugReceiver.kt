package com.macedonga.visor.debug

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import com.macedonga.visor.band.BandPair
import com.macedonga.visor.connection.ClassicBond
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.connection.InternetSwitch
import com.macedonga.visor.connection.PhoneMessages
import com.macedonga.visor.connection.RequestLog
import com.macedonga.visor.connection.WifiLink
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingMode
import com.macedonga.visor.core.assistant.MediaControl
import com.macedonga.visor.core.assistant.mcp.AuthKind
import com.macedonga.visor.core.assistant.mcp.McpHub
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.SignIn
import com.macedonga.visor.core.connection.Captions
import com.macedonga.visor.core.connection.CaptionsMode
import com.macedonga.visor.core.connection.CaptionsPhase
import com.macedonga.visor.core.connection.DISPLAY_STATUS_TYPES
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.PrompterLook
import com.macedonga.visor.core.connection.PrompterPhase
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.Teleprompter
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.look.GlassesLook
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.AppInstall
import com.macedonga.visor.core.media.GlassesApp
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.media.LiveSettingsStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.RecordStart
import com.macedonga.visor.core.media.Screencast
import com.macedonga.visor.core.media.ScreencastPhase
import com.macedonga.visor.core.media.SessionApp
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.media.StreamReceiver
import com.macedonga.visor.core.media.requestAsset
import com.macedonga.visor.core.protocol.Dat
import com.macedonga.visor.core.settings.GlassesContact
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.PERMISSIONS_NAMESPACE
import com.macedonga.visor.core.settings.contactObject
import com.macedonga.visor.core.settings.contactsNamespace
import com.macedonga.visor.core.settings.permissionObject
import com.macedonga.visor.dat.DatLayout
import com.macedonga.visor.dat.ToolkitStore
import com.macedonga.visor.display.CaptionPacks
import com.macedonga.visor.gallery.PhoneLibrary
import com.macedonga.visor.home.PairSeen
import com.macedonga.visor.kit.protocol.Layout
import com.macedonga.visor.kit.protocol.LayoutAlign
import com.macedonga.visor.kit.protocol.LayoutIconName
import com.macedonga.visor.kit.protocol.LayoutStyle
import com.macedonga.visor.language.LanguageInstaller
import com.macedonga.visor.language.restartGlassesAssistant
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.notify.GlassesNotification
import com.macedonga.visor.notify.PhoneContacts
import com.macedonga.visor.outside.DeveloperMode
import com.macedonga.visor.pairs.KnownPair
import com.macedonga.visor.pairs.Looks
import com.macedonga.visor.pairs.Pairs
import com.macedonga.visor.phone.PhoneActions
import com.macedonga.visor.setup.Provisioner
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.StageDebug
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only: the app over adb, with the phone locked. The answer is the broadcast's result
 * data; everything else also lands in `logcat -s Visor`.
 *
 *     adb shell am broadcast -n com.macedonga.visor/.debug.DebugReceiver -a com.macedonga.visor.debug.STATUS
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {

    @Inject lateinit var connection: GlassesConnection
    @Inject lateinit var provisioner: Provisioner
    @Inject lateinit var identities: IdentityStore
    @Inject lateinit var live: LiveStream
    @Inject lateinit var stream: StreamReceiver
    @Inject lateinit var media: MediaImport
    @Inject lateinit var settings: GlassesSettings
    @Inject lateinit var assistant: Assistant
    @Inject lateinit var log: EventLog
    @Inject lateinit var airship: AirshipReceiver
    @Inject lateinit var contacts: PhoneContacts
    @Inject lateinit var config: GlassesConfig
    @Inject lateinit var liveSettings: LiveSettingsStore
    @Inject lateinit var mcp: McpHub
    @Inject lateinit var pairs: Pairs
    @Inject lateinit var looks: Looks
    @Inject lateinit var phone: PhoneActions
    @Inject lateinit var prompter: Teleprompter
    @Inject lateinit var captions: Captions
    @Inject lateinit var languages: LanguageInstaller
    @Inject lateinit var captionPacks: CaptionPacks
    @Inject lateinit var screencast: Screencast
    @Inject lateinit var requests: RequestLog
    @Inject lateinit var login: MetaLogin
    @Inject lateinit var internet: InternetSwitch
    @Inject lateinit var developer: DeveloperMode
    @Inject lateinit var texts: PhoneMessages
    @Inject lateinit var wifiLink: WifiLink
    @Inject lateinit var hearing: Hearing
    @Inject lateinit var bandPair: BandPair
    @Inject lateinit var classicBond: ClassicBond
    @Inject lateinit var albums: Albums
    @Inject lateinit var library: PhoneLibrary
    @Inject lateinit var toolkit: ToolkitStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.substringAfterLast('.').orEmpty()
        Log.i("Visor", "API: $action")
        // A bad extra answers instead of killing the app; no branch throws after its goAsync().
        try {
            handle(context, intent, action)
        } catch (e: Exception) {
            resultData = "failed: ${e.message}"
        }
    }

    private fun handle(context: Context, intent: Intent, action: String) {
        when (action) {
            "STATUS" -> resultData = status()
            // The phone's thread list as Visor reads it for the glasses' Messages: --ei count.
            "THREADS" -> resultData = texts.describeThreads(intent.getIntExtra("count", 15))
            // A second pair on the home, a black Skyler, never connected; --ez on false takes it away.
            "DEMO_PAIR" -> resultData = demoPair(intent.getBooleanExtra("on", true))
            // Changes the owner of the connected pair again, reusing the owner key, as setup does; the steps go to the log.
            "PROVISION" -> resultData = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
                ?.let { provisioner.start(it); "started on $it; follow LOG" } ?: "no pair over Bluetooth"
            "PROVISION_STATUS" -> resultData = provisioner.status.value.toString()
            // Visor's own app link to the Display (AppLinkProbe): --es pem <base64 PEM of the app key> --ei version N
            // --es salt <hex>; then APP_MOTION [--ei rate], APP_LAYOUT, APP_STOP. Steps go to LOG as "AppLink:".
            // Without a pem: Visor's toolkit key and the manifest it installed on this pair.
            "APP_LINK" -> resultData = if (!intent.hasExtra("pem")) {
                val address = (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address
                val pair = connection.state.value.info?.serial?.let(toolkit::pair)
                if (address == null || pair == null) "no pair over Bluetooth, or no toolkit setup on it" else {
                    val (key, public) = toolkit.appKey()
                    AppLinkProbe.start(context, address, key, public, pair.version, pair.salt, 0, 0, intent.getBooleanExtra("dam", true), log)
                }
            } else (connection.state.value.target?.link as? GlassesLink.Bluetooth)?.address?.let { address ->
                AppLinkProbe.start(
                    context, address, Base64.decode(intent.getStringExtra("pem").orEmpty(), Base64.DEFAULT),
                    intent.getIntExtra("version", 0).toLong(), intent.getStringExtra("salt").orEmpty().hexToByteArray(),
                    intent.getIntExtra("tag", 0), intent.getIntExtra("sign", 0), intent.getBooleanExtra("dam", true), log,
                )
            } ?: "no pair over Bluetooth"
            "APP_MOTION" -> resultData = AppLinkProbe.capability(Dat.IMU, Dat.motionStart(intent.getIntExtra("rate", 10)))
            "APP_LAYOUT" -> resultData = AppLinkProbe.capability(
                Dat.DISPLAY,
                Dat.displayContent(
                    DatLayout.payload(
                        Layout {
                            padding = 24
                            gap = 16
                            row {
                                gap = 12
                                crossAlign = LayoutAlign.CENTER
                                icon(LayoutIconName.SMART_GLASSES)
                                text("Visor") { style = LayoutStyle.HEADING }
                            }
                            text("This layout came from Visor, through Meta's toolkit app.")
                            buttons { button("ok", "OK") { icon = LayoutIconName.CHECKMARK; primary = true } }
                        },
                    ),
                ),
            )
            "APP_STOP" -> resultData = AppLinkProbe.stop()
            // Grants a phone app the glasses' permissions over RLDrive, as the Meta app does: --es pkg (Visor by default)
            // --es perms DEV_MODE,IMU (short names); then lists what the namespace holds.
            "GRANT" -> answerLater {
                val app = intent.getStringExtra("pkg") ?: context.packageName
                val grants = intent.getStringExtra("perms").orEmpty().split(',').filter { it.isNotBlank() }
                    .map { permissionObject(app, "android.permission.${it.trim()}", "ALWAYS") }
                settings.readNamespace(PERMISSIONS_NAMESPACE, grants)?.joinToString("\n") { "${it.uri} ${it.type} ${it.data}" } ?: "the glasses did not answer"
            }
            // Each pair's receipt key, the key its constellation manifest names it by: public, so it may be printed.
            "DEVICE_KEYS" -> resultData = identities.all().joinToString("\n") { "${it.serial} ${it.devicePublicKey?.toHexString() ?: "none"}" }
            "CONNECT" -> resultData = runCatching { GlassesService.start(context); "connecting" }.getOrElse { "could not start the service: $it" }
            "DISCONNECT" -> {
                GlassesService.disconnect(context)
                resultData = "disconnecting"
            }
            // --ei n
            "LOG" -> resultData = log.lines.value.takeLast(intent.getIntExtra("n", 40)).joinToString("\n")
            // --ez wifi --ei band 2|5|0 --es quality --ei bitrate --ei fps; --ez auto true for the Live view's own start.
            "LIVE_START" -> if (intent.getBooleanExtra("auto", false)) {
                live.start(liveSettings.settings.value)
                resultData = "starting, automatic quality"
            } else {
                live.start(
                    app = GlassesApp.LIVE_STREAMING,
                    session = SessionApp.FAIR_DEMO,
                    quality = StreamQuality.valueOf(intent.getStringExtra("quality") ?: "FULL_HD"),
                    bitrate = intent.getIntExtra("bitrate", 4_000_000),
                    frameRate = intent.getIntExtra("fps", 24),
                    overWifi = intent.getBooleanExtra("wifi", true),
                    groupBand = intent.getIntExtra("band", 2),
                )
                resultData = "starting"
            }
            "DEVICE_REFRESH" -> {
                connection.refreshDeviceState()
                resultData = "subscribed again; battery temperature was ${connection.state.value.device?.battery?.temperatureC}"
            }
            "LIVE_STOP" -> {
                live.stop()
                resultData = "stopping"
            }
            "LIVE_REC" -> answerLater {
                if (live.outputs.recording.value != null) "saved ${live.outputs.stopRecording()}"
                else when (live.outputs.startRecording()) {
                    RecordStart.STARTED -> "recording"
                    RecordStart.NOT_READY -> "no stream to record"
                    RecordStart.FAILED -> "the phone would not create the file"
                }
            }
            "LIVE_REPLAY" -> answerLater { "replay ${live.outputs.replay.heldUs / 1000} ms: ${live.outputs.saveReplay()}" }
            "LIVE_FRAME" -> answerLater { "frame: ${live.outputs.saveFrame()}" }
            // --es file <pushed mp4> --es handle <capture> [--ez landscape] [--ei strength]: the phone's stabilization run again,
            // into steady_out.mp4 beside the pushed file.
            "STEADY" -> answerLater {
                val input = pushed(context, intent.getStringExtra("file")) ?: return@answerLater "no such pushed file"
                val out = File(input.parentFile, "steady_out.mp4")
                media.finishAgain(input, intent.getStringExtra("handle").orEmpty(), out, intent.getBooleanExtra("landscape", false), intent.getIntExtra("strength", 0))
            }
            "BUGREPORT" -> {
                media.requestBugReport(if (intent.hasExtra("mode")) intent.getIntExtra("mode", 0) else null)
                resultData = "asked; the zip goes to Download/Visor"
            }
            "DRIVE_SET" -> {
                val key = intent.getStringExtra("key").orEmpty()
                resultData = if (settings.writeDrive(key, intent.getStringExtra("value").orEmpty(), intent.getStringExtra("ns"))) {
                    "pushed over RLDrive; SETTINGS --es key $key to check"
                } else {
                    "refused: unknown namespace or not connected"
                }
            }
            // A notification built by hand: --ei type --ei provider --es title --es text --es sender --es pkg --es thread
            // (a text's thread in the SMS store, whose number becomes the sender's id).
            "NOTIFY" -> {
                val thread = intent.getStringExtra("thread").orEmpty()
                val n = GlassesNotification(
                    key = "visor|" + System.currentTimeMillis(),
                    pkg = intent.getStringExtra("pkg") ?: "org.telegram.messenger",
                    appName = intent.getStringExtra("app") ?: "Telegram",
                    title = intent.getStringExtra("title") ?: "Visor",
                    text = intent.getStringExtra("text") ?: "Test",
                    postTime = System.currentTimeMillis(),
                    type = intent.getIntExtra("type", 1),
                    provider = intent.getIntExtra("provider", 3),
                    sender = intent.getStringExtra("sender").orEmpty(),
                    senderId = intent.getStringExtra("sid") ?: texts.addressesOf(thread).firstOrNull().orEmpty(),
                    threadId = thread,
                )
                connection.submit { it.sendOnService(GlassesNotification.SVC, 1, n.envelope()) }
                resultData = "sent type ${n.type} provider ${n.provider}"
            }
            // A test contact in the glasses' contacts: --es ns native|whats_app --es id --es name --es number +39...
            "CONTACT" -> answerLater {
                val number = intent.getStringExtra("number").orEmpty()
                val c = GlassesContact(
                    id = intent.getStringExtra("id").orEmpty(),
                    name = intent.getStringExtra("name").orEmpty(),
                    numbers = if (number.isEmpty()) emptyList() else listOf(GlassesContact.Number(2, "39", "", number)),
                )
                val ns = contactsNamespace(intent.getStringExtra("ns") ?: "native")
                val after = settings.readNamespace(ns, listOf(contactObject(c))) ?: return@answerLater "no answer from the glasses; ${c.name} may not be in $ns"
                "pushed ${c.name} to $ns; the glasses had ${after.size} objects before"
            }
            "CONTACTS_SYNC" -> answerLater { contacts.sync() }
            // The home's light without the glasses: --es mood SEARCHING|...|auto, --es variant shiny_green|...|auto.
            "STAGE" -> {
                intent.getStringExtra("mood")?.let { m ->
                    StageDebug.mood.value = Mood.entries.firstOrNull { it.name == m.uppercase() }
                }
                intent.getStringExtra("variant")?.let { v -> StageDebug.variant.value = v.takeIf { it != "auto" } }
                resultData = "mood=${StageDebug.mood.value ?: "auto"} variant=${StageDebug.variant.value ?: "auto"}"
            }
            // The setup held for a look: --es page WELCOME|ACCESS|LOGIN|PAIRING|LINKING, --ef t seconds into the pairing story (-1 runs it).
            "ONBOARD" -> {
                intent.getStringExtra("page")?.let { StageDebug.onboardingPage.value = it.uppercase() }
                if (intent.hasExtra("t")) StageDebug.demoAt.value = intent.getFloatExtra("t", -1f).takeIf { it >= 0f }
                resultData = "page=${StageDebug.onboardingPage.value} t=${StageDebug.demoAt.value}"
            }
            // The on-device recogniser's languages for --es lang (default en-US); ASR_DOWNLOAD asks for that one.
            "ASR_CHECK", "ASR_DOWNLOAD" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    resultData = "needs Android 13"
                    return
                }
                val lang = intent.getStringExtra("lang") ?: "en-US"
                val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                val pending = goAsync()
                Handler(Looper.getMainLooper()).post {
                    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    if (action == "ASR_DOWNLOAD") {
                        r.triggerModelDownload(ask)
                        pending.resultData = "asked for $lang"
                        pending.finish()
                        r.destroy()
                        return@post
                    }
                    r.checkRecognitionSupport(ask, context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            pending.resultData = "installed=${s.installedOnDeviceLanguages} pending=${s.pendingOnDeviceLanguages} " +
                                "supported=${s.supportedOnDeviceLanguages.take(30)} online=${s.onlineLanguages.take(10)}"
                            pending.finish()
                            r.destroy()
                        }

                        override fun onError(error: Int) {
                            pending.resultData = "error $error"
                            pending.finish()
                            r.destroy()
                        }
                    })
                }
            }
            // Delete the enrolled custom wake word from the glasses.
            "CWW_CLEAR" -> answerLater { if (assistant.wakeWord.clear()) "sent; the glasses answer with a result in the log" else "not sent" }
            // Hearing correction: --es do correction (start, waited for) | stop | state.
            "HEARING" -> answerLater {
                when (intent.getStringExtra("do") ?: "state") {
                    "correction" -> {
                        hearing.startCorrection()
                        val on = hearing.awaitRunning(HearingMode.CORRECTION, 10_000)
                        "running=$on ${hearing.state.value}"
                    }
                    "stop" -> { hearing.stop(); hearing.awaitStopped(); "${hearing.state.value}" }
                    else -> "${hearing.state.value}"
                }
            }
            // Restart only the glasses' assistant (it re-reads MobileConfig): live_caption_voice_id away and back.
            "ASSISTANT_RESTART" -> answerLater { if (restartGlassesAssistant(settings)) "restarting the glasses' assistant" else "not connected" }
            // Developer mode on or off: --ez on false.
            "DEVELOPER" -> {
                developer.set(intent.getBooleanExtra("on", true))
                resultData = "developer mode ${developer.on.value}"
            }
            "MC_READ" -> {
                config.read()
                resultData = "asking the glasses for a bug report"
            }
            // MobileConfig values whose name contains --es key, as Visor last read or wrote them.
            "MC_GET" -> {
                val key = intent.getStringExtra("key").orEmpty()
                val s = config.state.value
                resultData = "params=${s.params.size} readAt=${s.readAt}\n" +
                    s.params.keys.filter { key in it }.joinToString("\n") { "$it=${s.values[it]} slot=${s.params[it]?.slot} sessionless=${s.params[it]?.sessionless}" }
            }
            "MC_BUILD", "MC_APPLY" -> {
                // --es on "a.b,c.d" sets them true, --es off "e.f" false, --es set "g.h=60,i.j=1.5" numbers.
                val on = intent.getStringExtra("on").orEmpty().split(',').filter { it.isNotBlank() }.associateWith { true as Any } +
                    intent.getStringExtra("off").orEmpty().split(',').filter { it.isNotBlank() }.associateWith { false as Any } +
                    intent.getStringExtra("set").orEmpty().split(',').filter { '=' in it }.associate { pair ->
                        val (k, v) = pair.split('=', limit = 2)
                        k.trim() to (v.trim().toLongOrNull() ?: v.trim().toDouble())
                    }
                if (action == "MC_APPLY") {
                    config.apply(on)
                    resultData = "sending; ${config.state.value.note}"
                } else {
                    val (name, zip) = config.preview(on)
                    File(context.getExternalFilesDir(null), name).writeBytes(zip)
                    resultData = "$name ${zip.size} bytes"
                }
            }
            // Every object in one RLDrive namespace: --es ns contacts_native
            "DRIVE_READ" -> answerLater {
                val objects = settings.readNamespace(intent.getStringExtra("ns").orEmpty()) ?: return@answerLater "no answer from the glasses"
                "${objects.size} objects\n" + objects.take(intent.getIntExtra("n", 5)).joinToString("\n") { o ->
                    "${o.type} ${o.uri} deleted=${o.deleted} raw=" + o.raw.toHexString()
                }
            }
            "READ_SETTINGS" -> {
                settings.read()
                resultData = "reading"
            }
            // --es key
            "SETTINGS" -> resultData = intent.getStringExtra("key")?.let { "$it=${settings.value(it)}" }
                ?: settings.state.value.stored.entries.joinToString("\n") { "${it.key}=${it.value}" }
            // --es key --es value
            "SET" -> {
                val key = intent.getStringExtra("key").orEmpty()
                resultData = if (settings.write(key, intent.getStringExtra("value").orEmpty())) "written; READ_SETTINGS to check" else "refused: unknown key or value"
            }
            // The albums, and how many of Visor's own files each top folder holds.
            "ALBUMS" -> resultData = buildString {
                appendLine("photos=${albums.photosPath()} videos=${albums.videosPath()} together=${albums.together.value} moving=${albums.moving.value} gallery=${library.items.value.size}")
                val store = MediaStore.VOLUME_EXTERNAL_PRIMARY
                for ((kind, uri) in listOf(
                    "images" to MediaStore.Images.Media.getContentUri(store),
                    "videos" to MediaStore.Video.Media.getContentUri(store),
                )) {
                    val counts = sortedMapOf<String, Int>()
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                        while (c.moveToNext()) c.getString(0).orEmpty().let { counts[it] = (counts[it] ?: 0) + 1 }
                    }
                    counts.forEach { (path, n) -> appendLine("$kind $path $n") }
                }
            }
            // --es photos --es videos --ez together: as Settings does, moves included.
            "ALBUM_SET" -> {
                intent.getStringExtra("photos")?.let(albums::setPhotos)
                intent.getStringExtra("videos")?.let(albums::setVideos)
                if (intent.hasExtra("together")) albums.setTogether(intent.getBooleanExtra("together", false))
                resultData = "set; ALBUMS to watch the move"
            }
            "RAW" -> resultData = intent.getStringExtra("key")?.let { key ->
                settings.rawObject(key)?.let { (ns, b) -> "$ns ${b.toHexString()}" } ?: "not read"
            }
            // An APK adb pushed into the external files dir, installed by the glasses' app manager: --es file --es pkg --el version.
            "APP_INSTALL" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val pkg = intent.getStringExtra("pkg").orEmpty()
                resultData = if (file == null || pkg.isEmpty()) "need --es file (in ${context.getExternalFilesDir(null)}) and --es pkg" else "installing; LOG for the result"
                if (file != null && pkg.isNotEmpty()) scope.launch {
                    val done = runCatching {
                        AppInstall(connection, airship).install(file, pkg, intent.getLongExtra("version", 1)) { log.add(it) }
                    }.getOrElse { it.toString() }
                    log.add("App install: $done")
                }
            }
            // What the glasses' app manager says about a package: --es pkg.
            "APP_QUERY" -> answerLater { AppInstall(connection, airship).query(intent.getStringExtra("pkg").orEmpty()) { log.add(it) } }
            // A file adb pushed into this app's external files dir, offered to the glasses over airship: --es file --es ns.
            "PUSH_FILE" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val namespace = intent.getStringExtra("ns").orEmpty()
                resultData = if (file == null) "no file ${intent.getStringExtra("file")} in ${context.getExternalFilesDir(null)}" else "sending ${file.length()} bytes; LOG for the result"
                if (file != null) scope.launch {
                    val done = CompletableDeferred<String?>()
                    connection.submit { mux -> done.complete(airship.send(mux, namespace, file.name, file.readBytes())) }
                    val why = withTimeoutOrNull(60_000) { done.await() }
                    log.add("Push file: " + if (done.isCompleted && why == null) "sent ${file.length()} bytes as ${file.name} in $namespace" else "failed: ${why ?: "no link or no answer"}")
                }
            }
            // A file adb pushed into the external files dir, offered over Wi-Fi Direct for its size: --es file --es ns.
            "PUSH_WIFI" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val namespace = intent.getStringExtra("ns").orEmpty()
                val name = intent.getStringExtra("name") ?: file?.name.orEmpty()
                // Minutes long, so it runs on its own and reports to LOG, not to the broadcast.
                resultData = if (file == null) "no file ${intent.getStringExtra("file")} in ${context.getExternalFilesDir(null)}" else "sending ${file.length()} bytes; LOG for the result"
                if (file != null) scope.launch {
                    val started = System.currentTimeMillis()
                    val why = runCatching { live.overWifi { mux -> airship.send(mux, namespace, name, file) } }.getOrElse { it.toString() }
                    log.add("Push Wi-Fi: " + if (why == null) "sent $name in $namespace in ${(System.currentTimeMillis() - started) / 1000} s" else "failed: $why")
                }
            }
            "MEDIA" -> resultData = media.captures.value.joinToString("\n") { c ->
                "${c.handle} kind=${c.kindCode} ${c.takenAtMillis}${if (c.landscape) " landscape" else ""} " + c.assets.joinToString(" ") { "${it.id}:0x${it.typeCode.toString(16)}:${it.size}" }
            }.ifEmpty { "no captures listed; open Media once or wait for the list" }
            // --es handle a,b: by handle prefix.
            "IMPORT" -> {
                val handles = intent.getStringExtra("handle")?.split(",").orEmpty()
                if (handles.isEmpty()) {
                    media.importAllNew()
                    resultData = "importing what is new"
                } else {
                    val chosen = media.captures.value.filter { c -> handles.any { c.handle.startsWith(it) } }
                    media.import(chosen)
                    resultData = "importing " + chosen.joinToString { "${it.handle.take(8)} ${it.type}" }
                }
            }
            "ASSET" -> {
                val handle = intent.getStringExtra("handle").orEmpty()
                val asset = intent.getStringExtra("asset").orEmpty()
                val capture = media.captures.value.firstOrNull { it.handle.startsWith(handle) }
                val id = capture?.assets?.firstOrNull { it.id.startsWith(asset) }?.id
                if (capture == null || id == null) resultData = "no such capture or asset"
                else {
                    connection.submit { requestAsset(it, capture.handle, id) }
                    resultData = "asked for $id"
                }
            }
            "PROMPTER" -> answerLater {
                if (intent.getBooleanExtra("close", false)) prompter.close()
                else prompter.show(
                    intent.getStringExtra("text") ?: "Testing the teleprompter from Visor.",
                    PrompterLook(paged = intent.getBooleanExtra("paged", false), followVoice = intent.getBooleanExtra("voice", false)),
                )
                delay(300)
                withTimeoutOrNull(8_000) {
                    prompter.state.first { it.phase != PrompterPhase.STARTING && it.phase != PrompterPhase.CLOSING }
                }
                prompter.state.value.let { "${it.phase} refusal=${it.refusal}" }
            }
            // --es features a,b: what Meta has for these constellation features on this pair.
            "PACKS" -> answerLater {
                val features = intent.getStringExtra("features").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                runCatching { languages.offersFor(features) }.fold(
                    { offers -> offers.joinToString("\n") { "${it.feature} ${it.resource} ${it.version} ${it.type} app ${it.appId} ${it.size} ${it.url}" }.ifEmpty { "none" } },
                    { "failed: ${it.message}" },
                )
            }
            // --ei svc --ei type --es hex: one message on a service; the answer is what the glasses said within 5 s.
            "SEND" -> answerLater {
                val svc = intent.getIntExtra("svc", 0)
                // A svc28 type the Display does not know kills its system process; a model not known yet may be one.
                val display = connection.state.value.info.let { it == null || isDisplayBuild(it.build) }
                val type = intent.getIntExtra("type", 0)
                if (svc == SVC_STATUS && display && type !in DISPLAY_STATUS_TYPES) {
                    return@answerLater "refused: the Display has no svc28 type $type"
                }
                val body = intent.getStringExtra("hex").orEmpty().chunked(2).filter { it.length == 2 }.map { it.toInt(16).toByte() }.toByteArray()
                val heard = Collections.synchronizedList(mutableListOf<String>())
                // Subscribed before the send, so a fast reply is not missed.
                val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    launch(start = CoroutineStart.UNDISPATCHED) { connection.serviceData.collect { if (it.serviceId == svc) heard += "type ${it.messageType}: " + it.payload.toHexString() } }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        connection.errors.collect { if (it.serviceId == svc) heard += "status 0x%04x".format(Locale.ROOT, it.errorId) }
                    }
                }
                connection.submit { it.sendOnService(svc, type, body) }
                delay(5_000)
                watch.cancel()
                heard.joinToString("\n").ifEmpty { "nothing" }
            }
            // --es doc <client_doc_id> --es name <friendly name> --es vars <json>: one query on Meta's ar GraphQL, as the
            // user; the answer's first 4000 characters.
            "GRAPHQL" -> answerLater {
                val text = login.graphql(
                    intent.getStringExtra("name").orEmpty(),
                    intent.getStringExtra("doc").orEmpty(),
                    intent.getStringExtra("vars64")?.let { String(Base64.decode(it, 0)) } ?: intent.getStringExtra("vars") ?: "{}",
                )
                if (!intent.getBooleanExtra("shape", false)) text.take(4000) else {
                    // Every path with its type, a string's length and start: the structure without the bulk.
                    val out = StringBuilder()
                    fun walk(path: String, v: Any?) {
                        when (v) {
                            is JSONObject -> v.keys().forEach { walk("$path.$it", v.opt(it)) }
                            is JSONArray -> { out.append("$path [${v.length()}]\n"); if (v.length() > 0) walk("$path[0]", v.opt(0)) }
                            is String -> out.append("$path str(${v.length}) ${v.take(60).replace('\n', ' ')}\n")
                            else -> out.append("$path = $v\n")
                        }
                    }
                    walk("", JSONObject(text))
                    out.toString().take(4000)
                }
            }
            // --ez on false: tell the Display the phone is offline, so it keeps its apps off the phone's internet.
            "INTERNET" -> {
                internet.sharing = intent.getBooleanExtra("on", true)
                resultData = "sharing=${internet.sharing}"
            }
            // The request log's newest entries, links cut at their query.
            "REQUESTS" -> resultData = requests.items.value.takeLast(intent.getIntExtra("n", 30)).joinToString("\n") {
                "${it.source} ${it.outcome ?: "running"} ${it.bytes}B ${it.millis}ms [${it.options}] ${it.url.substringBefore('?').take(160)}"
            }.ifEmpty { "none" }
            // --es key <MobileConfig param> --ez value true|false: one bool written to the glasses.
            "MC_SET" -> answerLater {
                config.send(mapOf(intent.getStringExtra("key").orEmpty() to intent.getBooleanExtra("value", false))) ?: "sent"
            }
            // --ez stop true to stop; the answer is the state once it settles.
            "MIRROR" -> answerLater {
                if (intent.getBooleanExtra("stop", false)) screencast.stop() else screencast.start()
                delay(500)
                withTimeoutOrNull(40_000) { screencast.state.first { it.phase != ScreencastPhase.STARTING } }
                screencast.state.value.toString()
            }
            // --es feature live_captions_en: Meta's pack installed through the glasses' OTA service.
            "CAPTION_PACK" -> {
                captionPacks.install(intent.getStringExtra("feature") ?: "live_captions_en")
                resultData = "installing; LOG for the result"
            }
            "CAPTIONS" -> answerLater {
                val self = intent.getStringExtra("lang") ?: "en"
                when {
                    intent.getBooleanExtra("stop", false) -> captions.stop()
                    intent.getBooleanExtra("show", false) -> Unit
                    else -> captions.start(
                        if (intent.hasExtra("other")) CaptionsMode.TRANSLATION else CaptionsMode.CAPTIONS,
                        self, intent.getStringExtra("other") ?: self,
                    )
                }
                delay(300)
                withTimeoutOrNull(8_000) {
                    captions.state.first { it.phase == CaptionsPhase.LIVE || it.phase == CaptionsPhase.IDLE }
                }
                captions.state.value.let { s -> "${s.phase} ${s.mode} refusal=${s.refusal}\n" + s.lines.joinToString("\n") { "${if (it.done) "" else "… "}${it.text}${it.source?.let { src -> " ($src)" }.orEmpty()}" } }
            }
            // The Neural Band paired with the Display over svc41109, set up here first when it has no record. It outlasts a
            // broadcast, so it answers at once; BAND_PAIR_STATUS says how it ended.
            "BAND_PAIR" -> {
                bandPair.start()
                resultData = "started; BAND_PAIR_STATUS for how it ended, LOG for the steps"
            }
            "BAND_PAIR_STATUS" -> resultData = bandPair.status.value.toString()
            // The strongest BLE advertisers heard in --ei ms (10 s by default), each with its manufacturer data by company id.
            "BLE_SCAN" -> answerLater { bleScan(context, intent.getIntExtra("ms", 10_000).toLong()) }
            // A Display's classic bond for calls: CLASSIC_PROBE only reads. CLASSIC_REBOND and CLASSIC_UNDO unpair the glasses
            // and pair them again, so they need --ez confirm true; they answer at once and log each step (LOG).
            "CLASSIC_PROBE" -> answerLater { classicBond.perform(ClassicBond.Action.PROBE).toString() }
            "CLASSIC_REBOND", "CLASSIC_UNDO" -> resultData = if (!intent.getBooleanExtra("confirm", false)) {
                "this unpairs the glasses and pairs them again: add --ez confirm true"
            } else {
                classicBond.start(if (action == "CLASSIC_REBOND") ClassicBond.Action.REBOND else ClassicBond.Action.UNDO)
                "started; LOG for the steps"
            }
            // --es do directions --es to --es mode | play --es q --es app | media --es c pause|resume|next|... | now.
            "PHONE" -> answerLater {
                when (intent.getStringExtra("do")) {
                    "directions" -> phone.directions(intent.getStringExtra("to").orEmpty(), intent.getStringExtra("mode").orEmpty()).toString()
                    "play" -> phone.play(intent.getStringExtra("q").orEmpty(), intent.getStringExtra("app").orEmpty()).toString()
                    "media" -> phone.control(MediaControl.valueOf(intent.getStringExtra("c").orEmpty().uppercase())).toString()
                    else -> phone.nowPlaying().toString()
                }
            }
            // --es q
            "ASK" -> answerLater { withTimeoutOrNull(25_000) { assistant.ask(intent.getStringExtra("q") ?: "Say hello.") } ?: "no answer in 25 s" }
            // --es q: a question typed for the Display, as the Meta AI page asks it; the answer is its turn's, the log says who gave it.
            "ASK_DISPLAY" -> answerLater {
                val id = assistant.askOnDisplay(intent.getStringExtra("q") ?: "What is the tallest mountain?")
                    ?: return@answerLater "not sent: not a Display, or its assistant is not registered"
                withTimeoutOrNull(25_000) {
                    var turn = assistant.diary.turn(id)
                    while (turn != null && turn.answer.isEmpty() && turn.problem.isEmpty()) {
                        delay(250)
                        turn = assistant.diary.turn(id)
                    }
                    turn?.let { it.answer.ifEmpty { it.problem } }
                } ?: "no answer in 25 s"
            }
            // MCP_PROBE, MCP_ADD: --es url --es name --es auth NONE|TOKEN|HEADER --es secret --es header.
            "MCP_PROBE" -> answerLater {
                val auth = AuthKind.valueOf(intent.getStringExtra("auth") ?: "NONE")
                mcp.probe(intent.getStringExtra("url").orEmpty(), auth, intent.getStringExtra("secret").orEmpty(), intent.getStringExtra("header").orEmpty()).toString()
            }
            "MCP_ADD" -> {
                val server = McpServer(
                    name = intent.getStringExtra("name") ?: "Test",
                    url = intent.getStringExtra("url").orEmpty(),
                    auth = AuthKind.valueOf(intent.getStringExtra("auth") ?: "NONE"),
                    secret = intent.getStringExtra("secret").orEmpty(),
                    header = intent.getStringExtra("header").orEmpty(),
                )
                mcp.store.add(server)
                resultData = server.id
            }
            "MCP_STATUS" -> resultData = mcp.store.servers.value.joinToString("\n") { s ->
                val st = mcp.status.value[s.id]
                "${s.id} ${s.name} ${s.url} auth=${s.auth} enabled=${s.enabled} transport=${s.transport} phase=${st?.phase} busy=${st?.busy} " +
                    "icon=${st?.icon?.size} error=${st?.error} info=${st?.info} tools=${st?.tools?.joinToString(",") { it.name }}"
            }
            // What the model would get.
            "MCP_TOOLS" -> answerLater {
                val box = mcp.toolbox()
                box.tools.joinToString("\n") { "${it.name} (${it.tool.name}) readOnly=${it.tool.readOnly}" } + "\nPROMPT:" + box.prompt
            }
            // --es tool exposed-name --es args json
            "MCP_CALL" -> answerLater {
                val box = mcp.toolbox()
                val tool = box.find(intent.getStringExtra("tool").orEmpty()) ?: return@answerLater "no such tool; have ${box.tools.joinToString { it.name }}"
                val (result, action) = mcp.call(tool, JSONObject(intent.getStringExtra("args") ?: "{}"))
                "error=${result.isError} action=$action\n${result.text}"
            }
            "MCP_SIGNIN" -> answerLater {
                val id = intent.getStringExtra("id").orEmpty()
                mcp.signIn(id)
                withTimeoutOrNull(20_000) {
                    mcp.signIn.first { it.id == id && (it is SignIn.InBrowser || it is SignIn.Failed) }
                }.toString()
            }
            "MCP_COMPLETE" -> answerLater {
                val taken = mcp.complete(intent.getStringExtra("uri").orEmpty())
                if (!taken) return@answerLater "not a sign-in's redirect"
                withTimeoutOrNull(20_000) {
                    mcp.signIn.first { it is SignIn.Done || it is SignIn.Failed }
                }.toString()
            }
            // A lease that needs Wi-Fi, held and let go as the Display's would be.
            "WIFI_LINK" -> resultData = if (intent.getBooleanExtra("on", true)) {
                wifiLink.refusal()?.let { "refused: $it" } ?: run { wifiLink.hold("debug"); "moving" }
            } else {
                wifiLink.release("debug")
                "released"
            }
            // --es id
            "MCP_REMOVE" -> {
                mcp.store.remove(intent.getStringExtra("id").orEmpty())
                resultData = "removed"
            }
            else -> resultData = "unknown action $action"
        }
    }

    private fun demoPair(on: Boolean): String {
        if (!on) {
            pairs.demo(null, null)
            looks.demo(DEMO_SERIAL, null, null)
            return "demo pair gone"
        }
        val look = GlassesLook("skyler", "Skyler", "shiny_black", "Shiny Black", "clear", "Clear", null)
        looks.demo(DEMO_SERIAL, look, DEMO_PICTURE)
        val seen = PairSeen(glasses = 64, case = 88, at = System.currentTimeMillis() - 2 * 3_600_000L, folded = true)
        pairs.demo(KnownPair(DEMO_SERIAL, "00:00:00:00:00:00"), seen)
        return "demo pair on: swipe the home's stage"
    }

    private fun status(): String {
        val s = connection.state.value
        val b = s.device?.battery
        val stats = stream.stats.value
        return listOf(
            "link=${s.link}" + if (s.reason.isNotBlank()) " (${s.reason})" else "",
            "glasses=${s.target?.serial ?: "none"} build=${s.info?.build ?: "?"}",
            "battery=" + (b?.let { "${it.percent}%" + (if (it.charging) " charging" else "") + " ${it.temperatureC} C" } ?: "?"),
            "thermal=${s.device?.thermal}",
            "case=" + (s.device?.case?.let { "${it.level}/${it.scale}" + (if (it.charging) " charging" else "") + (if (it.present) " present" else "") } ?: "?"),
            "assistant=${assistant.phase.value}",
            "live=${if (live.running.value) "running" else "stopped"} phase=${live.status.value.phase} frames=${stats.frames} fps=${stats.fps} kbit/s=${stats.bytesPerSecond * 8 / 1000}" +
                " rung=${live.rung.value} thermal=${live.device.value?.thermal?.health} rec=${live.outputs.recording.value != null} air=${live.outputs.broadcast.state.value.phase}",
            "settings read=${settings.state.value.readAt != null} stored=${settings.state.value.stored.size}",
        ).joinToString("\n")
    }

    /** For an answer that takes seconds: the broadcast stays open until it is ready. */
    @SuppressLint("MissingPermission")
    private suspend fun bleScan(context: Context, ms: Long): String {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return "Bluetooth is off"
        val heard = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val makers = record?.manufacturerSpecificData?.let { m ->
                    (0 until m.size()).joinToString(" ") { i -> "%04x:".format(m.keyAt(i)) + m.valueAt(i).joinToString("") { "%02x".format(it) } }
                }.orEmpty()
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                heard[result.device.address] = result.rssi to "${result.device.address} rssi=${result.rssi} name=$name maker=[$makers] services=${record?.serviceUuids.orEmpty()}"
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            delay(ms)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return heard.values.sortedByDescending { it.first }.take(SCAN_SHOWN).joinToString("\n") { it.second }.ifEmpty { "nothing heard" }
    }

    private fun answerLater(work: suspend () -> String) {
        val pending = goAsync()
        scope.launch {
            val text = runCatching { work() }.getOrElse { "failed: ${it.message}" }
            Log.i("Visor", "API answer: $text")
            pending.resultData = text
            pending.finish()
        }
    }

    /** A file adb pushed into the external files dir, by [name]; a name that leads out of the dir finds nothing. */
    private fun pushed(context: Context, name: String?): File? {
        val dir = context.getExternalFilesDir(null)?.canonicalFile ?: return null
        val file = File(dir, name ?: return null).canonicalFile
        return file.takeIf { it.toPath().startsWith(dir.toPath()) && it.isFile }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val DEMO_SERIAL = "DEMO00SKYLER00"
        const val SCAN_SHOWN = 25
        // Meta's own picture of a black Skyler, as its asset service hands it out.
        const val DEMO_PICTURE = "https://scontent.xx.fbcdn.net/mci_ab/uap/asset_manager/name_revision/?ab_b=e&ab_page=AssetManagerNameAndRevision" +
            "&ab_entry=RBM2-Skyler-ShinyBlack-Clear-Concord&set=mwa_device_image_assets&revision=1837680503843762&density=3&mode=dark"
    }
}
