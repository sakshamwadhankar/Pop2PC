package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T15-4 item 4: the onTimeout recovery-type ladder (audit row 8 / B-141b). The ints stand
 * in for the real ServiceInfo constants so the policy stays JVM-pure.
 */
class FgsTimeoutTest {
    private val connectedDevice = 1
    private val dataSync = 2
    private val mediaProjection = 3

    @Test
    fun primary_type_wins_when_it_ever_worked() {
        assertEquals(
            connectedDevice,
            FgsTimeout.recoveryType(true, false, connectedDevice, mediaProjection),
        )
        assertEquals(
            connectedDevice,
            FgsTimeout.recoveryType(true, true, connectedDevice, mediaProjection),
        )
    }

    @Test
    fun fallback_phone_keeps_an_active_capture_on_media_projection() {
        assertEquals(
            mediaProjection,
            FgsTimeout.recoveryType(false, true, connectedDevice, mediaProjection),
        )
    }

    @Test
    fun no_budget_free_type_means_notify_only() {
        assertNull(FgsTimeout.recoveryType(false, false, connectedDevice, mediaProjection))
    }

    @Test
    fun dataSync_is_never_a_recovery_answer() {
        // exhaustively: dataSync (2) must never come back out of the ladder
        for (worked in listOf(true, false)) {
            for (capture in listOf(true, false)) {
                val t = FgsTimeout.recoveryType(worked, capture, connectedDevice, mediaProjection)
                if (t != null) org.junit.Assert.assertNotEquals(dataSync, t)
            }
        }
    }
}
