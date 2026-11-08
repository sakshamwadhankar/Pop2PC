package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15-1 ITEM 3 (BUGS B-008): the guest's placeholder task (the engine's ProxyActivity on
 * display 0) covers the PopToPC home while a guest runs. Sending that task to the back
 * fixes the phone but stops the guest Activity, and the PC window then loses its frames
 * (measured live 2026-11-08), so the move itself is NOT shipped. The pure invariant kept
 * here is the guard any future fix must respect: only a *guest* package may ever be the
 * target of a move-to-back, never the PopToPC host's own phone UI.
 */
class GuestPresentationHostTest {

    @Test
    fun host_package_is_never_backgrounded() {
        assertTrue(GuestPresentationHost.isHostPackage("dev.unifieddesktop.app"))
        assertFalse("the host must never be mistaken for a guest",
            GuestPresentationHost.isHostPackage("dev.unifieddesktop.app") == false)
    }

    @Test
    fun guest_packages_are_candidates_for_move_to_back() {
        val guests = listOf("com.google.android.apps.tasks",
            "org.mozilla.firefox", "us.zoom.videomeetings", "com.android.vending")
        assertEquals("every real guest must be treated as a guest, not the host",
            guests.size, guests.count { !GuestPresentationHost.isHostPackage(it) })
    }

    @Test
    fun null_or_empty_package_is_not_the_host() {
        assertFalse(GuestPresentationHost.isHostPackage(null))
        assertFalse(GuestPresentationHost.isHostPackage(""))
        assertFalse(GuestPresentationHost.isHostPackage("  "))
    }

    @Test
    fun host_package_constant_is_stable() {
        // The guard's contract is pinned against the real manifest package.
        assertEquals("dev.unifieddesktop.app", GuestPresentationHost.HOST_PACKAGE)
    }
}
