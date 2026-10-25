package dev.drillbalancer.ble

import dev.drillbalancer.device.DisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun recoverableDropsBackOffThenGiveUp() {
        val delays = (0..5).map { ReconnectPolicy.delayMs(DisconnectReason.LINK_LOSS, it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, null), delays)
        assertEquals(1_000L, ReconnectPolicy.delayMs(DisconnectReason.DEVICE_CLOSED, 0))
        assertEquals(1_000L, ReconnectPolicy.delayMs(DisconnectReason.OTHER, 0))
    }

    @Test
    fun rebootWaitsForBoot() {
        for (r in listOf(DisconnectReason.REBOOT, DisconnectReason.FIRMWARE_UPDATED)) {
            assertEquals(4_000L, ReconnectPolicy.delayMs(r, 0))
            assertEquals(2_000L, ReconnectPolicy.delayMs(r, 1))
            assertTrue(ReconnectPolicy.retries(r))
        }
    }

    @Test
    fun hopelessDropsStay() {
        for (r in listOf(
            DisconnectReason.SLEEP, DisconnectReason.POWER_OFF, DisconnectReason.FACTORY_RESET,
            DisconnectReason.BLE_DISABLED, DisconnectReason.BLUETOOTH_OFF, DisconnectReason.NOT_SUPPORTED,
        )) {
            assertNull(r.name, ReconnectPolicy.delayMs(r, 0))
            assertFalse(ReconnectPolicy.retries(r))
        }
        assertNull(ReconnectPolicy.delayMs(DisconnectReason.LINK_LOSS, -1))
    }
}
