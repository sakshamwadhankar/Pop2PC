package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-125: the pairing notification decision when the screen/keyguard hides the app. */
class Phase4PairingPromptTest {
    @Test
    fun foregroundScreenOnUnlockedIsNotWanted() {
        assertFalse(Phase4PairingPrompt.wanted(appForeground = true, interactive = true, keyguardLocked = false))
    }

    @Test
    fun foregroundScreenOffIsWanted() {
        assertTrue(Phase4PairingPrompt.wanted(appForeground = true, interactive = false, keyguardLocked = false))
    }

    @Test
    fun foregroundKeyguardLockedIsWanted() {
        assertTrue(Phase4PairingPrompt.wanted(appForeground = true, interactive = true, keyguardLocked = true))
    }

    @Test
    fun backgroundScreenOnUnlockedIsWanted() {
        assertTrue(Phase4PairingPrompt.wanted(appForeground = false, interactive = true, keyguardLocked = false))
    }
}
