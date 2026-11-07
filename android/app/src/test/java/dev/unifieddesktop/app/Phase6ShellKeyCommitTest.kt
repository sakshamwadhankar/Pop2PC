package dev.unifieddesktop.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-135: the PC keyboard typed '=' and the shell's editable fields got '+'. The framework's
 * KeyCharacterMap for a synthesized KeyEvent turned the UNSHIFTED KEYCODE_EQUALS into '+'
 * (evidence: T14-2 logcat 13:37:51 `P6SHELL key code=81 meta=0` -> URL landed
 * `.../cookies/set/poptopc+t142`). The fix commits the character from
 * [Phase5Protocol.keyCodeToChar] through the input connection instead. These tests pin the
 * decision AND the mapping in both directions ('=' unshifted, '+' shifted).
 */
class Phase6ShellKeyCommitTest {

    /** The mapping itself, both directions (the fix's source of truth). */
    @Test
    fun equals_unshifted_maps_to_equals() {
        assertEquals('=', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_EQUALS, 0))
    }

    @Test
    fun equals_shifted_maps_to_plus() {
        assertEquals('+', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_SHIFT_ON))
    }

    /**
     * B-135 root cause: the PC '=' key (VK_OEM_PLUS, 0xBB) used to map to keycode 81, which
     * is KEYCODE_PLUS - a dedicated '+' key ('+' in both states in the framework keymap), so
     * every raw-dispatch path typed '+'. It must be KEYCODE_EQUALS (70).
     */
    @Test
    fun oem_plus_vk_maps_to_keycode_equals() {
        assertEquals(KeyEvent.KEYCODE_EQUALS, Phase5Protocol.vkToKeycode(0xBB))
        // A dedicated PLUS keycode keeps meaning '+' in both states.
        assertEquals('+', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_PLUS, 0))
        assertEquals('+', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_PLUS, KeyEvent.META_SHIFT_ON))
    }

    /** The old defect's exact shape: an unshifted '=' key must take the commit route. */
    @Test
    fun unshifted_equals_takes_commit_route() {
        val ch = Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_EQUALS, 0)
        assertTrue(ShellKeyCommit.decide(down = true, keyCode = KeyEvent.KEYCODE_EQUALS, meta = 0, ch = ch))
        assertEquals('=', ch)
    }

    @Test
    fun shifted_equals_takes_commit_route_and_yields_plus() {
        val ch = Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_SHIFT_ON)
        assertTrue(ShellKeyCommit.decide(down = true, keyCode = KeyEvent.KEYCODE_EQUALS, meta = KeyEvent.META_SHIFT_ON, ch = ch))
        assertEquals('+', ch)
    }

    /** Key UP never commits (the raw UP that follows a commit inserts nothing twice). */
    @Test
    fun key_up_never_commits() {
        assertFalse(ShellKeyCommit.decide(down = false, keyCode = KeyEvent.KEYCODE_EQUALS, meta = 0, ch = '='))
    }

    /** Ctrl/Alt combos stay real key events (shortcuts: Ctrl+= zoom, Alt+F4). */
    @Test
    fun ctrl_or_alt_keys_stay_raw() {
        assertFalse(ShellKeyCommit.decide(down = true, keyCode = KeyEvent.KEYCODE_EQUALS, meta = KeyEvent.META_CTRL_ON, ch = '='))
        assertFalse(ShellKeyCommit.decide(down = true, keyCode = KeyEvent.KEYCODE_EQUALS, meta = KeyEvent.META_ALT_ON, ch = '='))
        assertFalse(ShellKeyCommit.decide(down = true, keyCode = KeyEvent.KEYCODE_F4, meta = KeyEvent.META_ALT_ON, ch = '\u0000'))
    }

    /** Non-printable keys (Enter, Backspace, arrows) stay raw so deletion/movement keep working. */
    @Test
    fun non_printable_keys_stay_raw() {
        for (code in intArrayOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_LEFT)) {
            val ch = Phase5Protocol.keyCodeToChar(code, 0)
            assertFalse("keycode $code should stay raw", ShellKeyCommit.decide(down = true, keyCode = code, meta = 0, ch = ch))
        }
    }

    /** The shifted-punctuation mapping the fix rides on (B-059 family, extended to '='). */
    @Test
    fun shifted_punctuation_survives() {
        assertEquals(':', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.META_SHIFT_ON))
        assertEquals('_', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_MINUS, KeyEvent.META_SHIFT_ON))
        assertEquals('?', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON))
        assertEquals('=', Phase5Protocol.keyCodeToChar(KeyEvent.KEYCODE_EQUALS, 0))
    }
}
