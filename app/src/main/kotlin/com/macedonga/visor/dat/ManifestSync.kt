package com.macedonga.visor.dat

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.ConstellationManifest
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.log.EventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the glasses on the manifest Visor signed, as the Meta app's ManifestManager does on every connection: the
 * glasses' key salt is read (GET_MANIFEST_VERSION) and, when it is not the salt of Visor's manifest, the authority key
 * and the manifest go again. Glasses that missed a setup, or were given another manifest, catch up when they connect.
 */
@Singleton
class ManifestSync @Inject constructor(
    private val connection: GlassesConnection,
    private val store: ToolkitStore,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            connection.state.map { if (it.link == LinkState.READY) it.info?.serial else null }.distinctUntilChanged().collect { serial ->
                if (serial != null) runCatching { check(serial) }.onFailure { log.add("Manifest: the check failed: ${it.message}") }
            }
        }
    }

    private suspend fun check(serial: String) {
        val pair = store.pair(serial) ?: return
        val manifest = pair.manifest ?: return
        val held = version()
        if (held != null && held.salt.contentEquals(pair.salt)) return
        log.add("Manifest: the glasses hold ${held?.version ?: "an unreadable one"}, Visor's is ${pair.version}; sending it")
        val now = install(pair.authorityPublic, manifest)
        log.add(if (now?.salt?.contentEquals(pair.salt) == true) "Manifest: the glasses took ${now.version}" else "Manifest: the glasses still hold ${now?.version}")
    }

    /** Installs [manifest] under [authority] on the glasses (svc10), and reads back what they then hold. */
    suspend fun install(authority: ByteArray, manifest: ByteArray): ConstellationManifest.Version? {
        connection.submit { it.sendOnService(ConstellationManifest.SERVICE, ConstellationManifest.SET_AUTHORITY_KEY, ConstellationManifest.authorityKey(authority)) }
        ConstellationManifest.chunks(manifest).forEach { (type, chunk) -> connection.submit { it.sendOnService(ConstellationManifest.SERVICE, type, chunk) } }
        return version()
    }

    /** The manifest the glasses hold, or null when they do not answer in 5 s. */
    suspend fun version(): ConstellationManifest.Version? = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(5_000) {
                connection.serviceData.first { it.serviceId == ConstellationManifest.SERVICE && it.messageType == ConstellationManifest.VERSION_INFO }
            }
        }
        connection.submit { it.sendOnService(ConstellationManifest.SERVICE, ConstellationManifest.GET_VERSION, ByteArray(0)) }
        reply.await()?.let { ConstellationManifest.version(it.payload) }
    }
}
