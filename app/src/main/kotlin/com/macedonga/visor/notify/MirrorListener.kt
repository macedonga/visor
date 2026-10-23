package com.macedonga.visor.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.assistant.LiveAiPhase
import kotlinx.coroutines.delay
import com.macedonga.visor.core.assistant.Conversation
import com.macedonga.visor.core.assistant.Messaging
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.settings.hasDisplay
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's notifications on the glasses, as the Meta app mirrors them: svc46 type 1 carries a
 * ChunkEnvelope holding a NotificationData. It also keeps current_media_provider to the app that
 * is playing.
 */
@AndroidEntryPoint
class MirrorListener : NotificationListenerService(), Messaging {

    @Inject
    lateinit var connection: GlassesConnection

    @Inject
    lateinit var settings: GlassesSettings

    @Inject
    lateinit var assistant: Assistant

    @Inject
    lateinit var choice: NotifyChoice

    @Inject
    lateinit var apps: NotifyApps

    @Inject
    lateinit var textReplies: TextReplies

    @Inject
    lateinit var recent: RecentChats

    // Each conversation sent to the glasses, with the app's reply action, newest last.
    private val replyable = LinkedHashMap<String, Pair<Conversation, Notification.Action>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessions: MediaSessionManager? = null
    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { reportMedia(it.orEmpty()) }

    override fun onListenerConnected() {
        assistant.messaging = this
        scope.launch { release() }
        val manager = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, MirrorListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(onSessions, me)
            sessions = manager
            reportMedia(manager.getActiveSessions(me))
        }
        // The glasses may connect after the listener does: check again after every settings read,
        // which is also when Visor first knows what they hold.
        scope.launch {
            settings.state.map { it.readAt }.distinctUntilChanged().collect { at ->
                if (at != null) sessions?.let { m -> runCatching { reportMedia(m.getActiveSessions(me)) } }
            }
        }
    }

    override fun onListenerDisconnected() {
        if (assistant.messaging === this) assistant.messaging = null
        sessions?.removeOnActiveSessionsChangedListener(onSessions)
        sessions = null
        setProvider(NO_ACCESS)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!wanted(sbn, rankingMap)) return
        apps.saw(sbn.packageName)
        // The Messages app's conversations are kept whether or not the glasses listen: the Display's list reads them.
        val sms = sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)
        if (sms) remember(sbn)
        if (!choice.enabled.value || !ready() || sbn.packageName in apps.muted.value) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val r = Ranking()
        // The phone's texts, SMS or RCS, go as native messages, which the glasses' Messages app files by thread.
        (if (sms) PROVIDER_NATIVE else PROVIDERS[sbn.packageName])?.let { provider ->
            message(sbn, provider, appName)?.let { m ->
                deliver(m)
                replyAction(n)?.let { action ->
                    replyable.remove(sbn.key)
                    replyable[sbn.key] = Conversation(sbn.key, m.sender, appName) to action
                    while (replyable.size > 50) replyable.remove(replyable.keys.first())
                }
            }
            return
        }
        val note = GlassesNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            type = GlassesNotification.TYPE_MIRRORED,
            provider = PROVIDER_MIRRORED,
            actions = n.actions?.mapNotNull { it.title?.toString()?.takeIf(String::isNotEmpty) }.orEmpty(),
            icon = icon(sbn.packageName),
            importance = if (rankingMap.getRanking(sbn.key, r)) r.importance else NotificationManager.IMPORTANCE_DEFAULT,
            flags = n.flags,
            category = n.category,
            channel = n.channelId,
        )
        deliver(note)
    }

    // What came while the wearer was talking with the assistant, newest per notification, and since when.
    private val held = LinkedHashMap<String, GlassesNotification>()
    private var heldSince = 0L

    // The provider and uuid of the last notification sent for each key, which a removal names.
    private val mirrored = LinkedHashMap<String, Pair<Int, String>>()

    /** To the glasses now, or once the conversation with the assistant is over. */
    private fun deliver(note: GlassesNotification) {
        if (talking()) {
            synchronized(held) {
                if (held.isEmpty()) heldSince = System.currentTimeMillis()
                held.remove(note.key)
                held[note.key] = note
            }
            return
        }
        send(note)
    }

    private fun send(note: GlassesNotification) {
        mirrored.remove(note.key)
        mirrored[note.key] = note.provider to note.uuid
        while (mirrored.size > 300) mirrored.remove(mirrored.keys.first())
        val envelope = note.envelope()
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_UPSERT_CHUNK, envelope) }
    }

    /** A notification Visor sent is gone from the phone: the glasses take it away too. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // One held and not yet sent goes no further; one already on the glasses is removed.
        synchronized(held) { held.remove(sbn.key) }
        textReplies.remove(sbn.key)
        val (provider, uuid) = mirrored.remove(sbn.key) ?: return
        if (!display()) return
        val request = Wire.fieldBytes(1, sbn.key.toByteArray()) + Wire.fieldVarint(2, provider.toLong()) + Wire.fieldBytes(6, uuid.toByteArray())
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_REMOVE, request) }
    }

    /** The glasses listening, thinking or answering, Visor's model at work, or a Live AI session. */
    private fun talking(): Boolean =
        assistant.phase.value == AssistantPhase.THINKING || assistant.phase.value == AssistantPhase.SPEAKING ||
            assistant.liveAiPhase.value != LiveAiPhase.OFF ||
            connection.state.value.device?.assistantAttention in ATTENTIVE

    /** Lets the held ones go a moment after the conversation ends, or after a long wait whatever happens. */
    private suspend fun release() {
        while (true) {
            delay(RELEASE_CHECK_MS)
            val waited = synchronized(held) { if (held.isEmpty()) return@synchronized null else System.currentTimeMillis() - heldSince } ?: continue
            if (talking() && waited < MAX_HOLD_MS) continue
            delay(QUIET_MS)
            if (talking() && waited < MAX_HOLD_MS) continue
            val out = synchronized(held) { held.values.toList().also { held.clear() } }
            out.forEach(::send)
        }
    }

    // The last message of each conversation already sent, so an update that repeats it is not sent again.
    private val sentAt = LinkedHashMap<String, Long>()

    /**
     * A chat app's newest message as the glasses' own announcer takes it: an incoming message from
     * that provider, with the sender and the conversation from the notification's MessagingStyle.
     */
    private fun message(sbn: StatusBarNotification, provider: Int, appName: String): GlassesNotification? {
        val extras = sbn.notification.extras
        @Suppress("DEPRECATION")
        val last = (extras.getParcelableArray(Notification.EXTRA_MESSAGES))?.lastOrNull() as? android.os.Bundle
        @Suppress("DEPRECATION")
        val person = last?.getParcelable("sender_person") as? android.app.Person
        val sender = last?.getCharSequence("sender")?.toString()
            ?: person?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = last?.getCharSequence("text")?.toString()
            ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val time = last?.getLong("time")?.takeIf { it > 0 } ?: sbn.postTime
        if (text.isBlank()) return null
        // A reply sent from the notification is added to it as the user's own message: not news for the glasses.
        @Suppress("DEPRECATION")
        val user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person
        if (last != null && fromUser(last, user)) return null
        if (sentAt[sbn.key] == time) return null
        sentAt[sbn.key] = time
        while (sentAt.size > 300) sentAt.remove(sentAt.keys.first())
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val native = provider == PROVIDER_NATIVE
        // A text names its sender by number and its thread as the SMS store does, as svc70 lists them.
        val number = if (native) numberOf(person, sender) else null
        // The thread the glasses answer with (FoA 2) names the notification's reply action, for every provider.
        val threadId = threadOf(sbn, number, group)
        replyAction(sbn.notification)?.let { textReplies.put(threadId, sbn.key, it) }
        return GlassesNotification(
            key = sbn.key,
            pkg = APP_IDS[provider] ?: sbn.packageName,
            appName = appName,
            title = sender,
            text = text,
            postTime = time,
            type = TYPE_INCOMING_MESSAGE,
            provider = provider,
            sender = sender,
            senderId = number ?: person?.key ?: person?.uri?.substringAfter(':').orEmpty(),
            threadId = threadId,
            isGroup = group,
            groupName = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty(),
        )
    }

    /** The other side's number: the person's tel: uri, else the sender's number or contact. */
    private fun numberOf(person: android.app.Person?, sender: String): String? =
        person?.uri?.takeIf { it.startsWith("tel:") }?.let { Uri.decode(it.substringAfter("tel:")) } ?: numberOf(sender)

    /** The SMS store's thread for a one-to-one text, else the glasses' key for the conversation. */
    private fun threadOf(sbn: StatusBarNotification, number: String?, group: Boolean): String =
        number?.takeIf { !group }?.let { runCatching { Telephony.Threads.getOrCreateThreadId(this, it).toString() }.getOrNull() }
            ?: sbn.notification.shortcutId ?: sbn.key

    /** A message the user sent: no sender, and the user's own person when one is given. */
    private fun fromUser(m: android.os.Bundle, user: Person?): Boolean {
        @Suppress("DEPRECATION")
        val person = m.getParcelable("sender_person") as? android.app.Person
        return m.getCharSequence("sender") == null && (person == null || user != null && person.key == user.key && person.name == user.name)
    }

    /** Keeps a Messages conversation as its notification shows it, for the Display's list (see RecentChats). */
    private fun remember(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        @Suppress("DEPRECATION")
        val user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        @Suppress("DEPRECATION")
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().filterIsInstance<android.os.Bundle>()
        val numbers = LinkedHashSet<String>()
        val messages = bundles.mapNotNull { m ->
            val text = m.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val person = m.getParcelable("sender_person") as? android.app.Person
            val outgoing = fromUser(m, user)
            val sender = m.getCharSequence("sender")?.toString() ?: person?.name?.toString().orEmpty()
            val number = if (outgoing) null else numberOf(person, sender)
            number?.let { numbers += it }
            RecentChats.Message(text, sender, number.orEmpty(), m.getLong("time").takeIf { it > 0 } ?: sbn.postTime, outgoing)
        }
        if (messages.isEmpty()) return
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.takeIf { group } ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val id = threadOf(sbn, numbers.singleOrNull(), group)
        recent.remember(id, title?.toString().orEmpty(), group, numbers.toList(), messages)
    }

    /** A contact's number by their name, or the name itself when it is a number. */
    private fun numberOf(name: String): String? {
        if (name.isNotEmpty() && name.all { it.isDigit() || it in "+ -()" }) return name.filter { it.isDigit() || it == '+' }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.DISPLAY_NAME} = ?", arrayOf(name), null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    // The action that takes typed text: the app's own "Reply".
    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    override fun lastAnnounced(): Conversation? = replyable.values.lastOrNull()?.first

    override fun reply(conversation: Conversation, text: String): String? {
        val (_, action) = replyable[conversation.key] ?: return "that notification is gone"
        val inputs = action.remoteInputs ?: return "the app gives no reply field"
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { action.actionIntent.send(this, 0, fill) }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }

    private fun ready() = connection.state.value.link == LinkState.READY

    // The Display mirrors the phone's texts and removals too; other glasses get them over Bluetooth already.
    private fun display() = hasDisplay(connection.state.value.info?.build)

    // The Meta app's filters: nothing ongoing, no group summaries, nothing empty or below default
    // importance, and not the dialer, whose calls reach the glasses by Bluetooth already.
    private fun wanted(sbn: StatusBarNotification, ranking: RankingMap): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.packageName in SYSTEM) return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras
        if (extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() && extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()) return false
        val r = Ranking()
        if (ranking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        if (sbn.packageName == getSystemService(TelecomManager::class.java)?.defaultDialerPackage) return false
        if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this) && !display()) return false
        return true
    }

    /** The app's icon as a 32x32 PNG, as the Meta app sends it. */
    private fun icon(pkg: String): ByteArray? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 32, 32)
        d.draw(Canvas(bmp))
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    /** The package of the first session that is playing, or "" when none is. */
    private fun reportMedia(controllers: List<MediaController>) {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.packageName.orEmpty()
        setProvider(playing)
    }

    private fun setProvider(value: String) {
        if (!ready()) return
        if (settings.state.value.stored["current_media_provider"].orEmpty() == value) return
        settings.write("current_media_provider", value)
    }

    companion object {
        private const val PROVIDER_MIRRORED = 8
        private const val PROVIDER_NATIVE = 3
        private const val TYPE_INCOMING_MESSAGE = 1

        // The app id the glasses gate on, per provider, as the Meta app sends it. Messenger and Instagram are not their package names.
        private val APP_IDS = mapOf(1 to "com.facebook.messenger", 2 to "com.whatsapp", 3 to "com.android.sms", 4 to "com.instagram")

        // The chat apps the glasses announce themselves, by their NotificationProvider.
        private val PROVIDERS = mapOf(
            "com.whatsapp" to 2, "com.whatsapp.w4b" to 2,
            "com.facebook.orca" to 1, "com.facebook.mlite" to 1,
            "com.instagram.android" to 4,
        )
        private const val NO_ACCESS = "no_notification_access"
        private val SYSTEM = setOf("android", "com.android.systemui")
    }
}

/** The glasses' assistant attention states in which a notification would talk over the conversation. */
private val ATTENTIVE = setOf("WAKE", "LISTENING", "PROCESSING", "RESPONDING", "LONG_RUNNING_SESSION_ACTIVATED")
private const val RELEASE_CHECK_MS = 500L
private const val QUIET_MS = 1_500L
private const val MAX_HOLD_MS = 120_000L
