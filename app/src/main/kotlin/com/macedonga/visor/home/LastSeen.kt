package com.macedonga.visor.home

import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The glasses' batteries as last read, for when they are out of reach. */
data class LastBattery(val glasses: Int, val case: Int?, val at: Long)

/** How the glasses were the last time they said: folded, docked in the case. Their sleep looks like silence. */
data class LastPosture(val folded: Boolean, val inCase: Boolean)

/** One pair as last heard from: batteries, how it sat, the home's numbers, and the name it gave itself. */
data class PairSeen(
    val glasses: Int? = null,
    val case: Int? = null,
    // The case was on its charger at that reading.
    val caseCharging: Boolean = false,
    val at: Long = 0,
    val folded: Boolean = false,
    val inCase: Boolean = false,
    val freeMb: Long? = null,
    val volume: Int? = null,
    val temperatureC: Double? = null,
    val name: String? = null,
    // Named here while away: the glasses take the name when they are next connected.
    val nameToSend: Boolean = false,
) {
    val battery: LastBattery? get() = glasses?.let { LastBattery(it, case, at) }
    val posture: LastPosture get() = LastPosture(folded, inCase)
    val facts: GlassesFacts get() = GlassesFacts(freeMb, volume, temperatureC)
}

/**
 * How each pair was when last heard from, kept across launches. One for the process, so the service writes it while
 * no screen is open and the home, the widget and the tile all read the same thing. [battery] and [posture] are the
 * current pair's; the home's other pairs read [seen].
 */
@Singleton
class LastSeen @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val settings: GlassesSettings,
    live: LiveStream,
) {
    private val prefs = context.getSharedPreferences("pairs_seen", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val savedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val _seen = MutableStateFlow(prefs.all.mapNotNull { (serial, text) -> (text as? String)?.let(::decode)?.let { serial to it } }.toMap())

    /** Every pair heard from, by serial. */
    val seen: StateFlow<Map<String, PairSeen>> = _seen.asStateFlow()

    private val current = combine(connection.pair, _seen) { serial, all -> serial?.let(all::get) }
    val battery: StateFlow<LastBattery?> = current.map { it?.battery }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val posture: StateFlow<LastPosture> =
        current.map { it?.posture ?: LastPosture(false, false) }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, LastPosture(false, false))

    init {
        // The pair Visor is for has its readings from the start, the old shared ones included.
        scope.launch { connection.pair.filterNotNull().collect { serial -> if (serial !in _seen.value) update(serial) { it } } }
        scope.launch { connection.state.collect(::record) }
        // The glasses' own name, unless one given here is still on its way to them.
        scope.launch {
            settings.state.map { it.pair to (it.readAt != null) to it.stored[NAME_KEY] }.distinctUntilChanged().collect { (read, stored) ->
                val (serial, wasRead) = read
                if (serial == null || !wasRead) return@collect
                val mine = _seen.value[serial]?.takeIf { it.nameToSend }?.name
                when {
                    mine == null -> update(serial) { it.copy(name = stored?.takeIf { n -> n.isNotBlank() && n != LEFTOVER_NAME }) }
                    stored == mine -> update(serial) { it.copy(nameToSend = false) }
                    else -> settings.write(NAME_KEY, mine)
                }
            }
        }
        // The glasses stream only open and out of the case: a Live that ran says so while Bluetooth is away for it, and
        // the home it goes back to does not unfold the pair once the link is back.
        scope.launch {
            live.status.collect { status ->
                val serial = connection.pair.value
                if (status.phase == LivePhase.RUNNING && serial != null) update(serial) { it.copy(folded = false, inCase = false) }
            }
        }
    }

    /** Keep what [state] says about its pair: the connection's own, or a quick look at another pair. */
    fun record(state: GlassesState) {
        val target = state.target ?: return
        val device = state.device ?: return
        update(target.serial) { was ->
            val battery = device.battery
            var now = was
            if (battery != null) {
                // The case reads out only through the glasses in it. One that was charging has gone on charging since:
                // its last reading is out of date.
                val case = device.case?.levelPercent()
                now = now.copy(
                    glasses = battery.percent.toInt(), at = System.currentTimeMillis(),
                    case = case ?: was.case.takeUnless { was.caseCharging },
                    caseCharging = case != null && device.case?.charging == true,
                )
            }
            // The case's "present" only says the case is known; charging folded is what says they sit in it.
            if (state.link == LinkState.READY) {
                val inCase = battery?.charging?.let { it && device.hinge != "OPEN" }
                now = now.copy(folded = device.hinge?.let { it == "CLOSED" } ?: was.folded, inCase = inCase ?: was.inCase)
            }
            // A volume the glasses have not reported comes as -1: until they do, the one Android holds for them.
            now.copy(
                freeMb = device.storage?.freeMb?.takeIf { it >= 0 } ?: was.freeMb,
                volume = device.volume?.percent?.toInt()?.takeIf { it in 0..100 }
                    ?: heldVolume(context, (target.link as? GlassesLink.Bluetooth)?.address) ?: was.volume,
                temperatureC = battery?.temperatureC?.takeIf { it > -40 && it < 100 } ?: was.temperatureC,
            )
        }
    }

    /** Call [serial] [name]: the glasses take it at once when they are here, else when they are next connected. */
    fun rename(serial: String, name: String) {
        val here = connection.state.value.let { it.target?.serial == serial && it.link == LinkState.READY } && settings.state.value.pair == serial
        val sent = here && settings.write(NAME_KEY, name)
        update(serial) { it.copy(name = name, nameToSend = !sent) }
    }

    /** Hold [seen] for [serial] in memory only: a debug build's demo pair. */
    @Synchronized
    fun keep(serial: String, seen: PairSeen) {
        _seen.value = _seen.value + (serial to seen)
    }

    /** A pair forgotten: what it last said goes too. */
    @Synchronized
    fun forget(serial: String) {
        _seen.value = _seen.value - serial
        savedAt.remove(serial)
        prefs.edit().remove(serial).apply()
    }

    // The connection, a quick look and the name all write here, from their own threads.
    @Synchronized
    private fun update(serial: String, change: (PairSeen) -> PairSeen) {
        val was = _seen.value[serial] ?: migrated(serial)
        val now = change(was)
        if (now == _seen.value[serial]) return
        _seen.value = _seen.value + (serial to now)
        // Every reading moves the time; the file follows only a change, or a minute.
        if (now.copy(at = 0) != was.copy(at = 0) || now.at - (savedAt[serial] ?: 0) > 60_000) {
            savedAt[serial] = now.at
            prefs.edit().putString(serial, encode(now)).apply()
        }
    }

    /** Before Visor kept pairs apart, the one pair's readings were the home's: the first pair heard from takes them. */
    private fun migrated(serial: String): PairSeen {
        val home = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (!home.contains("glasses") && !home.contains("folded") && !home.contains("free_mb")) return PairSeen()
        val old = PairSeen(
            glasses = home.getInt("glasses", -1).takeIf { it >= 0 },
            case = home.getInt("case", -1).takeIf { it >= 0 },
            at = home.getLong("at", 0),
            folded = home.getBoolean("folded", false),
            inCase = home.getBoolean("in_case", false),
            freeMb = home.getLong("free_mb", -1).takeIf { it >= 0 },
            volume = home.getInt("volume", -1).takeIf { it in 0..100 },
            temperatureC = home.getFloat("temperature", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
        )
        home.edit().remove("glasses").remove("case").remove("at").remove("folded").remove("in_case")
            .remove("free_mb").remove("volume").remove("temperature").apply()
        return old
    }

    private fun encode(s: PairSeen): String = JSONObject().apply {
        s.glasses?.let { put("glasses", it) }
        s.case?.let { put("case", it) }
        if (s.caseCharging) put("case_charging", true)
        put("at", s.at)
        put("folded", s.folded)
        put("in_case", s.inCase)
        s.freeMb?.let { put("free_mb", it) }
        s.volume?.let { put("volume", it) }
        s.temperatureC?.let { put("temperature", it) }
        s.name?.let { put("name", it) }
        if (s.nameToSend) put("name_to_send", true)
    }.toString()

    private fun decode(text: String): PairSeen? = runCatching {
        val o = JSONObject(text)
        PairSeen(
            glasses = o.optInt("glasses", -1).takeIf { it >= 0 },
            case = o.optInt("case", -1).takeIf { it >= 0 },
            caseCharging = o.optBoolean("case_charging"),
            at = o.optLong("at", 0),
            folded = o.optBoolean("folded"),
            inCase = o.optBoolean("in_case"),
            freeMb = o.optLong("free_mb", -1).takeIf { it >= 0 },
            volume = o.optInt("volume", -1).takeIf { it in 0..100 },
            temperatureC = o.optDouble("temperature").takeIf { !it.isNaN() },
            name = o.optString("name").takeIf { it.isNotEmpty() },
            nameToSend = o.optBoolean("name_to_send"),
        )
    }.getOrNull()

    companion object {
        const val NAME_KEY = "device_custom_name"
        const val LEFTOVER_NAME = "VisorTest"
    }
}
