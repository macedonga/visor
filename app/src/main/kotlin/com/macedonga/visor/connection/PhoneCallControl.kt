package com.macedonga.visor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.notify.GlassesNotification
import com.macedonga.visor.phone.mayOpenApps
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * svc70 call control, beside the texts: OUTGOING_CALL_REQUEST (4) by OUTGOING_CALL_RESPONSE (10) {1 request id,
 * 2 result, 3 error detail}; CALL_STATE_REQUEST (3) by CALL_STATE_RESPONSE (9) {1 call states}; ANSWER_INCOMING_CALL_REQUEST
 * (38) by its response (39) {1 request id, 2 success}; COMMS_HEALTH_REQUEST (59) by COMMS_HEALTH_RESPONSE (60). The other
 * listeners get the ack they wait for: 6 by 15, 13 by 14, 22 by 36. Cellular calls (provider 3) go through Telecom, and
 * WhatsApp calls open the contact's WhatsApp call row; any other provider is refused.
 */
@Singleton
class PhoneCallControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: RequestLog,
    private val log: EventLog,
) {
    suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.flags.isClosed) return
        val reply = DataXConnection.mirror(message.info.channelId)
        val request = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        when (message.info.messageType) {
            MT_CALL_STATE_REQUEST -> callState(reply, connection)
            MT_OUTGOING_CALL_REQUEST -> outgoing(request, reply, connection)
            MT_ANSWER_INCOMING_CALL_REQUEST -> answer(request, reply, connection)
            MT_COMMS_HEALTH_REQUEST -> commsHealth(request, reply, connection)
            MT_CALL_ENGINE_SUBSCRIPTION -> send("calls://engine/subscribe", reply, connection, MT_CALL_ENGINE_SUBSCRIPTION_ACK, ByteArray(0), "call engine subscription acknowledged")
            MT_OUTGOING_ERROR_LISTENER -> send("calls://errors/subscribe", reply, connection, MT_OUTGOING_ERROR_LISTENER_ACK, ByteArray(0), "call error listener acknowledged")
            MT_CALLING_SVC_SUBSCRIBE -> send("calls://svc/subscribe", reply, connection, MT_CALLING_SVC_ACK, ByteArray(0), "calling service state acknowledged")
        }
    }

    /** CallState on the phone's own line: ringing in as RINGING_INBOUND, a call under way as ACTIVE, else none. */
    @Suppress("DEPRECATION")
    private suspend fun callState(reply: Int, mux: DataXConnection) {
        if (!context.allows(Manifest.permission.READ_PHONE_STATE)) {
            return send(STATE_URL, reply, mux, MT_CALL_STATE_RESPONSE, ByteArray(0), "phone state not allowed", failed = true)
        }
        val state = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()
        val entry = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> STATE_RINGING_INBOUND
            TelephonyManager.CALL_STATE_OFFHOOK -> STATE_ACTIVE
            else -> null
        }
        // CallState {1 call id, 2 provider, 3 state}, in GetCallStatesResponse {1}.
        val body = if (entry == null) ByteArray(0) else
            Wire.fieldBytes(1, Wire.fieldString(1, CELLULAR_CALL_ID) + Wire.fieldVarint(2, GlassesNotification.PROVIDER_NATIVE.toLong()) + Wire.fieldVarint(3, entry))
        send(STATE_URL, reply, mux, MT_CALL_STATE_RESPONSE, body, if (entry == null) "no call" else "call state $entry")
    }

    /** OutgoingCallRequest {1 provider, 2 callee, 7 request id}. */
    private suspend fun outgoing(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val provider = request.varint(1).toInt()
        val callee = request.bytes(2).decodeToString()
        val (result, error) = when (provider) {
            GlassesNotification.PROVIDER_NATIVE -> placeCall(callee)
            GlassesNotification.PROVIDER_WHATSAPP -> startWhatsApp(callee)
            else -> RESULT_UNKNOWN to "Calls on this provider are not supported"
        }
        val body = Wire.fieldBytes(1, request.bytes(7)) + Wire.fieldVarint(2, result) +
            (error?.let { Wire.fieldString(3, it) } ?: ByteArray(0))
        send("calls://outgoing/$provider", reply, mux, MT_OUTGOING_CALL_RESPONSE, body, error ?: "call started", failed = result != RESULT_OK)
    }

    /** A cellular call to [number], as the glasses' own requests place it: null when it started, else why not. */
    fun call(number: String): String? = placeCall(number).second

    /** A WhatsApp call to the contact that owns [number]: null when it started, else why not. */
    fun whatsAppCall(number: String): String? = startWhatsApp(number).second

    /** Telecom takes the call; the call itself rides HFP to the glasses. */
    @SuppressLint("MissingPermission") // checked with allows(), and a refusal is caught
    private fun placeCall(number: String): Pair<Long, String?> {
        if (!context.allows(Manifest.permission.CALL_PHONE)) return ERROR_PERMISSION to "Call permission is not allowed in Visor"
        if (number.isBlank()) return RESULT_UNKNOWN to "No number to call"
        return try {
            val telecom = requireNotNull(context.getSystemService(TelecomManager::class.java))
            val extras = Bundle().apply { simFor(telecom)?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) } }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            RESULT_OK to null
        } catch (e: Exception) {
            RESULT_UNKNOWN to (e.message ?: e.toString())
        }
    }

    /**
     * With two SIMs and none set for calls, the phone asks on its screen, which the wearer may not see: the SIM of the
     * last call made goes instead. Null to leave it to the phone.
     */
    @SuppressLint("MissingPermission") // checked with allows()
    private fun simFor(telecom: TelecomManager): PhoneAccountHandle? = runCatching {
        if (!context.allows(Manifest.permission.READ_PHONE_STATE) || !context.allows(Manifest.permission.READ_CALL_LOG)) return null
        if (telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL) != null) return null
        val sims = telecom.callCapablePhoneAccounts.takeIf { it.size > 1 } ?: return null
        val last = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID),
            "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}", null, "${CallLog.Calls.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
        sims.firstOrNull { it.componentName.flattenToString() == last.first && it.id == last.second }
    }.getOrNull()

    /** Android has no call API for WhatsApp, so this opens the contact's WhatsApp call row, which starts the call. */
    private fun startWhatsApp(number: String): Pair<Long, String?> {
        if (!context.allows(Manifest.permission.READ_CONTACTS)) return ERROR_PERMISSION to "Contacts permission is not allowed in Visor"
        if (!mayOpenApps(context)) return RESULT_UNKNOWN to "Visor cannot open WhatsApp from the background"
        val row = whatsAppRow(number) ?: return RESULT_UNKNOWN to "No WhatsApp call for this number"
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row)).setPackage(WHATSAPP_PACKAGE)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            RESULT_OK to null
        } catch (e: Exception) {
            RESULT_UNKNOWN to (e.message ?: e.toString())
        }
    }

    /** The data row holding the WhatsApp call of the contact that owns [number]. */
    private fun whatsAppRow(number: String): Long? {
        if (number.isBlank()) return null
        val contact = context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contact.toString(), WHATSAPP_CALL),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** AnswerIncomingCallRequest {1 provider, 2 request id, 3 call id}: the ringing cellular call is taken. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // checked with allows(), and a refusal is caught
    private suspend fun answer(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val provider = request.varint(1).toInt()
        val error = when {
            provider != GlassesNotification.PROVIDER_NATIVE -> "Answering on this provider is not supported"
            !context.allows(Manifest.permission.ANSWER_PHONE_CALLS) || !context.allows(Manifest.permission.READ_PHONE_STATE) ->
                "Call permission is not allowed in Visor"
            !ringing() -> "No ringing call"
            else -> try {
                requireNotNull(context.getSystemService(TelecomManager::class.java)).acceptRingingCall()
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
        val body = Wire.fieldBytes(1, request.bytes(2)) + Wire.fieldVarint(2, if (error == null) 1L else 0L)
        send("calls://answer/$provider", reply, mux, MT_ANSWER_INCOMING_CALL_RESPONSE, body, error ?: "call answered", failed = error != null)
    }

    /** CommsHealthResponse {1 request id, 2 has state, 4 network, 5 Bluetooth, 6 network time, 7 state time}. */
    private suspend fun commsHealth(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val now = System.currentTimeMillis()
        val network = if (networkUp()) SIGNAL_PASS else SIGNAL_FAIL
        val bluetooth = if (bluetoothOn()) SIGNAL_PASS else SIGNAL_FAIL
        val body = Wire.fieldBytes(1, request.bytes(1)) + Wire.fieldVarint(2, 1) + Wire.fieldVarint(4, network) +
            Wire.fieldVarint(5, bluetooth) + Wire.fieldVarint(6, now) + Wire.fieldVarint(7, now)
        val outcome = "network ${signal(network)}, bluetooth ${signal(bluetooth)}"
        send("calls://health", reply, mux, MT_COMMS_HEALTH_RESPONSE, body, outcome, failed = network == SIGNAL_FAIL || bluetooth == SIGNAL_FAIL)
    }

    @Suppress("DEPRECATION")
    private fun ringing(): Boolean = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
    }.getOrDefault(false)

    private fun networkUp(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun bluetoothOn(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private suspend fun send(url: String, reply: Int, mux: DataXConnection, type: Int, body: ByteArray, outcome: String, failed: Boolean = false) {
        val id = requests.start(url, RequestSource.CALLS)
        // The glasses may have closed the channel by now; the answer is theirs to miss.
        runCatching { mux.send(reply, body, messageType = type) }
        requests.finish(id, outcome, failed, bytes = body.size.toLong())
        if (failed || type == MT_OUTGOING_CALL_RESPONSE || type == MT_ANSWER_INCOMING_CALL_RESPONSE) log.add("Calls: $outcome")
    }

    private fun signal(value: Long) = if (value == SIGNAL_PASS) "pass" else "fail"

    companion object {
        /** The types this class answers. PhoneMessages hands them here before its own checks. */
        val TYPES = setOf(
            MT_CALL_STATE_REQUEST, MT_OUTGOING_CALL_REQUEST, MT_CALL_ENGINE_SUBSCRIPTION, MT_OUTGOING_ERROR_LISTENER,
            MT_CALLING_SVC_SUBSCRIBE, MT_ANSWER_INCOMING_CALL_REQUEST, MT_COMMS_HEALTH_REQUEST,
        )
        private const val MT_CALL_STATE_REQUEST = 3
        private const val MT_OUTGOING_CALL_REQUEST = 4
        private const val MT_CALL_ENGINE_SUBSCRIPTION = 6
        private const val MT_CALL_STATE_RESPONSE = 9
        private const val MT_OUTGOING_CALL_RESPONSE = 10
        private const val MT_OUTGOING_ERROR_LISTENER = 13
        private const val MT_OUTGOING_ERROR_LISTENER_ACK = 14
        private const val MT_CALL_ENGINE_SUBSCRIPTION_ACK = 15
        private const val MT_ANSWER_INCOMING_CALL_REQUEST = 38
        private const val MT_ANSWER_INCOMING_CALL_RESPONSE = 39
        private const val MT_CALLING_SVC_SUBSCRIBE = 22
        private const val MT_CALLING_SVC_ACK = 36
        private const val MT_COMMS_HEALTH_REQUEST = 59
        private const val MT_COMMS_HEALTH_RESPONSE = 60
        private const val RESULT_OK = 1L
        private const val ERROR_PERMISSION = 4L
        private const val RESULT_UNKNOWN = 12L
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        private const val STATE_RINGING_INBOUND = 1L
        private const val STATE_ACTIVE = 3L
        private const val CELLULAR_CALL_ID = "cellular"
        private const val STATE_URL = "calls://state"
        private const val SIGNAL_PASS = 1L
        private const val SIGNAL_FAIL = 2L
    }
}
