package dev.drillbalancer.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import androidx.test.core.app.ApplicationProvider
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.ui.firmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The parts of the BLE glue that run without a radio: the checks before connecting and
 * the scanner's handling of real advertising bytes. GATT itself needs a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BleGlueTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val sessions = mutableListOf<BleDeviceSession>()

    private fun session() = BleDeviceSession(app).also { sessions += it }

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    @After
    fun tearDown() = sessions.forEach { it.close() }

    private suspend fun expectError(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("expected an exception")
        throw AssertionError()
    }

    @Test
    fun connectNeedsPermissionAndBluetooth() = runTest {
        val s = session()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(expectError { s.connect("F4:12:FA:00:1A:2B") } is DeviceError.MissingPermission)

        grant(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(false)
        val off = expectError { s.connect("F4:12:FA:00:1A:2B") } as DeviceError.Link
        assertEquals(DisconnectReason.BLUETOOTH_OFF, off.reason)

        shadowOf(adapter).setEnabled(true)
        assertTrue(expectError { s.connect("not-an-address") } is DeviceError.InvalidData)
        assertEquals(ConnectionState.Disconnected(), s.connection.value)
    }

    @Test
    fun operationsNeedALink() = runTest {
        val s = session()
        assertTrue(expectError { s.readSettings() } is DeviceError.NotConnected)
        assertTrue(expectError { s.writeSettings(emptyUpdate()) } is DeviceError.NotConnected)
        assertTrue(expectError { s.sendCommand(Command.Zero) } is DeviceError.NotConnected)
        assertTrue(expectError { s.ensureBonded() } is DeviceError.NotConnected)
        assertTrue(expectError { s.startOta(firmware()).first() } is DeviceError.NotConnected)
        s.disconnect()
        assertEquals(ConnectionState.Disconnected(), s.connection.value)
    }

    @Test
    fun scanFindsNameInAdvertisingAndUuidInScanResponse() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val scanner = DeviceScanner(app)
        val seen = mutableListOf<List<ScannedDevice>>()
        val job = backgroundScope.launch { scanner.scan().collect { seen += it } }
        runCurrent()
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()

        val named = ADV_FLAGS + adName("DrillBalancer-1A2B")
        val uuidOnly = ADV_FLAGS + adUuid128(MAIN_UUID_LE)
        val other = ADV_FLAGS + adName("Headphones")
        callback.onScanResult(0, result("F4:12:FA:00:1A:2B", named, -60))
        callback.onScanResult(0, result("F4:12:FA:00:3C:4D", uuidOnly, -70))
        callback.onScanResult(0, result("00:11:22:33:44:55", other, -40))
        // The merged advertisement + scan response of the first device: name kept, rssi new.
        callback.onScanResult(0, result("F4:12:FA:00:1A:2B", named + adUuid128(MAIN_UUID_LE), -50))
        runCurrent()

        val list = seen.last()
        assertEquals(listOf("F4:12:FA:00:1A:2B", "F4:12:FA:00:3C:4D"), list.map { it.address })
        assertEquals("DrillBalancer-1A2B", list[0].name)
        assertEquals(-50, list[0].rssi)
        assertEquals(null, list[1].name)

        // Connecting stops the scan; so does stop().
        scanner.stop()
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
    }

    @Test
    fun scanWithoutPermissionFails() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertTrue(expectError { DeviceScanner(app).scan().first() } is SecurityException)
    }

    private fun emptyUpdate() = SettingsUpdate.diff(DeviceSettings.DEFAULTS, DeviceSettings.DEFAULTS)

    private fun result(address: String, record: ByteArray, rssi: Int): ScanResult {
        // ScanRecord.parseFromBytes is hidden; it is what the stack uses on these bytes.
        val parse = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        val scanRecord = parse.invoke(null, record) as ScanRecord
        @Suppress("DEPRECATION")
        return ScanResult(adapter.getRemoteDevice(address), scanRecord, rssi, 0L)
    }

    private companion object {
        val ADV_FLAGS = byteArrayOf(0x02, 0x01, 0x06)

        // d71b0001-8a3e-4f6c-b5d2-1c9e0a7f4b63, little-endian as on air (DB_UUID128_INIT).
        val MAIN_UUID_LE = intArrayOf(
            0x63, 0x4b, 0x7f, 0x0a, 0x9e, 0x1c, 0xd2, 0xb5, 0x6c, 0x4f, 0x3e, 0x8a, 0x01, 0x00, 0x1b, 0xd7,
        ).map { it.toByte() }.toByteArray()

        fun adName(name: String): ByteArray {
            val b = name.encodeToByteArray()
            return byteArrayOf((b.size + 1).toByte(), 0x09) + b
        }

        fun adUuid128(le: ByteArray) = byteArrayOf(17, 0x07) + le
    }
}
