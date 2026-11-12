package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-140: the repeat-key rules. The phone evidence (T15-2, logcat-full.log 23:53:47-48) shows
 * both 'p' presses reaching the phone and being queued to XTEST, while Notepad++ showed
 * "np15ok" - so the loss is inside the X server/clients, not the PC or the wire: this Xvfb
 * drops a synthetic KeyRelease when a keycode is pressed twice inside its auto-repeat delay.
 * These tests pin the two decisions that keep a repeat key landing.
 */
class X11KeyGateTest {

    @Test
    fun a_first_press_needs_no_release_first() {
        assertFalse(X11KeyGate.releaseBeforePress(down = true, alreadyHeld = false))
    }

    @Test
    fun a_press_for_a_key_we_still_hold_releases_it_first() {
        // The second 'p' of "npp": the first release was dropped and the key is still held.
        assertTrue(X11KeyGate.releaseBeforePress(down = true, alreadyHeld = true))
    }

    @Test
    fun releases_never_insert_another_release() {
        assertFalse(X11KeyGate.releaseBeforePress(down = false, alreadyHeld = true))
        assertFalse(X11KeyGate.releaseBeforePress(down = false, alreadyHeld = false))
    }

    @Test
    fun a_release_is_retried_only_while_the_keymap_still_shows_the_key_down() {
        assertFalse(X11KeyGate.needsAnotherRelease(stillDown = false))
        assertTrue(X11KeyGate.needsAnotherRelease(stillDown = true))
    }

    @Test
    fun the_keymap_parser_reads_bits_low_to_high() {
        // QueryKeymap: 32 bytes, bit n of the map = keycode n is down.
        val map = ByteArray(32)
        map[0] = 0x05 // bits 0 and 2
        map[5] = 0x80.toByte() // bit 47
        val bits = Phase2X11.X11.parseKeymap(map)
        assertEquals(256, bits.size)
        assertTrue(bits[0])
        assertFalse(bits[1])
        assertTrue(bits[2])
        assertFalse(bits[3])
        assertTrue(bits[47])
        assertFalse(bits[48])
        assertFalse(bits[255])
    }
}
