package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-131: the save/mute/restore decisions of [SessionAudioMute] (pure half). The Android
 * wrapper only reads the stream, applies these results and logs.
 */
class SessionAudioMuteTest {

    // ---- start -------------------------------------------------------------

    @Test
    fun toggle_off_and_no_existing_mute_means_today_s_behaviour() {
        val d = SessionAudioMute.start(enabled = false, current = 7, alreadyMuted = false)
        assertFalse(d.mute)
        assertEquals(-1, d.saved)
        assertEquals(0, d.holdsAfter)
    }

    @Test
    fun toggle_on_saves_the_current_volume_and_mutes() {
        val d = SessionAudioMute.start(enabled = true, current = 7, alreadyMuted = false)
        assertTrue(d.mute)
        assertEquals(7, d.saved)
        assertEquals(1, d.holdsAfter)
    }

    @Test
    fun a_second_session_never_saves_the_muted_zero() {
        // Reconnect replacement: session B starts while A still holds the mute (volume is
        // 0 now). The saved value must stay A's pre-session volume, whatever the toggle.
        val dOn = SessionAudioMute.start(enabled = true, current = 0, alreadyMuted = true)
        assertFalse(dOn.mute)
        assertEquals(-1, dOn.saved)
        assertEquals(1, dOn.holdsAfter)
        val dOff = SessionAudioMute.start(enabled = false, current = 0, alreadyMuted = true)
        assertFalse(dOff.mute)
        assertEquals(1, dOff.holdsAfter)
    }

    // ---- end ---------------------------------------------------------------

    @Test
    fun end_without_a_mute_touches_nothing() {
        val d = SessionAudioMute.end(muted = false, saved = -1, anotherHolder = false)
        assertNull(d.restore)
        assertEquals(0, d.holdsAfter)
    }

    @Test
    fun end_while_another_session_holds_keeps_the_mute() {
        val d = SessionAudioMute.end(muted = true, saved = 7, anotherHolder = true)
        assertNull(d.restore)
        assertEquals(1, d.holdsAfter)
    }

    @Test
    fun last_session_end_restores_the_saved_volume() {
        val d = SessionAudioMute.end(muted = true, saved = 7, anotherHolder = false)
        assertEquals(7, d.restore)
        assertEquals(0, d.holdsAfter)
    }

    @Test
    fun restore_never_uses_a_negative_volume() {
        val d = SessionAudioMute.end(muted = true, saved = -1, anotherHolder = false)
        assertEquals(0, d.restore)
    }

    // ---- the mechanism ladder (background volume restrictions) --------------

    @Test
    fun index_beats_mute_flag_and_none_is_last() {
        assertEquals(
            SessionAudioMute.Mechanism.INDEX,
            SessionAudioMute.chooseMechanism(indexWorked = true, muteFlagWorked = true),
        )
        assertEquals(
            SessionAudioMute.Mechanism.MUTE_FLAG,
            SessionAudioMute.chooseMechanism(indexWorked = false, muteFlagWorked = true),
        )
        assertEquals(
            SessionAudioMute.Mechanism.NONE,
            SessionAudioMute.chooseMechanism(indexWorked = false, muteFlagWorked = false),
        )
    }

    // ---- the crash / every-path rule ---------------------------------------

    @Test
    fun the_restore_decision_does_not_read_the_toggle() {
        // The session pump's finally calls endSession on EVERY exit path (clean exit, link
        // death, session exception, service stop) with only the mute state, so the restore
        // cannot depend on the Settings toggle. The pure end() takes no toggle argument by
        // construction; this test pins the restore behaviour the wrapper must show.
        assertTrue(SessionAudioMute.end(muted = true, saved = 5, anotherHolder = false).restore == 5)
        assertNull(SessionAudioMute.end(muted = false, saved = -1, anotherHolder = false).restore)
    }

    @Test
    fun a_mute_held_through_start_end_start_end_round_trips() {
        // The exact reconnect-replacement sequence, both orders, driven through the pure
        // decisions only: B starts before A ends, A ends, B ends.
        val b = SessionAudioMute.start(enabled = true, current = 0, alreadyMuted = true)
        val aEnd = SessionAudioMute.end(muted = true, saved = 7, anotherHolder = b.holdsAfter > 0)
        assertNull(aEnd.restore) // B still holds
        val bEnd = SessionAudioMute.end(muted = true, saved = 7, anotherHolder = false)
        assertEquals(7, bEnd.restore)
    }

    // ---- B-136: restore verification, owed volume, foreground restore -----------------

    /** (a) An INDEX restore is verified by the index read-back only - exact match or fail. */
    @Test
    fun restore_verification_is_index_readback_only() {
        assertTrue(SessionAudioMute.restoreVerified(indexReadBack = 7, wanted = 7))
        assertFalse(SessionAudioMute.restoreVerified(indexReadBack = 0, wanted = 7))
        assertFalse(SessionAudioMute.restoreVerified(indexReadBack = 5, wanted = 7))
    }

    /** (b) An unverified restore is recorded as owed, never dropped. */
    @Test
    fun unverified_restore_is_recorded_as_owed() {
        assertEquals(-1, SessionAudioMute.owedAfterRestore(verified = true, wanted = 7, owedBefore = -1))
        assertEquals(7, SessionAudioMute.owedAfterRestore(verified = false, wanted = 7, owedBefore = -1))
        // A second failed attempt keeps the same owed value (no stacking).
        assertEquals(7, SessionAudioMute.owedAfterRestore(verified = false, wanted = 7, owedBefore = 7))
    }

    /**
     * (b, live-found 2026-11-07): the background owed pre-clear failed, so the next session
     * saved the 0 it found; its own verified 0-restore must NOT erase the older owed 60 -
     * the user's real volume is still owed and the foreground restore must bring it back.
     */
    @Test
    fun an_older_owed_volume_survives_this_session_s_own_restore() {
        assertEquals(60, SessionAudioMute.owedAfterRestore(verified = true, wanted = 0, owedBefore = 60))
        // And it clears only when the verified restore WAS the owed value (or none existed).
        assertEquals(-1, SessionAudioMute.owedAfterRestore(verified = true, wanted = 60, owedBefore = 60))
        assertEquals(-1, SessionAudioMute.owedAfterRestore(verified = true, wanted = 0, owedBefore = -1))
    }

    /** (c) The foreground retry fires only when a volume is owed and no session holds. */
    @Test
    fun foreground_restore_needs_owed_volume_and_no_live_session() {
        assertTrue(SessionAudioMute.needsForegroundRestore(owed = 7, holdingSessions = 0))
        assertFalse("a live session's silence is intentional, not owed",
            SessionAudioMute.needsForegroundRestore(owed = 7, holdingSessions = 1))
        assertFalse("nothing owed -> nothing to restore",
            SessionAudioMute.needsForegroundRestore(owed = -1, holdingSessions = 0))
    }
}
