package com.macedonga.visor.core.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** An encoded audio packet, AAC-LC, on the glasses' clock like the video. */
class AudioPacket(val ptsUs: Long, val data: ByteArray)

/** AAC-LC as the recorder and RTMP take it: [asc] is the AudioSpecificConfig. */
class AacFormat(val sampleRate: Int, val channels: Int, val asc: ByteArray)

/**
 * One MP4 in Movies/Visor, written as the frames come: the glasses' video as it is (HEVC or H.264), the audio as AAC. The file
 * stays pending, so the gallery does not list it, until [finish]. Audio before the first video frame is dropped,
 * and everything is timed from that frame.
 */
class Mp4Recording(private val context: Context, config: ByteArray, audio: AacFormat?, name: String, takenAt: Long) {
    val uri: Uri
    private val muxer: MediaMuxer
    private val fd: android.os.ParcelFileDescriptor
    private val videoTrack: Int
    private val audioTrack: Int
    private var firstPts = -1L
    private var lastPts = 0L
    private var lastAudioPts = -1L
    private val info = MediaCodec.BufferInfo()

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Visor")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore refused the file" }
        fd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) { "no file to write" }
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val codec = configCodec(config)
        val (width, height) = videoSize(config) ?: (1080 to 1920)
        val video = MediaFormat.createVideoFormat(codec.mime, width, height)
        video.setByteBuffer("csd-0", ByteBuffer.wrap(onlyParameterSets(codec, config)))
        videoTrack = muxer.addTrack(video)
        audioTrack = if (audio != null) {
            val a = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, audio.channels)
            a.setByteBuffer("csd-0", ByteBuffer.wrap(audio.asc))
            muxer.addTrack(a)
        } else -1
        muxer.start()
    }

    /** How long it runs so far. */
    val durationUs: Long get() = if (firstPts < 0) 0 else lastPts - firstPts

    fun video(frame: VideoFrame) {
        if (firstPts < 0) {
            if (!frame.isKey) return
            firstPts = frame.ptsUs
        }
        if (frame.ptsUs < lastPts) return
        val data = withoutParameterSets(frame.codec, frame.data)
        info.set(0, data.size, frame.ptsUs - firstPts, if (frame.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(data), info)
        lastPts = frame.ptsUs
    }

    fun audio(packet: AudioPacket) {
        if (audioTrack < 0 || firstPts < 0 || packet.ptsUs < firstPts || packet.ptsUs <= lastAudioPts) return
        info.set(0, packet.data.size, packet.ptsUs - firstPts, 0)
        muxer.writeSampleData(audioTrack, ByteBuffer.wrap(packet.data), info)
        lastAudioPts = packet.ptsUs
    }

    /** Closes the file and shows it in the gallery; a recording with no frame in it is deleted instead. */
    fun finish(): Uri? {
        val any = firstPts >= 0
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { fd.close() }
        if (!any) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}

/**
 * The last minutes of the stream, kept in memory so a moment can be saved after it happened. It always starts
 * on a keyframe, and a change of picture size starts it over.
 */
class ReplayRing(@Volatile var windowUs: Long) {
    private val video = ArrayDeque<VideoFrame>()
    private val audio = ArrayDeque<AudioPacket>()
    private var config: ByteArray? = null

    @Synchronized
    fun config(next: ByteArray) {
        val size = { c: ByteArray? -> c?.let(::videoSize) }
        if (size(config) != size(next)) {
            video.clear()
            audio.clear()
        }
        config = next
    }

    @Synchronized
    fun add(frame: VideoFrame) {
        if (video.isEmpty() && !frame.isKey) return
        video.addLast(frame)
        while (video.size > 1 && frame.ptsUs - video.first().ptsUs > windowUs) video.removeFirst()
        while (video.isNotEmpty() && !video.first().isKey) video.removeFirst()
        val start = video.firstOrNull()?.ptsUs ?: return
        while (audio.isNotEmpty() && audio.first().ptsUs < start) audio.removeFirst()
    }

    @Synchronized
    fun add(packet: AudioPacket) {
        if (video.isNotEmpty()) audio.addLast(packet)
    }

    @Synchronized
    fun clear() {
        video.clear()
        audio.clear()
    }

    /** How much is held, in microseconds. */
    val heldUs: Long
        @Synchronized get() = if (video.size < 2) 0 else video.last().ptsUs - video.first().ptsUs

    @Synchronized
    fun take(): Replay? {
        val c = config ?: return null
        if (video.isEmpty()) return null
        return Replay(c, video.toList(), audio.toList())
    }

    /** The frames from the latest keyframe on: what it takes to decode the picture now. */
    @Synchronized
    fun lastPicture(): Replay? {
        val c = config ?: return null
        val key = video.indexOfLast { it.isKey }
        if (key < 0) return null
        return Replay(c, video.subList(key, video.size).toList(), emptyList())
    }
}

class Replay(val config: ByteArray, val video: List<VideoFrame>, val audio: List<AudioPacket>)

/** Only the parameter sets of [config], Annex-B, as csd-0 wants them. */
private fun onlyParameterSets(codec: VideoCodec, config: ByteArray): ByteArray = annexB(annexBUnits(config).filter { codec.isParameterSet(it) })

/** A frame with any parameter sets in it taken out: MP4 keeps them in the sample entry only. */
private fun withoutParameterSets(codec: VideoCodec, data: ByteArray): ByteArray {
    val units = annexBUnits(data)
    if (units.none { codec.isParameterSet(it) }) return data
    return annexB(units.filter { !codec.isParameterSet(it) })
}

private fun annexB(units: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for (u in units) {
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(u)
    }
    return out.toByteArray()
}
