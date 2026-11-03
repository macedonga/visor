package com.macedonga.visor.notify

import com.macedonga.visor.core.protocol.Wire
import java.io.ByteArrayOutputStream
import java.util.UUID

/** One notification as the glasses' NotificationData takes it (wearable/notification protos). */
data class GlassesNotification(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    // NotificationType: 1 incoming message, 7 apps, 14 mirrored (which this firmware drops unpresented).
    val type: Int,
    // NotificationProvider: 1 Messenger, 2 WhatsApp, 3 native, 7 SMS, 8 mirrored, 9 system.
    val provider: Int,
    val sender: String = "",
    // The provider's id for the sender (a WhatsApp jid, a phone number), which the glasses look up in their contacts.
    val senderId: String = "",
    val threadId: String = "",
    val isGroup: Boolean = false,
    val groupName: String = "",
    val actions: List<String> = emptyList(),
    val icon: ByteArray? = null,
    val importance: Int = 3,
    val flags: Int = 0,
    val category: String? = null,
    val channel: String? = null,
    // The uuid this notification carries, which a removal names.
    val uuid: String = UUID.randomUUID().toString(),
) {
    /** svc46 type 1: a single ChunkEnvelope holding the NotificationData. */
    fun envelope(): ByteArray = Wire.fieldBytes(1, UUID.randomUUID().toString().toByteArray()) +
        Wire.fieldVarint(2, 0) + Wire.fieldVarint(3, 1) + Wire.fieldBytes(4, data())

    fun data(): ByteArray {
        val out = ByteArrayOutputStream()
        fun str(field: Int, s: String) {
            if (s.isNotEmpty()) out.write(Wire.fieldBytes(field, s.toByteArray()))
        }
        str(1, key)
        out.write(Wire.fieldVarint(2, type.toLong()))
        str(3, title)
        str(4, text)
        str(5, pkg)
        out.write(Wire.fieldBytes(7, Wire.fieldVarint(1, postTime / 1000) + Wire.fieldVarint(2, (postTime % 1000) * 1_000_000)))
        actions.forEachIndexed { i, a -> out.write(Wire.fieldBytes(8, Wire.fieldBytes(1, a.toByteArray()) + Wire.fieldVarint(2, i.toLong()))) }
        icon?.let { out.write(Wire.fieldBytes(9, it)) }
        out.write(Wire.fieldVarint(10, importance.toLong().coerceAtLeast(0)))
        str(11, uuid)
        when (provider) {
            // Messenger, WhatsApp, Instagram: FoAMessageNotificationExtras as the Meta app fills it. The glasses
            // announce the thread by threadName (14) and say "unknown contact" without it.
            1, 2, 4 -> {
                val who = sender.ifEmpty { title }
                val foa = ByteArrayOutputStream().apply {
                    if (isGroup && groupName.isNotEmpty()) write(Wire.fieldBytes(1, groupName.toByteArray()))
                    write(Wire.fieldBytes(2, threadId.ifEmpty { key }.toByteArray()))
                    // Explicit TEXT, so the Display offers smart replies: an absent type reads as none.
                    write(Wire.fieldVarint(9, 0))
                    if (isGroup) write(Wire.fieldVarint(10, 1))
                    if (provider == 2) write(Wire.fieldVarint(11, 1))
                    if (senderId.isNotEmpty()) write(Wire.fieldBytes(12, senderId.toByteArray()))
                    write(Wire.fieldBytes(13, who.toByteArray()))
                    write(Wire.fieldBytes(14, (if (isGroup) groupName.ifEmpty { title } else who).toByteArray()))
                    write(Wire.fieldBytes(15, key.toByteArray()))
                    write(Wire.fieldVarint(17, postTime))
                }.toByteArray()
                out.write(Wire.fieldBytes(12, foa))
            }
            // Native and SMS: NativeMessageNotificationExtras {1 phone number, 7 threadId, 8 threadName, 11 senderID,
            // 12 senderName, 16 shared contact}. The Display's Messages app files it by threadId and senderID.
            3, 7 -> out.write(
                Wire.fieldBytes(
                    13,
                    (if (senderId.isNotEmpty()) Wire.fieldBytes(1, senderId.toByteArray()) else ByteArray(0)) +
                        Wire.fieldBytes(7, threadId.ifEmpty { key }.toByteArray()) +
                        Wire.fieldBytes(8, (if (isGroup) groupName.ifEmpty { title } else sender.ifEmpty { title }).toByteArray()) +
                        (if (senderId.isNotEmpty()) Wire.fieldBytes(11, senderId.toByteArray()) else ByteArray(0)) +
                        Wire.fieldBytes(12, sender.ifEmpty { title }.toByteArray()) + Wire.fieldVarint(16, 1),
                ),
            )
        }
        if (type == TYPE_MIRRORED) {
            val extra = ByteArrayOutputStream().apply {
                write(Wire.fieldBytes(1, appName.toByteArray()))
                write(Wire.fieldVarint(2, flags.toLong() and 0xffffffffL))
                category?.let { write(Wire.fieldBytes(3, it.toByteArray())) }
                write(Wire.fieldBytes(4, key.toByteArray()))
                channel?.let { write(Wire.fieldBytes(6, it.toByteArray())) }
            }.toByteArray()
            out.write(Wire.fieldBytes(14, extra))
        }
        out.write(Wire.fieldVarint(16, provider.toLong()))
        str(20, uuid)
        out.write(Wire.fieldVarint(22, 0))
        return out.toByteArray()
    }

    companion object {
        const val SVC = 46
        const val MT_UPSERT_CHUNK = 1
        const val MT_REMOVE = 1022
        const val TYPE_MIRRORED = 14
    }
}
