package com.macedonga.visor.phone

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings the phone so the wearer can find it: the ringtone at full alarm volume, which plays on silent, and vibration,
 * for [RING_MS] or until the phone's screen turns on. The alarm volume is put back after.
 */
@Singleton
class PhoneRinger @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var ringing: Job? = null

    /** True when it started; false when it was ringing already. What may keep it quiet, for the model to say. */
    fun ring(): Pair<Boolean, String?> {
        if (ringing?.isActive == true) return false to null
        val tone = tone()
        val buzz = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        val saved = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        // Picked up: the screen comes on, or the phone is unlocked.
        val found = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                ringing?.cancel()
            }
        }
        val screenWasOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { if (!screenWasOn) addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(context, found, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ringing = scope.launch {
            try {
                tone?.play()
                buzz?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
                delay(RING_MS)
            } finally {
                tone?.stop()
                buzz?.cancel()
                saved?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
                runCatching { context.unregisterReceiver(found) }
            }
        }
        val silent = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        val why = when {
            tone == null -> "the phone has no ringtone to play, so it only vibrates"
            silent -> "Do not disturb is set to total silence, so it may only vibrate"
            else -> null
        }
        return true to why
    }

    /** True when it was ringing. */
    fun stop(): Boolean {
        val job = ringing?.takeIf { it.isActive } ?: return false
        job.cancel()
        return true
    }

    private fun tone(): Ringtone? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return null
        return RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            isLooping = true
        }
    }

    companion object {
        const val RING_MS = 30_000L
    }
}
