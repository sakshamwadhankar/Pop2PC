package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-141: the first boot of a fresh Wine prefix takes ~5 min on the phone (measured
 * 2026-11-08) while the old installer wait was a fixed 150 s, so the first install aborted
 * and only a manual retry worked. These tests pin the first-run-aware wait and the single
 * retry rule.
 */
class InstallerWaitTest {

    @Test
    fun a_never_booted_prefix_waits_longer_than_the_measured_first_boot() {
        assertEquals(420_000L, InstallerWait.bootWaitMs(coldPrefix = true))
        assertTrue("must exceed the measured ~5 min first boot",
            InstallerWait.bootWaitMs(coldPrefix = true) > 5 * 60_000L)
    }

    @Test
    fun a_warm_prefix_keeps_the_shipped_wait() {
        assertEquals(150_000L, InstallerWait.bootWaitMs(coldPrefix = false))
        assertTrue("a warm boot measured ~35 s", InstallerWait.bootWaitMs(coldPrefix = false) > 35_000L)
    }

    @Test
    fun one_retry_only_while_the_boot_is_still_alive_without_a_marker() {
        assertTrue(InstallerWait.retryAllowed(attempt = 0, processAlive = true, markerSeen = false))
        assertFalse("the boot died: no reason to wait again",
            InstallerWait.retryAllowed(attempt = 0, processAlive = false, markerSeen = false))
        assertFalse("the marker is there: the boot already succeeded",
            InstallerWait.retryAllowed(attempt = 0, processAlive = true, markerSeen = true))
        assertFalse("only one extra round",
            InstallerWait.retryAllowed(attempt = 1, processAlive = true, markerSeen = false))
    }
}
