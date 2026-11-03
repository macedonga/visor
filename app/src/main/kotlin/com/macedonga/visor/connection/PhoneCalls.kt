package com.macedonga.visor.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's recent calls, asked for twice by the Display:
 *
 * - svc11006, phone_book_sync, by its telephony service, on connect, after a call and now and then: type 1 {1 request
 *   id}, answered with PhoneBookSyncResponse {1 request id, 2 call log, 3 error, 4 phone OS} (type 2). Told the phone is
 *   Android, it keeps the call log from the answer; an iPhone it would read over PBAP instead. The call log is JSON, a
 *   list of {"cType": "CDEntryAdd", "id", "entry": {id, cachedName, phoneNumber, type, dateEpochMilliseconds,
 *   durationSeconds}} with the type by name (INCOMING, OUTGOING, MISSED, ...).
 * - svc70 LoadCallLogRequest {1 provider, 2 request id, 3 count, 4 request type} (type 11), by the Phone app once per
 *   provider, answered with LoadCallLogResponse {1 request id, 2 CallLog} (type 12). The phone's own calls (provider 3)
 *   come from the call log; the other providers get an empty list, as the Meta app gives when it has no handler.
 */
@Singleton
class PhoneCalls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: RequestLog,
) : Responder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })

    private data class Call(val id: Long, val name: String?, val number: String?, val type: Int, val date: Long, val duration: Long, val video: Boolean)

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.messageType != MT_SYNC_REQUEST || message.info.flags.isClosed) return
        val requestId = runCatching { Wire.parse(message.payload).bytes(1) }.getOrDefault(ByteArray(0))
        val reply = DataXConnection.mirror(message.info.channelId)
        scope.launch {
            val id = requests.start("calls://sync", SOURCE_CALLS)
            val calls = if (allowed()) recent(MAX_CALLS) else null
            val json = JSONArray()
            calls?.forEach { c ->
                val entry = JSONObject()
                    .put("id", c.id)
                    .put("cachedName", c.name ?: JSONObject.NULL)
                    .put("phoneNumber", c.number ?: JSONObject.NULL)
                    .put("type", typeName(c.type))
                    .put("dateEpochMilliseconds", c.date)
                    .put("durationSeconds", c.duration)
                json.put(JSONObject().put("cType", "CDEntryAdd").put("id", c.date.toString()).put("entry", entry))
            }
            val body = Wire.fieldBytes(1, requestId) +
                Wire.fieldBytes(2, (if (calls != null) json.toString() else "").encodeToByteArray()) +
                Wire.fieldVarint(3, if (calls != null) SUCCESS else NO_PERMISSION) +
                Wire.fieldVarint(4, OS_ANDROID)
            connection.send(reply, body, messageType = MT_SYNC_RESPONSE)
            if (calls != null) requests.finish(id, "${calls.size} calls", failed = false, bytes = body.size.toLong())
            else requests.finish(id, "call history not allowed", failed = true)
        }
    }

    /** svc70 type 11, from [PhoneMessages]: [reply] is the request's mirror channel. */
    fun loadCallLog(request: Wire.Fields, reply: Int, mux: DataXConnection) {
        scope.launch {
            val provider = request.varint(1)
            val id = requests.start("calls://recent/$provider", SOURCE_CALLS)
            val count = request.varint(3).toInt().takeIf { it > 0 }?.coerceAtMost(MAX_CALLS) ?: DEFAULT_RECENT
            val calls = if (provider == NATIVE && allowed()) recent(count) else emptyList()
            var body = Wire.fieldBytes(1, request.bytes(2))
            for (c in calls) {
                // CallLog: 1 name, 3 time, 5 type (1 incoming, 2 outgoing, 3 missed, 0 other), 7 number, 11 video.
                if (c.type == CallLog.Calls.VOICEMAIL_TYPE) continue
                body += Wire.fieldBytes(
                    2,
                    (if (!c.name.isNullOrEmpty()) Wire.fieldBytes(1, c.name.encodeToByteArray()) else ByteArray(0)) +
                        Wire.fieldVarint(3, c.date) + Wire.fieldVarint(5, wireType(c.type)) +
                        (if (!c.number.isNullOrEmpty()) Wire.fieldBytes(7, c.number.encodeToByteArray()) else ByteArray(0)) +
                        (if (c.video) Wire.fieldVarint(11, 1) else ByteArray(0)),
                )
            }
            mux.send(reply, body, messageType = MT_LOAD_CALL_LOG_RESPONSE)
            val outcome = when {
                provider != NATIVE -> "not the phone's, none"
                !allowed() -> "call history not allowed"
                else -> "${calls.size} calls"
            }
            requests.finish(id, outcome, failed = provider == NATIVE && !allowed(), bytes = body.size.toLong())
        }
    }

    private fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun recent(count: Int): List<Call> {
        val calls = ArrayList<Call>()
        val columns = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.FEATURES,
        )
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, count.toString()).build()
        context.contentResolver.query(uri, columns, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && calls.size < count) {
                calls += Call(
                    c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getLong(5),
                    c.getInt(6) and CallLog.Calls.FEATURES_VIDEO != 0,
                )
            }
        }
        return calls
    }

    private fun typeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
        else -> "UNKNOWN"
    }

    // A rejected call shows as missed, as the Meta app sends it.
    private fun wireType(type: Int): Long = when (type) {
        CallLog.Calls.INCOMING_TYPE -> 1
        CallLog.Calls.OUTGOING_TYPE -> 2
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> 3
        else -> 0
    }

    companion object {
        const val SVC = 11006
        private const val MT_SYNC_REQUEST = 1
        private const val MT_SYNC_RESPONSE = 2
        private const val MT_LOAD_CALL_LOG_RESPONSE = 12
        private const val SUCCESS = 0L
        private const val NO_PERMISSION = 1L
        private const val OS_ANDROID = 1L
        private const val NATIVE = 3L
        private const val MAX_CALLS = 100
        private const val DEFAULT_RECENT = 15
        private const val SOURCE_CALLS = -5
    }
}
