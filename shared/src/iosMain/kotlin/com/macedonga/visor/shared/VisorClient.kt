@file:OptIn(ExperimentalForeignApi::class)

package com.macedonga.visor.shared

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.GlassesLink
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.connection.RealSessionHostFactory
import com.macedonga.visor.core.connection.TransportFactory
import com.macedonga.visor.core.identity.IdentityStore
import com.macedonga.visor.core.identity.parseBundles
import com.macedonga.visor.core.protocol.BtcProfiles
import com.macedonga.visor.core.protocol.PeerBuildInfo
import com.macedonga.visor.core.protocol.PhoneOs
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.core.transport.ConnectFailure
import com.macedonga.visor.core.transport.TransportConnectException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

/** What the iPhone app shows of the glasses, in plain values; -1 where nothing is known yet. */
data class VisorState(
    val serial: String,
    val noGlasses: Boolean,
    /** DISCONNECTED, CONNECTING, LINK_SETUP, TRUST, READY or LOST. */
    val link: String,
    val reason: String,
    /** Why a connect failed, as ConnectFailure names it, or empty. */
    val failure: String,
    /** HEALTHY, EVICTED, LINK_DOWN or UNKNOWN once judged, else empty. */
    val diagnosis: String,
    val battery: Int,
    val charging: Boolean,
    val caseBattery: Int,
    val worn: Boolean,
    val model: String,
    val build: String,
)

/** One pair this iPhone holds a key for; [linked] once it knows which glasses nearby are these. */
data class GlassesPair(val serial: String, val state: String, val linked: Boolean)

/** Ends a [VisorClient.watch]. */
class Watch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/**
 * Visor on an iPhone: the store, the connection and the Bluetooth link, behind calls Swift can make. Callbacks come on
 * the main queue.
 */
class VisorClient(private val appVersion: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = IdentityStore()

    private val connection = GlassesConnection(
        store,
        RealSessionHostFactory(
            transports = TransportFactory { link ->
                when (link) {
                    is GlassesLink.Bluetooth -> LeTransport(link.address)
                    is GlassesLink.Wifi -> throw TransportConnectException(ConnectFailure.BAD_ADDRESS, "Wi-Fi links are not on iPhone yet")
                }
            },
            responders = mapOf(PeerBuildInfo.SERVICE to PeerInfo(appVersion)),
        ),
    )

    init {
        // Each session: the glasses read this iPhone's notifications themselves, over ANCS.
        scope.launch {
            connection.state.map { it.link }.distinctUntilChanged().collect { link ->
                if (link == LinkState.READY) connection.submit { mux ->
                    mux.sendOnService(BtcProfiles.SERVICE, BtcProfiles.ANCS_ENABLE_REQUEST, ByteArray(0))
                }
            }
        }
    }

    fun pairs(): List<GlassesPair> = store.all().map { GlassesPair(it.serial, it.state, isIdentifier(it.glassesAddress)) }

    /** Keep the bundles in [json], one or an array, as lensbreak and Visor write them; returns their serials. */
    @Throws(Exception::class)
    fun importIdentity(json: String): List<String> {
        val bundles = parseBundles(json)
        bundles.forEach(store::save)
        bundles.lastOrNull()?.let { store.choose(it.serial) }
        return bundles.map { it.serial }
    }

    /** These glasses nearby, as this iPhone calls them, are the pair [serial]: connect to them from now on. */
    fun link(serial: String, identifier: String) {
        store.update(serial) { it.copy(glassesAddress = identifier) }
        store.choose(serial)
    }

    fun forget(serial: String) = store.remove(serial)

    fun start() = connection.start(scope)

    fun stop() {
        scope.launch { connection.stop() }
    }

    fun watch(onChange: (VisorState) -> Unit): Watch =
        Watch(scope.launch(Dispatchers.Main) { connection.state.collect { onChange(it.plain()) } })

    /** Report glasses heard nearby until [stopScan]; a Bluetooth problem comes as [onError]. */
    fun scan(onFound: (NearbyGlasses) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                Bluetooth.scan { found -> scope.launch(Dispatchers.Main) { onFound(found) } }
            } catch (error: Exception) {
                scope.launch(Dispatchers.Main) { onError(error.message ?: "Bluetooth is not ready") }
            }
        }
    }

    fun stopScan() {
        scope.launch { Bluetooth.stopScan() }
    }

    private fun GlassesState.plain() = VisorState(
        serial = target?.serial.orEmpty(),
        noGlasses = noGlasses,
        link = link.name,
        reason = reason,
        failure = failure?.name.orEmpty(),
        diagnosis = diagnosis?.name.orEmpty(),
        battery = device?.battery?.percent?.toInt() ?: -1,
        charging = device?.battery?.charging == true,
        caseBattery = device?.case?.percent ?: -1,
        worn = device?.worn == true,
        model = info?.model.orEmpty(),
        build = info?.build.orEmpty(),
    )

    // A peripheral id from this iPhone, not an address from another phone.
    private fun isIdentifier(address: String) = address.length == 36 && address.count { it == '-' } == 4
}

/** svc100: the Display's apps ask who the phone is; an iPhone, platform 1. */
private class PeerInfo(appVersion: String) : Responder {

    private val body = PeerBuildInfo.appInfo(
        PhoneOs.IOS,
        appVersion,
        UIDevice.currentDevice.systemVersion,
        "Apple",
        UIDevice.currentDevice.identifierForVendor?.UUIDString.orEmpty(),
        machine(),
    )

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        val info = message.info
        if (info.serviceId != PeerBuildInfo.SERVICE || info.messageType != PeerBuildInfo.GET_INFO) return
        if ((info.channelId and 0x8000) == 0 || info.flags.isClosed) return
        connection.send(DataXConnection.mirror(info.channelId), body, messageType = PeerBuildInfo.APP_INFO)
    }

    // The model identifier, such as iPhone15,2.
    private fun machine(): String = memScoped {
        val name = alloc<utsname>()
        if (uname(name.ptr) == 0) name.machine.toKString() else ""
    }
}
