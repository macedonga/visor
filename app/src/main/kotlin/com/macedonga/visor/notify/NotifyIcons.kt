package com.macedonga.visor.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Telephony
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The glasses' svc46 icon requests (type 9): an app's icon for a notification. */
class NotifyIcons @Inject constructor(
    @ApplicationContext private val context: Context,
) : Responder {

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        if (message.info.messageType != MT_ICON_REQUEST) return
        val pkg = runCatching { String(Wire.parse(message.payload).bytes(1)) }.getOrDefault("")
        val jpeg = withContext(Dispatchers.Default) { render(pkg) }
        val answer = if (jpeg != null) {
            Wire.fieldVarint(2, 1) + Wire.fieldBytes(3, jpeg)
        } else {
            Wire.fieldVarint(2, 0) + Wire.fieldString(4, "unknown package")
        }
        connection.send(DataXConnection.mirror(message.info.channelId), Wire.fieldString(1, pkg) + answer, messageType = MT_ICON_RESPONSE)
    }

    /** The app's icon as a 78 x 78 JPEG, or null when the package has none. */
    private fun render(pkg: String): ByteArray? = runCatching {
        val pm = context.packageManager
        val d = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            ?: installed(pkg).firstNotNullOfOrNull { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
            ?: return null
        iconBytes(d, ICON, Bitmap.CompressFormat.JPEG)
    }.getOrNull()

    // Chat notifications carry the app ids the glasses gate on, not the phone's packages.
    private fun installed(appId: String): List<String> = when (appId) {
        "com.android.sms" -> listOfNotNull(Telephony.Sms.getDefaultSmsPackage(context))
        else -> APP_PACKAGES[appId].orEmpty()
    }

    private companion object {
        val APP_PACKAGES = mapOf(
            "com.facebook.messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "com.whatsapp" to listOf("com.whatsapp.w4b"),
            "com.instagram" to listOf("com.instagram.android"),
        )
        const val MT_ICON_REQUEST = 9
        const val MT_ICON_RESPONSE = 10
        const val ICON = 78
    }
}

/** [d] drawn on a [px] x [px] square, compressed as [format]. */
internal fun iconBytes(d: Drawable, px: Int, format: Bitmap.CompressFormat): ByteArray {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, px, px)
    d.draw(Canvas(bmp))
    return ByteArrayOutputStream().also { bmp.compress(format, 100, it) }.toByteArray()
}
