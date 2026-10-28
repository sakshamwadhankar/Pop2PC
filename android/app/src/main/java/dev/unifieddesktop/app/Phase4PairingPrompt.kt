package dev.unifieddesktop.app

/**
 * B-125: when is the pairing notification worth showing?
 *
 * The home screen already shows the code and Yes/No while the user can actually see it.
 * It cannot be seen when the screen is off (not interactive) or the keyguard covers it,
 * so in those states the notification is the only prompt the user gets.
 */
object Phase4PairingPrompt {
    fun wanted(appForeground: Boolean, interactive: Boolean, keyguardLocked: Boolean): Boolean =
        !appForeground || !interactive || keyguardLocked
}
