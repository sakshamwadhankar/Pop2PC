package dev.unifieddesktop.app

/**
 * Adaptive bitrate (ALGORITHMS 3; audit row 25). Pure policy so it is JVM-tested; the
 * session applies the decision through [Phase5Encoder.setTargetBitrate] (a runtime
 * MediaCodec parameter, no encoder rebuild needed).
 *
 * Wire input: the PC sends `vq fps=<presented fps> dropped=<frames dropped this second>
 * rx=<bytes this second>` once a second on CH_CONTROL (view.rs stats tick).
 *
 * Rules (T15-4 item 3, manager spec):
 *  - a drop, or a presented fps below 90% of the plan target, is pressure: cut 20% at once
 *    AND request a keyframe (the stall just dropped frames; the decoder needs a resync);
 *  - presented fps at/above 95% of target with no drops is healthy; after 3 healthy
 *    seconds in a row, raise 20% (exponential up, never past the plan bitrate);
 *  - never below [MIN_BITRATE], never above the plan, and never two steps within
 *    [MIN_INTERVAL_MS] (one session can never prove a 1-2 ms change — OPTIMIZATION 5.3
 *    applies to bitrate just as it does to latency: measured steps, not noise).
 */
object Phase5Abr {
    const val MIN_BITRATE = 4_000_000
    const val MIN_INTERVAL_MS = 2_000L
    const val HEALTHY_STREAK_FOR_UP = 3

    class VqStats(val fps: Double, val dropped: Int, val rxBytes: Long)

    data class State(
        val bitrate: Int,
        val lastChangeMs: Long = Long.MIN_VALUE,
        val pressureStreak: Int = 0,
        val healthyStreak: Int = 0,
    )

    sealed class Decision {
        object None : Decision()
        data class Set(val bitrate: Int, val keyframe: Boolean = false) : Decision()
    }

    /** Parse `vq fps=42.0 dropped=3 rx=123456`; null on anything else. */
    fun parseVq(text: String): VqStats? {
        val t = text.trim()
        if (!t.startsWith("vq ")) return null
        var fps = Double.NaN
        var dropped = -1
        var rx = -1L
        for (part in t.removePrefix("vq ").trim().split(Regex("\\s+"))) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) {
                "fps" -> fps = kv[1].trim().removeSuffix("fps").trim().toDoubleOrNull() ?: Double.NaN
                "dropped" -> dropped = kv[1].trim().toIntOrNull() ?: -1
                "rx" -> rx = kv[1].trim().toLongOrNull() ?: -1L
            }
        }
        if (fps.isNaN() || dropped < 0) return null
        return VqStats(fps, dropped, rx)
    }

    /**
     * One PC stats sample -> the next state and (maybe) a bitrate change. `planBitrate` is
     * the quality plan's ceiling (QualityPref), `targetFps` the plan fps the PC should reach.
     */
    fun step(
        state: State,
        nowMs: Long,
        stats: VqStats,
        planBitrate: Int,
        targetFps: Double,
    ): Pair<State, Decision> {
        // Long.MIN_VALUE = "never changed yet"; guard the subtraction against overflow.
        val cooled = state.lastChangeMs == Long.MIN_VALUE ||
            nowMs - state.lastChangeMs >= MIN_INTERVAL_MS
        val pressure = stats.dropped > 0 || (targetFps > 0 && stats.fps > 0 && stats.fps < targetFps * 0.9)
        val healthy = stats.dropped == 0 && targetFps > 0 && stats.fps >= targetFps * 0.95
        var s = state.copy(
            pressureStreak = if (pressure) state.pressureStreak + 1 else 0,
            healthyStreak = if (healthy) state.healthyStreak + 1 else 0,
        )
        if (!cooled) return s to Decision.None
        return if (pressure) {
            val next = down(s.bitrate)
            if (next == s.bitrate) s to Decision.None
            // T15-4 item 3 (manager spec): every multiplicative down-step also asks for a
            // keyframe - a stall just dropped frames, so the decoder needs a clean resync.
            else s.copy(bitrate = next, lastChangeMs = nowMs) to Decision.Set(next, keyframe = true)
        } else if (healthy && s.healthyStreak >= HEALTHY_STREAK_FOR_UP && s.bitrate < planBitrate) {
            val next = up(s.bitrate).coerceAtMost(planBitrate)
            if (next == s.bitrate) s to Decision.None
            else s.copy(bitrate = next, lastChangeMs = nowMs) to Decision.Set(next)
        } else {
            s to Decision.None
        }
    }

    /** Multiplicative down: -20% (audit/manager spec), never below [MIN_BITRATE]. */
    fun down(b: Int): Int = (b / 5 * 4).coerceAtLeast(MIN_BITRATE)

    /** Exponential up: +20% per healthy gate, never past the plan (the caller clamps too). */
    fun up(b: Int): Int = (b / 5 * 6).coerceAtLeast(b + 1)
}
