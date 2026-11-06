package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-039/B-128 key routing: printable characters take the input-connection route (the IME
 * route an EditContext accepts), modifiers/Enter/arrows stay real key events, and the UP of
 * a committed key is swallowed. B-128's fix adds the click rebind: a pointer RELEASE in the
 * WebView rebinds the IME to the editable that is focused after the click.
 */
class Phase6KeyRouteTest {

    private fun decide(action: Int, keyCode: Int, meta: Int = 0, lastCommitted: Int = -1) =
        KeyRoute.decide(action, keyCode, meta, lastCommitted, Phase5Protocol.keyCodeToChar(keyCode, meta))

    @Test
    fun aPrintableKeyDownGoesThroughTheInputConnection() {
        // B-039: this is the only route Monaco's EditContext inserts text from.
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 44)) // p
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 33)) // e
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 7))  // 1
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 62)) // space
    }

    @Test
    fun aShiftedCharacterStillCommits() {
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 44, android.view.KeyEvent.META_SHIFT_ON))
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 7, android.view.KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun aCtrlChordStaysARealKeyEvent() {
        // Ctrl+S, Ctrl+P and friends must reach the page as raw keys (B-039 rule).
        val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 47, meta)) // s
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 44, android.view.KeyEvent.META_CTRL_ON))
    }

    @Test
    fun anAltChordStaysARealKeyEvent() {
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 34, android.view.KeyEvent.META_ALT_ON)) // d
    }

    @Test
    fun nonPrintableKeysStayRealKeyEvents() {
        // Enter, Backspace, Tab, arrows, F4: no character, no commit.
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 66)) // Enter
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 67)) // Backspace
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 61)) // Tab
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 20)) // DPAD down
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_DOWN, 131)) // F1
    }

    @Test
    fun theUpOfACommittedKeyIsSwallowed() {
        // The commit already inserted the character; a bare ACTION_UP would do nothing, and
        // forwarding it would risk Chromium interpreting the pair twice.
        assertEquals(KeyRoute.Route.SWALLOW, decide(android.view.KeyEvent.ACTION_UP, 44, lastCommitted = 44))
    }

    @Test
    fun theUpOfAnyOtherKeyIsNotSwallowed() {
        // Ctrl released after a chord, or a key that never committed: the UP goes through.
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_UP, 59)) // left shift
        assertEquals(KeyRoute.Route.RAW, decide(android.view.KeyEvent.ACTION_UP, 44, lastCommitted = 33))
    }

    @Test
    fun b128ClickRebindFiresOnlyOnThePointerRelease() {
        // A release may have moved the focused editable; a press or a move must not rebind.
        assertTrue(ImeRebind.afterClick(android.view.MotionEvent.ACTION_UP))
        assertFalse(ImeRebind.afterClick(android.view.MotionEvent.ACTION_DOWN))
        assertFalse(ImeRebind.afterClick(android.view.MotionEvent.ACTION_MOVE))
        assertFalse(ImeRebind.afterClick(android.view.MotionEvent.ACTION_CANCEL))
    }

    @Test
    fun b128RegressionAfterARebindTypingStillTakesTheCommitRoute() {
        // The B-128 live failure: after clicking the editor, typed text must still route
        // through the (rebound) input connection - never raw keys, never dropped.
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 43)) // p
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 45)) // r
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 46)) // o
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 30)) // b
        assertEquals(KeyRoute.Route.COMMIT, decide(android.view.KeyEvent.ACTION_DOWN, 33)) // e
        assertEquals(KeyRoute.Route.SWALLOW, decide(android.view.KeyEvent.ACTION_UP, 33, lastCommitted = 33))
    }
}
