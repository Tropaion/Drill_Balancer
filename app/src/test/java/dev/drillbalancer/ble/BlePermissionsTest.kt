package dev.drillbalancer.ble

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePermissionsTest {
    @Test
    fun permissionsPerApiLevel() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            BlePermissions.required(31),
        )
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(30))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(26))
        assertTrue(BlePermissions.needsLocationServices(30))
        assertFalse(BlePermissions.needsLocationServices(31))
    }

    @Test
    fun readinessOrder() {
        fun r(s: Boolean = true, p: Boolean = true, b: Boolean = true, l: Boolean = true) =
            BlePermissions.readiness(supported = s, permissionsGranted = p, bluetoothOn = b, locationOn = l)
        assertEquals(BleReadiness.READY, r())
        assertEquals(BleReadiness.UNSUPPORTED, r(s = false, p = false))
        // Bluetooth can only be switched on from the app once CONNECT is granted.
        assertEquals(BleReadiness.MISSING_PERMISSIONS, r(p = false, b = false))
        assertEquals(BleReadiness.BLUETOOTH_OFF, r(b = false, l = false))
        assertEquals(BleReadiness.LOCATION_OFF, r(l = false))
    }
}
