package com.macedonga.visor.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.telephony.TelephonyManager
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.toBytes
import com.macedonga.visor.core.session.DataXConnection
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** svc10, ATC LinkManager2: moves the glasses onto a network. */
const val SVC_ATC = 10
private const val MT_JOIN_ACCESS_POINT = 0x1010
private const val MT_JOIN_ACCESS_POINT_RESULT = 0x1011

/** LeaveAccessPoint, empty: the glasses drop off the network they joined. */
const val MT_LEAVE_ACCESS_POINT = 0x1012

/** Link type 7, HIGH: what media is registered as, and what gets 4096-byte chunks on Wi-Fi. */
const val LINK_WIFI = 7

/** Media cannot come over Wi-Fi this time, for [message]. */
class WifiUnavailable(message: String, val kind: ImportProblem.Kind = ImportProblem.Kind.WIFI_FAILED) : Exception(message)

class JoinResult(val status: Int, val address: String?, val port: Int) {
    val ok: Boolean get() = status == 1 && !address.isNullOrEmpty() && port > 0
    val statusName: String get() = JOIN_STATUS.getOrElse(status) { "status $status" }
}

private val JOIN_STATUS = listOf(
    "UNKNOWN", "SUCCESS", "INTERNAL_ERROR", "JOIN_FAILED", "UNAVAILABLE", "BAD_INPUT", "TIMED_OUT",
    "CANCELLED", "BUSY", "WIFI_NOT_ENABLED", "THROTTLED_THERMAL", "THROTTLED_LOW_BATTERY",
)

/** JoinAccessPoint's bandwidth, the ATC Bandwidth enum. */
const val BW_UNSPECIFIED = 0
const val BW_20MHZ = 1
const val BW_40MHZ = 2
const val BW_80MHZ = 3

/**
 * JoinAccessPoint: put the glasses on [ssid]. A phone-owned group is an ordinary WPA2 access point
 * to them; JoinWifiDirectGroup has never worked. The uuid must be v4 or they answer status 2.
 * [bandwidth] is what the Meta app sends, but joining they take the access point's width anyway:
 * the glasses' ATC applies it only to a SoftAP of their own.
 */
private suspend fun joinAccessPoint(connection: DataXConnection, ssid: String, passphrase: String, bandwidth: Int = BW_UNSPECIFIED) {
    val raw = UUID.randomUUID().toBytes()
    val payload = Wire.fieldString(1, ssid) + Wire.fieldString(2, passphrase) + Wire.fieldBytes(3, raw) +
        Wire.fieldVarintNonZero(4, bandwidth.toLong())
    connection.sendOnService(SVC_ATC, MT_JOIN_ACCESS_POINT, payload)
}

/** JoinAccessPointResult: 1 status, 2 addresses (NetworkAddress, the address as text in 3), 3 port. */
private fun parseJoinResult(payload: ByteArray): JoinResult {
    val f = Wire.parse(payload)
    val address = f.repeatedBytes(2)
        .map { Wire.parse(it).bytes(3).decodeToString() }
        .firstOrNull { it.isNotEmpty() }
    return JoinResult(f.varint(1).toInt(), address, f.varint(3).toInt())
}

/** JoinAccessPoint and its answer; null if none came in [timeoutMs]. [before] runs first, on the same turn. */
suspend fun joinGlasses(
    connection: GlassesConnection,
    ssid: String,
    passphrase: String,
    bandwidth: Int = BW_UNSPECIFIED,
    timeoutMs: Long,
    before: suspend (DataXConnection) -> Unit = {},
): JoinResult? = withTimeoutOrNull(timeoutMs) {
    connection.serviceData
        .onSubscription { connection.submit { before(it); joinAccessPoint(it, ssid, passphrase, bandwidth) } }
        .first { it.serviceId == SVC_ATC && it.messageType == MT_JOIN_ACCESS_POINT_RESULT }
        .let { parseJoinResult(it.payload) }
}

private const val MT_CREATE_SOFT_AP = 0x1000
const val MT_SOFT_AP_INFO = 0x1001
const val MT_DESTROY_SOFT_AP = 0x1002
private const val BAND_SOFT_AP_2GHZ = 1L
private const val SOFT_AP_CHANNEL = 6L
// Where the glasses listen on every network they are on.
private const val GLASSES_PORT = 20203

/** SoftApNetworkInfo: 1 status, 2 addresses (NetworkAddress, the text in 3), 3 credentials {1 ssid, 2 password}, 4 port. */
class SoftApInfo(val status: Int, val address: String?, val port: Int, val ssid: String, val password: String) {
    val ok: Boolean get() = status == 1 && !address.isNullOrEmpty() && ssid.isNotEmpty()
    val statusName: String get() = SOFT_AP_STATUS.getOrElse(status) { "status $status" }
}

private val SOFT_AP_STATUS = listOf("UNKNOWN", "SUCCESS", "INTERNAL_ERROR", "START_AP_FAILED", "GET_IP_FAILED")

/**
 * CreateSoftApNetwork: the glasses host a network of their own, 2.4 GHz on channel 6 at 20 MHz. It is the one
 * mode where they take the width they are asked for; joining a network they take its width.
 */
suspend fun createSoftAp(connection: DataXConnection, ssid: String, password: String, country: String) {
    val raw = UUID.randomUUID().toBytes()
    val credentials = Wire.fieldString(1, ssid) + Wire.fieldString(2, password)
    val payload = Wire.fieldVarintNonZero(3, BAND_SOFT_AP_2GHZ) + Wire.fieldVarintNonZero(4, SOFT_AP_CHANNEL) +
        Wire.fieldString(6, country) + Wire.fieldBytes(7, raw) + Wire.fieldBytes(8, credentials) +
        Wire.fieldVarintNonZero(9, BW_20MHZ.toLong())
    connection.sendOnService(SVC_ATC, MT_CREATE_SOFT_AP, payload)
}

/** Their reply; the credentials they used, else [ssid] and [password], and port 20203 when they leave it out. */
fun parseSoftApInfo(payload: ByteArray, ssid: String, password: String): SoftApInfo {
    val f = Wire.parse(payload)
    val address = f.repeatedBytes(2)
        .map { Wire.parse(it).bytes(3).decodeToString() }
        .firstOrNull { it.isNotEmpty() }
        ?.substringBefore('/')
    val credentials = f.repeatedBytes(3).firstOrNull()?.let(Wire::parse)
    val theirSsid = credentials?.bytes(1)?.decodeToString().orEmpty()
    val theirPassword = credentials?.bytes(2)?.decodeToString().orEmpty()
    return SoftApInfo(
        f.varint(1).toInt(), address, f.varint(4).toInt().takeIf { it > 0 } ?: GLASSES_PORT,
        theirSsid.ifEmpty { ssid }, theirPassword.ifEmpty { password },
    )
}

/**
 * The phone on the glasses' own network, beside its own and not in place of it: Android asks the person to allow
 * the join, and the network carries no internet, so the phone keeps its own for everything else.
 */
class GlassesNetwork(private val context: Context) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun join(ssid: String, password: String, timeoutMs: Int = 60_000): Network = suspendCancellableCoroutine { waiting ->
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val answered = AtomicBoolean(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (answered.compareAndSet(false, true)) waiting.resume(network)
            }

            override fun onUnavailable() {
                if (answered.compareAndSet(false, true)) waiting.resumeWithException(WifiUnavailable("the phone did not join the glasses' network"))
            }
        }
        callback = cb
        manager.requestNetwork(request, cb, timeoutMs)
        waiting.invokeOnCancellation { leave() }
    }

    fun leave() {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }
}

/** The country code the glasses' network starts with: the mobile network's, else the phone's region. */
fun countryCode(context: Context): String =
    context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.uppercase()?.takeIf { it.length == 2 }
        ?: Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

/**
 * What Wi-Fi Direct needs at runtime: location before Android 13, Nearby devices from 13, and from
 * 17 local network access too, without which the glasses' socket silently never answers.
 */
val wifiDirectPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 37 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun wifiDirectAllowed(context: Context): Boolean =
    wifiDirectPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun wifiIsOn(context: Context): Boolean = context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

/** The frequency of the network the phone itself is on, or 0. */
@Suppress("DEPRECATION")
fun phoneWifiMhz(context: Context): Int = context.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency?.takeIf { it > 0 } ?: 0

/**
 * Whether the phone's radio takes turns between its own network on [own] and a group on [group] MHz: two channels
 * of one band always, and of two bands on a phone that cannot use both at once. The group then misses the glasses'
 * frames whenever the radio is away, and a moving pair loses the link.
 */
fun radioTakesTurns(context: Context, own: Int, group: Int): Boolean {
    if (own <= 0 || own == group) return false
    if ((own < 3000) == (group < 3000)) return true
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || context.getSystemService(WifiManager::class.java)?.isDualBandSimultaneousSupported != true
}

private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

internal fun randomPassword(): String =
    SecureRandom().let { r -> (1..20).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("") }

/** The Wi-Fi Direct group's band, as [PhoneGroup.create] takes it. */
const val BAND_AUTO = 0
const val BAND_2GHZ = 2
const val BAND_5GHZ = 5

/** A Wi-Fi Direct group this phone owns, the way the Meta app does it. The glasses only join. */
class PhoneGroup(private val context: Context, private val note: (String) -> Unit = {}) {
    /** What the glasses are told to join: ours, or Android's own when it would only make one of its own. */
    var ssid = "DIRECT-Vi-Visor"
        private set
    var passphrase: String = randomPassword()
        private set

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    // The glasses look for the group on the channel they last joined it on and answer INTERNAL_ERROR for half a
    // minute before they scan further, so each group goes up where the last one was when Android allows it. The
    // phone's own network's channel comes first: there the radio never has to leave the group.
    private val prefs = context.getSharedPreferences("wifi_direct", Context.MODE_PRIVATE)

    /**
     * [band] [BAND_2GHZ] or [BAND_5GHZ] pins the group to 2.4 or 5 GHz; [BAND_AUTO] lets the phone choose. Returns the
     * frequency in MHz. A dropped channel or a group a killed run left up can make the first try fail, so a fresh
     * channel tries again, and last Android makes a group with a name and password of its own.
     */
    @SuppressLint("MissingPermission")
    suspend fun create(band: Int = BAND_AUTO): Int {
        val m = manager ?: throw WifiUnavailable("this phone has no Wi-Fi Direct")
        val last = phoneWifiMhz(context).takeIf { inBand(it, band) } ?: prefs.getInt(LAST_MHZ, 0).takeIf { inBand(it, band) } ?: 0
        var why = ""
        var answered = false
        for (attempt in 0 until ATTEMPTS) {
            channel?.close()
            val ch = m.initialize(context, p2pLooper) { note("Wi-Fi Direct: Android dropped the channel") }
                ?: throw WifiUnavailable("Wi-Fi Direct would not start")
            channel = ch
            val state = withTimeoutOrNull(P2P_MILLIS) { suspendCancellableCoroutine { cont -> m.requestP2pState(ch) { cont.resume(it) } } }
            note("Wi-Fi Direct try ${attempt + 1}: state ${when (state) { null -> "no answer"; WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "on"; else -> "off" }}")
            if (state != null) answered = true
            if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                why = "Wi-Fi Direct is off on this phone"
                delay(RETRY_MILLIS)
                continue
            }
            // A group left up answers "removed" at once and goes a moment later; creating before it has gone fails.
            val removed = act { m.removeGroup(ch, it) }
            note("Wi-Fi Direct try ${attempt + 1}: old group ${if (removed == null) "removed" else reason(removed)}")
            if (removed == null) {
                for (i in 0 until 12) {
                    groupInfo(m, ch) ?: break
                    delay(250)
                }
            }
            val own = attempt == ATTEMPTS - 1
            val mhz = if (attempt == 0) last else 0
            val refused = if (own) act { m.createGroup(ch, it) } else act { m.createGroup(ch, config(band, mhz), it) }
            val which = if (own) "Android's own group" else "our group" + if (mhz > 0) " on $mhz MHz" else ""
            note("Wi-Fi Direct try ${attempt + 1}: $which ${if (refused == null) "accepted" else reason(refused)}")
            if (refused == null) {
                // createGroup only accepts the request; the group is up once it reports an owner.
                var up: WifiP2pGroup? = null
                for (i in 0 until 40) {
                    up = groupInfo(m, ch)?.takeIf { it.isGroupOwner }
                    if (up != null) break
                    delay(250)
                }
                when {
                    up == null -> why = "the group never came up"
                    // Android's own group takes no band, and a Live at 5 GHz crashes the glasses' Wi-Fi in seconds.
                    !inBand(up.frequency, band) -> why = "the group came up on ${up.frequency} MHz"
                    else -> {
                        if (own) {
                            ssid = up.networkName
                            passphrase = up.passphrase
                        }
                        prefs.edit().putInt(LAST_MHZ, up.frequency).apply()
                        return up.frequency
                    }
                }
                note("Wi-Fi Direct try ${attempt + 1}: $why")
            } else {
                if (refused != NO_ANSWER) answered = true
                why = reason(refused)
            }
            delay(RETRY_MILLIS)
        }
        // A group left up on the wrong band, or late, goes with the channel.
        channel?.let { ch ->
            if (answered) act { m.removeGroup(ch, it) }
            ch.close()
        }
        channel = null
        throw WifiUnavailable(
            "the phone would not create a Wi-Fi Direct group ($why)",
            if (answered) ImportProblem.Kind.WIFI_FAILED else ImportProblem.Kind.WIFI_STUCK,
        )
    }

    private suspend fun groupInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(P2P_MILLIS) { suspendCancellableCoroutine<WifiP2pGroup?> { cont -> m.requestGroupInfo(ch) { cont.resume(it) } } }

    // A frequency and a band are exclusive in the builder.
    private fun config(band: Int, mhz: Int) = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).apply {
        when {
            mhz > 0 -> setGroupOperatingFrequency(mhz)
            band == BAND_2GHZ -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            band == BAND_5GHZ -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        }
    }.build()

    private fun inBand(mhz: Int, band: Int) = when (band) {
        BAND_2GHZ -> mhz in 2400..2500
        BAND_5GHZ -> mhz in 4900..5900
        else -> mhz > 0
    }

    suspend fun remove() {
        val m = manager ?: return
        val ch = channel ?: return
        act { m.removeGroup(ch, it) }
        ch.close()
        channel = null
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_MILLIS = 1_000L
        const val LAST_MHZ = "last_mhz"

        fun reason(code: Int) = when (code) {
            NO_ANSWER -> "Android did not answer"
            WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.ERROR -> "internal error"
            else -> "error $code"
        }
    }
}

/** Wi-Fi Direct answers on a thread of its own, so nothing on the main thread can hold them up. */
private val p2pLooper: Looper by lazy { HandlerThread("visor-p2p").apply { start() }.looper }

/** A WifiP2pManager call: null on success, else its failure reason; NO_ANSWER past P2P_MILLIS, since a dropped channel never calls back. */
private suspend fun act(call: (WifiP2pManager.ActionListener) -> Unit): Int? {
    val answer = withTimeoutOrNull(P2P_MILLIS) {
        suspendCancellableCoroutine<Int> { cont ->
            call(
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cont.resume(SUCCESS)
                    override fun onFailure(reason: Int) = cont.resume(reason)
                },
            )
        }
    } ?: return NO_ANSWER
    return answer.takeIf { it != SUCCESS }
}

private const val SUCCESS = Int.MIN_VALUE
private const val P2P_MILLIS = 8_000L
private const val NO_ANSWER = -1
