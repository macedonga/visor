package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.protocol.uuidText
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

const val SVC_GLASSES_PHOTO = 53

/**
 * svc53: the photo the glasses take for a question about what they see. Type 1 opens it with the image's id
 * (16 raw bytes, the id the question's request names), type 2 carries the HEIC in pieces, type 3 closes it. It can
 * land before the question does, so the last few are kept by id.
 */
class GlassesPhotos : Responder {
    private val parts = ByteArrayOutputStream()
    private var id = ""
    private val recent = LinkedHashMap<String, ByteArray>()

    private val _arrived = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 4)
    val arrived: SharedFlow<Pair<String, ByteArray>> = _arrived.asSharedFlow()

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        when (message.info.messageType) {
            1 -> {
                id = runCatching { Wire.parse(message.payload).bytes(1) }.getOrNull()?.takeIf { it.size == 16 }?.let(::uuidText).orEmpty()
                parts.reset()
            }
            2 -> parts.write(message.payload)
            3 -> {
                val bytes = parts.toByteArray()
                parts.reset()
                if (bytes.isEmpty()) return
                // One without an id is never taken by a later question.
                if (id.isNotEmpty()) synchronized(recent) {
                    recent[id] = bytes
                    while (recent.size > KEPT) recent.remove(recent.keys.first())
                }
                _arrived.tryEmit(id to bytes)
            }
        }
    }

    /** The photo with [id], if it already came; it is handed over once. */
    fun take(id: String): ByteArray? = synchronized(recent) { recent.remove(id) }

    private companion object {
        const val KEPT = 4
    }
}
