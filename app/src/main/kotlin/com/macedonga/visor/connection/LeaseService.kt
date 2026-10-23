package com.macedonga.visor.connection

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * svc41107, lease_service: the glasses ask for internet (type 1, InternetLeaseRequest) and for a link (type 4,
 * LinkLeaseRequest), and end a lease (type 3, no answer). Internet leases are answered as the Meta app answers them
 * (2, use the glasses' own Wi-Fi); a link lease (5) on Bluetooth is granted, one that needs Wi-Fi moves the phone to
 * Wi-Fi Direct ([WifiLease]) when it can, and is refused otherwise.
 */
@Singleton
class LeaseService @Inject constructor(
    private val log: Provider<EventLog>,
    private val wifiLease: Provider<WifiLease>,
) : Responder {
    // Leases the glasses hold, by lease id, so an expiry can be named in the log.
    private val held = ConcurrentHashMap<String, String>()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val channel = DataXConnection.mirror(message.info.channelId)
        val request = Wire.parse(message.payload)
        val id = request.bytes(1)
        val key = id.joinToString("") { "%02x".format(it) }
        when (message.info.messageType) {
            MT_INTERNET_REQUEST -> {
                val type = INTERNET_TYPES.getOrElse(request.varint(2).toInt()) { "UNKNOWN" }
                // As the Meta app does: a block is kept and not answered; any other kind is told to use the glasses' own Wi-Fi.
                // The phone's internet still reaches the Display's apps through the tunnel (svc41108, svc41111).
                if (type == "BLOCK") {
                    log.get().add("Lease: the glasses blocked internet leases")
                    return
                }
                log.get().add("Lease: the glasses asked for internet ($type); answered: use the glasses' Wi-Fi")
                connection.send(channel, Wire.fieldBytes(1, id) + refusal(ERROR_USE_DEVICE_WIFI, "ERROR_CODE_USE_DEVICE_WIFI"), messageType = MT_INTERNET_RESPONSE)
            }
            MT_LINK_REQUEST -> {
                // Field 7 is a link (2 Wi-Fi Direct, 3 SoftAP, 4 local Wi-Fi); field 6 a bandwidth (1 medium, 2 high).
                val wifi = when {
                    request.has(7) -> request.varint(7) in 2..4
                    request.has(6) -> request.varint(6) >= 1
                    else -> null
                }
                // Checked before anything is answered or moved: a Wi-Fi link the phone cannot take is refused.
                val unavailable = if (wifi == true) wifiLease.get().refusal() else null
                val granted = wifi == false || (wifi == true && unavailable == null)
                val reply = when {
                    wifi == null -> Wire.fieldBytes(1, id) + refusal(ERROR_BAD_REQUEST, "Invalid Request")
                    granted -> Wire.fieldBytes(1, id)
                    else -> Wire.fieldBytes(1, id) + refusal(ERROR_WIFI_DIRECT_NOT_AVAILABLE, "Wi-Fi is not available for this link.")
                }
                log.get().add(
                    when {
                        wifi == null -> "Lease: the glasses asked for a link with no requirement; refused"
                        wifi == false -> "Lease: the glasses asked for a link on Bluetooth; answered OK"
                        unavailable != null -> "Lease: the glasses asked for Wi-Fi; refused, $unavailable"
                        else -> "Lease: the glasses asked for Wi-Fi; answered OK"
                    },
                )
                if (wifi == false) held[key] = "link"
                if (wifi == true && granted) {
                    held[key] = "Wi-Fi link"
                    wifiLease.get().add(key)
                }
                connection.send(channel, reply, messageType = MT_LINK_RESPONSE)
                if (wifi == true && granted) wifiLease.get().hold()
            }
            MT_LEASE_EXPIRATION -> {
                held.remove(key)?.let { log.get().add("Lease: a $it lease ended") }
                wifiLease.get().remove(key)
            }
            else -> log.get().add("Lease: message type ${message.info.messageType} is not handled")
        }
    }

    private fun refusal(code: Int, text: String): ByteArray =
        Wire.fieldBytes(2, Wire.fieldVarint(1, code.toLong()) + Wire.fieldBytes(2, text.encodeToByteArray()))

    companion object {
        const val SVC = 41107
        private const val MT_INTERNET_REQUEST = 1
        private const val MT_INTERNET_RESPONSE = 2
        private const val MT_LEASE_EXPIRATION = 3
        private const val MT_LINK_REQUEST = 4
        private const val MT_LINK_RESPONSE = 5
        private val INTERNET_TYPES = listOf("AUTO_BIND", "WARM", "WARM_THEN_IDLE", "BLOCK")
        private const val ERROR_BAD_REQUEST = 0
        private const val ERROR_USE_DEVICE_WIFI = 4
        private const val ERROR_WIFI_DIRECT_NOT_AVAILABLE = 104
    }
}
