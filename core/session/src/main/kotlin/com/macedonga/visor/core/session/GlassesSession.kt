package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.EndLinkSetup
import com.macedonga.visor.core.protocol.LinkSetupMsgType
import com.macedonga.visor.core.protocol.LinkSwitch
import com.macedonga.visor.core.protocol.StreamCommand
import com.macedonga.visor.core.protocol.uuidText
import com.macedonga.visor.core.transport.Transport
import com.macedonga.visor.core.transport.TransportConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Work for the session to do on the link. Runs on the session's own coroutine. */
fun interface SessionCommand {
    suspend fun run(session: GlassesSession)
}

/** Runs for every message of one service, before the message is published. */
fun interface Responder {
    suspend fun respond(message: DataXMessage, connection: DataXConnection)

    /** The peer closed [channelId], a channel of this service. A close frame names no service, so it reaches only this. */
    suspend fun closed(channelId: Int, connection: DataXConnection) {}
}

/** How a link switch went: [ok], and what happened, for the event log. */
class LinkMove(val ok: Boolean, val detail: String)

/** A bounded wait on the link ran out. See [withLinkDeadline]. */
class LinkTimeoutException(what: String) : Exception(what)

/** Run [body], closing [transport] if it waits on the peer longer than [millis]. */
suspend fun <T> withLinkDeadline(
    transport: Transport,
    millis: Long,
    what: String,
    body: suspend () -> T,
): T = coroutineScope {
    // One atomic decides which of the two finished first.
    val settled = java.util.concurrent.atomic.AtomicBoolean(false)
    val watchdog = launch {
        delay(millis)
        if (settled.compareAndSet(false, true)) transport.close()
    }
    try {
        val result = body()
        // Claim, so a watchdog that has not fired yet cannot close a link that is up.
        settled.compareAndSet(false, true)
        result
    } catch (error: Throwable) {
        // A failed claim means the watchdog got there first, so this failure IS the deadline.
        if (error !is CancellationException && !settled.compareAndSet(false, true)) {
            throw LinkTimeoutException(what).apply { initCause(error) }
        }
        throw error
    } finally {
        watchdog.cancel()
    }
}

/** The one object that owns the link. */
class GlassesSession(
    private val transport: Transport,
    private val trust: TrustExchange? = null,
) {

    // An unlimited channel rather than a SharedFlow.
    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val commandChannel = Channel<SessionCommand>(Channel.UNLIMITED)

    // Read from the reader coroutine.
    private val responders = java.util.concurrent.ConcurrentHashMap<Int, Responder>()
    private val stopSignal = CompletableDeferred<Unit>()

    @Volatile
    private var stopped = false

    private var supervisor: Job? = null
    private var secureLink: SecureLink? = null
    private var registry = ChannelRegistry()

    /** Everything that happens on the link. One consumer, unbounded, drops nothing. */
    val events: Flow<SessionEvent> = eventChannel.receiveAsFlow()

    /** The live channel mux, rebuilt on every connect. */
    @Volatile
    var connection: DataXConnection? = null
        private set

    @Volatile
    var trustResult: TrustResult? = null
        private set

    /** The handshake line of the current attempt, once its link is up. */
    val handshake: String? get() = secureLink?.handshake

    // The attempt's scope, which the link pumps run in, and the main link's pump.
    @Volatile
    private var attempt: CoroutineScope? = null

    @Volatile
    private var mainFrames: LinkFrames? = null

    // A link brought up beside the main one by [addLink].
    private class Extra(val transport: Transport, val pump: Job)

    @Volatile
    private var extra: Extra? = null

    /** True while the traffic runs on the link [addLink] brought up. */
    @Volatile
    var onExtraLink = false
        private set

    /** Start connecting. Returns at once; the work happens on [scope]. */
    fun start(scope: CoroutineScope) {
        supervisor = scope.launch { supervise() }
    }

    /** Ask the session to finish, and wait until it lets go of the socket. */
    suspend fun stop() {
        stopped = true
        stopSignal.complete(Unit)
        extra?.transport?.close()
        transport.close()
        supervisor?.join()
        supervisor = null
        // Nothing more will be published, so let a collector finish rather than suspend on
        // a channel that will never speak again.
        eventChannel.close()
    }

    /** Queue work for the session's coroutine. */
    fun submit(command: SessionCommand) {
        commandChannel.trySend(command)
    }

    /** Run [responder] for every message of [serviceId], before the message is published. */
    fun addResponder(serviceId: Int, responder: Responder) {
        responders[serviceId] = responder
    }

    private fun publish(event: SessionEvent) {
        eventChannel.trySend(event)
    }

    /** Connect, then keep reconnecting with backoff until [stop]. */
    private suspend fun supervise() {
        var attempt = 0
        while (true) {
            if (runOnce()) attempt = 0 // a link that reached READY restarts the backoff
            if (stopped) return
            val wait = BACKOFF_MILLIS[minOf(attempt, BACKOFF_MILLIS.size - 1)]
            withTimeoutOrNull(wait) { stopSignal.await() }
            if (stopped) return
            attempt += 1
        }
    }

    /** One connection attempt. */
    private suspend fun runOnce(): Boolean {
        var reachedReady = false
        // Per attempt: a verdict read after this one fails must not see the last one's exchange.
        trustResult = null
        publish(LinkStateChanged(LinkState.CONNECTING))
        try {
            transport.connect()
            publish(LinkStateChanged(LinkState.LINK_SETUP))

            // Each phase has its own deadline.
            val link = SecureLink(transport)
            secureLink = link
            withLinkDeadline(transport, LINK_SETUP_READ_TIMEOUT_MILLIS, "the glasses stopped answering during link setup") {
                link.open()
            }

            registry = ChannelRegistry()
            val frames = LinkFrames(link)
            val mux = DataXConnection(link, registry, frames)
            connection = mux
            mainFrames = frames

            // One reader from here to the end of the attempt, and it is the same one `pump`
            // consumes at READY.
            coroutineScope {
                attempt = this
                val framePump = frames.start(this)
                val reader = mux.startReader(this)
                try {
                    val exchange = trust
                    if (exchange != null) {
                        publish(LinkStateChanged(LinkState.TRUST))
                        val result = exchange.run(mux, checkNotNull(link.binding))
                        trustResult = result
                        if (!result.complete) {
                            // Not fatal here, because the glasses decide and they answer the next
                            // step.
                            publish(ProtocolError(trustFault(result)))
                        }
                    }

                    link.finishLinkSetup()
                    answerTheirEndLinkSetup(mux, transport, registry)
                    settleChannels(mux)

                    publish(LinkStateChanged(LinkState.READY))
                    reachedReady = true
                    pump()
                    publish(LinkStateChanged(LinkState.DISCONNECTED))
                } finally {
                    // coroutineScope waits for its children and these never end by themselves, so
                    // cancelling them is what lets the attempt finish.
                    reader.cancel()
                    framePump.cancel()
                    dropExtra()
                    attempt = null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (stopped) {
                // stop() closed the socket underneath the reader. This end finishing is not
                // the link failing.
                publish(LinkStateChanged(LinkState.DISCONNECTED))
            } else {
                val reason = reason(error)
                publish(ProtocolError(reason))
                if (error is TransportConnectException) publish(ConnectFailed(error.failure))
                publish(LinkStateChanged(LinkState.LOST, reason))
            }
        } finally {
            secureLink = null
            connection = null
            transport.close()
        }
        return reachedReady
    }

    /**
     * Wait for the glasses' own EndLinkSetup, acknowledge it on the mirror when [ack], and return it. A link kept ready
     * gets no acknowledgement: the glasses drop its setup cipher at once, and the frame would wait on the link until the
     * session moves there, then fail under the session's cipher.
     */
    private suspend fun answerTheirEndLinkSetup(mux: DataXConnection, on: Transport, channels: ChannelRegistry, ack: Boolean = true): DataXMessage =
        withLinkDeadline(on, END_LINK_SETUP_TIMEOUT_MILLIS, "the glasses did not send their EndLinkSetup") {
            while (true) {
                val message = mux.receive()
                if (isTheirEndLinkSetup(message, channels)) {
                    if (ack) mux.sendStatus(DataXConnection.mirror(message.info.channelId), SUCCESS)
                    return@withLinkDeadline message
                }
                if (mux === connection) handleMessage(message)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    // The glasses answer on the link setup channel our RequestEncryption opened; the Neural Band on one of its own,
    // 0x9001, that names no service.
    private fun isTheirEndLinkSetup(message: DataXMessage, channels: ChannelRegistry): Boolean {
        val info = message.info
        if (info.messageType != LinkSetupMsgType.END_LINK_SETUP.value || info.flags.hasError) return false
        return info.channelId == LINK_SETUP_CHANNEL ||
            (DataXConnection.hasOriginatorBit(info.channelId) && info.serviceId == 0 && channels.serviceForInbound(info.channelId) == 0)
    }

    /**
     * Bring [next] up beside the session's link, kept ready, then move the traffic onto it with svc10
     * SwitchTransportLink, as Meta's link manager does: every channel stays open. Fails with [next] closed; a move half
     * done ends the attempt.
     */
    suspend fun addLink(next: Transport, nextTrust: TrustExchange?): LinkMove {
        val mux = connection ?: return LinkMove(false, "the session is not up")
        val scope = attempt ?: return LinkMove(false, "the session is not up")
        if (extra != null) return LinkMove(false, "a second link is already up")
        var pump: Job? = null
        try {
            next.connect()
            val link = SecureLink(next)
            withLinkDeadline(next, LINK_SETUP_READ_TIMEOUT_MILLIS, "the glasses stopped answering on the new link") { link.open() }
            val frames = LinkFrames(link)
            pump = frames.start(scope)
            // The new link's own trust and EndLinkSetup run on a mux of its own, before it joins the session.
            val setupChannels = ChannelRegistry()
            val setup = DataXConnection(link, setupChannels, frames)
            val theirs = coroutineScope {
                val reader = setup.startReader(this)
                try {
                    if (nextTrust != null) {
                        val result = nextTrust.run(setup, checkNotNull(link.binding))
                        if (!result.complete) error(trustFault(result))
                    }
                    link.finishLinkSetup(main = false)
                    answerTheirEndLinkSetup(setup, next, setupChannels, ack = false)
                } finally {
                    reader.cancel()
                }
            }
            extra = Extra(next, pump)
            val ready = "the new link is ready (ours ${uuidText(link.linkUuid)}, theirs ${EndLinkSetup.linkUuid(theirs.payload)?.let(::uuidText)})"
            val main = checkNotNull(secureLink)
            val named = move(mux, { main.moveTx(next) }, frames, link.linkUuid)
            if (named == null) {
                transport.close()
                return LinkMove(false, "$ready; the glasses did not switch, so the session starts again")
            }
            onExtraLink = true
            mainFrames?.drain()
            return LinkMove(true, "$ready; switched, the glasses named $named")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            pump?.cancel()
            next.close()
            extra = null
            return LinkMove(false, "the new link failed: ${error.message ?: error::class.simpleName}")
        }
    }

    /** Move the traffic back to the link the session opened on, then close the one [addLink] added. */
    suspend fun dropLink(): LinkMove {
        if (extra == null) return LinkMove(false, "no second link")
        val frames = mainFrames
        if (onExtraLink) {
            val mux = connection
            val main = secureLink
            if (mux == null || main == null || frames == null) return LinkMove(false, "the session is not up")
            frames.dropping = false
            if (move(mux, { main.moveTx(transport) }, frames, main.linkUuid) == null) {
                transport.close()
                return LinkMove(false, "the glasses did not switch back, so the session starts again")
            }
            onExtraLink = false
        }
        dropExtra()
        return LinkMove(true, "back on the first link; ${frames?.dropped ?: 0} frames came on it meanwhile")
    }

    private fun dropExtra() {
        val added = extra ?: return
        extra = null
        onExtraLink = false
        added.pump.cancel()
        added.transport.close()
    }

    /**
     * SwitchTransportLink to the link [ours] names; once the glasses answer with their own switch, read [frames] and
     * [sendThere], under the session's cipher. Their target, or null.
     */
    private suspend fun move(mux: DataXConnection, sendThere: () -> Unit, frames: FrameSource, ours: ByteArray): String? {
        var named = "nothing"
        val answered: (DataXMessage) -> Boolean = { message ->
            val info = message.info
            val theirs = !info.flags.hasError && DataXConnection.hasOriginatorBit(info.channelId) &&
                (info.messageType == LinkSwitch.SWITCH_LINK || info.messageType == LinkSwitch.SWITCH_TRANSPORT_LINK) &&
                (info.serviceId == LinkSwitch.SERVICE || registry.serviceForInbound(info.channelId) == LinkSwitch.SERVICE)
            if (theirs) LinkSwitch.target(message.payload)?.let { named = uuidText(it) }
            theirs
        }
        val switched = mux.switchLinks(
            LinkSwitch.SERVICE, LinkSwitch.SWITCH_TRANSPORT_LINK, LinkSwitch.encode(ours), frames, answered, SWITCH_TIMEOUT_MILLIS, sendThere,
        )
        return if (switched) named else null
    }

    /** Drain what the glasses said during link setup before anyone allocates a local channel. */
    private suspend fun settleChannels(mux: DataXConnection) {
        while (!stopped) {
            val message = mux.poll() ?: break
            handleMessage(message)
        }
    }

    /** Read the link until [stop], with commands going out beside the reads, not between. */
    private suspend fun pump() = coroutineScope {
        val commands = launch {
            for (command in commandChannel) guarded("a command") { command.run(this@GlassesSession) }
        }
        try {
            while (isActive && !stopped) {
                handleMessage(checkNotNull(connection).receive())
            }
        } finally {
            commands.cancel()
        }
    }

    private suspend fun handleMessage(message: DataXMessage) {
        val info = message.info
        val mux = checkNotNull(connection)

        // A peer-opened channel names its service on its first message only, so record it
        // here or every later frame on that channel is attributed to service 0.
        if (info.serviceId != 0) registry.bindInbound(info.channelId, info.serviceId)
        val serviceId = registry.serviceForInbound(info.channelId)

        if (serviceId in REFUSED_PEER_SERVICES &&
            DataXConnection.hasOriginatorBit(info.channelId) &&
            !info.flags.hasError
        ) {
            // The glasses open svc30, their device-peer trust, and svc79, constellation auth,
            // toward every companion.
            mux.sendStatus(DataXConnection.mirror(info.channelId), SERVICE_NOT_FOUND)
        }

        // svc4, connection_monitor: the glasses' delivery check (type 0, a counter) is confirmed with a bare OK status.
        if (serviceId == CONNECTION_MONITOR &&
            DataXConnection.hasOriginatorBit(info.channelId) &&
            !info.flags.hasError &&
            !info.flags.isClosed
        ) {
            mux.sendStatus(DataXConnection.mirror(info.channelId), OK)
        }

        val responder = responders[serviceId]
        if (responder != null && !info.flags.hasError) {
            guarded("responder for service $serviceId") { responder.respond(message, mux) }
        }
        val closing = responders[message.closedService]
        if (closing != null && info.flags.isClosed && !info.flags.hasError) {
            guarded("responder for service ${message.closedService}") { closing.closed(info.channelId, mux) }
        }

        if (info.flags.hasError) {
            if (info.errorId != SUCCESS) {
                if (info.errorId != OK) {
                    publish(
                        ProtocolError(
                            // Locale.ROOT: ASCII digits.
                            "service %d answered status 0x%04x on channel 0x%04x"
                                .format(java.util.Locale.ROOT, serviceId, info.errorId, info.channelId),
                        ),
                    )
                }
                // A service not up yet (0xC001) or a channel it dropped (0xC002) leaves the channel dead: the next
                // send opens a new one. A service that starts later, such as the data collection daemon, is reached.
                if (info.errorId == SERVICE_NOT_FOUND || info.errorId == CHANNEL_GONE) mux.forgetServiceChannel(info.channelId)
                publish(DataXError(serviceId, info.channelId, info.errorId))
            }
            return
        }
        // An empty close frame only ends a channel, and the registry has already forgotten whose it was.
        if (info.flags.isClosed && message.payload.isEmpty()) return
        publish(ServiceData(serviceId, info.messageType, message.payload))
    }

    /** A responder or command that fails is reported; the link goes on. */
    private suspend fun guarded(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            publish(ProtocolError("$what failed: ${error.message}"))
        }
    }

    /** A failure reason a reader can act on. */
    private fun reason(error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: "the link failed"
        return if (secureLink?.lastCommand == StreamCommand.LOST.value) {
            "$detail (the glasses signalled stream 0 LOST)"
        } else {
            detail
        }
    }

    /** Say which direction of the trust exchange failed. */
    private fun trustFault(result: TrustResult): String {
        val status = result.refusal?.let { " (status 0x%04x)".format(it) }.orEmpty()
        val faults = buildList {
            if (!result.txOk) add("the glasses did not accept our credential$status")
            if (!result.rxOk) add("the glasses sent no credential of their own")
        }
        return "trust incomplete: ${faults.joinToString(" and ")} " +
            "(txOk=${result.txOk} rxOk=${result.rxOk})"
    }

    companion object {
        /** How long the glasses get to send their EndLinkSetup. */
        const val END_LINK_SETUP_TIMEOUT_MILLIS = 5_000L

        /** How long one read may wait during link setup. */
        const val LINK_SETUP_READ_TIMEOUT_MILLIS = 10_000L

        /** How long the glasses get to answer a SwitchTransportLink with their own. */
        const val SWITCH_TIMEOUT_MILLIS = 5_000L

        private val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000)

        /** 0x1000 is the success status svc36 answers with; DataX's own is [OK]. */
        private const val SUCCESS = 0x1000

        /** DataX service_not_found: what a peer answers for a service it does not host. */
        private const val SERVICE_NOT_FOUND = 0xC001

        /** DataX status for a channel the peer no longer has, as the toolkit app answers after it restarts. */
        private const val CHANNEL_GONE = 0xC002

        private val REFUSED_PEER_SERVICES = setOf(30, 79)

        /** DataX OK, the status a delivery check is confirmed with. */
        private const val OK = 0

        private const val CONNECTION_MONITOR = 4
    }
}
