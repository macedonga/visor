package com.macedonga.visor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.GlassesApp
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.SessionApp
import com.macedonga.visor.core.media.StreamQuality
import com.macedonga.visor.core.media.StreamReceiver
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: the app over adb, with the phone locked. The answer is the broadcast's result
 * data; everything else also lands in `logcat -s Visor`.
 *
 *     adb shell am broadcast -n com.macedonga.visor/.debug.DebugReceiver -a com.macedonga.visor.debug.STATUS
 *
 * Actions: STATUS, CONNECT, DISCONNECT, LOG (--ei n), LIVE_START (--ez wifi --ei band 2|5|0 --es quality
 * --ei bitrate --ei fps --ei band; --ez auto true for the Live view's own start), LIVE_STOP, LIVE_REC (starts or stops a
 * recording), LIVE_REPLAY, LIVE_FRAME, BUGREPORT, SETTINGS (--es key), SET (--es key --es value),
 * READ_SETTINGS, MEDIA, IMPORT (--es handle a,b: by handle prefix), ASK (--es q), ONBOARD (--es page --ef t),
 * MCP_PROBE / MCP_ADD (--es url --es name --es auth NONE|TOKEN|HEADER --es secret --es header), MCP_STATUS,
 * MCP_TOOLS (what the model would get), MCP_CALL (--es tool exposed-name --es args json), MCP_REMOVE (--es id),
 * DEMO_PAIR (--ez on false takes it away): a second pair on the home, a black Skyler, never connected or looked at.
 * PHONE (--es do directions --es to --es mode | play --es q --es app | media --es c pause|resume|next|... | now).
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {

    @Inject lateinit var connection: GlassesConnection
    @Inject lateinit var live: LiveStream
    @Inject lateinit var stream: StreamReceiver
    @Inject lateinit var media: MediaImport
    @Inject lateinit var settings: GlassesSettings
    @Inject lateinit var assistant: Assistant
    @Inject lateinit var log: EventLog
    @Inject lateinit var airship: AirshipReceiver
    @Inject lateinit var contacts: com.macedonga.visor.notify.PhoneContacts
    @Inject lateinit var config: com.macedonga.visor.core.media.GlassesConfig
    @Inject lateinit var liveSettings: com.macedonga.visor.core.media.LiveSettingsStore
    @Inject lateinit var mcp: com.macedonga.visor.core.assistant.mcp.McpHub
    @Inject lateinit var pairs: com.macedonga.visor.pairs.Pairs
    @Inject lateinit var looks: com.macedonga.visor.stage.Looks
    @Inject lateinit var phone: com.macedonga.visor.phone.PhoneActions
    @Inject lateinit var prompter: com.macedonga.visor.core.connection.Teleprompter
    @Inject lateinit var captions: com.macedonga.visor.core.connection.Captions
    @Inject lateinit var languages: com.macedonga.visor.language.LanguageInstaller
    @Inject lateinit var captionPacks: com.macedonga.visor.display.CaptionPacks
    @Inject lateinit var screencast: com.macedonga.visor.core.media.Screencast
    @Inject lateinit var requests: com.macedonga.visor.connection.RequestLog
    @Inject lateinit var login: com.macedonga.visor.login.MetaLogin
    @Inject lateinit var internet: com.macedonga.visor.connection.InternetSwitch
    @Inject lateinit var developer: com.macedonga.visor.outside.DeveloperMode
    @Inject lateinit var texts: com.macedonga.visor.connection.PhoneMessages
    @Inject lateinit var wifiLink: com.macedonga.visor.connection.WifiLink

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.substringAfterLast('.').orEmpty()
        Log.i("Visor", "API: $action")
        when (action) {
            "STATUS" -> resultData = status()
            // The phone's thread list as Visor reads it for the glasses' Messages: --ei count.
            "THREADS" -> resultData = texts.describeThreads(intent.getIntExtra("count", 15))
            "DEMO_PAIR" -> resultData = demoPair(intent.getBooleanExtra("on", true))
            "CONNECT" -> resultData = runCatching { GlassesService.start(context); "connecting" }.getOrElse { "could not start the service: $it" }
            "DISCONNECT" -> {
                GlassesService.stop(context)
                resultData = "disconnecting"
            }
            "LOG" -> resultData = log.lines.value.takeLast(intent.getIntExtra("n", 40)).joinToString("\n")
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
                    bypassDonCheck = true,
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
                else if (live.outputs.startRecording()) "recording" else "no stream to record"
            }
            "LIVE_REPLAY" -> answerLater { "replay ${live.outputs.replay.heldUs / 1000} ms: ${live.outputs.saveReplay()}" }
            "LIVE_FRAME" -> answerLater { "frame: ${live.outputs.saveFrame()}" }
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
                val n = com.macedonga.visor.notify.GlassesNotification(
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
                connection.submit { it.sendOnService(com.macedonga.visor.notify.GlassesNotification.SVC, 1, n.envelope()) }
                resultData = "sent type ${n.type} provider ${n.provider}"
            }
            // A test contact in the glasses' contacts: --es ns native|whats_app --es id --es name --es number +39...
            "CONTACT" -> answerLater {
                val number = intent.getStringExtra("number").orEmpty()
                val c = com.macedonga.visor.core.settings.GlassesContact(
                    id = intent.getStringExtra("id").orEmpty(),
                    name = intent.getStringExtra("name").orEmpty(),
                    numbers = if (number.isEmpty()) emptyList() else listOf(com.macedonga.visor.core.settings.GlassesContact.Number(2, "39", "", number)),
                )
                val ns = com.macedonga.visor.core.settings.contactsNamespace(intent.getStringExtra("ns") ?: "native")
                val after = settings.readNamespace(ns, listOf(com.macedonga.visor.core.settings.contactObject(c)))
                "pushed ${c.name} to $ns; the glasses had ${after.size} objects before"
            }
            "CONTACTS_SYNC" -> answerLater { contacts.sync() }
            // The home's light without the glasses: --es mood SEARCHING|...|auto, --es variant shiny_green|...|auto.
            "STAGE" -> {
                intent.getStringExtra("mood")?.let { m ->
                    com.macedonga.visor.stage.StageDebug.mood.value = com.macedonga.visor.stage.Mood.entries.firstOrNull { it.name == m.uppercase() }
                }
                intent.getStringExtra("variant")?.let { v -> com.macedonga.visor.stage.StageDebug.variant.value = v.takeIf { it != "auto" } }
                resultData = "mood=${com.macedonga.visor.stage.StageDebug.mood.value ?: "auto"} variant=${com.macedonga.visor.stage.StageDebug.variant.value ?: "auto"}"
            }
            // The setup held for a look: --es page WELCOME|ACCESS|LOGIN|PAIRING|LINKING, --ef t seconds into the pairing story (-1 runs it).
            "ONBOARD" -> {
                intent.getStringExtra("page")?.let { com.macedonga.visor.stage.StageDebug.onboardingPage.value = it.uppercase() }
                if (intent.hasExtra("t")) com.macedonga.visor.stage.StageDebug.demoAt.value = intent.getFloatExtra("t", -1f).takeIf { it >= 0f }
                resultData = "page=${com.macedonga.visor.stage.StageDebug.onboardingPage.value} t=${com.macedonga.visor.stage.StageDebug.demoAt.value}"
            }
            // The MobileConfig zip for --es on "a.b,c.d" set true: MC_BUILD writes it to the external files dir, MC_APPLY sends it.
            // The on-device recogniser's languages for --es lang (default en-US); ASR_DOWNLOAD asks for that one.
            "ASR_CHECK", "ASR_DOWNLOAD" -> {
                val lang = intent.getStringExtra("lang") ?: "en-US"
                val ask = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang)
                val pending = goAsync()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val r = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    if (action == "ASR_DOWNLOAD") {
                        r.triggerModelDownload(ask)
                        pending.resultData = "asked for $lang"
                        pending.finish()
                        r.destroy()
                        return@post
                    }
                    r.checkRecognitionSupport(ask, context.mainExecutor, object : android.speech.RecognitionSupportCallback {
                        override fun onSupportResult(s: android.speech.RecognitionSupport) {
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
            // Restart only the glasses' assistant (it re-reads MobileConfig): live_caption_voice_id away and back.
            "ASSISTANT_RESTART" -> answerLater { if (com.macedonga.visor.language.restartGlassesAssistant(settings)) "restarting the glasses' assistant" else "not connected" }
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
                    java.io.File(context.getExternalFilesDir(null), name).writeBytes(zip)
                    resultData = "$name ${zip.size} bytes"
                }
            }
            // Every object in one RLDrive namespace: --es ns contacts_native
            "DRIVE_READ" -> answerLater {
                val objects = settings.readNamespace(intent.getStringExtra("ns").orEmpty())
                "${objects.size} objects\n" + objects.take(intent.getIntExtra("n", 5)).joinToString("\n") { o ->
                    "${o.type} ${o.uri} deleted=${o.deleted} raw=" + o.raw.joinToString("") { "%02x".format(it) }
                }
            }
            "READ_SETTINGS" -> {
                settings.read()
                resultData = "reading"
            }
            "SETTINGS" -> resultData = intent.getStringExtra("key")?.let { "$it=${settings.value(it)}" }
                ?: settings.state.value.stored.entries.joinToString("\n") { "${it.key}=${it.value}" }
            "SET" -> {
                val key = intent.getStringExtra("key").orEmpty()
                resultData = if (settings.write(key, intent.getStringExtra("value").orEmpty())) "written; READ_SETTINGS to check" else "refused: unknown key or value"
            }
            "RAW" -> resultData = intent.getStringExtra("key")?.let { key ->
                settings.rawObject(key)?.let { (ns, b) -> "$ns ${b.joinToString("") { "%02x".format(it) }}" } ?: "not read"
            }
            // An APK adb pushed into the external files dir, installed by the glasses' app manager: --es file --es pkg --el version.
            "APP_INSTALL" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val pkg = intent.getStringExtra("pkg").orEmpty()
                resultData = if (file == null || pkg.isEmpty()) "need --es file (in ${context.getExternalFilesDir(null)}) and --es pkg" else "installing; see logcat -s Visor"
                if (file != null && pkg.isNotEmpty()) scope.launch {
                    val done = runCatching {
                        com.macedonga.visor.core.media.AppInstall(connection, airship).install(file, pkg, intent.getLongExtra("version", 1)) { log.add(it) }
                    }.getOrElse { it.toString() }
                    log.add("App install: $done")
                }
            }
            // What the glasses' app manager says about a package: --es pkg.
            "APP_QUERY" -> answerLater { com.macedonga.visor.core.media.AppInstall(connection, airship).query(intent.getStringExtra("pkg").orEmpty()) { log.add(it) } }
            // A file adb pushed into this app's external files dir, offered to the glasses over airship.
            "PUSH_FILE" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val namespace = intent.getStringExtra("ns").orEmpty()
                answerLater {
                    if (file == null) return@answerLater "no file ${intent.getStringExtra("file")} in ${context.getExternalFilesDir(null)}"
                    val done = kotlinx.coroutines.CompletableDeferred<String?>()
                    connection.submit { mux -> done.complete(airship.send(mux, namespace, file.name, file.readBytes())) }
                    val why = withTimeoutOrNull(60_000) { done.await() }
                    if (done.isCompleted && why == null) "sent ${file.length()} bytes as ${file.name} in $namespace" else "failed: ${why ?: "no link or no answer"}"
                }
            }
            // A file adb pushed into the external files dir, offered over Wi-Fi Direct for its size: --es file --es ns.
            "PUSH_WIFI" -> {
                val file = pushed(context, intent.getStringExtra("file"))
                val namespace = intent.getStringExtra("ns").orEmpty()
                val name = intent.getStringExtra("name") ?: file?.name.orEmpty()
                // Minutes long, so it runs on its own and reports to logcat -s Visor, not to the broadcast.
                resultData = if (file == null) "no file ${intent.getStringExtra("file")} in ${context.getExternalFilesDir(null)}" else "sending ${file.length()} bytes; see logcat -s Visor"
                if (file != null) scope.launch {
                    val started = System.currentTimeMillis()
                    val why = runCatching { live.overWifi { mux -> airship.send(mux, namespace, name, file) } }.getOrElse { it.toString() }
                    Log.i("Visor", "PUSH_WIFI: " + if (why == null) "sent $name in $namespace in ${(System.currentTimeMillis() - started) / 1000} s" else "failed: $why")
                }
            }
            "MEDIA" -> resultData = media.captures.value.joinToString("\n") { c ->
                "${c.handle} kind=${c.kindCode} ${c.takenAtMillis}${if (c.landscape) " landscape" else ""} " + c.assets.joinToString(" ") { "${it.id}:0x${it.typeCode.toString(16)}:${it.size}" }
            }.ifEmpty { "no captures listed; open Media once or wait for the list" }
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
                    connection.submit { com.macedonga.visor.core.media.requestAsset(it, capture.handle, id) }
                    resultData = "asked for $id"
                }
            }
            "PROMPTER" -> answerLater {
                if (intent.getBooleanExtra("close", false)) prompter.close()
                else prompter.show(
                    intent.getStringExtra("text") ?: "Testing the teleprompter from Visor.",
                    com.macedonga.visor.core.connection.PrompterLook(paged = intent.getBooleanExtra("paged", false), followVoice = intent.getBooleanExtra("voice", false)),
                )
                kotlinx.coroutines.delay(300)
                withTimeoutOrNull(8_000) {
                    prompter.state.first { it.phase != com.macedonga.visor.core.connection.PrompterPhase.STARTING && it.phase != com.macedonga.visor.core.connection.PrompterPhase.CLOSING }
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
                val body = intent.getStringExtra("hex").orEmpty().chunked(2).filter { it.length == 2 }.map { it.toInt(16).toByte() }.toByteArray()
                val heard = java.util.Collections.synchronizedList(mutableListOf<String>())
                val watch = scope.launch {
                    launch { connection.serviceData.collect { if (it.serviceId == svc) heard += "type ${it.messageType}: " + it.payload.joinToString("") { b -> "%02x".format(b) } } }
                    launch { connection.warnings.collect { if (it.contains("service $svc ")) heard += it } }
                }
                connection.submit { it.sendOnService(svc, intent.getIntExtra("type", 0), body) }
                kotlinx.coroutines.delay(5_000)
                watch.cancel()
                heard.joinToString("\n").ifEmpty { "nothing" }
            }
            // --es doc <client_doc_id> --es name <friendly name> --es vars <json>: one query on Meta's ar GraphQL, as the
            // user; the answer's first 4000 characters.
            "GRAPHQL" -> answerLater {
                val text = login.graphql(
                    intent.getStringExtra("name").orEmpty(),
                    intent.getStringExtra("doc").orEmpty(),
                    intent.getStringExtra("vars64")?.let { String(android.util.Base64.decode(it, 0)) } ?: intent.getStringExtra("vars") ?: "{}",
                )
                if (!intent.getBooleanExtra("shape", false)) text.take(4000) else {
                    // Every path with its type, a string's length and start: the structure without the bulk.
                    val out = StringBuilder()
                    fun walk(path: String, v: Any?) {
                        when (v) {
                            is org.json.JSONObject -> v.keys().forEach { walk("$path.$it", v.opt(it)) }
                            is org.json.JSONArray -> { out.append("$path [${v.length()}]\n"); if (v.length() > 0) walk("$path[0]", v.opt(0)) }
                            is String -> out.append("$path str(${v.length}) ${v.take(60).replace('\n', ' ')}\n")
                            else -> out.append("$path = $v\n")
                        }
                    }
                    walk("", org.json.JSONObject(text))
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
                kotlinx.coroutines.delay(500)
                withTimeoutOrNull(40_000) { screencast.state.first { it.phase != com.macedonga.visor.core.media.ScreencastPhase.STARTING } }
                screencast.state.value.toString()
            }
            // --es feature live_captions_en: Meta's pack installed through the glasses' OTA service.
            "CAPTION_PACK" -> answerLater {
                captionPacks.install(intent.getStringExtra("feature") ?: "live_captions_en")
                kotlinx.coroutines.delay(500)
                withTimeoutOrNull(600_000) { captionPacks.work.first { it !is com.macedonga.visor.display.PackWork.Working } }
                captionPacks.work.value.toString()
            }
            "CAPTIONS" -> answerLater {
                val self = intent.getStringExtra("lang") ?: "en"
                when {
                    intent.getBooleanExtra("stop", false) -> captions.stop()
                    intent.getBooleanExtra("show", false) -> Unit
                    else -> captions.start(
                        if (intent.hasExtra("other")) com.macedonga.visor.core.connection.CaptionsMode.TRANSLATION else com.macedonga.visor.core.connection.CaptionsMode.CAPTIONS,
                        self, intent.getStringExtra("other") ?: self,
                    )
                }
                kotlinx.coroutines.delay(300)
                withTimeoutOrNull(8_000) {
                    captions.state.first { it.phase == com.macedonga.visor.core.connection.CaptionsPhase.LIVE || it.phase == com.macedonga.visor.core.connection.CaptionsPhase.IDLE }
                }
                captions.state.value.let { s -> "${s.phase} ${s.mode} refusal=${s.refusal}\n" + s.lines.joinToString("\n") { "${if (it.done) "" else "… "}${it.text}${it.source?.let { src -> " ($src)" }.orEmpty()}" } }
            }
            "PHONE" -> answerLater {
                when (intent.getStringExtra("do")) {
                    "directions" -> phone.directions(intent.getStringExtra("to").orEmpty(), intent.getStringExtra("mode").orEmpty()).toString()
                    "play" -> phone.play(intent.getStringExtra("q").orEmpty(), intent.getStringExtra("app").orEmpty()).toString()
                    "media" -> phone.control(com.macedonga.visor.core.assistant.MediaControl.valueOf(intent.getStringExtra("c").orEmpty().uppercase())).toString()
                    else -> phone.nowPlaying().toString()
                }
            }
            "ASK" -> answerLater { withTimeoutOrNull(25_000) { assistant.ask(intent.getStringExtra("q") ?: "Say hello.") } ?: "no answer in 25 s" }
            "MCP_PROBE" -> answerLater {
                val auth = com.macedonga.visor.core.assistant.mcp.AuthKind.valueOf(intent.getStringExtra("auth") ?: "NONE")
                mcp.probe(intent.getStringExtra("url").orEmpty(), auth, intent.getStringExtra("secret").orEmpty(), intent.getStringExtra("header").orEmpty()).toString()
            }
            "MCP_ADD" -> {
                val server = com.macedonga.visor.core.assistant.mcp.McpServer(
                    name = intent.getStringExtra("name") ?: "Test",
                    url = intent.getStringExtra("url").orEmpty(),
                    auth = com.macedonga.visor.core.assistant.mcp.AuthKind.valueOf(intent.getStringExtra("auth") ?: "NONE"),
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
            "MCP_TOOLS" -> answerLater {
                val box = mcp.toolbox()
                box.tools.joinToString("\n") { "${it.name} (${it.tool.name}) readOnly=${it.tool.readOnly}" } + "\nPROMPT:" + box.prompt
            }
            "MCP_CALL" -> answerLater {
                val box = mcp.toolbox()
                val tool = box.find(intent.getStringExtra("tool").orEmpty()) ?: return@answerLater "no such tool; have ${box.tools.joinToString { it.name }}"
                val (result, action) = mcp.call(tool, org.json.JSONObject(intent.getStringExtra("args") ?: "{}"))
                "error=${result.isError} action=$action\n${result.text}"
            }
            "MCP_SIGNIN" -> answerLater {
                val id = intent.getStringExtra("id").orEmpty()
                mcp.signIn(id)
                withTimeoutOrNull(20_000) {
                    mcp.signIn.first { it.id == id && (it is com.macedonga.visor.core.assistant.mcp.SignIn.InBrowser || it is com.macedonga.visor.core.assistant.mcp.SignIn.Failed) }
                }.toString()
            }
            "MCP_COMPLETE" -> answerLater {
                val taken = mcp.complete(intent.getStringExtra("uri").orEmpty())
                if (!taken) return@answerLater "not a sign-in's redirect"
                withTimeoutOrNull(20_000) {
                    mcp.signIn.first { it is com.macedonga.visor.core.assistant.mcp.SignIn.Done || it is com.macedonga.visor.core.assistant.mcp.SignIn.Failed }
                }.toString()
            }
            // A lease that needs Wi-Fi, held and let go as the Display's would be.
            "WIFI_LINK" -> resultData = if (intent.getBooleanExtra("on", true)) {
                wifiLink.hold("debug").let { "held: " + (wifiLink.refusal() ?: "moving") }
            } else {
                wifiLink.release("debug").let { "released" }
            }
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
        val look = com.macedonga.visor.core.identity.language.GlassesLook("skyler", "Skyler", "shiny_black", "Shiny Black", "clear", "Clear", null)
        looks.demo(DEMO_SERIAL, look, DEMO_PICTURE)
        val seen = com.macedonga.visor.home.PairSeen(glasses = 64, case = 88, at = System.currentTimeMillis() - 2 * 3_600_000L, folded = true)
        pairs.demo(com.macedonga.visor.pairs.KnownPair(DEMO_SERIAL, "00:00:00:00:00:00"), seen)
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
    private fun pushed(context: Context, name: String?): java.io.File? {
        val dir = context.getExternalFilesDir(null)?.canonicalFile ?: return null
        val file = java.io.File(dir, name ?: return null).canonicalFile
        return file.takeIf { it.toPath().startsWith(dir.toPath()) && it.isFile }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val DEMO_SERIAL = "DEMO00SKYLER00"
        // Meta's own picture of a black Skyler, as its asset service hands it out.
        const val DEMO_PICTURE = "https://scontent.xx.fbcdn.net/mci_ab/uap/asset_manager/name_revision/?ab_b=e&ab_page=AssetManagerNameAndRevision" +
            "&ab_entry=RBM2-Skyler-ShinyBlack-Clear-Concord&set=mwa_device_image_assets&revision=1837680503843762&density=3&mode=dark"
    }
}
