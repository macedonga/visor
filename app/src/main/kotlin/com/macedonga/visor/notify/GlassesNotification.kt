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
    // NotificationType: 1 incoming message, 7 apps, 14 mirrored (glasses without a display drop it).
    val type: Int,
    // NotificationProvider: one of the PROVIDER_ values.
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
    fun envelope(): ByteArray = Wire.fieldString(1, UUID.randomUUID().toString()) +
        Wire.fieldVarint(2, 0) + Wire.fieldVarint(3, 1) + Wire.fieldBytes(4, data())

    private fun data(): ByteArray {
        val out = ByteArrayOutputStream()
        fun str(field: Int, s: String) {
            if (s.isNotEmpty()) out.write(Wire.fieldString(field, s))
        }
        str(1, key)
        out.write(Wire.fieldVarint(2, type.toLong()))
        str(3, title)
        str(4, text)
        str(5, pkg)
        out.write(Wire.fieldBytes(7, Wire.fieldVarint(1, postTime / 1000) + Wire.fieldVarint(2, (postTime % 1000) * 1_000_000)))
        actions.forEachIndexed { i, a -> out.write(Wire.fieldBytes(8, Wire.fieldString(1, a) + Wire.fieldVarint(2, i.toLong()))) }
        icon?.let { out.write(Wire.fieldBytes(9, it)) }
        out.write(Wire.fieldVarint(10, importance.toLong().coerceAtLeast(0)))
        str(11, uuid)
        when (provider) {
            // Messenger, WhatsApp, Instagram: FoAMessageNotificationExtras. The glasses
            // announce the thread by threadName (14) and say "unknown contact" without it.
            PROVIDER_MESSENGER, PROVIDER_WHATSAPP, PROVIDER_INSTAGRAM -> {
                val who = sender.ifEmpty { title }
                val foa = ByteArrayOutputStream().apply {
                    if (isGroup && groupName.isNotEmpty()) write(Wire.fieldString(1, groupName))
                    write(Wire.fieldString(2, threadId.ifEmpty { key }))
                    // Explicit TEXT, so the Display offers smart replies: an absent type reads as none.
                    write(Wire.fieldVarint(9, 0))
                    if (isGroup) write(Wire.fieldVarint(10, 1))
                    if (provider == PROVIDER_WHATSAPP) write(Wire.fieldVarint(11, 1))
                    if (senderId.isNotEmpty()) write(Wire.fieldString(12, senderId))
                    write(Wire.fieldString(13, who))
                    write(Wire.fieldString(14, if (isGroup) groupName.ifEmpty { title } else who))
                    write(Wire.fieldString(15, key))
                    write(Wire.fieldVarint(17, postTime))
                }.toByteArray()
                out.write(Wire.fieldBytes(12, foa))
            }
            // Native and SMS: NativeMessageNotificationExtras {1 phone number, 7 threadId, 8 threadName, 11 senderID,
            // 12 senderName, 16 shared contact}. The Display's Messages app files it by threadId and senderID.
            PROVIDER_NATIVE, PROVIDER_SMS -> out.write(
                Wire.fieldBytes(
                    13,
                    (if (senderId.isNotEmpty()) Wire.fieldString(1, senderId) else ByteArray(0)) +
                        Wire.fieldString(7, threadId.ifEmpty { key }) +
                        Wire.fieldString(8, if (isGroup) groupName.ifEmpty { title } else sender.ifEmpty { title }) +
                        (if (senderId.isNotEmpty()) Wire.fieldString(11, senderId) else ByteArray(0)) +
                        Wire.fieldString(12, sender.ifEmpty { title }) + Wire.fieldVarint(16, 1),
                ),
            )
        }
        if (type == TYPE_MIRRORED) {
            val extra = ByteArrayOutputStream().apply {
                write(Wire.fieldString(1, appName))
                write(Wire.fieldVarint(2, flags.toLong() and 0xffffffffL))
                category?.let { write(Wire.fieldString(3, it)) }
                write(Wire.fieldString(4, key))
                channel?.let { write(Wire.fieldString(6, it)) }
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
        const val TYPE_INCOMING_MESSAGE = 1
        const val TYPE_MIRRORED = 14
        const val PROVIDER_MESSENGER = 1
        const val PROVIDER_WHATSAPP = 2
        const val PROVIDER_NATIVE = 3
        const val PROVIDER_INSTAGRAM = 4
        const val PROVIDER_SMS = 7
        const val PROVIDER_MIRRORED = 8

        /** svc46 type 1022: takes away the notification [key] sent with [provider] and [uuid]. */
        fun removal(key: String, provider: Int, uuid: String): ByteArray =
            Wire.fieldString(1, key) + Wire.fieldVarint(2, provider.toLong()) + Wire.fieldString(6, uuid)
    }
}
