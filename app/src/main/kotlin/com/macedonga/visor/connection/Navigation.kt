package com.macedonga.visor.connection

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.settings.hasDisplay
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * svc42, smartglasses_navigation, on the Display. The phone opens the channel and sends NavInitChannelRequest (type 0,
 * {1 platform, Android 1}); the glasses answer (type 1), then both sides send NavMessage (type 2) {1 msgType, and a
 * oneof whose case is the field number}. The glasses ask for a route (ROUTE_REQUEST, 0) and for guidance (NAV_REQUEST,
 * 2); Visor answers with ROUTE_RESPONSE (1), then NAV_UPDATE (3) each second with the phone's snapped position, a
 * MANEUVER_NOTIFICATION (8) per turn, REROUTE_UPDATE (7) after a re-route, and KEEPALIVE (17) on a timer. The route
 * is OpenStreetMap's (see [OsrmRouter]); its line goes out as that router's encoded polyline. The map tiles are the
 * glasses' own.
 */
@Singleton
class Navigation @Inject constructor(
    private val glasses: Provider<GlassesConnection>,
    private val position: PhonePosition,
    private val router: OsrmRouter,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.get().add("Navigation: failed: $e") })

    @Volatile
    private var session: Session? = null

    /** Opens the channel once a Display is linked, and drops the session when the link goes. */
    fun start(owner: CoroutineScope) {
        owner.launch {
            glasses.get().state.map { it.link == LinkState.READY && hasDisplay(it.info?.build) }.distinctUntilChanged().collect { ready ->
                if (ready) {
                    glasses.get().submit { it.sendOnService(SVC, MT_INIT_REQUEST, Wire.fieldVarint(1, PLATFORM_ANDROID)) }
                } else {
                    endSession()
                }
            }
        }
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return endSession()
        if (message.info.messageType != MT_NAV_MESSAGE) return
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        // The channel is Visor's own: the glasses answer on it without the originator bit, and Visor writes it with.
        val s = session ?: Session(connection, message.info.channelId xor 0x8000).also { session = it }
        when (f.varint(1).toInt()) {
            MSG_ROUTE_REQUEST -> scope.launch { routeRequest(f, s) }
            MSG_NAV_REQUEST -> startGuidance(s)
            MSG_STOP_NAVIGATION -> stopGuidance(s, "stopped")
            MSG_APP_START -> keepAlive(s)
            MSG_APP_EXIT, MSG_APP_DIED -> endSession()
        }
    }

    private suspend fun routeRequest(f: Wire.Fields, s: Session) {
        val req = Wire.parse(f.bytes(2))
        val requestId = req.bytes(2)
        val place = Wire.parse(req.bytes(1))
        val geo = Wire.parse(place.bytes(4))
        val title = place.bytes(1).decodeToString().ifBlank { "place" }
        val to = if (geo.has(1)) LatLng(geo.double(1), geo.double(2)) else null
        val logId = requests.start("route://$title", SOURCE_MAPS, "walking")
        if (s.guiding) return answerRoute(s, requestId, null, "Error: Navigation in progress.", logId)
        val from = position.current()
        if (to == null || from == null) return answerRoute(s, requestId, null, "No phone location or destination", logId)
        val route = try {
            router.route(from, to)
        } catch (e: Exception) {
            return answerRoute(s, requestId, null, "No route found", logId, e.message)
        }
        s.route = route
        s.destination = to
        s.title = title
        s.spot = Spot(route.line[0], 0, 0.0)
        answerRoute(s, requestId, route, null, logId)
    }

    private suspend fun answerRoute(s: Session, requestId: ByteArray, route: Route?, error: String?, logId: Long, detail: String? = null) {
        val body = if (route != null) {
            Wire.fieldVarint(1, RESULT_SUCCESS) + Wire.fieldVarint(2, SOURCE_GLASS) + Wire.fieldBytes(3, routeDetails(route)) + Wire.fieldBytes(6, requestId)
        } else {
            Wire.fieldVarint(1, RESULT_ERROR) + Wire.fieldVarint(2, SOURCE_MWA) + Wire.fieldBytes(4, (error ?: "").encodeToByteArray()) + Wire.fieldBytes(6, requestId)
        }
        s.mux.send(s.reply, nav(MSG_ROUTE_RESPONSE, CASE_ROUTE_RESPONSE, body), MT_NAV_MESSAGE)
        if (route != null) {
            requests.finish(logId, "${route.steps.size} steps, ${(route.metres / 100).roundToLong() / 10.0} km", failed = false)
            log.get().add("Navigation: route to ${s.title}, ${route.steps.size} maneuvers")
        } else {
            requests.finish(logId, detail ?: error.orEmpty(), failed = true)
            log.get().add("Navigation: no route: ${error.orEmpty()}")
        }
    }

    private suspend fun startGuidance(s: Session) {
        val route = s.route ?: return log.get().add("Navigation: guidance asked with no route")
        if (s.guiding) return
        s.guiding = true
        s.notified = -1
        s.offTicks = 0
        s.logId = requests.start("guide://${s.title}", SOURCE_MAPS, "walking")
        position.use()
        s.spot = Spot(route.line[0], 0, 0.0)
        sendUpdate(s, route, STATE_STARTED, NAVIGATOR_ON_ROUTE, 0)
        s.ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                if (!tick(s)) break
            }
        }
    }

    /** One second of guidance. Returns false once guidance has ended. */
    private suspend fun tick(s: Session): Boolean {
        if (!s.guiding) return false
        val route = s.route ?: return false
        val here = position.current()
        if (here == null) {
            sendUpdate(s, route, STATE_IN_PROGRESS, NAVIGATOR_BAD_GPS, max(s.notified, 0))
            return true
        }
        val snap = route.snap(here)
        val offRoute = snap.off > OFF_ROUTE_M
        s.offTicks = if (offRoute) s.offTicks + 1 else 0
        if (s.offTicks >= REROUTE_TICKS) {
            reroute(s, here)
            return s.guiding
        }
        s.spot = Spot(snap.at, snap.index, snap.along)
        if (route.metres - snap.along < ARRIVE_M) {
            sendUpdate(s, route, STATE_ARRIVED, NAVIGATOR_STOPPED, route.steps.lastIndex)
            finish(s, "arrived")
            return false
        }
        val steps = route.steps
        val next = steps.indexOfFirst { snap.along < it.along + PASSED_M }.let { if (it < 0) steps.lastIndex else it }
        sendUpdate(s, route, STATE_IN_PROGRESS, if (offRoute) NAVIGATOR_OFF_ROUTE else NAVIGATOR_ON_ROUTE, next)
        if (next != s.notified) {
            s.notified = next
            s.mux.send(s.reply, nav(MSG_MANEUVER_NOTIFICATION, CASE_MANEUVER, notification(steps[next])), MT_NAV_MESSAGE)
        }
        return true
    }

    private suspend fun reroute(s: Session, here: LatLng) {
        val to = s.destination ?: return
        s.offTicks = 0
        sendUpdate(s, s.route ?: return, STATE_IN_PROGRESS, NAVIGATOR_REROUTING, max(s.notified, 0))
        val fresh = try {
            router.route(here, to)
        } catch (e: Exception) {
            log.get().add("Navigation: reroute failed: ${e.message}")
            s.mux.send(s.reply, nav(MSG_REROUTE_UPDATE, CASE_REROUTE, Wire.fieldVarint(1, RESULT_ERROR)), MT_NAV_MESSAGE)
            return
        }
        s.route = fresh
        s.spot = Spot(fresh.line[0], 0, 0.0)
        s.notified = -1
        s.mux.send(s.reply, nav(MSG_REROUTE_UPDATE, CASE_REROUTE, Wire.fieldVarint(1, RESULT_SUCCESS) + Wire.fieldBytes(2, routeDetails(fresh))), MT_NAV_MESSAGE)
        log.get().add("Navigation: rerouted, ${fresh.steps.size} maneuvers")
    }

    private suspend fun stopGuidance(s: Session, outcome: String) {
        if (!s.guiding) return
        s.ticker?.cancel()
        s.ticker = null
        s.route?.let { sendUpdate(s, it, STATE_STOPPED, NAVIGATOR_STOPPED, max(s.notified, 0)) }
        finish(s, outcome)
    }

    /** Ends guidance's bookkeeping. Only called while guidance runs. */
    private fun finish(s: Session, outcome: String) {
        s.guiding = false
        position.release()
        requests.finish(s.logId, outcome, failed = false)
        log.get().add("Navigation: $outcome")
    }

    private fun keepAlive(s: Session) {
        s.keepAlive?.cancel()
        s.keepAlive = scope.launch {
            while (true) {
                s.mux.send(s.reply, nav(MSG_KEEPALIVE), MT_NAV_MESSAGE)
                delay(KEEPALIVE_MS)
            }
        }
    }

    private suspend fun endSession() {
        val s = session ?: return
        session = null
        s.keepAlive?.cancel()
        runCatching { stopGuidance(s, "ended by the link") }
    }

    /** NAV_UPDATE for the spot, with the next maneuver's distance and time, as the glasses' guidance needs them. */
    private suspend fun sendUpdate(s: Session, route: Route, state: Long, navigator: Long, maneuver: Int) {
        val spot = s.spot
        val remaining = max(0.0, route.metres - spot.along)
        val next = route.steps.getOrNull(maneuver)
        val toNext = max(0.0, (next?.along ?: route.metres) - spot.along)
        val perMetre = route.secondsPerMetre
        val body = Wire.fieldVarint(1, state) +
            Wire.fieldBytes(2, Wire.fieldDouble(1, spot.at.lat) + Wire.fieldDouble(2, spot.at.lng)) +
            Wire.fieldVarint(3, spot.index.toLong()) +
            Wire.fieldVarint(4, maneuver.toLong()) +
            Wire.fieldVarint(5, (remaining * perMetre).roundToLong()) +
            Wire.fieldVarint(6, remaining.roundToLong()) +
            Wire.fieldVarint(7, (toNext * perMetre).roundToLong()) +
            Wire.fieldVarint(8, toNext.roundToLong()) +
            Wire.fieldBytes(10, (next?.text ?: "").encodeToByteArray()) +
            Wire.fieldVarint(13, navigator)
        s.mux.send(s.reply, nav(MSG_NAV_UPDATE, CASE_NAV_UPDATE, body), MT_NAV_MESSAGE)
    }

    private fun routeDetails(r: Route): ByteArray {
        var b = Wire.fieldVarint(1, ROUTE_ID) + Wire.fieldVarint(2, r.seconds.roundToLong()) + Wire.fieldVarint(3, r.metres.roundToLong())
        for (step in r.steps) b += Wire.fieldBytes(4, maneuver(step))
        return b + Wire.fieldBytes(5, r.encoded.encodeToByteArray())
    }

    private fun maneuver(step: Step): ByteArray =
        Wire.fieldVarint(1, step.direction.toLong()) +
            Wire.fieldBytes(2, step.street.encodeToByteArray()) +
            Wire.fieldVarint(3, step.metres.toLong()) +
            Wire.fieldVarint(4, step.seconds.toLong()) +
            Wire.fieldBytes(5, step.text.encodeToByteArray())

    private fun notification(step: Step): ByteArray =
        Wire.fieldBytes(1, maneuver(step)) + Wire.fieldBytes(2, "HNNAV-${UUID.randomUUID().toString().take(8)}".encodeToByteArray())

    private fun nav(type: Int, case: Int = 0, body: ByteArray = ByteArray(0)): ByteArray =
        Wire.fieldVarint(1, type.toLong()) + if (case == 0) ByteArray(0) else Wire.fieldBytes(case, body)

    private class Spot(val at: LatLng, val index: Int, val along: Double)

    /** The channel's state: where replies go, the route and the guidance running on it. */
    private class Session(val mux: DataXConnection, val reply: Int) {
        @Volatile var route: Route? = null
        @Volatile var destination: LatLng? = null
        @Volatile var title = ""
        @Volatile var guiding = false
        @Volatile var logId = 0L
        @Volatile var spot = Spot(LatLng(0.0, 0.0), 0, 0.0)
        @Volatile var notified = -1
        @Volatile var offTicks = 0
        var ticker: Job? = null
        var keepAlive: Job? = null
    }

    companion object {
        const val SVC = 42
        private const val SOURCE_MAPS = -6
        private const val MT_INIT_REQUEST = 0
        private const val MT_NAV_MESSAGE = 2
        private const val PLATFORM_ANDROID = 1L

        private const val MSG_ROUTE_REQUEST = 0
        private const val MSG_ROUTE_RESPONSE = 1
        private const val MSG_NAV_REQUEST = 2
        private const val MSG_NAV_UPDATE = 3
        private const val MSG_STOP_NAVIGATION = 6
        private const val MSG_REROUTE_UPDATE = 7
        private const val MSG_MANEUVER_NOTIFICATION = 8
        private const val MSG_APP_START = 13
        private const val MSG_APP_EXIT = 14
        private const val MSG_APP_DIED = 15
        private const val MSG_KEEPALIVE = 17

        private const val CASE_ROUTE_RESPONSE = 3
        private const val CASE_NAV_UPDATE = 5
        private const val CASE_REROUTE = 7
        private const val CASE_MANEUVER = 8

        private const val RESULT_SUCCESS = 0L
        private const val RESULT_ERROR = 1L
        private const val SOURCE_GLASS = 0L
        private const val SOURCE_MWA = 1L
        private const val ROUTE_ID = 1L

        private const val STATE_STARTED = 0L
        private const val STATE_IN_PROGRESS = 1L
        private const val STATE_STOPPED = 2L
        private const val STATE_ARRIVED = 3L

        private const val NAVIGATOR_ON_ROUTE = 1L
        private const val NAVIGATOR_OFF_ROUTE = 2L
        private const val NAVIGATOR_BAD_GPS = 3L
        private const val NAVIGATOR_REROUTING = 4L
        private const val NAVIGATOR_STOPPED = 5L

        /** The Meta app's keepalive period was not found; this is a guess. */
        private const val KEEPALIVE_MS = 5_000L
        private const val TICK_MS = 1_000L
        private const val OFF_ROUTE_M = 50.0
        private const val REROUTE_TICKS = 10
        private const val PASSED_M = 15.0
        private const val ARRIVE_M = 25.0
    }
}
