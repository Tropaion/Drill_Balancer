package dev.drillbalancer.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Finds DrillBalancers nearby. Collect [scan] while the scan screen is visible: it scans
 * in low-latency mode and stops when the collection ends, [stop] is called, or a
 * connection starts (BleDeviceSession calls [stop]).
 */
class DeviceScanner(context: Context) {
    private val context = context.applicationContext
    private val lock = Any()
    private val throttle = ScanThrottle()           // under lock
    private var stopActive: (() -> Unit)? = null   // under lock

    // Kept across scans: a scan restarted after a connection carries on with what was
    // heard before, minus what aged out meanwhile.
    private val results = ScanResults()

    /**
     * The devices found so far, newest state of each, in discovery order; devices not
     * heard for 10 s drop out. Fails with [ScanException] if the phone cannot scan, or
     * SecurityException if a permission is missing (check [BlePermissions.readiness]).
     * One scan at a time: a new collection stops the previous one. The first list comes at
     * once: the devices earlier scans heard in the last 10 s, possibly none.
     */
    fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        stop()
        var retried = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName
                if (!isDrillBalancer(name, record?.serviceUuids?.map { it.uuid })) return
                val seen = ScannedDevice(result.device.address, name, result.rssi, isBonded(result.device), now())
                synchronized(results) { if (results.update(seen)) trySend(results.devices) }
            }

            override fun onBatchScanResults(batch: MutableList<ScanResult>) = batch.forEach { onScanResult(0, it) }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                // Registration failures are usually a stack hiccup: one more try.
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED && !retried) {
                    retried = true
                    val self = this
                    launch {
                        delay(RETRY_MS)
                        start(self)
                    }
                    return
                }
                close(ScanException(scanErrorMessage(errorCode)))
            }
        }

        val stopThis = { channel.close(); Unit }
        synchronized(lock) { stopActive = stopThis }
        // Before start(), which may wait for Android's scan limit.
        synchronized(results) {
            results.prune(now())
            trySend(results.devices)
        }
        start(callback)
        launch {
            while (true) {
                delay(PRUNE_MS)
                synchronized(results) { if (results.prune(now())) trySend(results.devices) }
            }
        }
        awaitClose {
            synchronized(lock) { if (stopActive === stopThis) stopActive = null }
            stopHardware(callback)
        }
    }.conflate()

    /** Ends the running scan, if any. */
    fun stop() {
        val stopThat = synchronized(lock) { stopActive.also { stopActive = null } }
        stopThat?.invoke()
    }

    private suspend fun start(callback: ScanCallback) {
        val wait = synchronized(lock) { throttle.waitMs(now()) }
        if (wait > 0) {
            Log.i(TAG, "scan start delayed $wait ms (Android scan limit)")
            delay(wait)
        }
        if (!hasScanPermission()) throw SecurityException("Bluetooth scan permission missing")
        // Null while Bluetooth is off.
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
            ?: throw ScanException("Bluetooth is turned off.")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        synchronized(lock) { throttle.recordStart(now()) }
        // No ScanFilter: see isDrillBalancer(). Fine while the screen is on, which a
        // visible scan screen implies.
        scanner.startScan(null, settings, callback)
    }

    private fun stopHardware(callback: ScanCallback) {
        try {
            BlePermissions.adapter(context)?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan: ${e.message}")   // revoked: the system stopped the scan
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan: ${e.message}")   // Bluetooth turned off meanwhile
        }
    }

    private fun isBonded(device: BluetoothDevice): Boolean =
        BlePermissions.canConnect(context) &&
            try {
                device.bondState == BluetoothDevice.BOND_BONDED
            } catch (_: SecurityException) {
                false
            }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlePermissions.isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            BlePermissions.isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "DeviceScanner"
        const val PRUNE_MS = 1_000L
        const val RETRY_MS = 1_500L
    }
}
