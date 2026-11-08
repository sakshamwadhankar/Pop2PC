package dev.unifieddesktop.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-008 (T15-2): the two engine-level attempts must be pinned. The display-first launch may
 * only be asked for a real imported guest on a real virtual display. The home-to-front
 * reorder was measured to fail on the phone (T15-2B) and is therefore switched OFF - it is
 * kept as a documented, disabled attempt that only the measured policy flag can re-enable.
 */
class B008PolicyTest {

    @Test
    fun display_first_is_asked_for_an_imported_guest_on_a_real_display() {
        assertTrue(B008Policy.displayFirst(displayId = 7, imported = true))
        assertFalse("no virtual display yet", B008Policy.displayFirst(displayId = 0, imported = true))
        assertFalse("display id is never negative here, but stay defensive",
            B008Policy.displayFirst(displayId = -1, imported = true))
        assertFalse("import failed - nothing to launch", B008Policy.displayFirst(displayId = 7, imported = false))
    }

    @Test
    fun home_to_front_is_off_after_the_measured_failure() {
        // T15-2B measured it on the phone: forced onto display 0 the home did come to the
        // front, but the guest stopped and the PC window went black by +8 s. The switch is
        // off so the no-move guard stays; a future mechanism needs new evidence.
        assertFalse("attempt (a) is off - it killed the guest's PC stream",
            B008Policy.homeToFront(directLaunchWorked = false, guestLaunched = true))
        assertFalse("a direct-display launch keeps display 0 clean - reordering would be noise",
            B008Policy.homeToFront(directLaunchWorked = true, guestLaunched = true))
        assertFalse("the guest never started - do not reorder anything",
            B008Policy.homeToFront(directLaunchWorked = false, guestLaunched = false))
    }

    @Test
    fun the_home_task_is_reordered_with_new_task() {
        // NEW_TASK is required to start from a non-activity context; REORDER_TO_FRONT is the
        // attempt-(a) mechanism: the home task moves over the guest task, which itself is
        // never moved (the T15-1 moveTaskToBack guard stays).
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            B008Policy.HOME_TO_FRONT_FLAGS)
        assertEquals(6000L, B008Policy.HOME_TO_FRONT_DELAY_MS)
    }
}
