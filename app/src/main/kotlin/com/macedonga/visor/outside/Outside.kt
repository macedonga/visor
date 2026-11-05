package com.macedonga.visor.outside

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.home.LastBattery
import com.macedonga.visor.home.LastPosture
import com.macedonga.visor.home.LastSeen
import com.macedonga.visor.home.PhoneShot
import com.macedonga.visor.home.levelPercent
import com.macedonga.visor.home.moments
import com.macedonga.visor.home.recentShots
import com.macedonga.visor.home.restingMood
import com.macedonga.visor.home.shownBattery
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.moodOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home's status line, for outside the app: what the widget and the Quick Settings tile show. */
data class Glimpse(
    val mood: Mood,
    /** Connected, so the numbers are live. */
    val here: Boolean,
    val glasses: Int?,
    val charging: Boolean,
    val case: Int?,
    /** The newest moment on the phone, newest first; empty before there is one. */
    val moment: List<PhoneShot>,
)

/** Follows the glasses for the widget and the tile while no screen is open, in the home's words and numbers, and pushes each change to both. */
@Singleton
class Outside @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albums: Albums,
    private val connection: GlassesConnection,
    private val store: IdentityStore,
    lastSeen: LastSeen,
    keeper: LeKeeper,
    live: LiveStream,
    private val media: MediaImport,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val paired = MutableStateFlow<Boolean?>(null)
    private val moment = MutableStateFlow<List<PhoneShot>>(emptyList())
    // Once a try has gone unanswered the reconnect ladder flips between trying and failing; like the home, keep one word.
    @Volatile
    private var unanswered = false

    val glimpse: StateFlow<Glimpse> = combine(
        combine(connection.state, connection.running, ::Pair),
        combine(lastSeen.battery, lastSeen.posture, ::Pair),
        combine(keeper.connected, live.running, media.importing, ::Triple),
        paired,
        moment,
    ) { (state, running), (battery, posture), (nearby, liveOn, importing), paired, moment ->
        glimpseOf(state, running, battery, posture, nearby, liveOn, importing, paired, moment)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Glimpse(Mood.FAR, false, null, false, null, emptyList()))

    init {
        scope.launch { media.saved.collect { moment.value = newestMoment() } }
        scope.launch {
            connection.state.map { it.target }.distinctUntilChanged().collect { paired.value = readPaired() }
        }
        scope.launch {
            glimpse.collect {
                showWidgets(context, it)
                GlassesTile.ask(context)
            }
        }
    }

    /** Read the store and the gallery again: a widget asking for an update gets it fresh. */
    suspend fun refresh() {
        paired.value = readPaired()
        moment.value = newestMoment()
    }

    /** A tap asked for a fresh try: the next attempt shows as searching again. */
    fun retrying() {
        unanswered = false
    }

    private suspend fun readPaired() = withContext(Dispatchers.IO) { runCatching { store.all().isNotEmpty() }.getOrNull() }

    private suspend fun newestMoment() = moments(recentShots(context, albums), max = 1).firstOrNull().orEmpty()

    private fun glimpseOf(
        state: GlassesState,
        running: Boolean,
        battery: LastBattery?,
        posture: LastPosture,
        nearby: Boolean,
        liveOn: Boolean,
        importing: Boolean,
        paired: Boolean?,
        moment: List<PhoneShot>,
    ): Glimpse {
        // A stopped connection keeps its last state; only a running one is still talking to the glasses.
        val ready = running && state.link == LinkState.READY
        if (ready) unanswered = false
        else if (state.failure != null || state.link == LinkState.LOST) unanswered = true
        val trying = running && state.target != null && !unanswered && state.link != LinkState.DISCONNECTED
        val mood = when {
            paired == false -> Mood.ABSENT
            importing -> Mood.IMPORTING
            liveOn && !ready -> Mood.RECORDING
            ready -> moodOf(state, liveOn)
            trying -> Mood.SEARCHING
            else -> restingMood(posture, nearby)
        }
        val shown = shownBattery(mood, state.device?.battery?.takeIf { ready }, state.device?.case?.levelPercent(), battery)
        return Glimpse(
            mood = mood,
            here = shown.here,
            glasses = shown.glasses,
            // Docked, they charge as far as anyone can tell from the last thing they said.
            charging = if (shown.here) state.device?.battery?.charging == true else mood == Mood.IN_CASE,
            case = shown.case,
            moment = moment,
        )
    }
}
