package com.macedonga.visor.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.provision.WrongOwnerError
import com.macedonga.visor.core.identity.devicePublicKey
import com.macedonga.visor.core.identity.provision.ProvisionStep
import com.macedonga.visor.core.identity.provision.provision
import com.macedonga.visor.core.protocol.Band
import com.macedonga.visor.core.transport.BluetoothLeTransport
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.login.MetaLogin
import com.macedonga.visor.setup.BondFailed
import com.macedonga.visor.setup.NearbyGlasses
import com.macedonga.visor.setup.words
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Change owner on the Neural Band, as on the glasses: same svc36 steps, same owner key, over its own PSM. */
@Singleton
class BandSetup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: GlassesConnection,
    private val store: IdentityStore,
    private val bands: Bands,
    private val link: BandLink,
    private val nearby: NearbyGlasses,
    private val login: MetaLogin,
    private val log: EventLog,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val step: ProvisionStep?) : Status
        data class Done(val serial: String) : Status
        data class Failed(val reason: Reason, val detail: String = "") : Status
    }

    enum class Reason { NO_GLASSES, NOT_PAIRED, NO_ANSWER, WRONG_OWNER, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start() {
        if (_status.value is Status.Running) return
        _status.value = Status.Running(null)
        scope.launch {
            val glasses = connection.pair.value
            val owned = withContext(Dispatchers.IO) { store.ownerKeyPem() != null }
            if (glasses == null || !owned) return@launch fail(Reason.NO_GLASSES)
            link.release()
            try {
                // Not bonded yet: the band heard nearby is bonded here, as the glasses are.
                val candidates = bondedBands().ifEmpty { listOfNotNull(nearby.bandNearby()?.let { nearby.bond(it) }) }
                if (candidates.isEmpty()) return@launch fail(Reason.NOT_PAIRED)
                // A band paired more than once leaves a bond per pairing; the one that answers is the band.
                val address = candidates.firstOrNull { reachable(it) } ?: return@launch fail(Reason.NO_ANSWER)
                log.add("Band setup: changing owner on $address")
                val result = provision(
                    transport = BluetoothLeTransport(context, address, defaultPsm = Band.PSM),
                    // No existing key: the band gets one of its own, as the Meta app keeps one per device.
                    source = login.source,
                ) { step ->
                    _status.value = Status.Running(step)
                    log.add("Band setup: ${step.words()}")
                }
                // The key Meta's final receipt names is the one the Display's lease hints use; the certificate's is a fallback.
                val publicKey = result.deviceEcPublicKey ?: result.deviceCert?.let(::devicePublicKey)
                bands.save(BandRecord(result.serial, address, publicKey, glasses, key = result.keyPem))
                val from = if (result.deviceEcPublicKey != null) "receipt" else if (publicKey != null) "certificate" else "none"
                log.add("Band setup: owner changed. Serial ${result.serial}, device key from $from")
                _status.value = Status.Done(result.serial)
            } catch (failed: BondFailed) {
                fail(if (failed.why == BondFailed.Why.NO_ANSWER) Reason.NO_ANSWER else Reason.FAILED, failed.why.name)
            } catch (error: Exception) {
                val wrong = error is WrongOwnerError
                fail(if (wrong) Reason.WRONG_OWNER else Reason.FAILED, error.message ?: error.javaClass.simpleName)
            } finally {
                link.hold()
            }
        }
    }

    fun reset() {
        if (_status.value !is Status.Running) _status.value = Status.Idle
    }

    private fun fail(reason: Reason, detail: String = "") {
        log.add("Band setup failed: $reason $detail")
        _status.value = Status.Failed(reason, detail)
    }

    @SuppressLint("MissingPermission")
    private fun bondedBands(): List<String> = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && it.name?.startsWith(BAND_NAME) == true }
            .map { it.address }
    }.getOrDefault(emptyList())

    /** A GATT connection that comes up within a few seconds: this bond is the band's current one. */
    @SuppressLint("MissingPermission")
    private suspend fun reachable(address: String): Boolean {
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return false
        val up = CompletableDeferred<Boolean>()
        val gatt = device.connectGatt(
            context, false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    up.complete(newState == BluetoothProfile.STATE_CONNECTED)
                }
            },
            BluetoothDevice.TRANSPORT_LE,
        ) ?: return false
        return try {
            withTimeoutOrNull(PROBE_MS) { up.await() } == true
        } finally {
            gatt.disconnect()
            gatt.close()
        }
    }

    private companion object {
        const val BAND_NAME = "Meta Band"
        const val PROBE_MS = 6_000L
    }
}
