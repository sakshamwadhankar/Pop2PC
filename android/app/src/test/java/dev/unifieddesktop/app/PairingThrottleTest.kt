package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit B-144: at most MAX_PROMPTS pairing prompts per WINDOW_MS; the window then resets.
 * Legitimate pairing (one PC, one prompt) and the trusted silent reconnect are unaffected.
 */
class PairingThrottleTest {

    @Test
    fun allows_five_prompts_then_refuses_until_the_window_resets() {
        var s = PairingThrottle.initial()
        var now = 1_000L
        repeat(PairingThrottle.MAX_PROMPTS) {
            val (ns, ok) = PairingThrottle.allow(s, now)
            s = ns
            assertTrue(ok)
        }
        val (s2, ok6) = PairingThrottle.allow(s, now + 1)
        assertFalse(ok6)
        // a fresh window (60 s after the first prompt) allows again
        val (s3, ok7) = PairingThrottle.allow(s2, 1_000L + PairingThrottle.WINDOW_MS + 1)
        assertTrue(ok7)
        assertEquals(1, s3.count)
    }
}
