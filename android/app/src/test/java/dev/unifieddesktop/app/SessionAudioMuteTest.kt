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
}
