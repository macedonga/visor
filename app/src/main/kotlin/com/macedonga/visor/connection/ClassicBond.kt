package com.macedonga.visor.connection

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesTarget
import com.macedonga.visor.core.connection.Nexus
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.transport.LeKeeper
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.pairs.Pairs
import com.macedonga.visor.phone.companionId
import com.macedonga.visor.phone.disassociateCompanion
import com.macedonga.visor.phone.removeCompanionBond
import com.macedonga.visor.setup.BondFailed
import com.macedonga.visor.setup.NearbyGlasses
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A Display's classic bond for calls (Android 17+): PROBE reads, REBOND swaps the LE bond for BR/EDR in its pairing window, UNDO goes back. */
@Singleton
class ClassicBond @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val store: IdentityStore,
    private val nearby: NearbyGlasses,
    private val keeper: LeKeeper,
    private val live: LiveStream,
    private val media: MediaImport,
    private val pairs: Pairs,
    private val log: EventLog,
) {
    enum class Action { PROBE, REBOND, UNDO }

    sealed interface Status {
        data object Idle : Status
        data class Running(val repairing: Boolean) : Status
        data class Probed(val verdict: Verdict) : Status
        data class Done(val calls: Boolean) : Status
        data class Failed(val reason: Reason, val detail: String = "") : Status
    }

    enum class Verdict { CLASSIC_PRESENT, CALLS_CONNECTED, UNREACHABLE, READY }

    enum class Reason { UNSUPPORTED, NOT_CONNECTED, NO_ADDRESS, BLUETOOTH_OFF, BUSY, NO_COMPANION, UNREACHABLE, REFUSED, UNPAIR_FAILED, RESTORED, LOST, FAILED }

    private class Stop(val reason: Reason, val detail: String = "") : Exception(detail)

    private class Glasses(val serial: String, val address: String, val classic: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nexus = Nexus(connection)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    // While the bond is being swapped: keeps the link let go.
    @Volatile
    private var guard: Job? = null

    // The LE address each pair had before its classic bond, by serial: what an undo bonds again.
    private val leAddresses get() = context.getSharedPreferences("classic_bond", Context.MODE_PRIVATE)

    /** Whether the pair connected now has a classic bond made here, with its LE address kept from before it. */
    val undoable: StateFlow<Boolean> = combine(_status, connection.state.map { it.target }.distinctUntilChanged()) { _, target -> canUndo(target) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    fun start(action: Action) {
        scope.launch { perform(action) }
    }

    /** Runs [action] and returns how it ended; while one runs, another returns the status at once. */
    suspend fun perform(action: Action): Status {
        if (!lock.tryLock()) return _status.value
        try {
            _status.value = Status.Running(action != Action.PROBE)
            note(action.name.lowercase())
            val end = try {
                if (Build.VERSION.SDK_INT < 37) throw Stop(Reason.UNSUPPORTED)
                when (action) {
                    Action.PROBE -> Status.Probed(verdict(glasses()))
                    Action.REBOND -> rebond()
                    Action.UNDO -> undo()
                }
            } catch (stop: Stop) {
                Status.Failed(stop.reason, stop.detail)
            } catch (cancelled: CancellationException) {
                _status.value = Status.Idle
                throw cancelled
            } catch (error: Exception) {
                Status.Failed(Reason.FAILED, error.message ?: error.javaClass.simpleName)
            }
            note("ended: $end")
            _status.value = end
            return end
        } finally {
            lock.unlock()
        }
    }

    private fun canUndo(target: GlassesTarget?): Boolean {
        target ?: return false
        val address = (target.link as? GlassesLink.Bluetooth)?.address ?: return false
        val le = leAddresses.getString(target.serial, null) ?: return false
        return !le.equals(address, ignoreCase = true)
    }

    private fun glasses(): Glasses {
        val state = connection.state.value
        val target = state.target
        // On a Wi-Fi link the session's target has no Bluetooth address; the pair's own is in its bundle.
        val address = (target?.link as? GlassesLink.Bluetooth)?.address
            ?: target?.serial?.let { s -> runCatching { store.load(s)?.glassesAddress }.getOrNull() }
        if (state.link != LinkState.READY || target == null || address == null) throw Stop(Reason.NOT_CONNECTED)
        if (!isDisplayBuild(state.info?.build)) throw Stop(Reason.UNSUPPORTED)
        val classic = state.info?.btcAddress.orEmpty().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(classic)) throw Stop(Reason.NO_ADDRESS)
        if (!nearby.enabled) throw Stop(Reason.BLUETOOTH_OFF)
        return Glasses(target.serial, address.uppercase(), classic)
    }

    /** The bonds as the phone holds them and the Display's HFP state; changes nothing. */
    @RequiresApi(37)
    private suspend fun verdict(g: Glasses): Verdict {
        note("classic address ${g.classic}, pair address ${g.address}, companion association ${companionId(context, g.address) ?: "none"}")
        note("bonded: ${bonded()}")
        note(nearby.describe(g.address))
        if (!g.classic.equals(g.address, ignoreCase = true)) note(nearby.describe(g.classic))
        val reply = nexus.request(Nexus.CHECK_CONNECTED_TELEPHONY)
        val calls = Nexus.telephony(reply)
        note("telephony check: ${reply ?: "no answer in 5 s"}" + (calls?.let { ", status $it" } ?: ""))
        val listed = nearby.bondedTypes().map { it.first }
        val classicListed = listed.any { it.equals(g.classic, ignoreCase = true) }
        val leListed = !g.address.equals(g.classic, ignoreCase = true) && listed.any { it.equals(g.address, ignoreCase = true) }
        return when {
            classicListed && !leListed -> Verdict.CLASSIC_PRESENT
            calls == null -> Verdict.UNREACHABLE
            calls == Nexus.CONNECTED -> Verdict.CALLS_CONNECTED
            else -> Verdict.READY
        }
    }

    /** The LE bond swapped for a BR/EDR one inside the Display's pairing window. */
    @RequiresApi(37)
    private suspend fun rebond(): Status {
        val g = glasses()
        free()
        val verdict = verdict(g)
        if (verdict != Verdict.READY) return Status.Probed(verdict)
        val id = companionId(context, g.address) ?: throw Stop(Reason.NO_COMPANION, g.address)
        val opened = openWindow()
        letGo()
        var paired = false
        try {
            unpair(g, id, listOf(g.address, g.classic))
            val type = try {
                nearby.bondClassic(g.classic, opened + CLASSIC_MS)
            } catch (failed: BondFailed) {
                note("BR/EDR bond failed: ${failed.why}; bonding over LE again")
                nearby.cancelBond(g.classic)
                null
            }
            // A bond ends the window; EXIT_PAIRING_MODE after one shows a pairing failure on the glasses.
            if (type == null) {
                val le = bondLe(g.address)
                comeBack(g.serial, le ?: g.address)
                throw Stop(if (le == null) Reason.LOST else Reason.RESTORED)
            }
            paired = true
            note("bonded over BR/EDR, type $type; bonded: ${bonded()}")
            leAddresses.edit().putString(g.serial, g.address).apply()
            // The old association names an address no longer bonded; Visor asks for the new one once connected.
            return settle(g, id, g.classic)
        } catch (error: Exception) {
            recover(error, g, if (paired) g.classic else g.address, paired)
        } finally {
            keepLetGo(false)
        }
    }

    /** Back to the LE bond the pair had before [rebond], the classic one again if that fails inside the window. */
    @RequiresApi(37)
    private suspend fun undo(): Status {
        val g = glasses()
        free()
        val le = leAddresses.getString(g.serial, null)?.takeIf { !it.equals(g.address, ignoreCase = true) } ?: throw Stop(Reason.FAILED, "no LE address kept")
        val id = companionId(context, g.address) ?: throw Stop(Reason.NO_COMPANION, g.address)
        note("back to the LE bond on $le")
        val opened = openWindow()
        letGo()
        var paired: String? = null
        try {
            unpair(g, id, listOf(g.address))
            val address = bondLe(le)
            if (address == null) {
                val again = try {
                    nearby.bondClassic(g.address, opened + WINDOW_MS)
                    true
                } catch (failed: BondFailed) {
                    note("BR/EDR bond failed: ${failed.why}")
                    false
                }
                comeBack(g.serial, g.address)
                throw Stop(if (again) Reason.RESTORED else Reason.LOST)
            }
            paired = address
            leAddresses.edit().remove(g.serial).apply()
            return settle(g, id, address)
        } catch (error: Exception) {
            recover(error, g, paired ?: g.address, paired != null)
        } finally {
            keepLetGo(false)
        }
    }

    /** The old companion association dropped and the session opened on [address]; whether calls then work. */
    @RequiresApi(37)
    private suspend fun settle(g: Glasses, id: Int, address: String): Status {
        note("companion association $id dropped: ${disassociateCompanion(context, id)}")
        return Status.Done(comeBack(g.serial, address) && calls())
    }

    /** A failure inside the pairing window: the pair brought back on [address] while let go, the window closed while unpaired. */
    private suspend fun recover(error: Exception, g: Glasses, address: String, paired: Boolean): Nothing {
        if (error is Stop || error is CancellationException) throw error
        note("failed in the pairing window: $error")
        if (guard != null) comeBack(g.serial, address)
        if (!paired) closeWindow()
        throw error
    }

    /** Nothing else may have the glasses or the phone's audio while the bond goes. */
    private fun free() {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        val call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_CALL_SCREENING
        if (call || live.usingWifi || media.importing.value || connection.onWifi) throw Stop(Reason.BUSY)
    }

    /** ENTER_PAIRING_MODE: the Display takes a new bond from any phone for 120 s. Returns when the window opened. */
    private suspend fun openWindow(): Long {
        val answer = nexus.request(Nexus.ENTER_PAIRING_MODE)
        note("pairing mode: ${answer ?: "no answer"}")
        if (Nexus.ok(answer)) return SystemClock.elapsedRealtime()
        if (answer == null) closeWindow()
        throw Stop(if (answer == null) Reason.UNREACHABLE else Reason.REFUSED, answer?.toString().orEmpty())
    }

    private suspend fun closeWindow() {
        if (connection.state.value.link != LinkState.READY) return note("no session to close the pairing window; it closes on its own")
        note("pairing mode closed: ${nexus.request(Nexus.EXIT_PAIRING_MODE) ?: "no answer"}")
    }

    /** The session and the LE link let go, and kept so: an LE connect now would pair over LE in the window. */
    private suspend fun letGo() {
        connection.pause("re-pairing")
        keeper.release()
        note("link let go")
        keepLetGo(true)
    }

    private fun keepLetGo(on: Boolean) {
        guard?.cancel()
        guard = if (!on) null else scope.launch {
            connection.state.map { it.link }.distinctUntilChanged().collect { link ->
                if (link == LinkState.DISCONNECTED) return@collect
                note("a reconnect came during the re-pairing; letting go again")
                connection.pause("re-pairing")
                keeper.release()
            }
        }
    }

    /** Has Android remove the bond through association [id], and waits until none of [addresses] is bonded. */
    @RequiresApi(36)
    private suspend fun unpair(g: Glasses, id: Int, addresses: List<String>) {
        note("removing the bond through companion association $id")
        val removed = removeCompanionBond(context, id) { note("removeBond failed: $it") }
        val gone = removed && withTimeoutOrNull(UNPAIR_MS) {
            while (nearby.bondedTypes().any { (a, _) -> addresses.any { it.equals(a, ignoreCase = true) } }) delay(POLL_MS)
            true
        } == true
        note(if (gone) "bond removed; bonded: ${bonded()}" else if (removed) "the phone still lists the bond" else "the phone did not remove the bond")
        if (gone) return
        comeBack(g.serial, g.address)
        closeWindow()
        throw Stop(Reason.UNPAIR_FAILED)
    }

    /** Visor's own LE bond, a scan first so the phone knows [address] as an LE one, as at setup; the address it is kept under. */
    private suspend fun bondLe(address: String): String? {
        val heard = try {
            withTimeoutOrNull(SCAN_MS) { nearby.scan().first { list -> list.any { it.address.equals(address, ignoreCase = true) } } } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            note("scan failed: ${error.message}")
            false
        }
        note(if (heard) "heard $address" else "did not hear $address; bonding anyway")
        return try {
            nearby.bond(address).also { note("bonded over LE as $it; bonded: ${bonded()}") }
        } catch (failed: BondFailed) {
            note("LE bond failed: ${failed.why}")
            null
        }
    }

    /** The pair kept under [address] from now on, the LE link held and the session opened on it; true once it is up. */
    private suspend fun comeBack(serial: String, address: String): Boolean {
        keepLetGo(false)
        withContext(Dispatchers.IO) {
            val moved = store.update(serial) { if (it.glassesAddress.equals(address, ignoreCase = true)) it else it.copy(glassesAddress = address) }
            if (moved) note("pair address now $address")
        }
        pairs.refresh()
        keeper.hold(address)
        connection.restart(scope, GlassesTarget(GlassesLink.Bluetooth(address), serial))
        val up = withTimeoutOrNull(SESSION_MS) { connection.state.first { it.link == LinkState.READY } } != null
        note(if (up) "connected again on $address" else "no session on $address yet")
        return up
    }

    /** Whether the Display has HFP to the phone, after one CONNECT_TELEPHONY when it has not. */
    private suspend fun calls(): Boolean {
        var status = Nexus.telephony(nexus.request(Nexus.CHECK_CONNECTED_TELEPHONY))
        note("telephony: ${status ?: "no answer"}")
        if (status == Nexus.CONNECTED) return true
        if (status == Nexus.DISCONNECTED) note("connect telephony: ${nexus.request(Nexus.CONNECT_TELEPHONY, millis = TELEPHONY_MS) ?: "no answer"}")
        delay(TELEPHONY_MS)
        status = Nexus.telephony(nexus.request(Nexus.CHECK_CONNECTED_TELEPHONY))
        note("telephony: ${status ?: "no answer"}")
        return status == Nexus.CONNECTED
    }

    private fun bonded(): String = nearby.bondedTypes().joinToString { "${it.first} type ${it.second}" }.ifEmpty { "none" }

    private fun note(text: String) = log.add("Classic bond: $text")

    private companion object {
        // From ENTER_PAIRING_MODE: the BR/EDR bond gives up here, leaving the LE fallback the rest of the 120 s.
        const val CLASSIC_MS = 60_000L
        const val WINDOW_MS = 120_000L
        const val UNPAIR_MS = 10_000L
        const val SCAN_MS = 10_000L
        const val SESSION_MS = 60_000L
        const val TELEPHONY_MS = 10_000L
        const val POLL_MS = 400L
    }
}
