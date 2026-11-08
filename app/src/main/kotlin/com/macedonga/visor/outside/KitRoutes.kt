@file:OptIn(KitInternal::class)

package com.macedonga.visor.outside

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.macedonga.visor.band.BandErrand
import com.macedonga.visor.band.BandLink
import com.macedonga.visor.band.BandRecord
import com.macedonga.visor.band.BandState
import com.macedonga.visor.band.Bands
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.CAMERA_VIDEO_STARTED
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesInput as GlassesTouch
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.HINGE_CLOSED
import com.macedonga.visor.core.connection.PrompterLook
import com.macedonga.visor.core.connection.PrompterPhase
import com.macedonga.visor.core.connection.SVC_STATUS
import com.macedonga.visor.core.connection.Shutter
import com.macedonga.visor.core.connection.Teleprompter
import com.macedonga.visor.core.connection.VideoKind as GlassesVideoKind
import com.macedonga.visor.core.connection.decodeInputs
import com.macedonga.visor.core.connection.isDeviceStateType
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.connection.parseBuild
import com.macedonga.visor.core.connection.productId
import com.macedonga.visor.core.identity.look.GlassesLook as MetaLook
import com.macedonga.visor.core.media.Capture as GlassesCapture
import com.macedonga.visor.core.media.CaptureType as GlassesCaptureType
import com.macedonga.visor.core.media.Kept
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.protocol.Dat
import com.macedonga.visor.core.protocol.ServiceId
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.dat.BriefingCards
import com.macedonga.visor.dat.ToolkitSession
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.home.PairSeen
import com.macedonga.visor.kit.KitException
import com.macedonga.visor.kit.protocol.BandStatus
import com.macedonga.visor.kit.protocol.Capture
import com.macedonga.visor.kit.protocol.CaptureFailure
import com.macedonga.visor.kit.protocol.CaptureProgress
import com.macedonga.visor.kit.protocol.CaptureType
import com.macedonga.visor.kit.protocol.CardClick
import com.macedonga.visor.kit.protocol.ErrorCode
import com.macedonga.visor.kit.protocol.Failure
import com.macedonga.visor.kit.protocol.Feeds
import com.macedonga.visor.kit.protocol.Finger
import com.macedonga.visor.kit.protocol.Gesture
import com.macedonga.visor.kit.protocol.GestureAction
import com.macedonga.visor.kit.protocol.GlassesInput
import com.macedonga.visor.kit.protocol.GlassesLook
import com.macedonga.visor.kit.protocol.GlassesPair
import com.macedonga.visor.kit.protocol.GlassesStatus
import com.macedonga.visor.kit.protocol.InputAction
import com.macedonga.visor.kit.protocol.InputSource
import com.macedonga.visor.kit.protocol.KitInternal
import com.macedonga.visor.kit.protocol.Link
import com.macedonga.visor.kit.protocol.Methods
import com.macedonga.visor.kit.protocol.MotionRequest
import com.macedonga.visor.kit.protocol.RawMessage
import com.macedonga.visor.kit.protocol.SpeakRequest
import com.macedonga.visor.kit.protocol.ThermalLevel
import com.macedonga.visor.kit.protocol.Topics
import com.macedonga.visor.kit.protocol.VideoKind
import com.macedonga.visor.kit.protocol.problem
import com.macedonga.visor.kit.server.Caller
import com.macedonga.visor.kit.server.Routes
import com.macedonga.visor.pairs.KnownPair
import com.macedonga.visor.pairs.Looks
import com.macedonga.visor.pairs.Pairs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** What the VDK's methods and topics do in Visor: each one is a thing Visor already does for its own screens. */
@Singleton
class KitRoutes @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val shutter: Shutter,
    private val media: MediaImport,
    private val teleprompter: Teleprompter,
    private val band: BandLink,
    private val bands: Bands,
    private val lastSeen: LastSeen,
    private val voices: KitVoices,
    private val tools: KitTools,
    private val notices: KitNotices,
    private val developer: DeveloperMode,
    private val pairs: Pairs,
    private val looks: Looks,
    private val live: LiveStream,
    private val kitLive: KitLive,
    private val transcripts: KitTranscripts,
    private val settings: KitSettings,
    private val conversations: KitConversations,
    private val toolkit: ToolkitSession,
    private val motion: GlassesMotion,
    private val briefing: BriefingCards,
) {
    fun routes(): Routes = Routes().apply {
        answer(Methods.GLASSES_STATUS) { _, _ -> glassesStatus(known()) }
        // Not before Visor has read its store: until then, no pair reads the same as none set up.
        stream(Topics.GLASSES_STATUS) { _, _ ->
            connection.state.filter { it.target != null || it.noGlasses }.map(::glassesStatus).distinctUntilChanged()
        }
        answer(Methods.GLASSES_PAIRS) { _, _ -> pairsNow() }
        stream(Topics.GLASSES_PAIRS) { _, _ ->
            val active = combine(connection.pair, connection.state.map { it.link }) { pair, link -> pair to link }
            combine(pairs.all, lastSeen.seen, looks.all, bands.all, active) { all, seen, looks, bands, (pair, link) ->
                all.filterNot { it.demo }.map { kitPair(it, pair, link, seen[it.serial], looks[it.serial], bands) }
            }.onStart { readPairs() }.distinctUntilChanged()
        }
        answer(Methods.GLASSES_SELECT) { _, selection -> select(selection.serial) }
        // Over Wi-Fi while the Live runs, over Bluetooth otherwise. On a Display the touchpad and the band come through
        // a toolkit session instead, which needs no toolkit app: the Display does not push their gestures on svc28.
        stream(Topics.GLASSES_INPUTS) { caller, request ->
            val pushed = merge(connection.serviceData, live.wifiData)
                .filter { it.serviceId == SVC_STATUS && isDeviceStateType(it.messageType) }
                .transform { push ->
                    val display = isDisplayBuild(connection.state.value.info?.build)
                    runCatching { decodeInputs(push.messageType, push.payload, display) }.getOrDefault(emptyList()).forEach { emit(kitInput(it)) }
                }
            val wanted = request.sources
            val session = isDisplayBuild(connection.state.value.info?.build) &&
                (wanted == null || InputSource.TOUCHPAD in wanted || InputSource.BAND in wanted)
            // While a layout shows, the toolkit app's navigation gives the swipes as nav, so the session's go.
            val gestures = toolkit.inputs(caller.packageName).mapNotNull(::sessionInput)
                .filter { !(toolkit.navigating && it.action in NAV_ACTIONS) }
            (if (session) merge(pushed, gestures, toolkit.navigation(request.keepBack).mapNotNull(::navigationInput)) else pushed)
                .filter { wanted?.contains(it.source) ?: true }
        }

        answer(Methods.CAMERA_PHOTO) { caller, request -> photo(caller, request.import) }
        answer(Methods.CAMERA_VIDEO_START) { _, request ->
            val kind = GlassesVideoKind.entries.firstOrNull { it.wire == request.kind.id }
                ?: throw KitException(ErrorCode.INVALID_PARAMS, "The glasses film ${VideoKind.VIDEO} or ${VideoKind.HYPERLAPSE}, not ${request.kind}.")
            ready()
            if (!shutter.video(kind)) throw KitException(ErrorCode.TIMEOUT, "The glasses did not start the video.")
        }
        answer(Methods.CAMERA_VIDEO_STOP) { _, _ ->
            ready()
            if (!shutter.stop()) throw KitException(ErrorCode.TIMEOUT, "The glasses did not stop the video.")
        }

        feed(Feeds.CAMERA_LIVE) { caller, config -> kitLive.watch(caller, config) }
        answer(Methods.CAMERA_LIVE_PHOTO) { caller, _ -> kitLive.photo(caller) }

        answer(Methods.CAPTURES_LIST) { caller, _ ->
            ready()
            media.captures.value.filter { it.isNew && it.isMedia }.map { kitCapture(caller, it) }
        }
        answer(Methods.CAPTURES_IMPORT) { caller, request -> import(caller, request.id) }
        stream(Topics.CAPTURES_AVAILABLE) { caller, _ -> available(caller) }
        stream(Topics.CAPTURES_PROGRESS) { _, _ -> progress() }
        stream(Topics.CAPTURES_IMPORTED) { caller, _ -> media.imported.mapNotNull { media.index.entries.value[it]?.let { kept -> kitCapture(caller, kept) } } }
        stream(Topics.CAPTURES_FAILED) { _, _ -> failures() }

        answer(Methods.BAND_STATUS) { _, _ -> bandStatus(band.state.value) }
        // The Display reports the band it holds in its device state, so that changes the answer too.
        stream(Topics.BAND_STATUS) { _, _ ->
            combine(connection.pair, bands.all, band.state, connection.state) { _, _, state, _ -> bandStatus(state) }.distinctUntilChanged()
        }
        stream(Topics.BAND_GESTURES) { caller, _ -> gestures(caller.packageName) }
        answer(Methods.BAND_BUZZ) { _, request ->
            requireBand()
            when (band.vibrate(request.durationMs.toLong(), request.strength)) {
                BandErrand.DONE, BandErrand.NOT_CHANGED -> Unit
                BandErrand.NO_ANSWER -> throw KitException(ErrorCode.NOT_CONNECTED, "The band did not come up in time.")
                BandErrand.REFUSED -> throw KitException(ErrorCode.REFUSED, "The band refused the buzz.")
            }
        }

        answer(Methods.DISPLAY_PROMPTER_SHOW) { _, request ->
            requireDisplay()
            val style = request.style
            teleprompter.show(request.text, PrompterLook(style.sizeSp, style.speed, style.paged, style.followVoice))
            val now = withTimeoutOrNull(ANSWER_MS) { teleprompter.state.first { it.phase != PrompterPhase.STARTING } }
                ?: throw KitException(ErrorCode.TIMEOUT, "The Display did not open the teleprompter.")
            now.refusal?.let { throw KitException(ErrorCode.REFUSED, "The Display refused the teleprompter (code $it).") }
        }
        answer(Methods.DISPLAY_PROMPTER_CLOSE) { _, _ ->
            requireDisplay()
            teleprompter.close()
        }

        stream(Topics.VOICE_REQUESTS) { caller, offer -> voices.offer(caller.packageName, offer.phrases) }
        stream(Topics.VOICE_TRANSCRIPTS) { caller, request -> transcripts.transcripts(caller, request) }
        // Motion comes from the glasses' data collection daemon, with nothing on their screen; layouts from Meta's
        // toolkit app, through a toolkit session of Visor's own.
        stream(Topics.GLASSES_MOTION) { _, request ->
            if (request.rateHz !in MotionRequest.RATES) {
                throw KitException(ErrorCode.INVALID_PARAMS, "The glasses sample at ${MotionRequest.RATES.joinToString()} Hz.")
            }
            motion.samples(request.rateHz)
        }
        answer(Methods.DISPLAY_LAYOUT_SHOW) { caller, layout -> requireDisplay(); toolkit.show(caller.packageName, layout) }
        answer(Methods.DISPLAY_LAYOUT_CLOSE) { caller, _ -> toolkit.close(caller.packageName) }
        answer(Methods.DISPLAY_CARDS_SET) { caller, card ->
            requireDisplay()
            card.problem()?.let { throw KitException(ErrorCode.INVALID_PARAMS, it) }
            briefing.set(caller.packageName, card.id, card.name, card.icon.id, card.layout, card.detail)
        }
        answer(Methods.DISPLAY_CARDS_REMOVE) { caller, removal -> briefing.remove(caller.packageName, removal.id) }
        stream(Topics.DISPLAY_LAYOUT_CLICKS) { caller, _ -> toolkit.clicks(caller.packageName) }
        stream(Topics.DISPLAY_CARDS_CLICKS) { caller, _ ->
            briefing.clicks.filter { it.card.app == caller.packageName }.map { CardClick(it.card.id, it.id, it.time) }
        }
        stream(Topics.DISPLAY_LAYOUT_STATE) { caller, _ -> toolkit.state(caller.packageName) }
        stream(Topics.DISPLAY_VIDEO) { caller, _ -> toolkit.video(caller.packageName) }
        answer(Methods.VOICE_REPLY) { caller, reply -> voices.reply(caller.packageName, reply) }
        answer(Methods.VOICE_SPEAK) { _, request ->
            val text = request.text.trim()
            if (text.isEmpty() || text.length > SpeakRequest.MAX_LENGTH) {
                throw KitException(ErrorCode.INVALID_PARAMS, "The text must hold 1 to ${SpeakRequest.MAX_LENGTH} characters.")
            }
            ready()
            motion.say(text)
        }
        stream(Topics.ASSISTANT_TOOLS) { caller, offer -> tools.offer(caller.packageName, offer.tools) }
        answer(Methods.ASSISTANT_TOOLS_ANSWER) { caller, answer -> tools.answer(caller.packageName, answer) }
        answer(Methods.ASSISTANT_HISTORY) { _, query -> conversations.history(query) }
        stream(Topics.ASSISTANT_TURNS) { _, _ -> conversations.turns() }
        answer(Methods.NOTIFY_POST) { caller, notice -> notices.post(caller.packageName, notice) }
        answer(Methods.NOTIFY_REMOVE) { caller, removal -> notices.remove(caller.packageName, removal.id) }
        answer(Methods.SETTINGS_LIST) { _, query -> settings.list(query) }
        answer(Methods.SETTINGS_GET) { _, request -> settings.get(request.key) }
        answer(Methods.SETTINGS_SET) { _, write -> settings.set(write.key, write.value) }
        stream(Topics.SETTINGS_CHANGES) { _, query -> settings.changes(query) }

        answer(Methods.RAW_SEND) { _, send ->
            requireRaw(send.service)
            ready()
            val payload = try {
                Base64.getDecoder().decode(send.payload)
            } catch (e: IllegalArgumentException) {
                throw KitException(ErrorCode.INVALID_PARAMS, "The payload is not Base64.")
            }
            connection.submit { it.sendOnService(send.service, send.type, payload) }
        }
        stream(Topics.RAW_MESSAGES) { _, filter ->
            requireRaw(filter.service)
            connection.serviceData.filter { it.serviceId == filter.service }.map {
                RawMessage(it.serviceId, it.messageType, Base64.getEncoder().encodeToString(it.payload), System.currentTimeMillis())
            }
        }
    }

    /** Raw access is for research: only while Developer mode is on, and never to the services Visor keeps to itself. */
    private fun requireRaw(service: Int) {
        if (!developer.on.value) throw KitException(ErrorCode.UNSUPPORTED, "Raw access needs Developer mode on in Visor.")
        if (service in KEPT_SERVICES) throw KitException(ErrorCode.REFUSED, "Visor keeps service $service to itself.")
    }

    /** The state once Visor knows whether it has glasses: just after a start, its store is still being read. */
    private suspend fun known(): GlassesState =
        withTimeoutOrNull(READ_MS) { connection.state.first { it.target != null || it.noGlasses } } ?: connection.state.value

    private fun glassesStatus(state: GlassesState): GlassesStatus {
        val target = state.target ?: return GlassesStatus(paired = false, link = Link.DISCONNECTED)
        val device = state.device
        val battery = device?.battery
        return GlassesStatus(
            paired = true,
            link = kitLink(state.link),
            model = state.info?.model?.ifEmpty { null },
            name = lastSeen.seen.value[target.serial]?.name,
            serial = target.serial,
            firmware = parseBuild(state.info?.build)?.firmware?.ifEmpty { null },
            display = (productId(state.info?.build) ?: lastSeen.product.value)?.let(::isDisplayProduct),
            battery = battery?.percent?.toInt(),
            charging = battery?.charging,
            worn = device?.worn,
            folded = device?.hinge?.let { it == HINGE_CLOSED },
            recording = device?.camera?.let { it == CAMERA_VIDEO_STARTED },
            caseBattery = device?.case?.takeIf { it.known && it.present }?.percent,
            thermal = device?.thermal?.health?.takeIf { it != "UNKNOWN" }?.let { ThermalLevel(it.lowercase()) },
            throttling = device?.thermal?.throttling,
        )
    }

    // Visor reads its pairs from the identity store when its home first asks; after a fresh start, the kit asks.
    private suspend fun readPairs() {
        if (pairs.stored.value != null) return
        pairs.refresh()
        withTimeoutOrNull(READ_MS) { pairs.stored.first { it != null } }
    }

    private suspend fun pairsNow(): List<GlassesPair> {
        readPairs()
        val pair = connection.pair.value
        val link = connection.state.value.link
        return pairs.all.value.filterNot { it.demo }.map {
            kitPair(it, pair, link, lastSeen.seen.value[it.serial], looks.all.value[it.serial], bands.all.value)
        }
    }

    private fun kitPair(pair: KnownPair, active: String?, link: LinkState, seen: PairSeen?, look: MetaLook?, bands: List<BandRecord>) = GlassesPair(
        serial = pair.serial,
        name = seen?.name,
        active = pair.serial == active,
        link = if (pair.serial == active) kitLink(link) else Link.DISCONNECTED,
        product = seen?.product,
        display = seen?.product?.let(::isDisplayProduct),
        look = look?.let { GlassesLook(it.style, it.styleName, it.color, it.colorName, it.lens, it.lensName, it.size) },
        battery = seen?.glasses,
        caseBattery = seen?.case,
        lastSeen = seen?.at?.takeIf { it > 0 },
        band = bands.firstOrNull { it.glasses == pair.serial }?.serial,
    )

    /** Makes [serial] the active pair, as the person's pick on the home does, and answers once Visor has switched. */
    private suspend fun select(serial: String): GlassesPair {
        readPairs()
        if (pairs.all.value.none { it.serial == serial && !it.demo }) {
            throw KitException(ErrorCode.INVALID_PARAMS, "Visor has no pair $serial set up.", reason = "not_found")
        }
        if (serial != connection.pair.value) {
            if (live.usingWifi || media.importing.value) throw KitException(ErrorCode.BUSY, "A Live view or an import holds the glasses.")
            pairs.connect(serial)
            withTimeoutOrNull(SWITCH_MS) { connection.pair.first { it == serial } }
                ?: throw KitException(ErrorCode.TIMEOUT, "Visor did not switch to $serial.")
        }
        return pairsNow().first { it.serial == serial }
    }

    private fun kitInput(input: GlassesTouch): GlassesInput {
        val source = when (input.source) {
            "captouch" -> InputSource.TOUCHPAD
            "capture_button" -> InputSource.CAPTURE_BUTTON
            else -> InputSource.ACTION_BUTTON
        }
        val action = when (input.action) {
            "SINGLE_TAP" -> InputAction.TAP
            "PRESENCE_TOUCHED" -> InputAction.TOUCH
            "PRESENCE_CLEAR", "SHORT_PRESS_UP" -> InputAction.RELEASE
            "SHORT_PRESS_DOWN" -> InputAction.PRESS
            "LONG_PRESS_UP" -> InputAction.LONG_RELEASE
            "VERY_VERY_LONG_PRESS_UP" -> InputAction("very_very_long_release")
            else -> InputAction(input.action.lowercase())
        }
        return GlassesInput(source, action, System.currentTimeMillis())
    }

    private fun kitLink(link: LinkState) = when (link) {
        LinkState.READY -> Link.CONNECTED
        LinkState.DISCONNECTED, LinkState.LOST -> Link.DISCONNECTED
        else -> Link.CONNECTING
    }

    /** Waits for the glasses when they are not connected, as Developer mode's intents do; not for long. */
    private suspend fun ready() {
        if (connection.state.value.link == LinkState.READY) return
        runCatching { GlassesService.reconnect(context) }
        withTimeoutOrNull(WAKE_MS) { connection.state.first { it.link == LinkState.READY } }
            ?: throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
    }

    private suspend fun photo(caller: Caller, import: Boolean): Capture {
        ready()
        if (connection.state.value.device?.camera == CAMERA_VIDEO_STARTED) throw KitException(ErrorCode.BUSY, "The glasses are recording a video.")
        val known = media.captures.value.mapTo(HashSet()) { it.handle }
        val taken = coroutineScope {
            // Watching before the shutter, so a quick photo is not missed.
            val listed = async(start = CoroutineStart.UNDISPATCHED) {
                media.captures.map { list -> list.firstOrNull { it.handle !in known && it.isMedia } }.filterNotNull().first()
            }
            if (!shutter.photo()) {
                listed.cancel()
                throw KitException(ErrorCode.TIMEOUT, "The glasses did not take the photo.")
            }
            withTimeoutOrNull(LIST_MS) { listed.await() } ?: run {
                listed.cancel()
                throw KitException(ErrorCode.TIMEOUT, "The photo did not show up on the glasses.")
            }
        }
        return if (import) import(caller, taken.handle) else kitCapture(caller, taken)
    }

    private suspend fun import(caller: Caller, id: String): Capture {
        val kept = media.index.entries.value[id]
        val listed = media.captures.value.firstOrNull { it.handle == id }
        if (kept != null && listed?.isNew != true) return kitCapture(caller, kept)
        ready()
        val capture = listed ?: run {
            // Just connected, or just taken: the list may not have it yet.
            media.refresh()
            withTimeoutOrNull(LIST_MS) { media.captures.map { list -> list.firstOrNull { it.handle == id } }.filterNotNull().first() }
        }
        if (capture == null || !capture.isMedia) throw KitException(ErrorCode.FAILED, "The glasses have no capture $id.", reason = "not_found")
        val done = withTimeoutOrNull(IMPORT_MS) {
            coroutineScope {
                val imported = async(start = CoroutineStart.UNDISPATCHED) { media.imported.first { it == id } }
                val failed = async(start = CoroutineStart.UNDISPATCHED) { media.problem.filterNotNull().first { id in it.handles } }
                media.import(listOf(capture))
                select {
                    imported.onAwait { null }
                    failed.onAwait { it }
                }.also {
                    imported.cancel()
                    failed.cancel()
                }
            }?.let { problem -> throw KitException(ErrorCode.FAILED, "The import of $id failed.", reason = problem.kind.name.lowercase()) }
            media.index.entries.value[id]
        } ?: throw KitException(ErrorCode.TIMEOUT, "The import of $id did not finish in time.")
        return kitCapture(caller, done)
    }

    private fun available(caller: Caller): Flow<Capture> = flow {
        val told = HashSet<String>()
        media.captures.collect { list ->
            for (capture in list) if (capture.isNew && capture.isMedia && told.add(capture.handle)) emit(kitCapture(caller, capture))
        }
    }

    private fun progress(): Flow<CaptureProgress> = flow {
        val shown = HashMap<String, Int>()
        media.transfers.collect { transfers ->
            for (t in transfers) {
                // The photo or the video itself; the pieces that come with it are small.
                val capture = media.captures.value.firstOrNull { it.main?.id == t.uuid } ?: continue
                val percent = if (t.size > 0) (t.received * 100 / t.size).toInt() else 0
                if (shown.put(capture.handle, percent) != percent) emit(CaptureProgress(capture.handle, t.received, t.size))
            }
        }
    }

    private fun failures(): Flow<CaptureFailure> = flow {
        val told = HashSet<String>()
        media.problem.collect { problem ->
            // Each import starts without a problem: what fails in it is new.
            if (problem == null) return@collect told.clear()
            for (handle in problem.handles) {
                if (!told.add(handle)) continue
                val failure = Failure(ErrorCode.FAILED, "The import of $handle failed.", reason = problem.kind.name.lowercase())
                emit(CaptureFailure(handle, failure))
            }
        }
    }

    private fun kitCapture(caller: Caller, capture: GlassesCapture): Capture =
        media.index.entries.value[capture.handle]?.takeUnless { capture.isNew }?.let { kitCapture(caller, it) }
            ?: Capture(capture.handle, kitType(capture.type), capture.takenAtMillis, capture.main?.size)

    /** A capture in the gallery, which the caller may now read without a media permission. */
    private fun kitCapture(caller: Caller, kept: Kept): Capture {
        if (!caller.shell) {
            try {
                context.grantUriPermission(caller.packageName, Uri.parse(kept.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // The app then needs its own permission to read photos and videos.
            }
        }
        return Capture(kept.handle, kitType(kept.type), kept.takenAt, uri = kept.uri)
    }

    private fun kitType(type: GlassesCaptureType) = when (type) {
        GlassesCaptureType.PHOTO -> CaptureType.PHOTO
        GlassesCaptureType.LIVE -> CaptureType.LIVE
        GlassesCaptureType.VIDEO -> CaptureType.VIDEO
        GlassesCaptureType.OTHER -> CaptureType("other")
    }

    private fun paired(): Boolean = bands.forGlasses(connection.pair.value) != null

    private fun requireBand() {
        if (paired()) return
        if (connection.state.value.device?.bandBattery != null) {
            throw KitException(ErrorCode.UNSUPPORTED, "Only the Display holds this Neural Band. Set it up in Visor's band settings.", reason = "not_set_up")
        }
        throw KitException(ErrorCode.UNSUPPORTED, "No Neural Band is paired with these glasses.")
    }

    private fun requireDisplay() {
        val product = productId(connection.state.value.info?.build) ?: lastSeen.product.value
        if (product != null && !isDisplayProduct(product)) throw KitException(ErrorCode.UNSUPPORTED, "These glasses have no display.")
        if (connection.state.value.link != LinkState.READY) throw KitException(ErrorCode.NOT_CONNECTED, "The glasses are not connected.")
    }

    /** The band Visor holds, or else the one the Display reports it holds, with the battery it reports. */
    private fun bandStatus(state: BandState): BandStatus {
        val held = paired()
        val device = connection.state.value.device
        if (!held && device?.bandBattery == null) return BandStatus(paired = false, link = Link.DISCONNECTED)
        return BandStatus(
            paired = true,
            link = if (!held) {
                Link.DISCONNECTED
            } else {
                when (state.link) {
                    LinkState.READY -> Link.CONNECTED
                    LinkState.DISCONNECTED, LinkState.LOST -> Link.DISCONNECTED
                    else -> Link.CONNECTING
                }
            },
            battery = state.battery.takeIf { held } ?: device?.bandBattery,
            charging = state.charging.takeIf { held } ?: device?.bandCharging,
            leftHanded = state.leftHanded.takeIf { held },
            borrowed = held && state.gestures,
            held = held,
        )
    }

    /** The band's gestures while collected: the band is borrowed from the Display for as long as that lasts. */
    // The band stays on the Display: its gestures come forwarded through a toolkit session, which needs no toolkit app.
    private fun gestures(app: String): Flow<Gesture> = flow {
        // Any band the Display holds will do: the session needs none of the band's keys.
        if (!paired() && connection.state.value.device?.bandBattery == null) {
            throw KitException(ErrorCode.UNSUPPORTED, "No Neural Band is paired with these glasses.")
        }
        emitAll(toolkit.inputs(app).filter { it.source == Dat.INPUT_EMG }.map(::kitGesture))
    }

    // A touchpad or band gesture forwarded by a toolkit session, as DAT names them.
    private fun sessionInput(input: Dat.SessionInput): GlassesInput? = GlassesInput(
        source = when (input.source) {
            Dat.INPUT_CAPTOUCH -> InputSource.TOUCHPAD
            Dat.INPUT_EMG -> InputSource.BAND
            else -> return null
        },
        action = SESSION_INPUTS[input.gesture] ?: InputAction("unknown"),
        time = System.currentTimeMillis(),
    )

    // The toolkit app's navigation, as DAT gives it.
    private fun navigationInput(n: Dat.Navigation): GlassesInput? {
        val source = when (n.source) {
            Dat.NAV_TOUCHPAD -> InputSource.TOUCHPAD
            Dat.NAV_BAND, Dat.NAV_BAND_DRAG -> InputSource.BAND
            else -> return null
        }
        val action = when (n.kind) {
            Dat.NAV -> NAV_DIRECTIONS[n.direction]
            Dat.SELECT -> InputAction.SELECT
            Dat.BACK -> InputAction.BACK
            Dat.DRAG -> DRAG_ACTIONS[n.dragAction]
            else -> null
        } ?: return null
        val drag = n.kind == Dat.DRAG
        return GlassesInput(
            source = source,
            action = action,
            time = System.currentTimeMillis(),
            x = n.x.toDouble().takeIf { drag },
            y = n.y.toDouble().takeIf { drag },
            dx = n.dx.toDouble().takeIf { drag },
            dy = n.dy.toDouble().takeIf { drag },
        )
    }

    private fun kitGesture(input: Dat.SessionInput) = Gesture(
        finger = when (input.actuator) {
            3 -> Finger.THUMB
            4 -> Finger.INDEX
            5 -> Finger.MIDDLE
            else -> Finger("unknown")
        },
        action = SESSION_GESTURES[input.gesture] ?: GestureAction.UNKNOWN,
        // The glasses forward the gesture as the band made it out: an app reading [Gesture.derived] gets it too.
        derived = SESSION_GESTURES[input.gesture] ?: GestureAction.UNKNOWN,
        time = System.currentTimeMillis(),
    )

    private companion object {
        // A raw message there can lock the pair out: identity and ownership (36), the link's authentication (54
        // AirShield, 79 constellation), and firmware updates (29).
        val KEPT_SERVICES = setOf(ServiceId.IDENTITY.value, 54, 79, 29)
        const val WAKE_MS = 20_000L
        const val SWITCH_MS = 5_000L
        const val READ_MS = 3_000L
        const val ANSWER_MS = 8_000L
        const val LIST_MS = 15_000L
        const val IMPORT_MS = 10 * 60_000L

        // EmgImu$GestureEvent's action and derived action, as Band.Gesture documents them.
        val SESSION_INPUTS = mapOf(
            1 to InputAction.TAP, 2 to InputAction.DOUBLE_TAP, 3 to InputAction.TRIPLE_TAP, 4 to InputAction.TAP_HOLD,
            5 to InputAction.NAV_UP, 6 to InputAction.NAV_DOWN, 7 to InputAction.NAV_LEFT, 8 to InputAction.NAV_RIGHT,
        )
        val NAV_ACTIONS = setOf(InputAction.NAV_UP, InputAction.NAV_DOWN, InputAction.NAV_LEFT, InputAction.NAV_RIGHT)
        val NAV_DIRECTIONS = mapOf(1 to InputAction.NAV_UP, 2 to InputAction.NAV_DOWN, 3 to InputAction.NAV_LEFT, 4 to InputAction.NAV_RIGHT)
        val DRAG_ACTIONS = mapOf(1 to InputAction.DRAG_START, 2 to InputAction.DRAG_MOVE, 3 to InputAction.DRAG_END)
        val SESSION_GESTURES = mapOf(
            1 to GestureAction.TAP, 2 to GestureAction.DOUBLE_TAP, 3 to GestureAction("triple_tap"), 4 to GestureAction.HOLD,
            5 to GestureAction.UP, 6 to GestureAction.DOWN, 7 to GestureAction.LEFT, 8 to GestureAction.RIGHT,
        )
    }
}
