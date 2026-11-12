package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit row 25: the adaptive-bitrate policy. Pure decisions pinned here; the session
 * applies them through Phase5Encoder.setTargetBitrate (a runtime MediaCodec parameter).
 */
class Phase5AbrTest {

    private fun vq(fps: Double, dropped: Int = 0, rx: Long = 0) = Phase5Abr.VqStats(fps, dropped, rx)

    @Test
    fun parse_reads_the_vq_line() {
        val s = Phase5Abr.parseVq("vq fps=42.0 dropped=3 rx=123456")
        assertEquals(42.0, s?.fps ?: 0.0, 0.001)
        assertEquals(3, s?.dropped)
        assertEquals(123456L, s?.rxBytes)
        assertNull(Phase5Abr.parseVq("pong 123"))
        assertNull(Phase5Abr.parseVq("vq fps=oops dropped=0"))
    }

    @Test
    fun a_drop_steps_down_immediately() {
        val (s, d) = Phase5Abr.step(
            Phase5Abr.State(12_000_000), 0L, vq(fps = 20.0, dropped = 2), 12_000_000, 60.0,
        )
        assertTrue(d is Phase5Abr.Decision.Set)
        assertEquals(9_600_000, (d as Phase5Abr.Decision.Set).bitrate)
        assertEquals(9_600_000, s.bitrate)
    }

    @Test
    fun slow_fps_is_pressure_after_the_cooldown() {
        val start = Phase5Abr.State(12_000_000, lastChangeMs = 0)
        // inside the 2 s cooldown: no change even under pressure
        val (s1, d1) = Phase5Abr.step(start, 1_000L, vq(fps = 30.0), 12_000_000, 60.0)
        assertTrue(d1 is Phase5Abr.Decision.None)
        assertEquals(1, s1.pressureStreak)
        // past the cooldown: one -20% step
        val (s2, d2) = Phase5Abr.step(s1, 5_000L, vq(fps = 30.0), 12_000_000, 60.0)
        assertTrue(d2 is Phase5Abr.Decision.Set)
        assertEquals(9_600_000, (d2 as Phase5Abr.Decision.Set).bitrate)
        assertEquals(5_000L, s2.lastChangeMs)
    }

    @Test
    fun healthy_seconds_raise_and_never_pass_the_plan() {
        var s = Phase5Abr.State(9_600_000, lastChangeMs = Long.MIN_VALUE)
        var raised = false
        for (i in 1..5) {
            val (ns, nd) = Phase5Abr.step(s, i * 5_000L, vq(fps = 59.5), 12_000_000, 60.0)
            s = ns
            if (nd is Phase5Abr.Decision.Set) {
                raised = true
                assertTrue("never past the plan", nd.bitrate <= 12_000_000)
            }
        }
        assertTrue("at least one +20% raise happened", raised)
        assertEquals("settles at the plan ceiling", 12_000_000, s.bitrate)
    }

    @Test
    fun never_below_the_floor_and_above_the_plan() {
        val (s1, d1) = Phase5Abr.step(
            Phase5Abr.State(Phase5Abr.MIN_BITRATE), 0L, vq(fps = 10.0, dropped = 5), 12_000_000, 60.0,
        )
        assertTrue(d1 is Phase5Abr.Decision.None || (d1 as Phase5Abr.Decision.Set).bitrate >= Phase5Abr.MIN_BITRATE)
        assertEquals(Phase5Abr.MIN_BITRATE, s1.bitrate)
        val (s2, d2) = Phase5Abr.step(
            Phase5Abr.State(12_000_000, lastChangeMs = Long.MIN_VALUE, healthyStreak = 5),
            0L, vq(fps = 60.0), 12_000_000, 60.0,
        )
        assertTrue(d2 is Phase5Abr.Decision.None) // already at the plan: nothing to raise
        assertEquals(12_000_000, s2.bitrate)
    }

    @Test
    fun down_and_up_math() {
        assertEquals(9_600_000, Phase5Abr.down(12_000_000))
        assertEquals(Phase5Abr.MIN_BITRATE, Phase5Abr.down(4_000_000))
        // raw +20% steps; the plan ceiling is the caller's clamp (step() coerces)
        assertEquals(12_000_000, Phase5Abr.up(10_000_000))
        assertEquals(13_200_000, Phase5Abr.up(11_000_000))
    }

    @Test
    fun every_down_step_requests_a_keyframe_and_up_steps_do_not() {
        val (s1, d1) = Phase5Abr.step(
            Phase5Abr.State(12_000_000), 0L, vq(fps = 10.0, dropped = 4), 12_000_000, 60.0,
        )
        assertTrue(d1 is Phase5Abr.Decision.Set)
        assertTrue("the decoder must resync after a stall", (d1 as Phase5Abr.Decision.Set).keyframe)
        val (_, d2) = Phase5Abr.step(
            s1.copy(lastChangeMs = Long.MIN_VALUE, healthyStreak = 3),
            10_000L, vq(fps = 59.9), 12_000_000, 60.0,
        )
        if (d2 is Phase5Abr.Decision.Set) assertFalse("up-steps need no keyframe", d2.keyframe)
    }
}
