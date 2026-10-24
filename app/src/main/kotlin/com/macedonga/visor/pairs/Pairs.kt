package com.macedonga.visor.pairs

import android.content.Context
import com.macedonga.visor.connection.AndroidTransportFactory
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.RealSessionHostFactory
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.home.PairSeen
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.stage.Looks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A pair set up on this phone. [demo] is one a debug build shows to try the home with: never connected or looked at. */
data class KnownPair(val serial: String, val address: String, val demo: Boolean = false)

/**
 * The pairs set up on this phone, in the order they were set up. One is connected at a time; the home shows the others
 * as they were last heard from, and a pair it shows that is not connected gets a quick look: its own LE link and a
 * session that only reads the batteries, the case and the hinge, then lets go.
 */
@Singleton
class Pairs @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: IdentityStore,
    private val connection: GlassesConnection,
    private val live: LiveStream,
    private val media: MediaImport,
    private val lastSeen: LastSeen,
    private val looks: Looks,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = MutableStateFlow<List<KnownPair>>(emptyList())
    private val demoPair = MutableStateFlow<KnownPair?>(null)
    val all: StateFlow<List<KnownPair>> = combine(stored, demoPair) { pairs, extra -> pairs + listOfNotNull(extra) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _peeking = MutableStateFlow<String?>(null)

    /** The pair being looked at right now, if any. */
    val peeking: StateFlow<String?> = _peeking.asStateFlow()

    private var peek: Job? = null
    private val peekedAt = mutableMapOf<String, Long>()

    fun refresh() {
        scope.launch {
            val bundles = withContext(Dispatchers.IO) { runCatching { store.all() }.getOrDefault(emptyList()) }
            stored.value = bundles.sortedBy { it.createdAt }.map { KnownPair(it.serial, it.glassesAddress) }
            bundles.forEach { looks.want(it.serial) }
            // Kept in memory and small: the stage needs one for every pair but a Wayfarer, and to tell pairs apart.
            looks.wantPictures(bundles.map { it.serial })
        }
    }

    /** Connect to [serial] instead of the pair connected now. False while the Live or an import has the glasses. */
    fun connect(serial: String): Boolean {
        if (live.running.value || media.importing.value) return false
        val pair = all.value.firstOrNull { it.serial == serial }?.takeIf { !it.demo } ?: return false
        stopPeek()
        GlassesService.start(context, GlassesTarget(GlassesLink.Bluetooth(pair.address), pair.serial))
        return true
    }

    /** A quick look at [serial], a pair that is not connected: at most once a minute, never during a Live or an import. */
    fun peek(serial: String) {
        if (serial == connection.state.value.target?.serial || serial == connection.pair.value && connection.running.value) return
        if (live.running.value || media.importing.value) return
        val now = System.currentTimeMillis()
        if (now - (peekedAt[serial] ?: 0) < PEEK_EVERY_MS) return
        val pair = all.value.firstOrNull { it.serial == serial }?.takeIf { !it.demo } ?: return
        peekedAt[serial] = now
        peek?.cancel()
        peek = scope.launch {
            val keeper = LeKeeper(context)
            val look = GlassesConnection(store, RealSessionHostFactory(AndroidTransportFactory(context, keeper = keeper)), chooses = false)
            _peeking.value = serial
            log.add("Pairs: looking at ${pair.serial}")
            try {
                keeper.hold(pair.address)
                // Asleep, they answer on LE only: wait for it a moment before the session picks its link.
                withTimeoutOrNull(LE_WAIT_MS) { keeper.connected.first { it } }
                look.start(this, GlassesTarget(GlassesLink.Bluetooth(pair.address), pair.serial))
                val seen = withTimeoutOrNull(PEEK_MS) { look.state.first { it.device?.battery != null } }
                // The case comes in its own update, when there is one.
                if (seen != null) withTimeoutOrNull(CASE_MS) { look.state.first { it.device?.case != null } }
                look.state.value.let(lastSeen::record)
                log.add(if (seen != null) "Pairs: ${pair.serial} at ${seen.device?.battery?.percent?.toInt()}%" else "Pairs: ${pair.serial} did not answer")
            } finally {
                // Let go of the socket even when the look was called off: the glasses hold one session.
                withContext(NonCancellable) { look.stop() }
                keeper.release()
                _peeking.value = null
            }
        }
    }

    /** Debug builds: show [pair] beside the real ones, as last heard from with [seen]; null takes it away. */
    fun demo(pair: KnownPair?, seen: PairSeen?) {
        demoPair.value?.let { lastSeen.forget(it.serial) }
        if (pair != null && seen != null) lastSeen.keep(pair.serial, seen)
        demoPair.value = pair?.copy(demo = true)
    }

    fun stopPeek() {
        peek?.cancel()
        peek = null
    }

    private companion object {
        const val PEEK_EVERY_MS = 60_000L
        const val LE_WAIT_MS = 6_000L
        const val PEEK_MS = 20_000L
        const val CASE_MS = 2_000L
    }
}
