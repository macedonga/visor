package com.macedonga.visor.connection

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.phone.companionAssociated
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
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
    private val log: Provider<EventLog>,
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
        if (!allowed(Manifest.permission.READ_PHONE_STATE)) {
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
            Wire.fieldBytes(1, Wire.fieldBytes(1, CELLULAR_CALL_ID.encodeToByteArray()) + Wire.fieldVarint(2, NATIVE) + Wire.fieldVarint(3, entry))
        send(STATE_URL, reply, mux, MT_CALL_STATE_RESPONSE, body, if (entry == null) "no call" else "call state $entry")
    }

    /** OutgoingCallRequest {1 provider, 2 callee, 7 request id}. */
    private suspend fun outgoing(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val provider = request.varint(1)
        val callee = request.bytes(2).decodeToString()
        val (result, error) = when (provider) {
            NATIVE -> placeCall(callee)
            WHATSAPP_PROVIDER -> startWhatsApp(callee)
            else -> RESULT_UNKNOWN to "Calls on this provider are not supported"
        }
        val body = Wire.fieldBytes(1, request.bytes(7)) + Wire.fieldVarint(2, result) +
            (error?.let { Wire.fieldBytes(3, it.encodeToByteArray()) } ?: ByteArray(0))
        send("calls://outgoing/$provider", reply, mux, MT_OUTGOING_CALL_RESPONSE, body, error ?: "call started", failed = result != RESULT_OK)
    }

    /** Telecom takes the call; the call itself rides HFP to the glasses. */
    private fun placeCall(number: String): Pair<Long, String?> {
        if (!allowed(Manifest.permission.CALL_PHONE)) return ERROR_PERMISSION to "Call permission is not allowed in Visor"
        if (number.isBlank()) return RESULT_UNKNOWN to "No number to call"
        return try {
            requireNotNull(context.getSystemService(TelecomManager::class.java)).placeCall(Uri.fromParts("tel", number, null), Bundle())
            RESULT_OK to null
        } catch (e: Exception) {
            RESULT_UNKNOWN to (e.message ?: e.toString())
        }
    }

    /** Android has no call API for WhatsApp, so this opens the contact's WhatsApp call row, which starts the call. */
    private fun startWhatsApp(number: String): Pair<Long, String?> {
        if (!allowed(Manifest.permission.READ_CONTACTS)) return ERROR_PERMISSION to "Contacts permission is not allowed in Visor"
        if (!startable()) return RESULT_UNKNOWN to "Visor cannot open WhatsApp from the background"
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
    private suspend fun answer(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        val provider = request.varint(1)
        val error = when {
            provider != NATIVE -> "Answering on this provider is not supported"
            !allowed(Manifest.permission.ANSWER_PHONE_CALLS) || !allowed(Manifest.permission.READ_PHONE_STATE) ->
                "Call permission is not allowed in Visor"
            !ringing() -> "No ringing call"
            else -> try {
                requireNotNull(context.getSystemService(TelecomManager::class.java)).acceptRingingCall()
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
        val body = Wire.fieldBytes(1, request.bytes(2)) + Wire.fieldVarint(2, if (error == null) RESULT_OK else 0L)
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

    /** Android lets an app start an activity from the screen or as the glasses' companion, as PhoneMedia does. */
    private fun startable(): Boolean = onScreen() || companionAssociated(context)

    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo()
        .also(ActivityManager::getMyMemoryState).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun send(url: String, reply: Int, mux: DataXConnection, type: Int, body: ByteArray, outcome: String, failed: Boolean = false) {
        val id = requests.start(url, SOURCE_CALLS)
        // The glasses may have closed the channel by now; the answer is theirs to miss.
        runCatching { mux.send(reply, body, messageType = type) }
        requests.finish(id, outcome, failed, bytes = body.size.toLong())
        log.get().add("Calls: $outcome")
    }

    private fun signal(value: Long) = if (value == SIGNAL_PASS) "pass" else "fail"

    companion object {
        /** The types this class answers. PhoneMessages hands them here before its own checks. */
        val TYPES = setOf(3, 4, 6, 13, 22, 38, 59)
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
        private const val NATIVE = 3L
        private const val WHATSAPP_PROVIDER = 2L
        private const val STATE_RINGING_INBOUND = 1L
        private const val STATE_ACTIVE = 3L
        private const val CELLULAR_CALL_ID = "cellular"
        private const val STATE_URL = "calls://state"
        private const val SIGNAL_PASS = 1L
        private const val SIGNAL_FAIL = 2L
        private const val SOURCE_CALLS = -5
    }
}
