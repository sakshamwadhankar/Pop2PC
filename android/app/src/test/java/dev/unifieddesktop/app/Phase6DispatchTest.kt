package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-113: the typing-chip arm and the page-keyboard probe are two different commands. */
class Phase6DispatchTest {

    @Test
    fun probeCommandsAreDistinctAndArmParsingIsTruthful() {
        assertNotEquals(ProbeCommands.CHIP, ProbeCommands.PAGE)
        assertEquals("probe-chip", ProbeCommands.CHIP)
        assertEquals("probe", ProbeCommands.PAGE)
        assertTrue(ProbeCommands.chipOn(""))
        assertTrue(ProbeCommands.chipOn("on"))
        assertFalse(ProbeCommands.chipOn("off"))
        assertFalse(ProbeCommands.chipOn(" OFF "))
    }
}
