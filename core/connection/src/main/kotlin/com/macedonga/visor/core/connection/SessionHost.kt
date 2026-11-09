package com.macedonga.visor.core.connection

import com.macedonga.visor.core.identity.IdentityBundle
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.LinkMove
import com.macedonga.visor.core.session.SecureLink
import com.macedonga.visor.core.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** One live session, as the layer above it needs to see one. */
interface SessionHost {

    /** Everything that happens on the link. One consumer, and the connection is it. */
    val events: Flow<SessionEvent>

    /** True once the svc36 exchange has completed in both directions on this attempt. */
    val trustComplete: Boolean

    /** True once the glasses have sent their own svc36 credential on this attempt, even if they refused ours. */
    val credentialReceived: Boolean

    /** What the glasses sent in the link handshake, or null before the link is up. */
    val handshake: String?

    /** The session's secure link, for a relay to carry sub-streams on; null before it is up or for a session without one. */
    val link: SecureLink? get() = null

    fun start(scope: CoroutineScope)

    /** Finish, and do not return until the socket has been let go of. */
    suspend fun stop()

    /** Run [work] on the session's own coroutine, with its live mux. */
    fun submit(work: suspend (DataXConnection) -> Unit)

    /** True while the traffic runs on the link [addLink] brought up. */
    val onAddedLink: Boolean get() = false

    /** Bring [link] up beside the session's own and move the traffic onto it, every channel kept. */
    suspend fun addLink(link: GlassesLink): LinkMove = LinkMove(false, "this session cannot add a link")

    /** Move the traffic back to the session's own link and close the one [addLink] brought up. */
    suspend fun dropLink(): LinkMove = LinkMove(false, "this session has no second link")
}

/** Builds one [SessionHost] per connection. */
fun interface SessionHostFactory {
    fun create(target: GlassesTarget, bundle: IdentityBundle?): SessionHost
}
