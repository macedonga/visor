package com.macedonga.visor.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import com.macedonga.visor.connection.PhoneCallControl
import com.macedonga.visor.connection.PhoneMessages
import com.macedonga.visor.connection.PhonePosition
import com.macedonga.visor.core.assistant.Handset
import com.macedonga.visor.core.assistant.HandsetDone
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.ui.allows
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The phone's own things for the model, in words it reads back to the wearer. */
@Singleton
class PhoneLine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactFinder,
    private val calls: PhoneCallControl,
    private val messages: PhoneMessages,
    private val ringer: PhoneRinger,
    private val position: PhonePosition,
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

    override suspend fun text(who: String, kind: String, message: String): HandsetDone = withContext(Dispatchers.IO) {
        if (!context.allows(Manifest.permission.SEND_SMS)) {
            return@withContext HandsetDone(
                "Visor may not send texts. Tell the wearer to allow Text messages in Visor, Settings > Phone access.", failed = true,
            )
        }
        val number = when (val pick = contacts.pick(who, kind)) {
            is Pick.Not -> return@withContext HandsetDone(pick.text, failed = !pick.ask, asked = pick.ask)
            is Pick.One -> pick.number
        }
        val why = messages.text(number.number, message)
        log.add("Phone: ${if (why == null) "texted" else "could not text"} ${number.label}${why?.let { ": $it" }.orEmpty()}")
        if (why != null) HandsetDone("The text to ${number.label} did not go: $why.", failed = true, subject = number.name)
        else HandsetDone("Sent to ${number.label}: \"$message\".", subject = number.name)
    }

    override suspend fun recentCalls(count: Int, missed: Boolean): HandsetDone = withContext(Dispatchers.IO) {
        if (!context.allows(Manifest.permission.READ_CALL_LOG)) {
            return@withContext HandsetDone(
                "Visor may not read the call history. Tell the wearer to allow Call history in Visor, Settings > Phone access.", failed = true,
            )
        }
        val lines = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                if (missed) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                buildList {
                    while (size < count && c.moveToNext()) {
                        val name = c.getString(0)?.takeIf { it.isNotBlank() }
                        val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: "a hidden number"
                        val who = if (name != null) "$name ($number)" else number
                        val what = when (c.getInt(2)) {
                            CallLog.Calls.MISSED_TYPE -> "missed call from $who"
                            CallLog.Calls.OUTGOING_TYPE -> "call to $who"
                            CallLog.Calls.REJECTED_TYPE -> "declined call from $who"
                            CallLog.Calls.BLOCKED_TYPE -> "blocked call from $who"
                            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail from $who"
                            else -> "call from $who"
                        }
                        val minutes = c.getLong(4) / 60
                        add("$what, ${clock(c.getLong(3))}${if (minutes > 0) ", $minutes min" else ""}")
                    }
                }
            }.orEmpty()
        }.getOrElse { return@withContext HandsetDone("The call history could not be read: ${it.message}.", failed = true) }
        HandsetDone(lines.joinToString("\n").ifEmpty { if (missed) "No missed calls." else "The call history is empty." })
    }

    override suspend fun findPhone(stop: Boolean): HandsetDone {
        if (stop) return HandsetDone(if (ringer.stop()) "The phone stopped ringing." else "The phone was not ringing.")
        val (started, why) = ringer.ring()
        if (!started) return HandsetDone("The phone is ringing already.")
        log.add("Phone: ringing so the wearer can find it")
        val seconds = PhoneRinger.RING_MS / 1000
        return HandsetDone("The phone rings for $seconds seconds, or until its screen turns on${why?.let { "; $it" }.orEmpty()}.")
    }

    override suspend fun location(): HandsetDone {
        if (!context.allows(Manifest.permission.ACCESS_FINE_LOCATION) && !context.allows(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return HandsetDone("Visor may not use the phone's location. Tell the wearer to allow Location in Visor, Settings > Phone access.", failed = true)
        }
        if (!position.enabled()) return HandsetDone("Location is off on the phone; the wearer turns it on in the phone's quick settings.", failed = true)
        val now = System.currentTimeMillis()
        val fix = position.freshFix(FIX_MS) ?: position.fix()?.takeIf { now - it.time < STALE_MS }
            ?: return HandsetDone(
                "The phone could not tell where it is just now." + if (!context.allows(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    " With Visor in the background, Android needs Location set to Allow all the time, in Visor, Settings > Phone access."
                } else "",
                failed = true,
            )
        val place = address(fix)
        val minutes = (now - fix.time) / 60_000
        val where = "%.5f, %.5f, within %d m".format(Locale.ROOT, fix.latitude, fix.longitude, fix.accuracy.toInt()) +
            if (minutes > 0) ", $minutes min ago" else ""
        return HandsetDone(if (place != null) "$place ($where)." else "No address is known for it ($where).")
    }

    /** The street address of [fix], in the phone's language, when the phone's geocoder knows it. */
    private suspend fun address(fix: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val found: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(GEOCODE_MS) {
                suspendCancellableCoroutine { done ->
                    geocoder.getFromLocation(fix.latitude, fix.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (done.isActive) done.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (done.isActive) done.resume(null) }
                    })
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { runCatching { geocoder.getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull() }
        }
        return found?.getAddressLine(0) ?: found?.run { listOfNotNull(thoroughfare, locality, countryName).joinToString(", ") }?.ifEmpty { null }
    }

    @SuppressLint("MissingPermission") // checked with allows()
    private fun inCall(): Boolean = context.allows(Manifest.permission.READ_PHONE_STATE) &&
        runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall == true }.getOrDefault(false)

    private companion object {
        const val FIX_MS = 8_000L
        const val STALE_MS = 15 * 60_000L
        const val GEOCODE_MS = 5_000L

        /** "today 14:02", "yesterday 9:30" or "Mon 9 Nov 14:02", as the model reads a time. */
        fun clock(time: Long): String {
            val at = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            val day = at.toLocalDate()
            val today = LocalDate.now()
            val hour = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
            return when (day) {
                today -> "today $hour"
                today.minusDays(1) -> "yesterday $hour"
                else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))
            }
        }
    }
}
