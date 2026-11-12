package dev.unifieddesktop.app

/**
 * T15-4 item 4 (audit row 8 / B-141b, manager design): what the link service does when
 * Android 15 stops its `dataSync` foreground service after the 6 h/24 h budget and calls
 * `Service.onTimeout()`. The service has a few seconds; the decision is which type to
 * re-promote with, if any:
 *
 *  - the primary `connectedDevice` type has no time budget: if it worked on this phone,
 *    it is the first re-promote (it only falls back to dataSync on phones that refuse it);
 *  - otherwise, while desktop audio is being captured, the `mediaProjection` type is
 *    already justified and has no 6 h budget - keep the session on it;
 *  - otherwise there is no budget-free type available, so the honest behaviour is the
 *    "Session paused by Android" notification (a tap on it reopens the app and restarts
 *    the link; the phone desktop runtime survives via Phase10Runtime) instead of a silent
 *    death. `shortService` (~3 min) cannot carry a desktop session, so it is not used.
 *
 * Pure (type ids passed in as ints) so the ladder is unit-tested without a device.
 */
object FgsTimeout {
    /** @return the foreground type to re-promote with, or null = notify and end. */
    fun recoveryType(
        connectedDeviceWorkedBefore: Boolean,
        captureActive: Boolean,
        connectedDevice: Int,
        mediaProjection: Int,
    ): Int? = when {
        connectedDeviceWorkedBefore -> connectedDevice
        captureActive -> mediaProjection
        else -> null
    }
}
