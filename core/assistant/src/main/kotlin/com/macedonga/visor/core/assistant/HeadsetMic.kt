package com.macedonga.visor.core.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [ms] of the glasses' microphone as a Bluetooth headset (SCO or LE audio), 16 kHz 16-bit mono PCM, with each buffer's
 * peak to [peak]; null when the glasses at [address] are no headset to this phone, the call mic does not reach them,
 * or it stays silent. Never the phone's own microphone.
 */
@SuppressLint("MissingPermission")
internal suspend fun headsetTake(context: Context, address: String?, ms: Long, say: (String) -> Unit, peak: (Int) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
    if (address == null) return@withContext null
    val am = context.getSystemService(AudioManager::class.java)
    val glasses = am.availableCommunicationDevices.firstOrNull { it.isHeadset() && it.address.equals(address, ignoreCase = true) }
    if (glasses == null) {
        say("the glasses are no Bluetooth headset to this phone (${am.availableCommunicationDevices.joinToString { "${it.type}:${it.productName}" }})")
        return@withContext null
    }
    val mode = am.mode
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    try {
        if (!am.setCommunicationDevice(glasses)) {
            say("could not route the call mic to ${glasses.productName}")
            return@withContext null
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        }.getOrElse { say("no headset recorder: ${it.message}"); return@withContext null }
        try {
            rec.setPreferredDevice(am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == glasses.type && it.address.equals(address, ignoreCase = true) })
            rec.startRecording()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(3_200)
            // The headset link takes a second or two to come up and reads exact zeros until then: time the take from
            // the first sound, or give up.
            val opened = System.currentTimeMillis()
            var waited = -1L
            while (System.currentTimeMillis() - opened < LIVE_WAIT_MS) {
                ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if ((0 until n).any { buf[it].toInt() != 0 }) {
                    waited = System.currentTimeMillis() - opened
                    out.write(buf, 0, n)
                    break
                }
            }
            if (waited < 0) {
                say("headset from ${glasses.productName} stayed silent for $LIVE_WAIT_MS ms")
                return@withContext null
            }
            val routed = rec.routedDevice
            if (routed == null || !routed.isHeadset()) {
                say("the call mic went to ${routed?.productName ?: "nothing"}, not the glasses")
                return@withContext null
            }
            var loudest = peakOf(buf, out.size())
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                out.write(buf, 0, n)
                val p = peakOf(buf, n)
                peak(p)
                loudest = maxOf(loudest, p)
            }
            val pcm = out.toByteArray()
            say("headset take from ${glasses.productName} (type ${glasses.type}, routed to ${routed.productName}): ${pcm.size / CustomWakeWord.BYTES_PER_MS} ms, peak $loudest, live after $waited ms")
            pcm
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    } finally {
        am.clearCommunicationDevice()
        am.mode = mode
    }
}

private fun AudioDeviceInfo.isHeadset() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

/** The loudest of the first [n] bytes of 16-bit little-endian PCM. */
private fun peakOf(pcm: ByteArray, n: Int): Int {
    var peak = 0
    for (k in 0 until n / 2) peak = maxOf(peak, kotlin.math.abs((pcm[k * 2].toInt() and 0xFF) or (pcm[k * 2 + 1].toInt() shl 8)))
    return peak
}

private const val RATE = 16_000
private const val LIVE_WAIT_MS = 4_000L
