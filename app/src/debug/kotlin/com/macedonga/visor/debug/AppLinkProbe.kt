package com.macedonga.visor.debug

import android.content.Context
import com.macedonga.visor.core.identity.BundleCredentials
import com.macedonga.visor.core.identity.BundleState
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.nowIso
import com.macedonga.visor.core.protocol.Dat
import com.macedonga.visor.core.session.DataXError
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.LinkStateChanged
import com.macedonga.visor.core.session.ProtocolError
import com.macedonga.visor.core.session.ServiceData
import com.macedonga.visor.core.session.SessionCommand
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.log.EventLog
import java.security.PrivateKey
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Debug only: a link of Visor's own to the Display under its app key, trusted on svc79 against the constellation
 * manifest as Meta's toolkit's links are, and a toolkit session on it (svc7009, then the toolkit app on svc6500).
 * Every step goes to the event log as "AppLink:".
 */
internal object AppLinkProbe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: GlassesSession? = null
    private var job: Job? = null
    private val ids = AtomicInteger()
    private val samples = AtomicInteger()

    fun start(context: Context, address: String, pem: ByteArray, version: Long, salt: ByteArray, tag: Int, sign: Int, dam: Boolean, log: EventLog): String {
        val credentials = BundleCredentials(IdentityBundle(pem, address, "visor-app", nowIso(), "app", BundleState.VERIFIED))
        return start(context, address, credentials.appEcKey, credentials.appPublicKey, version, salt, tag, sign, dam, log)
    }

    fun start(context: Context, address: String, key: PrivateKey, publicKey: ByteArray, version: Long, salt: ByteArray, tag: Int, sign: Int, dam: Boolean, log: EventLog): String {
        stop()
        val trust = ProbeConstellationTrust(key, publicKey, version, salt, tag, sign) { log.add("AppLink: $it") }
        val opened = GlassesSession(BluetoothLeTransport(context, address), trust)
        session = opened
        job = scope.launch {
            opened.events.collect { event ->
                when (event) {
                    is LinkStateChanged -> {
                        log.add("AppLink: link ${event.state} ${event.reason}".trim())
                        if (event.state == LinkState.READY) {
                            log.add("AppLink: trust ${opened.trustResult}")
                            send(Dat.SESSION_SERVICE, Dat.SESSION_START_REQUEST, Dat.sessionStart("com.macedonga.visor", UUID.randomUUID().toString(), "Visor", "1.0.1", dam))
                        }
                    }
                    is ProtocolError -> log.add("AppLink: ${event.detail}")
                    is DataXError -> {
                        log.add("AppLink: svc${event.serviceId} status 0x%04x".format(Locale.ROOT, event.errorId))
                        // A refused channel is dead: the next request opens a fresh one.
                        if (event.serviceId == Dat.DWA_SERVICE) dwaChannel = null
                    }
                    is ServiceData -> heard(event, log)
                    else -> Unit
                }
            }
        }
        opened.start(scope)
        return "connecting to $address with manifest $version"
    }

    /** Adds a capability on the toolkit app, then sends its first request: motion at [rateHz], or a test layout. */
    fun capability(capability: Int, payload: ByteArray?): String {
        val now = System.currentTimeMillis()
        send(Dat.DWA_SERVICE, Dat.REQUEST, Dat.requests("add-${ids.incrementAndGet()}", capability, Dat.addCapability(capability), null, now).single())
        if (payload != null) {
            Dat.requests("req-${ids.incrementAndGet()}", capability, null, payload, now).forEach { send(Dat.DWA_SERVICE, Dat.REQUEST, it) }
        }
        return "sent to capability $capability"
    }

    fun stop(): String {
        val running = session ?: return "no app link"
        runBlocking { running.stop() }
        job?.cancel()
        session = null
        return "stopped; ${samples.getAndSet(0)} motion samples"
    }

    @Volatile
    private var dwaChannel: Int? = null

    private fun send(service: Int, type: Int, payload: ByteArray) {
        session?.submit(
            SessionCommand { s ->
                val connection = s.connection ?: return@SessionCommand
                if (service != Dat.DWA_SERVICE) return@SessionCommand connection.sendOnService(service, type, payload)
                // The toolkit app's own channel, opened once it is up, so a refusal before then does not stick. Only the
                // opening frame names the service: one that names it again reads as a new open.
                val open = dwaChannel
                if (open != null) return@SessionCommand connection.send(open, payload, messageType = type)
                val channel = connection.openChannel(service).also { dwaChannel = it }
                connection.send(channel, payload, messageType = type, serviceId = service)
            },
        )
    }

    private fun heard(event: ServiceData, log: EventLog) {
        when (event.serviceId) {
            Dat.SESSION_SERVICE -> if (event.messageType == Dat.SESSION_START_RESPONSE) {
                val started = Dat.sessionStarted(event.payload)
                log.add("AppLink: session status ${started.status}, toolkit app ${started.dwaVersion}, launch ${started.dwaLaunch}, compatibility ${started.compatibility}")
                // The toolkit app needs about a second to come up after the launch before it hears svc6500.
                if (started.status == Dat.START_OK && started.dwaLaunch == 1) scope.launch {
                    delay(2_000)
                    log.add("AppLink: ${capability(Dat.IMU, Dat.motionStart(10))}")
                }
            } else {
                log.add("AppLink: svc7009 type ${event.messageType} ${event.payload.toHexString()}")
            }
            Dat.DWA_SERVICE -> when (event.messageType) {
                Dat.RESPONSE -> Dat.response(event.payload).let {
                    log.add("AppLink: response ${it.requestId} capability ${it.capability} status ${it.status} active ${it.active} error ${it.error} denied ${it.denied}")
                }
                Dat.EVENT -> Dat.event(event.payload).let { e ->
                    val motion = if (e.capability == Dat.IMU) e.payload?.let(Dat::motionSamples).orEmpty() else emptyList()
                    val n = samples.addAndGet(motion.size)
                    when {
                        motion.isNotEmpty() -> if (n % 50 < motion.size) {
                            val a = motion.last().accelerometer
                            log.add("AppLink: $n motion samples, last accelerometer ${a?.x} ${a?.y} ${a?.z}")
                        }
                        e.heartbeat -> Unit
                        else -> log.add("AppLink: event capability ${e.capability} shutdown ${e.shutdown} ${e.payload?.toHexString()}")
                    }
                }
                else -> log.add("AppLink: svc6500 type ${event.messageType} ${event.payload.toHexString()}")
            }
            else -> log.add("AppLink: svc${event.serviceId} type ${event.messageType} ${event.payload.size} bytes")
        }
    }
}
