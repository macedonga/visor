package com.macedonga.visor.features

import android.content.Context
import com.macedonga.visor.core.assistant.FocusStyle
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.ConfigState
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.pairs.pairPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a feature stands, from what the glasses last said. */
enum class Standing {
    /** Visor has not read the glasses' configuration yet, so it cannot tell. */
    UNKNOWN,
    OFF,

    /** Some of its switches are on, not all. */
    PARTLY,

    /** Being written. */
    SENDING,

    /** Sent; the glasses' configuration is being read to see whether they kept it. */
    CHECKING,

    /** Sent, with no reading since to say whether it landed. */
    UNCONFIRMED,

    /** The glasses kept their old values. */
    REFUSED,
    ON,
}

/** What the screens read. */
data class FeaturesState(
    val standings: Map<Feature, Standing> = emptyMap(),
    /** Which way each feature was last turned, including what is still on its way: what its switch shows. */
    val wanted: Map<Feature, Boolean> = emptyMap(),
    /** The glasses' configuration is being read or written. */
    val busy: Boolean = false,
    /** Visor holds the glasses' configuration, which every MobileConfig switch needs. */
    val configRead: Boolean = false,
    val readAt: Long? = null,
    /** Turned on with switches the glasses read when they start: waiting for a restart. */
    val restart: Set<Feature> = emptySet(),
    val problem: String? = null,
    /** The Meta product id of the pair, as [Feature.on] reads it. */
    val product: Int? = null,
)

/**
 * Turns hidden features on and off. Settings are written at once and read back by [GlassesSettings]. MobileConfig
 * switches wait a moment for more flips, go out as one table, and the glasses' configuration is then read again: a
 * feature counts as on only when the glasses say so.
 */
@Singleton
class Features @Inject constructor(
    private val config: GlassesConfig,
    private val settings: GlassesSettings,
    private val connection: GlassesConnection,
    private val hearing: Hearing,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefsFor = connection.pair.value
    @Volatile
    private var prefs = pairPrefs(context, "features", prefsFor)

    // MobileConfig values waiting for the next table, and the features they are for.
    private val queued = MutableStateFlow<Map<String, Any>>(emptyMap())
    private val turning = mutableMapOf<Feature, Boolean>()
    private val checking = MutableStateFlow(false)
    private val refused = MutableStateFlow(load(REFUSED))
    private val restart = MutableStateFlow(load(RESTART).filter { f -> f.gates.any { it is Gate.Config && !it.live } }.toSet())
    private val problem = MutableStateFlow<String?>(null)
    private var flush: Job? = null

    private val _state = MutableStateFlow(FeaturesState())
    val state: StateFlow<FeaturesState> = _state.asStateFlow()

    init {
        // Back after a restart is pending: a quick read says whether the glasses booted again, and that clears it.
        scope.launch {
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { up ->
                if (up && restart.value.isNotEmpty() && prefs.getString(RESTART_BOOT, null) != null) config.read()
            }
        }
        scope.launch {
            config.state.map { it.boot }.distinctUntilChanged().collect { boot ->
                val marked = prefs.getString(RESTART_BOOT, null)
                if (boot != null && marked != null && boot != marked && restart.value.isNotEmpty()) restarted()
            }
        }
        scope.launch {
            val marks = combine(checking, refused, restart, problem) { c, f, r, p -> Marks(c, f, r, p) }
            val device = connection.state.map { device(it.info?.build) }.distinctUntilChanged()
            combine(config.state, settings.state, queued, marks, device) { c, s, q, m, d ->
                FeaturesState(
                    standings = Feature.entries.associateWith { standing(it.gatesFor(d), c, s, q, m, it in m.refused) },
                    wanted = Feature.entries.associateWith { wants(it.gatesFor(d), c, s, q) },
                    busy = c.busy.isNotEmpty() || m.checking,
                    configRead = c.params.isNotEmpty(),
                    readAt = c.reportedAt ?: c.readAt,
                    restart = m.restart,
                    problem = m.problem,
                    product = s.product,
                )
            }.collect { _state.value = it }
        }
    }

    private class Marks(val checking: Boolean, val refused: Set<Feature>, val restart: Set<Feature>, val problem: String?)

    val ready: Boolean get() = connection.state.value.link == LinkState.READY

    /** Turn [feature] on or off. False when the glasses are not there to take it. */
    fun set(feature: Feature, on: Boolean): Boolean {
        if (!ready) return false
        val gates = feature.gatesFor(device(connection.state.value.info?.build))
        if (gates.any { it is Gate.Config } && config.state.value.params.isEmpty()) return false
        problem.value = null
        refused.update { it - feature }
        for (gate in gates) {
            if (!on && neededElsewhere(feature, gate)) continue
            when (gate) {
                is Gate.Setting -> {
                    val want = if (on) gate.on else gate.off ?: settings.catalogue[gate.key]?.default ?: continue
                    if (!same(settings.catalogue[gate.key]?.kind, settings.value(gate.key), want)) settings.write(gate.key, want)
                }
                is Gate.Config -> {
                    if (gate.floor && (!on || gate.holds(config.state.value.values[gate.name]))) continue
                    val want = if (on) gate.on else offValue(gate) ?: continue
                    queued.update { it + (gate.name to want) }
                }
            }
        }
        if (!on) restart.update { it - feature }
        if (feature.gates.any { it is Gate.Config }) {
            synchronized(turning) { turning[feature] = on }
            flush?.cancel()
            flush = scope.launch {
                delay(GATHER_MS)
                send()
            }
        }
        save()
        return true
    }

    private val _focus = MutableStateFlow(
        FocusStyle.entries.getOrElse(prefs.getInt(FOCUS_STYLE, 0)) { FocusStyle.STANDARD } to prefs.getInt(FOCUS_VOLUME, Hearing.DEFAULT_VOLUME),
    )

    /** Conversation focus's style and how much louder the voice is, as last chosen here. */
    val focus: StateFlow<Pair<FocusStyle, Int>> = _focus.asStateFlow()

    /** Start conversation focus now, in the chosen style. */
    fun startFocus() = hearing.start(_focus.value.first)

    fun stopFocus() = hearing.stop()

    fun resumeFocus() = hearing.resume()

    /** Choose the style and volume; the glasses take them at once, or keep them for the next start. */
    fun tuneFocus(style: FocusStyle, volume: Int) {
        val v = volume.coerceIn(Hearing.MIN_VOLUME, Hearing.MAX_VOLUME)
        _focus.value = style to v
        prefs.edit().putInt(FOCUS_STYLE, style.ordinal).putInt(FOCUS_VOLUME, v).apply()
        if (ready) hearing.tune(style, v)
    }

    private val _tested = MutableStateFlow(prefs.getBoolean(HEARING_TESTED, false))

    /** Whether a hearing test made hearing correction's profile, kept here as the Meta app keeps it. */
    val tested: StateFlow<Boolean> = _tested.asStateFlow()

    fun hearingTested() {
        _tested.value = true
        prefs.edit().putBoolean(HEARING_TESTED, true).apply()
    }

    /** Whether tap and hold on the touchpad starts conversation focus. */
    fun holdStartsFocus(s: SettingsState): Boolean = same(settings.catalogue[HOLD]?.kind, s.pending[HOLD] ?: s.stored[HOLD] ?: settings.catalogue[HOLD]?.default, HOLD_FOCUS)

    /** Make tap and hold start conversation focus, or give it back what it did before. */
    fun setHoldStartsFocus(on: Boolean): Boolean {
        val now = settings.value(HOLD)
        if (on) {
            if (now != null && now != HOLD_FOCUS) prefs.edit().putString(HOLD_BEFORE, now).apply()
            return settings.write(HOLD, HOLD_FOCUS)
        }
        return settings.write(HOLD, prefs.getString(HOLD_BEFORE, null)?.takeIf { it != HOLD_FOCUS } ?: HOLD_ASSISTANT)
    }

    /** Read the glasses' configuration again, to see where every feature stands. */
    fun check() {
        scope.launch { verify() }
    }

    /** The glasses were restarted: what waited for it is in use. */
    fun restarted() {
        restart.value = emptySet()
        prefs.edit().remove(RESTART_BOOT).apply()
        save()
    }

    private suspend fun send() {
        val c = config.state.value
        // Already what the glasses hold and what Visor last sent: nothing to write.
        val changes = queued.value.filterNot { (k, v) -> c.reported[k]?.let { matches(it, v) } == true && c.values[k]?.let { matches(it, v) } == true }
        val features = synchronized(turning) { turning.toMap().also { turning.clear() } }
        if (changes.isEmpty()) {
            queued.value = emptyMap()
            return
        }
        val why = config.send(changes)
        queued.update { it - changes.keys }
        if (why != null) {
            problem.value = why
            return
        }
        if (!verify()) return
        val after = config.state.value
        val missed = changes.filterNot { (k, v) -> after.reported[k]?.let { matches(it, v) } == true }.keys
        val failed = features.keys.filter { f -> f.gates.any { it is Gate.Config && it.name in missed } }
        if (failed.isNotEmpty()) refused.update { it + failed }
        // A switch the glasses keep per session only takes effect once they start again.
        val waiting = features.filter { (f, on) ->
            on && f !in failed && f.gates.any { it is Gate.Config && it.name in changes && it.needsRestart(after.params) }
        }.keys
        restart.update { it + waiting }
        if (waiting.isNotEmpty()) after.boot?.let { prefs.edit().putString(RESTART_BOOT, it).apply() }
        save()
    }

    private suspend fun verify(): Boolean {
        checking.value = true
        return try {
            config.readNow().also { if (!it) problem.value = "The glasses did not send their configuration." }
        } finally {
            checking.value = false
        }
    }

    /** Another feature that is on uses this switch too, so turning [feature] off leaves it alone. */
    private fun neededElsewhere(feature: Feature, gate: Gate): Boolean =
        Feature.entries.any { other -> other != feature && gate in other.gates && state.value.standings[other] == Standing.ON }

    /** What a switch goes back to: Meta's own value from before Visor's first write, else the opposite. */
    private fun offValue(gate: Gate.Config): Any? {
        config.state.value.baseline?.get(gate.name)?.takeIf { !matches(it, gate.on) }?.let { return it }
        return gate.off ?: (gate.on as? Boolean)?.not()
    }

    private fun standing(gates: List<Gate>, c: ConfigState, s: SettingsState, q: Map<String, Any>, m: Marks, refused: Boolean): Standing {
        val configs = gates.filterIsInstance<Gate.Config>()
        val sets = gates.filterIsInstance<Gate.Setting>()
        if (configs.isNotEmpty() && c.params.isEmpty()) return Standing.UNKNOWN
        if (configs.any { it.name in q } || sets.any { it.key in s.pending }) return Standing.SENDING
        if (refused || sets.any { it.key in s.kept }) return Standing.REFUSED
        // Sent and not yet in a report: the glasses' configuration has to be read to know.
        if (configs.any { g -> c.values[g.name]?.let { v -> c.reported[g.name]?.let { matches(it, v) } != true } == true }) {
            return if (m.checking) Standing.CHECKING else Standing.UNCONFIRMED
        }
        // A floor never turns a feature off, so with every other switch off the feature is off.
        val switches = configs.filterNot { it.floor }
        val on = switches.count { g -> g.holds(c.reported[g.name]) } +
            sets.count { g -> same(settings.catalogue[g.key]?.kind, s.stored[g.key] ?: settings.catalogue[g.key]?.default, g.on) }
        val floored = configs.all { !it.floor || it.holds(c.reported[it.name]) }
        return when {
            on == 0 -> Standing.OFF
            on == switches.size + sets.size && floored -> Standing.ON
            else -> Standing.PARTLY
        }
    }

    private fun wants(gates: List<Gate>, c: ConfigState, s: SettingsState, q: Map<String, Any>): Boolean =
        gates.all { g ->
            when (g) {
                is Gate.Config -> g.holds(q[g.name] ?: c.values[g.name])
                is Gate.Setting -> same(settings.catalogue[g.key]?.kind, s.pending[g.key] ?: s.stored[g.key] ?: settings.catalogue[g.key]?.default, g.on)
            }
        }

    private fun load(key: String): Set<Feature> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { id -> Feature.entries.firstOrNull { it.id == id } }.toSet()

    private fun save() {
        prefs.edit()
            .putStringSet(RESTART, restart.value.map { it.id }.toSet())
            .putStringSet(REFUSED, refused.value.map { it.id }.toSet())
            .apply()
    }

    // Last, so everything it resets is there: another pair has its own marks, sound and test.
    init {
        scope.launch {
            connection.pair.filterNotNull().distinctUntilChanged().collect { serial ->
                if (serial == prefsFor) return@collect
                prefsFor = serial
                prefs = pairPrefs(context, "features", serial)
                flush?.cancel()
                synchronized(turning) { turning.clear() }
                queued.value = emptyMap()
                problem.value = null
                refused.value = load(REFUSED)
                restart.value = load(RESTART).filter { f -> f.gates.any { it is Gate.Config && !it.live } }.toSet()
                _focus.value = FocusStyle.entries.getOrElse(prefs.getInt(FOCUS_STYLE, 0)) { FocusStyle.STANDARD } to prefs.getInt(FOCUS_VOLUME, Hearing.DEFAULT_VOLUME)
                _tested.value = prefs.getBoolean(HEARING_TESTED, false)
            }
        }
    }

    private companion object {
        const val GATHER_MS = 1_500L
        const val RESTART = "restart"
        const val REFUSED = "refused"
        const val HOLD = "tap_and_hold_mapping"
        const val HOLD_FOCUS = "7"
        const val HOLD_ASSISTANT = "2"
        const val HOLD_BEFORE = "hold_before"
        const val FOCUS_STYLE = "focus_style"
        const val FOCUS_VOLUME = "focus_volume"
        const val HEARING_TESTED = "hearing_tested"
        const val RESTART_BOOT = "restart_boot"
    }
}

/** The device in a build fingerprint, "Meta/<product>/<device>:...". */
private fun device(build: String?): String? = build?.split('/')?.getOrNull(2)?.substringBefore(':')?.lowercase()

/** A value as the glasses report it against the one a feature wants: 1 and 1L, "true" and true. */
internal fun matches(have: Any, want: Any): Boolean = when (want) {
    is Boolean -> have == want || have.toString().equals(want.toString(), ignoreCase = true)
    is Number -> (have as? Number)?.toDouble() == want.toDouble() || have.toString() == want.toString()
    else -> have.toString() == want.toString()
}
