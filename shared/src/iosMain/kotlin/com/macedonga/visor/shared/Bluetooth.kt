@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.macedonga.visor.shared

import com.macedonga.visor.core.transport.ConnectFailure
import com.macedonga.visor.core.transport.TransportConnectException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateDisconnected
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/** The glasses' GATT service, which they also advertise. */
internal const val META_SERVICE = "FD5F"

/** Meta Platforms' Bluetooth company id, at the head of the glasses' manufacturer data. */
internal const val META_COMPANY_ID = 0x01AB

/** Glasses heard advertising: [identifier] is this iPhone's id for them, the only one iOS gives. */
data class NearbyGlasses(val identifier: String, val name: String, val rssi: Int, val productId: Int)

/**
 * The one CBCentralManager, and the callbacks the transports wait on. Every call into CoreBluetooth and every callback
 * runs on the main queue, so the waits need no lock.
 */
internal object Bluetooth {

    private val delegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private var manager: CBCentralManager? = null

    val state = MutableStateFlow(CBManagerStateUnknown)

    private val connects = HashMap<String, CompletableDeferred<NSError?>>()
    private val channels = HashMap<String, CompletableDeferred<CBL2CAPChannel?>>()
    private val disconnects = HashMap<String, (String) -> Unit>()
    private var onFound: ((NearbyGlasses) -> Unit)? = null

    private fun central(): CBCentralManager = manager ?: CBCentralManager(delegate, null).also { manager = it }

    /** The powered-on manager, or why there is none. */
    suspend fun ready(): CBCentralManager = withContext(Dispatchers.Main) {
        val central = central()
        val settled = withTimeoutOrNull(STATE_MS) { state.first { it != CBManagerStateUnknown && it != CBManagerStateResetting } } ?: state.value
        when (settled) {
            CBManagerStatePoweredOn -> central
            CBManagerStatePoweredOff -> throw TransportConnectException(ConnectFailure.BLUETOOTH_OFF, "Bluetooth is off")
            CBManagerStateUnauthorized -> throw TransportConnectException(ConnectFailure.PERMISSION_REFUSED, "Visor may not use Bluetooth yet")
            CBManagerStateUnsupported -> throw TransportConnectException(ConnectFailure.BLUETOOTH_UNSUPPORTED, "this iPhone has no Bluetooth LE")
            else -> throw TransportConnectException(ConnectFailure.NO_ANSWER, "Bluetooth is not ready")
        }
    }

    /** The peripheral this iPhone knows by [identifier], from an earlier scan or connection. */
    suspend fun peripheral(identifier: String): CBPeripheral {
        val central = ready()
        val uuid = NSUUID(uUIDString = identifier)
        return withContext(Dispatchers.Main) {
            central.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as? CBPeripheral
        } ?: throw TransportConnectException(ConnectFailure.BAD_ADDRESS, "this iPhone has not met these glasses yet; pick them from the nearby list")
    }

    /** Connect, or throw why not. [onDisconnect] hears the link go later. */
    suspend fun connect(peripheral: CBPeripheral, timeoutMs: Long, onDisconnect: (String) -> Unit) {
        val central = ready()
        val id = peripheral.identifier.UUIDString
        // A link this app just closed reports its end later; its callback must not land on the new one.
        withTimeoutOrNull(SETTLE_MS) {
            while (withContext(Dispatchers.Main) { peripheral.state } != CBPeripheralStateDisconnected) delay(50)
        }
        val done = CompletableDeferred<NSError?>()
        withContext(Dispatchers.Main) {
            connects[id] = done
            disconnects[id] = onDisconnect
            peripheral.delegate = peripheralDelegate
            central.connectPeripheral(peripheral, null)
        }
        val error = withTimeoutOrNull(timeoutMs) { done.await() }
        withContext(Dispatchers.Main) { connects.remove(id) }
        if (!done.isCompleted) {
            disconnect(peripheral)
            throw TransportConnectException(ConnectFailure.NO_ANSWER, "nothing answered: asleep, in the case or out of range")
        }
        if (error != null) throw TransportConnectException(ConnectFailure.NO_ANSWER, "the glasses would not connect: ${error.localizedDescription}")
    }

    /** Open the L2CAP channel on [psm]. iOS pairs first if the glasses ask for encryption. */
    suspend fun openL2cap(peripheral: CBPeripheral, psm: Int, timeoutMs: Long): CBL2CAPChannel {
        val id = peripheral.identifier.UUIDString
        val done = CompletableDeferred<CBL2CAPChannel?>()
        withContext(Dispatchers.Main) {
            channels[id] = done
            peripheral.openL2CAPChannel(psm.toUShort())
        }
        val channel = withTimeoutOrNull(timeoutMs) { done.await() }
        withContext(Dispatchers.Main) { channels.remove(id) }
        return channel ?: throw TransportConnectException(ConnectFailure.NO_ANSWER, "the glasses did not open L2CAP psm $psm")
    }

    suspend fun disconnect(peripheral: CBPeripheral) = withContext(Dispatchers.Main) {
        disconnects.remove(peripheral.identifier.UUIDString)
        manager?.cancelPeripheralConnection(peripheral)
        Unit
    }

    /** Report every pair of glasses heard, until [stopScan]. */
    suspend fun scan(found: (NearbyGlasses) -> Unit) {
        val central = ready()
        withContext(Dispatchers.Main) {
            onFound = found
            central.scanForPeripheralsWithServices(listOf(CBUUID.UUIDWithString(META_SERVICE)), mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to false))
        }
    }

    suspend fun stopScan() = withContext(Dispatchers.Main) {
        onFound = null
        manager?.stopScan()
        Unit
    }

    private class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            state.value = central.state
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            connects[didConnectPeripheral.identifier.UUIDString]?.complete(null)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            connects[didFailToConnectPeripheral.identifier.UUIDString]?.complete(error)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            channels.remove(id)?.complete(null)
            disconnects.remove(id)?.invoke(error?.localizedDescription ?: "the glasses closed the link")
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val found = onFound ?: return
            val maker = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray() ?: ByteArray(0)
            // Company id, then the product id, both little-endian: 0x0102 is Ray-Ban Meta Gen 2.
            val product = if (maker.size >= 4) (maker[2].toInt() and 0xFF) or ((maker[3].toInt() and 0xFF) shl 8) else 0
            val name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?: didDiscoverPeripheral.name ?: "Meta glasses"
            found(NearbyGlasses(didDiscoverPeripheral.identifier.UUIDString, name, RSSI.intValue, product))
        }
    }

    private class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didOpenL2CAPChannel: CBL2CAPChannel?, error: NSError?) {
            channels[peripheral.identifier.UUIDString]?.complete(if (error == null) didOpenL2CAPChannel else null)
        }
    }

    private const val STATE_MS = 5_000L
    private const val SETTLE_MS = 3_000L

}
