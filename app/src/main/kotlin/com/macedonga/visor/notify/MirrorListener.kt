package com.macedonga.visor.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.assistant.AssistantPhase
import com.macedonga.visor.core.assistant.Conversation
import com.macedonga.visor.core.assistant.LiveAiPhase
import com.macedonga.visor.core.assistant.Messaging
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.ui.allows
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's notifications on the glasses: svc46 type 1 carries a
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

    // The newest conversation sent to the glasses that its app lets answer.
    @Volatile private var announced: Conversation? = null

    // What came while the wearer was talking with the assistant, newest per notification, and since when.
    private val held = LinkedHashMap<String, GlassesNotification>()
    private var heldSince = 0L
    private var releasing: Job? = null

    // The provider and uuid of the last notification sent for each key, which a removal names.
    private val mirrored = LinkedHashMap<String, Pair<Int, String>>()

    // The last message of each conversation already sent, so an update that repeats it is not sent again.
    private val sentAt = LinkedHashMap<String, Long>()

    // One thread, in order, off the main one: the state above is only touched there.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var sessions: MediaSessionManager? = null
    private var watcher: Job? = null
    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { reportMedia(it.orEmpty()) }

    override fun onListenerConnected() {
        assistant.messaging = this
        val manager = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, MirrorListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(onSessions, me)
            sessions = manager
            reportMedia(manager.getActiveSessions(me))
        }
        // The glasses may connect after the listener does: check again after every settings read,
        // which is also when Visor first knows what they hold.
        watcher?.cancel()
        watcher = scope.launch {
            settings.state.map { it.readAt }.distinctUntilChanged().collect { at ->
                if (at != null) sessions?.let { m -> runCatching { reportMedia(m.getActiveSessions(me)) } }
            }
        }
    }

    override fun onListenerDisconnected() {
        if (assistant.messaging === this) assistant.messaging = null
        watcher?.cancel()
        watcher = null
        sessions?.removeOnActiveSessionsChangedListener(onSessions)
        sessions = null
        setProvider(NO_ACCESS)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        scope.launch { posted(sbn, rankingMap) }
    }

    /** A notification Visor sent is gone from the phone: the glasses take it away too. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        scope.launch { removed(sbn) }
    }

    private fun posted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!wanted(sbn, rankingMap)) return
        val display = display()
        // The Messages app's conversations are kept whether or not the glasses listen: the Display's list reads them.
        val sms = sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)
        if (sms) remember(sbn)
        if (sms && !display) return
        // Glasses without a display read out chat messages only and drop the rest unpresented: another app's
        // chat goes to them as a WhatsApp message, anything else not at all.
        val chatProvider = PROVIDERS[sbn.packageName] ?: GlassesNotification.PROVIDER_WHATSAPP.takeIf { !display && chat(sbn.notification) }
        if (chatProvider == null && !display) return
        apps.saw(sbn.packageName)
        if (!choice.enabled.value || !ready() || sbn.packageName in apps.muted.value) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val r = Ranking()
        // The phone's texts, SMS or RCS, go as native messages, which the glasses' Messages app files by thread.
        (if (sms) GlassesNotification.PROVIDER_NATIVE else chatProvider)?.let { provider ->
            message(sbn, provider, appName)?.let { m ->
                deliver(m)
                if (replyAction(n) != null) announced = Conversation(sbn.key, m.sender, appName)
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
            provider = GlassesNotification.PROVIDER_MIRRORED,
            actions = n.actions?.mapNotNull { it.title?.toString()?.takeIf(String::isNotEmpty) }.orEmpty(),
            icon = icon(sbn.packageName),
            importance = if (rankingMap.getRanking(sbn.key, r)) r.importance else NotificationManager.IMPORTANCE_DEFAULT,
            flags = n.flags,
            category = n.category,
            channel = n.channelId,
        )
        deliver(note)
    }

    /** To the glasses now, or once the conversation with the assistant is over. */
    private fun deliver(note: GlassesNotification) {
        if (talking()) {
            if (held.isEmpty()) heldSince = System.currentTimeMillis()
            held.remove(note.key)
            held[note.key] = note
            if (releasing?.isActive != true) releasing = scope.launch { release() }
            return
        }
        send(note)
    }

    private fun send(note: GlassesNotification) {
        mirrored.remove(note.key)
        mirrored[note.key] = note.provider to note.uuid
        while (mirrored.size > MAX_KEPT) mirrored.remove(mirrored.keys.first())
        val envelope = note.envelope()
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_UPSERT_CHUNK, envelope) }
    }

    private fun removed(sbn: StatusBarNotification) {
        // One held and not yet sent goes no further; one already on the glasses is removed.
        held.remove(sbn.key)
        textReplies.remove(sbn.key)
        if (announced?.key == sbn.key) announced = null
        val (provider, uuid) = mirrored.remove(sbn.key) ?: return
        if (!display()) return
        val request = GlassesNotification.removal(sbn.key, provider, uuid)
        connection.submit { it.sendOnService(GlassesNotification.SVC, GlassesNotification.MT_REMOVE, request) }
    }

    /** The glasses listening, thinking or answering, Visor's model at work, or a Live AI session. */
    private fun talking(): Boolean =
        assistant.phase.value == AssistantPhase.THINKING || assistant.phase.value == AssistantPhase.SPEAKING ||
            assistant.liveAiPhase.value != LiveAiPhase.OFF ||
            connection.state.value.device?.assistantAttention in ATTENTIVE

    /** Lets the held ones go a moment after the conversation ends, or after a long wait whatever happens. */
    private suspend fun release() {
        while (held.isNotEmpty()) {
            delay(RELEASE_CHECK_MS)
            if (talking() && System.currentTimeMillis() - heldSince < MAX_HOLD_MS) continue
            delay(QUIET_MS)
            if (talking() && System.currentTimeMillis() - heldSince < MAX_HOLD_MS) continue
            val out = held.values.toList()
            held.clear()
            out.forEach(::send)
        }
    }

    /**
     * A chat app's newest message as the glasses' own announcer takes it: an incoming message from
     * that provider, with the sender and the conversation from the notification's MessagingStyle.
     */
    private fun message(sbn: StatusBarNotification, provider: Int, appName: String): GlassesNotification? {
        val extras = sbn.notification.extras
        @Suppress("DEPRECATION")
        val last = (extras.getParcelableArray(Notification.EXTRA_MESSAGES))?.lastOrNull() as? Bundle
        @Suppress("DEPRECATION")
        val person = last?.getParcelable("sender_person") as? Person
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
        while (sentAt.size > MAX_KEPT) sentAt.remove(sentAt.keys.first())
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val native = provider == GlassesNotification.PROVIDER_NATIVE
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
            type = GlassesNotification.TYPE_INCOMING_MESSAGE,
            provider = provider,
            sender = sender,
            senderId = number ?: person?.key ?: person?.uri?.substringAfter(':').orEmpty(),
            threadId = threadId,
            isGroup = group,
            groupName = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty(),
        )
    }

    /** The other side's number: the person's tel: uri, else the sender's number or contact. */
    private fun numberOf(person: Person?, sender: String): String? =
        person?.uri?.takeIf { it.startsWith("tel:") }?.let { Uri.decode(it.substringAfter("tel:")) } ?: numberOf(sender)

    /** The SMS store's thread for a one-to-one text, else the glasses' key for the conversation. */
    private fun threadOf(sbn: StatusBarNotification, number: String?, group: Boolean): String =
        number?.takeIf { !group }?.let { runCatching { Telephony.Threads.getOrCreateThreadId(this, it).toString() }.getOrNull() }
            ?: sbn.notification.shortcutId ?: sbn.key

    /** A message the user sent: no sender, and the user's own person when one is given. */
    private fun fromUser(m: Bundle, user: Person?): Boolean {
        @Suppress("DEPRECATION")
        val person = m.getParcelable("sender_person") as? Person
        return m.getCharSequence("sender") == null && (person == null || user != null && person.key == user.key && person.name == user.name)
    }

    /** Keeps a Messages conversation as its notification shows it, for the Display's list (see RecentChats). */
    private fun remember(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        @Suppress("DEPRECATION")
        val user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        @Suppress("DEPRECATION")
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().filterIsInstance<Bundle>()
        val numbers = LinkedHashSet<String>()
        // Each sender looked up once; "" for none found.
        val lookups = HashMap<Pair<String?, String>, String>()
        val messages = bundles.mapNotNull { m ->
            val text = m.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val person = m.getParcelable("sender_person") as? Person
            val outgoing = fromUser(m, user)
            val sender = m.getCharSequence("sender")?.toString() ?: person?.name?.toString().orEmpty()
            val number = if (outgoing) null else lookups.getOrPut(person?.uri to sender) { numberOf(person, sender).orEmpty() }.ifEmpty { null }
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
        if (!allows(Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.DISPLAY_NAME} = ?", arrayOf(name), null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** A conversation in the chat layout (MessagingStyle), which the glasses' announcer reads as a message. */
    @Suppress("DEPRECATION")
    private fun chat(n: Notification): Boolean = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.isNotEmpty() == true

    // The action that takes typed text: the app's own "Reply".
    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    override fun lastAnnounced(): Conversation? = announced

    override fun reply(conversation: Conversation, text: String): String? {
        val action = textReplies.byKey(conversation.key) ?: return "that notification is gone"
        return textReplies.send(this, action, text)
    }

    private fun ready() = connection.state.value.link == LinkState.READY

    // The Display mirrors the phone's texts and removals too; other glasses get them over Bluetooth already.
    private fun display() = isDisplayBuild(connection.state.value.info?.build)

    // Nothing ongoing, no group summaries, nothing empty or below default
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
        return true
    }

    /** The app's icon as a 32x32 PNG. */
    private fun icon(pkg: String): ByteArray? =
        runCatching { iconBytes(packageManager.getApplicationIcon(pkg), 32, Bitmap.CompressFormat.PNG) }.getOrNull()

    /** Sets current_media_provider to the playing session's package, or "" when none plays. */
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
        // The app id the glasses gate on, per provider. Messenger and Instagram are not their package names.
        private val APP_IDS = mapOf(
            GlassesNotification.PROVIDER_MESSENGER to "com.facebook.messenger",
            GlassesNotification.PROVIDER_WHATSAPP to "com.whatsapp",
            GlassesNotification.PROVIDER_NATIVE to "com.android.sms",
            GlassesNotification.PROVIDER_INSTAGRAM to "com.instagram",
        )

        // The chat apps the glasses announce themselves, by their NotificationProvider.
        private val PROVIDERS = mapOf(
            "com.whatsapp" to GlassesNotification.PROVIDER_WHATSAPP, "com.whatsapp.w4b" to GlassesNotification.PROVIDER_WHATSAPP,
            "com.facebook.orca" to GlassesNotification.PROVIDER_MESSENGER, "com.facebook.mlite" to GlassesNotification.PROVIDER_MESSENGER,
            "com.instagram.android" to GlassesNotification.PROVIDER_INSTAGRAM,
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
private const val MAX_KEPT = 300
