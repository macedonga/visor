package com.macedonga.visor.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.macedonga.visor.core.connection.Battery
import com.macedonga.visor.core.connection.CaseBattery
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.HINGE_CLOSED
import com.macedonga.visor.core.connection.productId
import com.macedonga.visor.core.media.LivePhase
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.stage.Mood
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

/** Not talking to the phone now: out of reach, still looking, asleep or docked. */
private val Mood.away: Boolean get() = this == Mood.FAR || this == Mood.ABSENT || this == Mood.SEARCHING || this == Mood.ASLEEP || this == Mood.IN_CASE

/** The batteries a status line shows; [here] when they are live. */
class ShownBattery(val here: Boolean, val glasses: Int?, val case: Int?)

fun shownBattery(mood: Mood, live: Battery?, liveCase: Int?, remembered: LastBattery?): ShownBattery {
    val here = live != null && !mood.away
    // Asleep or docked, the last percentage is still close to true; out of reach or still looking, it would mislead.
    val keep = mood == Mood.ASLEEP || mood == Mood.IN_CASE || !mood.away
    return ShownBattery(
        here = here,
        glasses = if (here) live.percent.toInt() else remembered?.glasses?.takeIf { keep },
        case = if (here) liveCase ?: remembered?.case else remembered?.case?.takeIf { keep },
    )
}

/** The word for a pair that has gone quiet, from how it last was. */
fun restingMood(posture: LastPosture, nearby: Boolean): Mood = when {
    posture.inCase -> Mood.IN_CASE
    posture.folded || nearby -> Mood.ASLEEP
    else -> Mood.FAR
}

/** What the glasses report besides their battery, live or as last read. */
data class GlassesFacts(val freeMb: Long? = null, val volume: Int? = null, val temperatureC: Double? = null)

/** The case's charge in percent, from its level on the glasses' own scale when no percent came; known with the case away too. */
fun CaseBattery.levelPercent(): Int? = when {
    !known || scale <= 0 -> null
    else -> percent ?: (level * 100L / scale).toInt()
}

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
    // The model, from the build the glasses last gave: what the app shows for this pair while it is away.
    val product: Int? = null,
) {
    val battery: LastBattery? get() = glasses?.let { LastBattery(it, case, at) }
    val posture: LastPosture get() = LastPosture(folded, inCase)
    val facts: GlassesFacts get() = GlassesFacts(freeMb, volume, temperatureC)
}

/** How each pair was when last heard from, kept across launches; one per process, written by the service, read by every screen. */
@Singleton
class LastSeen @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val settings: GlassesSettings,
    live: LiveStream,
) {
    private val prefs = context.getSharedPreferences("pairs_seen", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val savedAt = mutableMapOf<String, Long>()
    private val _seen = MutableStateFlow(prefs.all.mapNotNull { (serial, text) -> (text as? String)?.let(::decode)?.let { serial to it } }.toMap())

    /** Every pair heard from, by serial. */
    val seen: StateFlow<Map<String, PairSeen>> = _seen.asStateFlow()

    private val current = combine(connection.pair, _seen) { serial, all -> serial?.let(all::get) }
    val battery: StateFlow<LastBattery?> = current.map { it?.battery }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val posture: StateFlow<LastPosture> =
        current.map { it?.posture ?: LastPosture(false, false) }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, LastPosture(false, false))

    /** The selected pair's model, connected or not; null until it has once said. */
    val product: StateFlow<Int?> = current.map { it?.product }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The pair Visor is for has its readings from the start.
        scope.launch { connection.pair.filterNotNull().collect { serial -> if (serial !in _seen.value) update(serial) { it } } }
        scope.launch { connection.state.collect(::record) }
        // The glasses' own name, unless one given here is still on its way to them.
        scope.launch {
            settings.state.map { Triple(it.pair, it.readAt != null, it.stored[NAME_KEY]) }.distinctUntilChanged().collect { (serial, wasRead, stored) ->
                if (serial == null || !wasRead) return@collect
                val mine = _seen.value[serial]?.takeIf { it.nameToSend }?.name
                when {
                    mine == null -> {
                        update(serial) { it.copy(name = stored?.takeIf { n -> n.isNotBlank() && n != LEFTOVER_NAME }) }
                        // A test write's leftover name is cleared.
                        if (stored == LEFTOVER_NAME) settings.writeQuietly(NAME_KEY, "")
                    }
                    stored == mine -> update(serial) { it.copy(nameToSend = false) }
                    else -> settings.write(NAME_KEY, mine)
                }
            }
        }
        // The glasses stream only open and out of the case, so a running Live says how they sit.
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
        productId(state.info?.build)?.let { id -> if (_seen.value[target.serial]?.product != id) update(target.serial) { it.copy(product = id) } }
        val device = state.device ?: return
        update(target.serial) { was ->
            val battery = device.battery
            var now = was
            if (battery != null) {
                // With no new case reading, one taken while it charged is out of date.
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
                now = now.copy(folded = device.hinge?.let { it == HINGE_CLOSED } ?: was.folded, inCase = inCase ?: was.inCase)
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
        val was = _seen.value[serial] ?: migrated()
        val now = change(was)
        if (now == _seen.value[serial]) return
        _seen.value = _seen.value + (serial to now)
        // Every reading moves the time; the file follows only a change, or a minute.
        if (now.copy(at = 0) != was.copy(at = 0) || now.at - (savedAt[serial] ?: 0) > SAVE_EVERY_MS) {
            savedAt[serial] = now.at
            prefs.edit().putString(serial, encode(now)).apply()
        }
    }

    /** The single-pair readings the "home" file held, moved to the first pair heard from. */
    private fun migrated(): PairSeen {
        val home = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
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
        s.product?.let { put("product", it) }
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
            product = o.optInt("product", -1).takeIf { it >= 0 },
        )
    }.getOrNull()

    companion object {
        const val NAME_KEY = "device_custom_name"
        const val LEFTOVER_NAME = "VisorTest"
        const val NAME_MAX = 40
        private const val SAVE_EVERY_MS = 60_000L
        private const val HOME_PREFS = "home"
    }
}

/** The phone's media volume in percent while media plays to the glasses at [address] (AVRCP absolute volume), else null. */
private fun heldVolume(context: Context, address: String?): Int? {
    address ?: return null
    val audio = context.getSystemService(AudioManager::class.java) ?: return null
    val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    } else {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }
    val glasses = route.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, ignoreCase = true) }
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (!glasses || max <= 0) return null
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
}
