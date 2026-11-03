package com.macedonga.visor.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.AirshipReceiver
import com.macedonga.visor.core.media.ExtraFiles
import com.macedonga.visor.core.media.NAMESPACE_SHARING
import com.macedonga.visor.core.media.Offer
import com.macedonga.visor.core.media.Received
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.session.Responder
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.settings.hasDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * svc26, smartglasses_sharing: the glasses share a capture through the phone. Visor opens the channel on connect with
 * an empty enumerate request (3), as the Meta app does; the glasses' sharing service refuses to start without it. A
 * photo comes as an offer (7, or 11 for the last capture) that Visor accepts (8), then over airship "Sharing" under the
 * offer's uuid, then a media intent (5) naming where it goes. A share to the phone's own texts (provider 3) goes out
 * as an MMS and is answered (6) with the result; other destinations are the Meta app's and are turned down. The
 * assistant's photo comes the same way and still reaches the assistant.
 */
@Singleton
class PhoneSharing @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: Provider<GlassesConnection>,
    private val airship: AirshipReceiver,
    private val messages: PhoneMessages,
    private val kind: LinkKind,
    private val requests: RequestLog,
    private val log: Provider<EventLog>,
) : Responder, ExtraFiles {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.get().add("Sharing: failed: $e") })
    private val files = ConcurrentHashMap<String, CompletableDeferred<File>>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            connection.get().state.map { it.link == LinkState.READY }.distinctUntilChanged().collect { ready ->
                // Only the Display's sharing is used; other glasses keep their own.
                if (!ready || !hasDisplay(connection.get().state.value.info?.build)) return@collect
                if (this@PhoneSharing !in airship.extras) airship.extras.add(0, this@PhoneSharing)
                connection.get().submit { it.sendOnService(SVC, MT_ENUMERATE_REQUEST, ByteArray(0)) }
            }
        }
    }

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.payload.isEmpty() && message.info.flags.isClosed) return
        val type = message.info.messageType
        // The channel is Visor's own: the glasses answer on it without the originator bit, and Visor writes it with.
        val reply = message.info.channelId xor 0x8000
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        log.get().add("Sharing: type $type, ${message.payload.size} bytes, fields ${f.fieldNumbers()}")
        when (type) {
            MT_MEDIA_OFFER, MT_MEDIA_OFFER_LAST -> {
                val uuid = f.bytes(1)
                files.getOrPut(uuidText(uuid)) { CompletableDeferred() }
                connection.send(reply, Wire.fieldBytes(1, uuid) + Wire.fieldVarint(2, ACCEPT), messageType = MT_MEDIA_OFFER_RESPONSE)
            }
            // The phone keeps the link it has: the glasses are told which one that is.
            MT_LINK_SWITCH -> connection.send(reply, Wire.fieldVarint(1, if (kind.overLe.value) LINK_BLE else LINK_BTC), messageType = MT_LINK_UPDATE)
            MT_MEDIA_INTENT -> scope.launch { intent(f, reply, connection) }
        }
    }

    override fun wants(offer: Offer): Boolean = offer.namespace == NAMESPACE_SHARING && files.containsKey(offer.uuid)

    // The receiver deletes its file afterwards, so the photo is copied; the assistant may want it too.
    override fun take(received: Received) {
        val kept = File(File(context.cacheDir, "share").apply { mkdirs() }, received.offer.uuid)
        received.file.copyTo(kept, overwrite = true)
        files[received.offer.uuid]?.complete(kept)
        airship.extras.filter { it !== this && it.wants(received.offer) }.forEach { runCatching { it.take(received) } }
    }

    /** MediaIntent {1 uuid, 2 media uuid, 4 ShareIntent, 8 AssistantOffloadIntent}: answered with MediaIntentResponse. */
    private suspend fun intent(f: Wire.Fields, reply: Int, mux: DataXConnection) {
        val uuid = f.bytes(1)
        // A share of a photo just offered names no media uuid: the intent's own uuid is the offer's.
        val media = uuidText(if (f.has(2)) f.bytes(2) else uuid)
        try {
            when {
                f.has(INTENT_SHARE) -> {
                    val (result, error) = try {
                        share(Wire.parse(f.bytes(INTENT_SHARE)), media)
                    } catch (e: Exception) {
                        SHARE_ERROR_APP to (e.message ?: e.toString())
                    }
                    val response = (error?.let { Wire.fieldBytes(1, it.encodeToByteArray()) } ?: ByteArray(0)) + Wire.fieldVarint(2, result) +
                        (if (result == SHARE_SUCCESS) Wire.fieldVarint(3, System.currentTimeMillis()) else ByteArray(0))
                    mux.send(reply, Wire.fieldBytes(1, uuid) + Wire.fieldBytes(RESPONSE_SHARE, response), messageType = MT_MEDIA_INTENT_RESPONSE)
                }
                // The assistant has the photo from the airship transfer.
                f.has(INTENT_ASSISTANT_OFFLOAD) ->
                    mux.send(reply, Wire.fieldBytes(1, uuid) + Wire.fieldBytes(RESPONSE_ASSISTANT_OFFLOAD, Wire.fieldVarint(1, OFFLOAD_SUCCESS)), messageType = MT_MEDIA_INTENT_RESPONSE)
                else -> log.get().add("Sharing: intent ${f.fieldNumbers()} not served")
            }
        } finally {
            files.remove(media)?.let { d -> if (d.isCompleted) runCatching { d.getCompleted().delete() } }
        }
    }

    /** ShareIntent {2 ShareDestination {1 provider, 3 thread, 6 numbers}, 5 text, 8 share id}: the result and why not. */
    private suspend fun share(share: Wire.Fields, media: String): Pair<Long, String?> {
        val destination = Wire.parse(share.bytes(2))
        val id = requests.start("mms://share/${share.bytes(8).decodeToString()}", SOURCE_SMS)
        val provider = destination.varint(1)
        val outcome: Pair<Long, String?> = if (provider != PROVIDER_NATIVE) {
            SHARE_ERROR_APP to "Only the phone's texts can be sent through Visor"
        } else {
            val numbers = destination.repeatedBytes(6).map { Wire.parse(it).bytes(1).decodeToString() }.filter { it.isNotBlank() }
            val to = numbers.ifEmpty { messages.addressesOf(destination.bytes(3).decodeToString()) }
            val file = files[media]?.let { withTimeoutOrNull(FILE_WAIT_MS) { it.await() } }
            when {
                context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED -> SHARE_ERROR_NATIVE_DISABLED to "SMS not allowed in Visor"
                to.isEmpty() -> SHARE_ERROR_APP to "No recipient"
                to.size > 1 -> SHARE_ERROR_APP to "Group messages are not supported"
                file == null -> SHARE_ERROR_MEDIA_PROCESSING to "The picture did not arrive"
                else -> Mms.send(context, to.single(), file.readBytes(), share.bytes(5).decodeToString())?.let { SHARE_ERROR_APP to it } ?: (SHARE_SUCCESS to null)
            }
        }
        requests.finish(id, outcome.second ?: "sent as MMS", failed = outcome.second != null)
        return outcome
    }

    private fun uuidText(raw: ByteArray): String {
        val h = raw.joinToString("") { "%02x".format(it) }
        if (raw.size != 16) return h
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    companion object {
        const val SVC = 26
        private const val MT_ENUMERATE_REQUEST = 3
        private const val MT_MEDIA_INTENT = 5
        private const val MT_MEDIA_INTENT_RESPONSE = 6
        private const val MT_MEDIA_OFFER = 7
        private const val MT_MEDIA_OFFER_RESPONSE = 8
        private const val MT_MEDIA_OFFER_LAST = 11
        private const val MT_LINK_SWITCH = 12
        private const val MT_LINK_UPDATE = 13
        private const val ACCEPT = 0L
        private const val LINK_BLE = 1L
        private const val LINK_BTC = 2L
        private const val INTENT_SHARE = 4
        private const val INTENT_ASSISTANT_OFFLOAD = 8
        private const val RESPONSE_SHARE = 2
        private const val RESPONSE_ASSISTANT_OFFLOAD = 5
        private const val OFFLOAD_SUCCESS = 1L
        private const val PROVIDER_NATIVE = 3L
        private const val SHARE_SUCCESS = 1L
        private const val SHARE_ERROR_MEDIA_PROCESSING = 2L
        private const val SHARE_ERROR_APP = 5L
        private const val SHARE_ERROR_NATIVE_DISABLED = 8L
        private const val FILE_WAIT_MS = 90_000L
        private const val SOURCE_SMS = -4
    }
}
