package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The audit fixes' pure decisions, pinned in one place:
 *  - A-21: the governor's recovery ladder (one rung back per calm streak, no jumps);
 *  - B-140b: the scratch-buffer image compare;
 *  - B-008(c): the translucent guest-window decision;
 *  - B-142: forget cuts the matching live session(s);
 *  - A-11: the trust store survives labels with quotes/colons (org.json) and writes atomically.
 */
class AuditFixesTest {

    // ------------------------------------------------------------------ governor recovery

    private fun healthyInput() = Phase10GovernorInput(false, 0, 2_000_000L, 0, true)

    @Test
    fun recovery_walks_back_one_rung_per_call_without_jumps() {
        assertEquals(
            Phase10GovernorAction.RestoreResolution::class,
            Phase10Governor.recover(Phase10GovernorStage.WARNED, 60)!!.action::class,
        )
        assertEquals(Phase10GovernorStage.LOW_RESOLUTION, Phase10Governor.recover(Phase10GovernorStage.WARNED, 60)!!.next)
        // LOW_RESOLUTION -> FPS_15 stage carries no action (resolution was just restored)
        val r2 = Phase10Governor.recover(Phase10GovernorStage.LOW_RESOLUTION, 60)!!
        assertTrue(r2.action is Phase10GovernorAction.None)
        assertEquals(Phase10GovernorStage.FPS_15, r2.next)
        assertEquals(30, (Phase10Governor.recover(Phase10GovernorStage.FPS_15, 60)!!.action as Phase10GovernorAction.SetFps).fps)
        assertEquals(60, (Phase10Governor.recover(Phase10GovernorStage.FPS_30, 60)!!.action as Phase10GovernorAction.SetFps).fps)
        assertTrue(Phase10Governor.recover(Phase10GovernorStage.HIDDEN_STOPPED, 60)!!.action is Phase10GovernorAction.ResumeHiddenWindows)
        assertNull(Phase10Governor.recover(Phase10GovernorStage.NORMAL, 60))
    }

    @Test
    fun recovery_is_only_used_after_the_calm_streak() {
        assertEquals(5, Phase10Governor.RECOVERY_STREAK)
        // a healthy sample still returns None from next() - recovery is a separate call
        assertTrue(Phase10Governor.next(Phase10GovernorStage.WARNED, healthyInput()).action is Phase10GovernorAction.None)
    }

    @Test
    fun forward_ladder_is_unchanged() {
        val p = Phase10GovernorInput(false, 0, 2_000_000L, 3, true)
        var stage = Phase10GovernorStage.NORMAL
        val seen = ArrayList<kotlin.reflect.KClass<*>>()
        repeat(5) {
            val d = Phase10Governor.next(stage, p)
            seen.add(d.action::class)
            stage = d.next
        }
        assertEquals(
            listOf(
                Phase10GovernorAction.StopHiddenWindows::class,
                Phase10GovernorAction.SetFps::class,
                Phase10GovernorAction.SetFps::class,
                Phase10GovernorAction.LowerResolution::class,
                Phase10GovernorAction.WarnPc::class,
            ),
            seen,
        )
    }

    // ------------------------------------------------------------------ X11 image compare

    @Test
    fun scratch_compare_matches_only_equal_lengths_and_bytes() {
        val prev = byteArrayOf(1, 2, 3, 4, 5)
        val scratch = byteArrayOf(1, 2, 3, 4, 5, 9, 9) // oversized reusable buffer
        assertTrue(x11ImageUnchangedLen(prev, scratch, 5))
        assertFalse(x11ImageUnchangedLen(prev, scratch, 4)) // partial len never matches
        assertFalse(x11ImageUnchangedLen(null, scratch, 5))
        scratch[2] = 9
        assertFalse(x11ImageUnchangedLen(prev, scratch, 5))
    }

    // ------------------------------------------------------------------ B-008 (c)

    @Test
    fun the_guest_window_is_translucent_by_decision() {
        assertTrue(B008Policy.GUEST_WINDOW_TRANSLUCENT)
        // the measured-failure paths stay off
        assertFalse(B008Policy.HOME_TO_FRONT)
        assertFalse(B008Policy.homeToFront(directLaunchWorked = false, guestLaunched = true))
    }

    // ------------------------------------------------------------------ forget cuts sessions

    @Test
    fun forget_closes_matching_and_only_matching_sessions() {
        val fpr = "2B3F10C2DEAD1234000000000000000000000000000000000000000000000000"
        assertTrue(Phase4LinkService.forgetShouldClose(fpr, fpr))
        assertTrue(Phase4LinkService.forgetShouldClose("", fpr)) // forget-all cuts everything
        assertFalse(Phase4LinkService.forgetShouldClose("3C", fpr))
        assertTrue(Phase4LinkService.forgetShouldClose("2b3f", "2B3F")) // case-insensitive prefix
    }

    // ------------------------------------------------------------------ trust store JSON

    @Test
    fun trust_store_survives_labels_with_quotes_and_json_tokens() {
        val f = File.createTempFile("trusted", ".ndjson")
        try {
            Phase4Crypto.trustRemember(f, "AABBCCDD", "label with \"quotes\" and :colons: and \\\\ backslash")
            val rows = Phase4Crypto.trustRows(f)
            assertEquals(1, rows.size)
            assertEquals("AABBCCDD", rows[0].first)
            assertEquals("label with \"quotes\" and :colons: and \\\\ backslash", rows[0].second)
            assertTrue(Phase4Crypto.trustForget(f, "AABB"))
            assertEquals(0, Phase4Crypto.trustRows(f).size)
            // a malformed line is skipped, never fatal
            f.writeText("{not json at all\n")
            assertEquals(0, Phase4Crypto.trustRows(f).size)
        } finally {
            f.delete()
        }
    }
}
