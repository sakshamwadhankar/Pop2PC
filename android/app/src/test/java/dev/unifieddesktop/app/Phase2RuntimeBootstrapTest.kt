package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T14-2: the product-path runtime bootstrap ([Phase2Data.ensureWineRuntime]'s pure half).
 * A real user's first Windows-app install starts from empty app data; the boot must stage
 * the Wine/Xvfb runtime instead of failing with "wine patch failed" (B-133's live finding).
 */
class Phase2RuntimeBootstrapTest {

    @Test
    fun required_paths_cover_loader_wineserver_msiexec_and_xvfb() {
        val paths = Phase2Data.runtimeRequiredPaths("wine-11.19-amd64", "lib/wine/x86_64-unix/wine")
        assertEquals(4, paths.size)
        assertTrue(paths[0].endsWith("wine/wine-11.19-amd64/lib/wine/x86_64-unix/wine"))
        assertTrue(paths[1].endsWith("wine/wine-11.19-amd64/bin/wineserver"))
        // The silent-install path runs msiexec (B-081); without it the MSI installer aborts.
        assertTrue(paths[2].endsWith("wine/wine-11.19-amd64/lib/wine/x86_64-windows/msiexec.exe"))
        // Xvfb comes from the runtime-libs lock into the rootfs (Phase2X11.xvfbBin).
        assertTrue(paths[3].endsWith("rootfs/usr/bin/Xvfb"))
    }

    @Test
    fun required_paths_follow_the_active_pin() {
        // A 9.x/10.x pin uses bin/wine64 and its own unpack dir; the bootstrap must never
        // decide completeness from the default pin's layout.
        val paths = Phase2Data.runtimeRequiredPaths("wine-9.0-amd64", "bin/wine64")
        assertTrue(paths[0].endsWith("wine/wine-9.0-amd64/bin/wine64"))
        assertTrue(paths[1].endsWith("wine/wine-9.0-amd64/bin/wineserver"))
    }

    @Test
    fun all_present_means_nothing_missing_and_no_fetch() {
        val paths = Phase2Data.runtimeRequiredPaths("wine-11.19-amd64", "lib/wine/x86_64-unix/wine")
        assertEquals(emptyList<Int>(), Phase2Data.runtimeMissing(paths.map { true }))
    }

    @Test
    fun each_missing_file_is_reported_by_index() {
        // Only the wineserver and Xvfb present: loader (0) and msiexec (2) must be named.
        assertEquals(listOf(0, 2), Phase2Data.runtimeMissing(listOf(false, true, false, true)))
        assertEquals(listOf(3), Phase2Data.runtimeMissing(listOf(true, true, true, false)))
        assertEquals(listOf(0, 1, 2, 3), Phase2Data.runtimeMissing(listOf(false, false, false, false)))
    }

    @Test
    fun default_pin_paths_are_the_bootstrapped_ones() {
        // The bootstrap runs with winePin(ctx); the default pin's layout must satisfy
        // runtimeRequiredPaths without hand-tuning (pickPin is unit-tested separately).
        val pin = Phase2Data.pickPin(null)
        val paths = Phase2Data.runtimeRequiredPaths(pin.dir, pin.loaderRel)
        assertTrue(paths[0].contains(pin.dir))
        assertTrue(paths[0].endsWith(pin.loaderRel))
    }
}
