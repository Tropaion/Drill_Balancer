package dev.drillbalancer.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import android.util.Log
import dev.drillbalancer.device.DeviceInfo
import dev.drillbalancer.protocol.Uuids
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.PhyRequest
import no.nordicsemi.android.ble.Request
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The GATT side of the device on Nordic's BleManager: service discovery and validation,
 * link setup, and suspend wrappers around its request queue. The queue runs one GATT
 * operation at a time and waits for each callback, which is also the flow control for the
 * writes without response during OTA. Thin on purpose: the policy lives in
 * BleDeviceSession, the mappings in GattErrors.
 *
 * Every suspend function throws Nordic's exceptions (RequestFailedException with the GATT
 * status, DeviceDisconnectedException, BluetoothDisabledException, InvalidRequestException);
 * the session maps them.
 */
internal class DrillBalancerManager(
    context: Context,
    private val callbackHandler: Handler,
    private val events: Events,
) : BleManager(context, callbackHandler) {

    /** Called on the manager's handler thread, except [onPairingRequest]. */
    interface Events {
        fun onAngle(value: ByteArray)
        fun onStatus(value: ByteArray)
        fun onBattery(value: ByteArray)
        fun onOtaCtrl(value: ByteArray)

        /**
         * Android's pairing request for the device being connected (its system dialog is up).
         * Called on the main thread: Nordic's broadcast receiver has no handler.
         */
        fun onPairingRequest()
    }

    private var angle: BluetoothGattCharacteristic? = null
    private var config: BluetoothGattCharacteristic? = null
    private var command: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null
    private var battery: BluetoothGattCharacteristic? = null
    private var manufacturer: BluetoothGattCharacteristic? = null
    private var model: BluetoothGattCharacteristic? = null
    private var firmwareRev: BluetoothGattCharacteristic? = null
    private var otaCtrl: BluetoothGattCharacteristic? = null
    private var otaData: BluetoothGattCharacteristic? = null

    /** Set when the next disconnect follows a firmware update: the GATT table may change. */
    @Volatile var clearCacheOnDisconnect = false

    val currentMtu: Int get() = mtu
    val hasOta: Boolean get() = otaCtrl != null && otaData != null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun onPairingRequestReceived(device: BluetoothDevice, variant: Int, key: Int) {
        events.onPairingRequest()
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val main = gatt.getService(Uuids.SVC_MAIN) ?: return false
        angle = main.find(Uuids.CHR_ANGLE, PROP_NOTIFY)
        config = main.find(Uuids.CHR_CONFIG, PROP_READ or PROP_WRITE)
        command = main.find(Uuids.CHR_COMMAND, PROP_WRITE)
        status = main.find(Uuids.CHR_STATUS, PROP_READ or PROP_NOTIFY)
        return angle != null && config != null && command != null && status != null
    }

    override fun isOptionalServiceSupported(gatt: BluetoothGatt): Boolean {
        gatt.getService(Uuids.SVC_OTA)?.let {
            otaCtrl = it.find(Uuids.CHR_OTA_CTRL, PROP_WRITE or PROP_INDICATE)
            otaData = it.find(Uuids.CHR_OTA_DATA, PROP_WRITE_NO_RESPONSE)
        }
        // Any Device Information service: BlueZ (the simulator's host) adds its own, with
        // only the PnP ID, ahead of the device's.
        manufacturer = gatt.services.findCharacteristic(Uuids.SVC_DEVICE_INFO, Uuids.CHR_MANUFACTURER, PROP_READ)
        model = gatt.services.findCharacteristic(Uuids.SVC_DEVICE_INFO, Uuids.CHR_MODEL, PROP_READ)
        firmwareRev = gatt.services.findCharacteristic(Uuids.SVC_DEVICE_INFO, Uuids.CHR_FIRMWARE_REV, PROP_READ)
        battery = gatt.getService(Uuids.SVC_BATTERY)?.find(Uuids.CHR_BATTERY_LEVEL, PROP_READ or PROP_NOTIFY)
        return true
    }

    // Queued before the connect request completes. Failures here are not fatal: the
    // link then runs at the default MTU, 1M PHY or the central's interval.
    override fun initialize() {
        setNotificationCallback(angle).with { _, data -> data.value?.let(events::onAngle) }
        setNotificationCallback(status).with { _, data -> data.value?.let(events::onStatus) }
        setNotificationCallback(battery).with { _, data -> data.value?.let(events::onBattery) }
        setIndicationCallback(otaCtrl).with { _, data -> data.value?.let(events::onOtaCtrl) }
        // 247 = 251-byte LL packets (the device sets that data length) minus L2CAP header.
        requestMtu(MTU).enqueue()
        setPreferredPhy(PhyRequest.PHY_LE_2M_MASK, PhyRequest.PHY_LE_2M_MASK, PhyRequest.PHY_OPTION_NO_PREFERRED)
            .enqueue()
        requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED).enqueue()
    }

    override fun onServicesInvalidated() {
        angle = null
        config = null
        command = null
        status = null
        battery = null
        manufacturer = null
        model = null
        firmwareRev = null
        otaCtrl = null
        otaData = null
    }

    override fun shouldClearCacheWhenDisconnected(): Boolean = clearCacheOnDisconnect.also {
        clearCacheOnDisconnect = false
    }

    // ---- operations ----

    suspend fun subscribe() {
        enableNotifications(angle).timeout(TIMEOUT_MS).awaitOnHandler()
        enableNotifications(status).timeout(TIMEOUT_MS).awaitOnHandler()
        if (battery != null) enableNotifications(battery).timeout(TIMEOUT_MS).awaitOnHandler()
    }

    suspend fun readStatus(): ByteArray = read(status)

    suspend fun readBattery(): ByteArray? = battery?.let { read(it) }

    /** Null if the service is missing. */
    suspend fun readDeviceInfo(): DeviceInfo? {
        val m = manufacturer ?: return null
        val mo = model ?: return null
        val fw = firmwareRev ?: return null
        return DeviceInfo(read(m).decodeToString(), read(mo).decodeToString(), read(fw).decodeToString())
    }

    /**
     * The settings JSON can be longer than ATT_MTU - 1. A plain read is enough: Android's
     * GATT client continues a read whose response fills the MTU with Read Blob requests
     * and reports the whole value (the device captures the JSON at offset 0, so the parts
     * are consistent). The caller still parses it, which catches a truncated value.
     */
    suspend fun readConfig(): ByteArray = read(config)

    /**
     * Write with response and no splitter: for a value longer than ATT_MTU - 3, Android
     * sends it as one long write (Prepare Write requests, then Execute), which the device
     * applies once. split() would send separate writes, each parsed as its own JSON.
     */
    suspend fun writeConfig(json: ByteArray) = write(config, json, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    suspend fun writeCommand(value: ByteArray) = write(command, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    suspend fun writeOtaCtrl(value: ByteArray) = write(otaCtrl, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    /** Completes once Android took the packet (onCharacteristicWrite): no flooding. */
    suspend fun writeOtaData(value: ByteArray) = write(otaData, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    suspend fun setOtaIndications(on: Boolean) {
        val request = if (on) enableIndications(otaCtrl) else disableIndications(otaCtrl)
        request.timeout(TIMEOUT_MS).awaitOnHandler()
    }

    suspend fun setHighPriority(high: Boolean) {
        val priority = if (high) {
            ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH
        } else {
            ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED
        }
        requestConnectionPriority(priority).suspend()
    }

    /** Starts pairing if the phone holds no bond; Android shows its passkey dialog. */
    suspend fun pair() = createBondInsecure().suspend()

    private suspend fun read(c: BluetoothGattCharacteristic?): ByteArray {
        var data: Data? = null
        readCharacteristic(c).timeout(TIMEOUT_MS).with { _, d -> data = d }.awaitOnHandler()
        return data?.value ?: ByteArray(0)
    }

    private suspend fun write(c: BluetoothGattCharacteristic?, value: ByteArray, type: Int) {
        writeCharacteristic(c, value, type).timeout(TIMEOUT_MS).awaitOnHandler()
    }

    /**
     * ble-ktx's suspend() with this manager's Handler for the callbacks. ble-ktx sets none,
     * and Nordic then runs a request's timeout on a new java.util.Timer, a thread that
     * lives until the timeout would have fired: one per OTA chunk, thousands at a time.
     * With a Handler the timeout is a message that is removed when the request completes.
     * Not cancellable, like ble-ktx's: a GATT operation in flight cannot be withdrawn.
     * Not named await(): TimeoutableRequest has a blocking member await() that would win
     * over this extension (and throws on the main thread).
     */
    private suspend fun Request.awaitOnHandler(): Unit = suspendCoroutine { continuation ->
        setHandler(callbackHandler)
            .invalid { continuation.resumeWithException(InvalidRequestException(this)) }
            .fail { _, status ->
                continuation.resumeWithException(
                    when (status) {
                        FailCallback.REASON_BLUETOOTH_DISABLED -> BluetoothDisabledException()
                        FailCallback.REASON_DEVICE_DISCONNECTED -> DeviceDisconnectedException()
                        else -> RequestFailedException(this, status)
                    },
                )
            }
            .done { continuation.resume(Unit) }
            .enqueue()
    }

    private companion object {
        const val TAG = "DrillBalancerBle"
        const val MTU = 247
        const val TIMEOUT_MS = 10_000L
        const val PROP_READ = BluetoothGattCharacteristic.PROPERTY_READ
        const val PROP_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        const val PROP_WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        const val PROP_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val PROP_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}

private fun BluetoothGattService.find(uuid: UUID, props: Int): BluetoothGattCharacteristic? =
    getCharacteristic(uuid)?.takeIf { it.properties and props == props }

/** The characteristic with [props] in the first instance of [service] that has it. */
internal fun List<BluetoothGattService>.findCharacteristic(service: UUID, uuid: UUID, props: Int) =
    firstNotNullOfOrNull { s -> s.takeIf { it.uuid == service }?.find(uuid, props) }
