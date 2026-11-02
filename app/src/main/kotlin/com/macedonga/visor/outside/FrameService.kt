package com.macedonga.visor.outside

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import com.macedonga.visor.core.media.LiveStream
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.outside.DeveloperIntents as I
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Live view's picture, as JPEG stills, for apps that hold developer mode's token. One grab a tick serves every
 * subscriber; each gets a frame once its own interval has passed. Frames come only while a Live runs.
 */
@AndroidEntryPoint
class FrameService : Service() {
    @Inject lateinit var developer: DeveloperMode
    @Inject lateinit var live: LiveStream
    @Inject lateinit var log: EventLog

    private class Subscriber(val who: String, val token: String, val gapMs: Long, var sentAt: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscribers = ConcurrentHashMap<IBinder, Pair<Messenger, Subscriber>>()
    private var pump: Job? = null
    private var tooBig = false

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { msg -> take(msg); true })

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        scope.cancel()
        subscribers.clear()
        super.onDestroy()
    }

    private fun take(msg: Message) {
        val client = msg.replyTo ?: return
        val who = packageManager.getNameForUid(msg.sendingUid) ?: "uid ${msg.sendingUid}"
        val data = runCatching { msg.data }.getOrNull()
        when (msg.what) {
            I.FRAMES_SUBSCRIBE -> {
                val token = runCatching { data?.getString(I.EXTRA_TOKEN) }.getOrNull()
                val refused = when {
                    !developer.on.value -> OFF
                    !developer.holds(token) -> TOKEN
                    else -> null
                }
                if (refused != null) {
                    log.add("Developer mode: no Live frames for $who: $refused")
                    send(client, I.FRAMES_REFUSED, Bundle().apply { putString(I.EXTRA_REASON, refused) })
                    return
                }
                val gap = (runCatching { data?.getLong(I.EXTRA_INTERVAL_MS) }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_GAP_MS).coerceAtLeast(MIN_GAP_MS)
                subscribers[client.binder] = client to Subscriber(who, token!!, gap)
                log.add("Developer mode: Live frames for $who every $gap ms")
                start()
            }
            I.FRAMES_UNSUBSCRIBE -> subscribers.remove(client.binder)?.let { log.add("Developer mode: no more Live frames for ${it.second.who}") }
        }
    }

    private fun start() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (subscribers.isNotEmpty()) {
                live.running.first { it }
                val began = SystemClock.elapsedRealtime()
                val jpeg = runCatching { live.frameNow() }.getOrNull()
                if (jpeg != null && jpeg.size > MAX_BYTES) {
                    // Past what one Binder call carries.
                    if (!tooBig) log.add("Developer mode: a Live frame of ${jpeg.size / 1000} KB is too big to hand over")
                    tooBig = true
                } else if (jpeg != null) {
                    serve(jpeg)
                }
                val gap = subscribers.values.minOfOrNull { it.second.gapMs } ?: break
                delay((gap - (SystemClock.elapsedRealtime() - began)).coerceAtLeast(MIN_PAUSE_MS))
            }
        }
    }

    private fun serve(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        for ((key, entry) in subscribers) {
            val (client, sub) = entry
            // The mode turned off or the token renewed since it subscribed.
            if (!developer.on.value || !developer.holds(sub.token)) {
                subscribers.remove(key)
                log.add("Developer mode: no more Live frames for ${sub.who}")
                send(client, I.FRAMES_REFUSED, Bundle().apply { putString(I.EXTRA_REASON, if (developer.on.value) TOKEN else OFF) })
                continue
            }
            // A grab takes a while: a frame a little early beats one a whole tick late.
            if (now - sub.sentAt < sub.gapMs - SLACK_MS) continue
            val frame = Bundle().apply {
                putByteArray(I.EXTRA_JPEG, jpeg)
                putLong(I.EXTRA_TIME, System.currentTimeMillis())
            }
            if (send(client, I.FRAMES_FRAME, frame)) sub.sentAt = now else subscribers.remove(key)
        }
    }

    private fun send(client: Messenger, what: Int, data: Bundle): Boolean = try {
        client.send(Message.obtain(null, what).apply { this.data = data })
        true
    } catch (_: RemoteException) {
        false
    }

    private companion object {
        const val OFF = "OFF"
        const val TOKEN = "TOKEN"
        const val DEFAULT_GAP_MS = 1_000L
        const val MIN_GAP_MS = 200L
        const val MIN_PAUSE_MS = 20L
        const val SLACK_MS = 50L
        const val MAX_BYTES = 900_000
    }
}
