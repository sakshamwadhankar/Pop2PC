package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-103: the clipboard may be READ only while the app is the display-0 focused app
 * (Android 10+ background policy - primaryClip returns null otherwise, verified live
 * 2026-11-06). The focus-gain decision: read once when the window focus is GAINED,
 * never on loss or on a repeated gain without an intervening loss.
 */
class Phase11ClipFocusReadTest {

    @Test
    fun aFocusGainReadsOnce() {
        assertTrue(Phase11Clip.FocusRead.shouldRead(true, false))
    }

    @Test
    fun aFocusLossReadsNothing() {
        assertFalse(Phase11Clip.FocusRead.shouldRead(false, false))
        assertFalse(Phase11Clip.FocusRead.shouldRead(false, true))
    }

    @Test
    fun aRepeatedFocusCallbackWithoutALossDoesNotReadAgain() {
        // The first gain already read; a second onWindowFocusChanged(true) with no loss in
        // between must not re-read (it would fight the echo guard for nothing).
        assertFalse(Phase11Clip.FocusRead.shouldRead(true, true))
    }
}
