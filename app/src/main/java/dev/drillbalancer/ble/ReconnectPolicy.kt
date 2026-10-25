package dev.drillbalancer.ble

import dev.drillbalancer.device.DisconnectReason

/**
 * When to reconnect to the last device after a link ended by itself. Only drops the
 * device recovers from are retried; a few attempts with backoff, then the user decides.
 */
object ReconnectPolicy {
    /** Backoff before attempt n (0-based); the list length is the attempt limit. */
    val DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    /** A rebooting device needs about this long before it advertises again. */
    const val BOOT_MS = 3_000L

    /** Whether a drop for [reason] is worth reconnecting after. */
    fun retries(reason: DisconnectReason): Boolean = delayMs(reason, 0) != null

    /** Delay before reconnect [attempt] (0-based), or null to stay disconnected. */
    fun delayMs(reason: DisconnectReason, attempt: Int): Long? {
        if (attempt !in DELAYS_MS.indices) return null
        val boot = when (reason) {
            DisconnectReason.LINK_LOSS, DisconnectReason.DEVICE_CLOSED, DisconnectReason.OTHER -> 0L
            DisconnectReason.REBOOT, DisconnectReason.FIRMWARE_UPDATED -> if (attempt == 0) BOOT_MS else 0L
            // Asleep, off, Bluetooth off on either side, wrong device, or the bond is gone
            // (factory reset): retrying cannot help.
            DisconnectReason.NOT_SUPPORTED, DisconnectReason.BLUETOOTH_OFF, DisconnectReason.SLEEP,
            DisconnectReason.POWER_OFF, DisconnectReason.FACTORY_RESET, DisconnectReason.BLE_DISABLED,
            -> return null
        }
        return boot + DELAYS_MS[attempt]
    }
}
