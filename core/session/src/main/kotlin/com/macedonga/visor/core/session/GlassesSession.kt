package com.macedonga.visor.core.session

import com.macedonga.visor.core.protocol.LinkSetupMsgType
import com.macedonga.visor.core.protocol.StreamCommand
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

    /** Start connecting. Returns at once; the work happens on [scope]. */
    fun start(scope: CoroutineScope) {
        supervisor = scope.launch { supervise() }
    }

    /** Ask the session to finish, and wait until it lets go of the socket. */
    suspend fun stop() {
        stopped = true
        stopSignal.complete(Unit)
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
            val mux = DataXConnection(link, registry)
            connection = mux

            // One reader from here to the end of the attempt, and it is the same one `pump`
            // consumes at READY.
            coroutineScope {
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
                    answerTheirEndLinkSetup(mux)
                    settleChannels(mux)

                    publish(LinkStateChanged(LinkState.READY))
                    reachedReady = true
                    pump()
                    publish(LinkStateChanged(LinkState.DISCONNECTED))
                } finally {
                    // coroutineScope waits for its children and this one never ends by itself, so
                    // cancelling it is what lets the attempt finish.
                    reader.cancel()
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

    /** Wait for the glasses' own EndLinkSetup, then acknowledge it on the mirror. */
    private suspend fun answerTheirEndLinkSetup(mux: DataXConnection) {
        withLinkDeadline(transport, END_LINK_SETUP_TIMEOUT_MILLIS, "the glasses did not send their EndLinkSetup") {
            while (true) {
                val message = mux.receive()
                if (isTheirEndLinkSetup(message)) {
                    mux.sendStatus(DataXConnection.mirror(message.info.channelId), SUCCESS)
                    return@withLinkDeadline
                }
                handleMessage(message)
            }
        }
    }

    // The glasses answer on the link setup channel our RequestEncryption opened; the Neural Band on one of its own,
    // 0x9001, that names no service.
    private fun isTheirEndLinkSetup(message: DataXMessage): Boolean {
        val info = message.info
        if (info.messageType != LinkSetupMsgType.END_LINK_SETUP.value || info.flags.hasError) return false
        return info.channelId == LINK_SETUP_CHANNEL ||
            (DataXConnection.hasOriginatorBit(info.channelId) && info.serviceId == 0 && registry.serviceForInbound(info.channelId) == 0)
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

        private val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000)

        /** 0x1000 is the success status svc36 answers with; DataX's own is [OK]. */
        private const val SUCCESS = 0x1000

        /** DataX service_not_found: what a peer answers for a service it does not host. */
        private const val SERVICE_NOT_FOUND = 0xC001

        private val REFUSED_PEER_SERVICES = setOf(30, 79)

        /** DataX OK, the status a delivery check is confirmed with. */
        private const val OK = 0

        private const val CONNECTION_MONITOR = 4
    }
}
