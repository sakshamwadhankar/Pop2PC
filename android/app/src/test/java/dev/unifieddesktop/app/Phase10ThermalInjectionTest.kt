package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B-127 decision (manager 2026-11-07): the D22 debug thermal injection. The harness
 * command `p10:thermal N` reaches the governor's decision input on DEBUG builds only;
 * release builds ignore the action entirely and the shipped governor keeps reading the
 * real PowerManager signal.
 */
class Phase10ThermalInjectionTest {

    @Test
    fun aDebugBuildAcceptsTheInjection() {
        assertEquals(3, ThermalInjection.parse("thermal 3", true))
        assertEquals(2, ThermalInjection.parse("thermal 2", true))
    }

    @Test
    fun zeroClearsTheOverride() {
        assertEquals(0, ThermalInjection.parse("thermal 0", true))
    }

    @Test
    fun aReleaseBuildIgnoresTheActionEntirely() {
        assertNull(ThermalInjection.parse("thermal 3", false))
        assertNull(ThermalInjection.parse("thermal 0", false))
    }

    @Test
    fun malformedOrOutOfRangeArgumentsAreIgnored() {
        assertNull(ThermalInjection.parse("thermal off", true))
        assertNull(ThermalInjection.parse("thermal 9", true))
        assertNull(ThermalInjection.parse("thermal -1", true))
        assertNull(ThermalInjection.parse("", true))
    }

    @Test
    fun theOverrideReachesTheGovernorDecisionInput() {
        // Real signal 0 with the injection set: the governor samples 3 (the ladder fires).
        assertEquals(3, ThermalInjection.effectiveThermal(0, 3))
        // No override: the shipped path is untouched.
        assertEquals(2, ThermalInjection.effectiveThermal(2, null))
        assertEquals(0, ThermalInjection.effectiveThermal(0, null))
        // The override also DROPS a real hot reading back to cool (session-local control).
        assertEquals(0, ThermalInjection.effectiveThermal(3, 0))
    }
}
