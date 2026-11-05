package com.macedonga.visor.core.media

/** One step of the automatic ladder. */
data class Rung(val quality: StreamQuality, val bitrate: Int, val frameRate: Int)

/**
 * From the best a 2.4 GHz group has held (1080p at 4 Mbit/s and 30 fps) down to 360p at 600 kbit/s.
 * The 24 fps rung under it keeps the size and eases the bitrate first.
 */
val LADDER = listOf(
    Rung(StreamQuality.FULL_HD, 4_000_000, 30),
    Rung(StreamQuality.FULL_HD, 3_500_000, 24),
    Rung(StreamQuality.HIGH, 2_500_000, 30),
    Rung(StreamQuality.MEDIUM, 1_500_000, 30),
    Rung(StreamQuality.LOW, 1_000_000, 24),
    Rung(StreamQuality.VERY_LOW, 600_000, 24),
)

/** While recording or broadcasting the size stays put and only the bitrate moves, through these shares. */
private val SHARES = listOf(1f, 0.7f, 0.5f, 0.35f)

/**
 * Picks the stream's quality second by second from how it arrives: down a rung when frames come late, slow or with
 * gaps, back up after a long calm, never to a rung that failed a minute ago, and never above what the glasses'
 * heat or their own ceiling allow.
 */
class QualityPilot {
    private var index = 0
    private var share = 0

    private var lastChange = 0L
    private var upAt = 0L
    private var bad = 0
    private var calmSince = 0L
    private var baseline = Long.MAX_VALUE
    private var baselineAt = 0L
    private val blocked = HashMap<Int, Long>()

    val rung: Rung
        get() = LADDER[index].let { it.copy(bitrate = (it.bitrate * SHARES[share]).toInt()) }

    fun reset(now: Long) {
        index = 0
        share = 0
        lastChange = now
        upAt = 0
        bad = 0
        calmSince = now
        baseline = Long.MAX_VALUE
        blocked.clear()
    }

    /** The lowest index heat and the glasses' ceiling allow right now. */
    private fun floor(thermal: String?, supported: Int?): Int {
        val heat = when (thermal) {
            "MODERATE" -> StreamQuality.HIGH
            "SEVERE" -> StreamQuality.LOW
            "CRITICAL", "SHUTDOWN" -> StreamQuality.VERY_LOW
            else -> null
        }
        val ceiling = when (supported) {
            0 -> StreamQuality.LOW
            1 -> StreamQuality.MEDIUM
            else -> null
        }
        fun at(q: StreamQuality?) = q?.let { LADDER.indexOfFirst { r -> r.quality == it } } ?: 0
        return maxOf(at(heat), at(ceiling))
    }

    /** One second of [stats]; the rung to move to, or null to stay. */
    fun step(now: Long, stats: StreamStats, thermal: String?, supported: Int?, holdSize: Boolean): Rung? {
        if (now - baselineAt > BASELINE_MS || stats.delayMs < baseline) {
            baseline = stats.delayMs
            baselineAt = now
        }
        val target = rung
        val struggling = stats.fps < target.frameRate * 0.6f || stats.delayMs - baseline > LATE_MS || stats.maxGapMs > GAP_MS
        bad = if (struggling) bad + 1 else 0
        if (struggling) calmSince = now
        val settled = now - lastChange > SETTLE_MS
        val lowest = floor(thermal, supported)

        // Heat or the glasses' ceiling come first.
        if (!holdSize && index < lowest) return move(now, lowest, 0)
        if (holdSize && index < lowest && share < SHARES.lastIndex && settled) return move(now, index, share + 1)

        if (bad >= 2 && settled) {
            if (upAt > 0 && now - upAt < FAILED_UP_MS) blocked[index] = now + BLOCK_MS
            upAt = 0
            return when {
                holdSize && share < SHARES.lastIndex -> move(now, index, share + 1)
                !holdSize && index < LADDER.lastIndex -> move(now, index + 1, 0)
                else -> null
            }
        }
        if (now - calmSince > CALM_MS && settled) {
            val up = when {
                share > 0 -> index to share - 1
                !holdSize && index > lowest && (blocked[index - 1] ?: 0) < now -> index - 1 to 0
                else -> return null
            }
            upAt = now
            return move(now, up.first, up.second)
        }
        return null
    }

    /** The glasses turned a rung down: skip it for a while. */
    fun refused(now: Long): Rung? {
        blocked[index] = now + BLOCK_MS
        if (index >= LADDER.lastIndex) return null
        return move(now, index + 1, share)
    }

    private fun move(now: Long, to: Int, toShare: Int): Rung {
        index = to
        share = toShare
        lastChange = now
        calmSince = now
        bad = 0
        baseline = Long.MAX_VALUE
        return rung
    }

    private companion object {
        const val LATE_MS = 700L
        const val GAP_MS = 900L
        const val SETTLE_MS = 4_000L
        const val CALM_MS = 40_000L
        const val FAILED_UP_MS = 20_000L
        const val BLOCK_MS = 180_000L
        const val BASELINE_MS = 15_000L
    }
}
