package com.macedonga.visor.core.media

import kotlin.math.abs

/**
 * When each frame is due on screen. The glasses film at an even rate but send in bursts, four or five frames in a few
 * ms and then 110-160 ms of nothing, and shown on arrival a burst collapses into one or two pictures. A frame is
 * due at its own capture time on the phone's clock plus a delay that covers the worst lateness of the last 10 s.
 */
class Playout {
    private class Sample(val at: Long, val lag: Long)

    // Arrival minus pts over the window, kept monotonic: the first of each is the smallest and the largest.
    private val lows = ArrayDeque<Sample>()
    private val highs = ArrayDeque<Sample>()
    private var delayUs = MIN_US
    private var lastUs = 0L

    /** When the frame filmed at [ptsUs] that arrived at [arrivedNs] should be shown, both on System.nanoTime(). */
    fun due(ptsUs: Long, arrivedNs: Long): Long {
        val now = arrivedNs / 1000
        val lag = now - ptsUs
        // The glasses' clock started again.
        lows.firstOrNull()?.let { if (abs(lag - it.lag) > JUMP_US) clear() }
        while (lows.isNotEmpty() && lows.last().lag >= lag) lows.removeLast()
        while (highs.isNotEmpty() && highs.last().lag <= lag) highs.removeLast()
        lows.addLast(Sample(now, lag))
        highs.addLast(Sample(now, lag))
        while (lows.first().at < now - WINDOW_US) lows.removeFirst()
        while (highs.first().at < now - WINDOW_US) highs.removeFirst()

        val offset = lows.first().lag
        val target = (highs.first().lag - offset + MARGIN_US).coerceIn(MIN_US, MAX_US)
        // Up at once; down by at most 1% of the time passed, so nobody sees it.
        delayUs = if (target >= delayUs || lastUs == 0L) target else maxOf(target, delayUs - (now - lastUs) / 100)
        lastUs = now
        return (ptsUs + offset + delayUs) * 1000
    }

    private fun clear() {
        lows.clear()
        highs.clear()
        lastUs = 0
    }

    private companion object {
        const val WINDOW_US = 10_000_000L
        const val MARGIN_US = 15_000L
        const val MIN_US = 20_000L
        const val MAX_US = 500_000L
        const val JUMP_US = 3_000_000L
    }
}
