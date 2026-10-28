package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-126: a PC that has forgotten this phone asks again with "pair-request". */
class Phase4RePairTest {
    @Test
    fun wantedTrueOnlyForPairedNotPromptingPairRequest() {
        assertTrue(Phase4RePair.wanted(paired = true, alreadyPrompting = false, control = "pair-request"))
    }

    @Test
    fun wantedFalseWhenUnpaired() {
        assertFalse(Phase4RePair.wanted(paired = false, alreadyPrompting = false, control = "pair-request"))
    }

    @Test
    fun wantedFalseWhenAlreadyPrompting() {
        assertFalse(Phase4RePair.wanted(paired = true, alreadyPrompting = true, control = "pair-request"))
    }

    @Test
    fun wantedFalseForOtherControls() {
        assertFalse(Phase4RePair.wanted(paired = true, alreadyPrompting = false, control = "trusted"))
        assertFalse(Phase4RePair.wanted(paired = true, alreadyPrompting = false, control = "pair 123456"))
        assertFalse(Phase4RePair.wanted(paired = true, alreadyPrompting = false, control = ""))
    }
}
