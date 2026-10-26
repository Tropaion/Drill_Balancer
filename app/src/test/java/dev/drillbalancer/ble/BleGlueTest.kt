package dev.drillbalancer.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.protocol.Uuids
import dev.drillbalancer.ui.firmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.UUID

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

    // Robolectric runs the test on the main thread, where the app's ViewModels call in.
    // Nordic's blocking TimeoutableRequest.await() throws there; the manager's GATT calls
    // must suspend instead (here they fail through the request's callback: no link).
    @Test
    fun gattCallsSuspendOnTheMainThread() = runTest {
        val thread = HandlerThread("test-ble").apply { start() }
        val manager = DrillBalancerManager(app, Handler(thread.looper), object : DrillBalancerManager.Events {
            override fun onAngle(value: ByteArray) = Unit
            override fun onStatus(value: ByteArray) = Unit
            override fun onBattery(value: ByteArray) = Unit
            override fun onOtaCtrl(value: ByteArray) = Unit
            override fun onRecState(value: ByteArray) = Unit
            override fun clearCacheOnClose() = false
            override fun onPairingRequest() = Unit
        })
        try {
            val calls = listOf<suspend () -> Unit>(
                { manager.subscribe() },
                { manager.readStatus() },
                { manager.writeCommand(byteArrayOf(1)) },
                { manager.setOtaIndications(true) },
            )
            for (call in calls) {
                val e = expectError(call)
                assertTrue(e.toString(), e !is IllegalStateException)
            }
        } finally {
            manager.close()
            thread.quitSafely()
        }
    }

    // After a factory reset the phone still holds its bond. Android marks the device
    // BONDING, asks to pair and holds GATT back until the connect times out: a stale bond,
    // not "not in range". Robolectric's GATT never connects, so every connect times out.
    @Test
    fun connectHeldBackForPairingWithABondReportsStaleBond() = runTest {
        grant(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val address = "F4:12:FA:00:1A:2B"
        val device = adapter.getRemoteDevice(address)
        val s = session()
        suspend fun connect(pairingRequest: Boolean, bondedAtStart: Boolean): Throwable? {
            shadowOf(device).setBondState(if (bondedAtStart) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE)
            val gatts = gattsCreated()
            val result = async { runCatching { s.connect(address) }.exceptionOrNull() }
            runCurrent()
            awaitReal { gattsCreated() > gatts }   // Nordic called connectGatt
            if (pairingRequest) {
                shadowOf(device).setBondState(BluetoothDevice.BOND_BONDING)
                app.sendBroadcast(
                    Intent(BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                        .putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PIN),
                )
                shadowOf(Looper.getMainLooper()).idle()
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
            return result.await()
        }

        val stale = connect(pairingRequest = true, bondedAtStart = true)
        assertTrue(stale.toString(), stale is DeviceError.StaleBond)
        assertTrue(s.connection.value.let { it is ConnectionState.Disconnected && it.error is DeviceError.StaleBond && !it.reconnecting })
        // The next connect starts afresh; without a request it is "not in range" again.
        val plain = connect(pairingRequest = false, bondedAtStart = true)
        assertTrue(plain.toString(), plain is DeviceError.Link)
        // A first pairing (no bond on the phone) is no stale bond either.
        val first = connect(pairingRequest = true, bondedAtStart = false)
        assertTrue(first.toString(), first is DeviceError.Link)
    }

    // The framework's BluetoothGatt logs its creation; Robolectric does not track it.
    private fun gattsCreated() = ShadowLog.getLogsForTag("BluetoothGatt").count { it.msg.startsWith("BluetoothGatt()") }

    // Waits for work on a real thread (the BLE handler thread).
    private fun awaitReal(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > until) fail("timed out")
            Thread.sleep(10)
        }
    }

    @Test
    fun deviceInfoFromAnyInstanceOfTheService() {
        val read = BluetoothGattCharacteristic.PROPERTY_READ
        fun service(vararg chars: UUID) = BluetoothGattService(Uuids.SVC_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            chars.forEach { addCharacteristic(BluetoothGattCharacteristic(it, read, BluetoothGattCharacteristic.PERMISSION_READ)) }
        }
        val pnpId = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
        val bluez = service(pnpId)                                   // BlueZ's own, listed first
        val device = service(Uuids.CHR_MANUFACTURER, Uuids.CHR_MODEL)
        val found = listOf(bluez, device).findCharacteristic(Uuids.SVC_DEVICE_INFO, Uuids.CHR_MODEL, read)
        assertTrue(found === device.getCharacteristic(Uuids.CHR_MODEL))
        assertEquals(null, listOf(bluez, device).findCharacteristic(Uuids.SVC_DEVICE_INFO, Uuids.CHR_FIRMWARE_REV, read))
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
        callback.onScanResult(0, result("F4:12:FA:00:1A:2B", named, -75))
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
    fun restartedScanDropsDevicesThatAgedOutMeanwhile() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val scanner = DeviceScanner(app)
        val named = ADV_FLAGS + adName("DrillBalancer-1A2B")
        backgroundScope.launch { scanner.scan().collect {} }
        runCurrent()
        shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
            .onScanResult(0, result("F4:12:FA:00:1A:2B", named, -60))
        // Connecting stops the scan; the device then went to sleep and stopped advertising.
        scanner.stop()
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))

        // Back on the scan screen: heard 3 s ago, still listed at once.
        val seen = mutableListOf<List<ScannedDevice>>()
        backgroundScope.launch { scanner.scan().collect { seen += it } }
        runCurrent()
        assertEquals(listOf("F4:12:FA:00:1A:2B"), seen.single().map { it.address })
        scanner.stop()
        runCurrent()

        // Not heard for 11 s: a new scan starts with an empty list, not the old one.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
        seen.clear()
        backgroundScope.launch { scanner.scan().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(emptyList<ScannedDevice>()), seen)
        scanner.stop()
        runCurrent()
    }

    @Test
    fun scanWithoutPermissionFails() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertTrue(expectError { DeviceScanner(app).scan().first() } is SecurityException)
    }

    // Android 8 to 14 throw from startScan() when the adapter turned off since the scanner
    // was fetched; the screen shows that as a scan error instead of crashing.
    @Test
    @Config(shadows = [AdapterTurnedOffScanner::class])
    fun bluetoothTurnedOffAtStartFailsTheScan() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val e = expectError { DeviceScanner(app).scan().collect {} }
        assertTrue(e.toString(), e is ScanException)
        assertEquals("Bluetooth is turned off.", e.message)
    }

    @Test
    @Config(shadows = [AdapterTurnedOffScanner::class])
    fun failedStartsDoNotCountAgainstTheScanLimit() = runTest {
        // Android never saw them: the next real scan starts at once, not 30 s later.
        grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val scanner = DeviceScanner(app)
        repeat(5) { assertTrue(expectError { scanner.scan().collect {} } is ScanException) }
        AdapterTurnedOffScanner.off = false
        try {
            backgroundScope.launch { scanner.scan().collect {} }
            runCurrent()
            assertEquals(1, shadowOf(adapter.bluetoothLeScanner).scanCallbacks.size)
            scanner.stop()
            runCurrent()
        } finally {
            AdapterTurnedOffScanner.off = true
        }
    }

    @Implements(BluetoothLeScanner::class)
    class AdapterTurnedOffScanner : ShadowBluetoothLeScanner() {
        @Implementation
        public override fun startScan(filters: MutableList<ScanFilter>?, settings: ScanSettings?, callback: ScanCallback?) {
            if (off) throw IllegalStateException("BT Adapter is not turned ON")
            super.startScan(filters, settings, callback)
        }

        companion object {
            @Volatile var off = true   // false: a working scanner
        }
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
