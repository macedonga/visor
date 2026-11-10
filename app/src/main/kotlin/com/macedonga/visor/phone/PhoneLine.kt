package com.macedonga.visor.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.telecom.TelecomManager
import com.macedonga.visor.connection.PhoneCallControl
import com.macedonga.visor.core.assistant.Handset
import com.macedonga.visor.core.assistant.HandsetDone
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's own things for the model, in words it reads back to the wearer. */
@Singleton
class PhoneLine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactFinder,
    private val calls: PhoneCallControl,
    private val log: EventLog,
) : Handset {

    override suspend fun call(who: String, kind: String, whatsApp: Boolean): HandsetDone = withContext(Dispatchers.IO) {
        if (!whatsApp && !context.allows(Manifest.permission.CALL_PHONE)) {
            return@withContext HandsetDone(
                "Visor may not place calls. Tell the wearer to allow Place and answer calls in Visor, Settings > Phone access.", failed = true,
            )
        }
        if (inCall()) return@withContext HandsetDone("A call is already under way on the phone.", failed = true)
        val number = when (val pick = contacts.pick(who, kind)) {
            is Pick.Not -> return@withContext HandsetDone(pick.text, failed = !pick.ask, asked = pick.ask)
            is Pick.One -> pick.number
        }
        val why = if (whatsApp) calls.whatsAppCall(number.number) else calls.call(number.number)
        log.add("Phone: ${if (why == null) "calling" else "could not call"} ${number.label}${if (whatsApp) " on WhatsApp" else ""}${why?.let { ": $it" }.orEmpty()}")
        if (why != null) HandsetDone("The call to ${number.label} did not start: $why.", failed = true, subject = number.name)
        else HandsetDone("Calling ${number.label}${if (whatsApp) " on WhatsApp" else ""}. Say only that, in a few words.", subject = number.name)
    }

    @SuppressLint("MissingPermission") // checked with allows()
    private fun inCall(): Boolean = context.allows(Manifest.permission.READ_PHONE_STATE) &&
        runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall == true }.getOrDefault(false)
}
