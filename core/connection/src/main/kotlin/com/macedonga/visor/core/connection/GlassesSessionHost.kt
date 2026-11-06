package com.macedonga.visor.core.connection

import com.macedonga.visor.core.identity.BundleCredentials
import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.identity.IdentityTrustExchange
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.GlassesSession
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.session.SessionCommand
import com.macedonga.visor.core.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The real session, behind the seam the connection is written against. */
class GlassesSessionHost(private val session: GlassesSession) : SessionHost {

    override val events: Flow<SessionEvent> get() = session.events

    override val trustComplete: Boolean get() = session.trustResult?.complete == true

    override val credentialReceived: Boolean get() = session.trustResult?.rxOk == true

    override val handshake: String? get() = session.handshake

    override fun start(scope: CoroutineScope) = session.start(scope)

    override suspend fun stop() = session.stop()

    override fun submit(work: suspend (DataXConnection) -> Unit) {
        session.submit(
            SessionCommand { running ->
                // `connection` is null between attempts. A command queued then runs when the
                // next attempt has one, and there is nothing useful to do with a null.
                running.connection?.let { work(it) }
            },
        )
    }
}

/** Builds a real session on a real transport, with the svc36 exchange the bundle's key needs. */
class RealSessionHostFactory(
    private val transports: TransportFactory,
    // Services this app hosts, by service id.
    private val responders: Map<Int, Responder> = emptyMap(),
) : SessionHostFactory {

    override fun create(target: GlassesTarget, bundle: IdentityBundle?): SessionHost {
        val transport = transports.create(target.link)
        val trust = bundle?.let { IdentityTrustExchange(BundleCredentials(it)) }
        val session = GlassesSession(transport, trust)
        responders.forEach(session::addResponder)
        return GlassesSessionHost(session)
    }
}
