package com.macedonga.visor.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.core.identity.token.LinkedAccountType
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.log.EventLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * svc70 type 28, TriggerProviderLinking {1 provider, 2 requestId, 3 source, 4 scenario (0 connect, 1 reconnect)}: a
 * Display app's "Open on phone". As the Meta app does, the phone posts a notification that opens its linking page, here
 * Accounts with the asked account first, and answers type 29 {1 requestId, 2 success}.
 */
@Singleton
class LinkPrompt @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: EventLog,
) {
    /** The account the glasses last asked for, for the Accounts page to put first; Messenger rides on Facebook. */
    private val _asked = MutableStateFlow<LinkedAccountType?>(null)
    val asked: StateFlow<LinkedAccountType?> = _asked.asStateFlow()

    fun answered() {
        _asked.value = null
    }

    suspend fun trigger(request: Wire.Fields, reply: Int, connection: DataXConnection) {
        val provider = request.varint(1)
        val name = PROVIDERS[provider]
        val account = ACCOUNTS[provider]
        val reconnect = request.varint(4) == SCENARIO_RECONNECT
        val shown = name != null && account != null && show(name, reconnect)
        if (shown) _asked.value = account
        log.add("Accounts: the glasses asked to link ${name ?: "provider $provider"} on the phone" + if (shown) "" else "; not shown")
        val answer = Wire.fieldBytes(1, request.bytes(2)) + Wire.fieldVarint(2, if (shown) 1L else 0L)
        connection.send(reply, answer, messageType = MT_RESPONSE)
    }

    private fun show(name: String, reconnect: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.link_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_visor)
            .setContentTitle(context.getString(if (reconnect) R.string.link_reconnect_title else R.string.link_title, name))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        const val MT_TRIGGER = 28
        private const val MT_RESPONSE = 29
        private const val SCENARIO_RECONNECT = 1L
        private const val CHANNEL = "linking"
        private const val NOTIFICATION_ID = 9

        // CommunicateProvider, as the Meta app numbers it.
        private val PROVIDERS = mapOf(1L to "Messenger", 2L to "WhatsApp", 4L to "Instagram", 6L to "Facebook", 7L to "Threads")

        // The svc89 account each is linked as; WhatsApp has none (see AccountLinking).
        private val ACCOUNTS = mapOf(
            1L to LinkedAccountType.FACEBOOK, 4L to LinkedAccountType.INSTAGRAM,
            6L to LinkedAccountType.FACEBOOK, 7L to LinkedAccountType.THREADS,
        )
    }
}
