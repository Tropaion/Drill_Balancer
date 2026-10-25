package dev.drillbalancer.ble

import dev.drillbalancer.protocol.Uuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScanModelTest {
    @Test
    fun matchesByNameOrScanResponseUuid() {
        assertTrue(isDrillBalancer("DrillBalancer-1A2B", null))
        // Scan response only (no name): the UUID alone is enough.
        assertTrue(isDrillBalancer(null, listOf(Uuids.SVC_MAIN)))
        assertTrue(isDrillBalancer("Other", listOf(Uuids.sig(0x180F), Uuids.SVC_MAIN)))
        assertFalse(isDrillBalancer("Drill", listOf(Uuids.sig(0x180F))))
        assertFalse(isDrillBalancer(null, null))
        assertFalse(isDrillBalancer("drillbalancer-1A2B", emptyList()))
        assertFalse(isDrillBalancer(null, listOf(UUID.fromString("d71b0002-8a3e-4f6c-b5d2-1c9e0a7f4b63"))))
    }

    @Test
    fun resultsKeepNameAndOrder() {
        val r = ScanResults(staleMs = 10_000)
        assertTrue(r.update(ScannedDevice("AA", "DrillBalancer-00AA", -60, false, 0)))
        assertTrue(r.update(ScannedDevice("BB", null, -70, true, 0)))
        // A report without the advertising packet keeps the name seen before.
        assertTrue(r.update(ScannedDevice("AA", null, -55, false, 100)))
        assertEquals(listOf("AA", "BB"), r.devices.map { it.address })
        assertEquals("DrillBalancer-00AA", r.devices[0].name)
        assertEquals(-55, r.devices[0].rssi)
        // Nothing new: no update for the UI.
        assertFalse(r.update(ScannedDevice("AA", null, -55, false, 200)))
        assertTrue(r.update(ScannedDevice("BB", "DrillBalancer-00BB", -70, true, 200)))
        assertEquals("DrillBalancer-00BB", r.devices[1].name)
    }

    @Test
    fun staleDevicesDropOut() {
        val r = ScanResults(staleMs = 10_000)
        r.update(ScannedDevice("AA", "DrillBalancer-00AA", -60, false, 0))
        r.update(ScannedDevice("BB", "DrillBalancer-00BB", -60, false, 5_000))
        assertFalse(r.prune(10_000))
        assertTrue(r.prune(10_001))
        assertEquals(listOf("BB"), r.devices.map { it.address })
    }

    @Test
    fun throttleKeepsFiveStartsPerThirtySeconds() {
        val t = ScanThrottle()
        for (i in 0 until 5) {
            assertEquals(0, t.waitMs(i * 1_000L))
            t.recordStart(i * 1_000L)
        }
        // Sixth start at 5 s: wait until the first start leaves the window at 30 s.
        assertEquals(25_000, t.waitMs(5_000))
        assertEquals(1, t.waitMs(29_999))
        assertEquals(0, t.waitMs(30_000))
        t.recordStart(30_000)
        assertEquals(1_000, t.waitMs(30_000))   // the start at 1 s is still in the window
    }

    @Test
    fun scanErrorsHaveMessages() {
        assertTrue(scanErrorMessage(SCAN_FAILED_APPLICATION_REGISTRATION_FAILED).contains("Turn Bluetooth off and on"))
        assertTrue(scanErrorMessage(SCAN_FAILED_SCANNING_TOO_FREQUENTLY).contains("30 seconds"))
        assertEquals("Scanning failed (error 42).", scanErrorMessage(42))
    }
}
