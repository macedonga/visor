package com.macedonga.visor.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Where a [DataXConnection] reads its sealed frames from. */
fun interface FrameSource {
    suspend fun receive(): ByteArray
}

/**
 * One link's frames, still sealed, read on a coroutine of their own: the session's mux reads this link or another,
 * and opens each frame itself, so the session's one cipher sees them in order. A link nobody reads gets drained.
 */
class LinkFrames(private val link: SecureLink) : FrameSource {

    private val frames = Channel<ByteArray>(Channel.UNLIMITED)

    /** While true, frames are read and dropped: the glasses send this link nothing the session needs. */
    @Volatile
    var dropping = false

    /** Frames dropped so far. */
    @Volatile
    var dropped = 0
        private set

    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            while (true) {
                val frame = link.nextSdu()
                if (dropping) dropped++ else frames.send(frame)
            }
        } catch (cancellation: CancellationException) {
            frames.close()
            throw cancellation
        } catch (error: Throwable) {
            frames.close(error)
        }
    }

    override suspend fun receive(): ByteArray = frames.receive()

    /** Drop what is queued and what comes from now on. */
    fun drain() {
        dropping = true
        while (frames.tryReceive().isSuccess) dropped++
    }
}
