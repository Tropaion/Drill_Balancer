package dev.drillbalancer.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceInfo
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.CalState
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.Decoded
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.OtaClient
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaTransport
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.protocol.StatusPacket
import dev.drillbalancer.protocol.decodeBatteryLevel
import dev.drillbalancer.protocol.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * The real device over BLE (Nordic Android-BLE-Library). One per process: create it in
 * the Application and share it.
 *
 * All GATT work is asynchronous on a dedicated handler thread; the suspend functions can
 * be called from any dispatcher, the main thread included, and the StateFlows can be
 * collected from Compose.
 *
 * Disconnects carry a [DisconnectReason] in ConnectionState.Disconnected(error). A drop
 * the device recovers from (link loss, reboot, firmware update) is followed by a few
 * reconnect attempts (ReconnectPolicy).
 */
class BleDeviceSession(
    context: Context,
    private val scanner: DeviceScanner? = null,
    private val lastDevice: LastDeviceStore? = null,
) : DeviceSession {
    private val context = context.applicationContext
    private val thread = HandlerThread("DrillBalancer-BLE").apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val linkLock = Mutex()   // connect and pairing, one at a time
    private val otaLock = Mutex()    // one firmware update at a time, clean-up included

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    private val _angle = MutableStateFlow<AnglePacket?>(null)
    private val _status = MutableStateFlow<StatusPacket?>(null)
    private val _battery = MutableStateFlow<Int?>(null)
    private val _info = MutableStateFlow<DeviceInfo?>(null)
    private val _settings = MutableStateFlow<DeviceSettings?>(null)
    private val otaIndications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val angle: StateFlow<AnglePacket?> = _angle.asStateFlow()
    override val status: StateFlow<StatusPacket?> = _status.asStateFlow()
    override val batteryPct: StateFlow<Int?> = _battery.asStateFlow()
    override val info: StateFlow<DeviceInfo?> = _info.asStateFlow()
    override val settings: StateFlow<DeviceSettings?> = _settings.asStateFlow()

    @Volatile private var expectedDrop: DisconnectReason? = null

    // The connect in progress got a pairing request although the phone held a bond when
    // it started. Checked against the start: Android marks the device BONDING before it
    // sends the request.
    @Volatile private var bondedAtConnect = false
    @Volatile private var keysLost = false

    // The device of the last open(). Nordic forgets its own as soon as the link drops,
    // before it fails the request in flight, and the bond state is needed right then.
    @Volatile private var device: BluetoothDevice? = null
    private val reconnectJob = AtomicReference<Job?>()

    private val manager = DrillBalancerManager(
        this.context,
        Handler(thread.looper),
        object : DrillBalancerManager.Events {
            // Undecodable packets are dropped; the next one comes within 100 ms / 1 s.
            override fun onAngle(value: ByteArray) {
                AnglePacket.decode(value).getOrNull()?.let {
                    _angle.value = it
                    syncMode(it)
                }
            }

            override fun onStatus(value: ByteArray) {
                StatusPacket.decode(value).getOrNull()?.let {
                    _status.value = it
                    syncCal(it)
                }
            }

            override fun onBattery(value: ByteArray) {
                decodeBatteryLevel(value).getOrNull()?.let { _battery.value = it }
            }

            override fun onOtaCtrl(value: ByteArray) {
                otaIndications.tryEmit(value)
            }

            override fun onPairingRequest() {
                if (_connection.value is ConnectionState.Connecting && bondedAtConnect) keysLost = true
            }
        },
    )

    private val transport = object : OtaTransport {
        override val mtu: Int get() = manager.currentMtu
        override val ctrlIndications: Flow<ByteArray> = otaIndications
        override suspend fun writeCtrl(value: ByteArray) = gatt(GattOp.OTA) { writeOtaCtrl(value) }
        override suspend fun writeData(value: ByteArray) = gatt(GattOp.OTA) { writeOtaData(value) }

        // Any state but Connected: the transfer's link is gone (a reconnect is a new link).
        override val linkLost: Flow<Unit> = _connection.filter { it !is ConnectionState.Connected }.map { }
    }

    init {
        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit   // connect() throws
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = onLinkClosed(reason)
        })
    }

    // ---- DeviceSession ----

    override suspend fun connect(address: String) {
        cancelReconnect()
        open(address)
    }

    override suspend fun disconnect() {
        cancelReconnect()
        // State first, so the disconnect callback finds no session to report. Not under
        // linkLock: this also cancels a connect in progress.
        _connection.value = ConnectionState.Disconnected()
        clearLive()
        withContext(NonCancellable) { closeLink() }
    }

    override suspend fun ensureBonded() = linkLock.withLock {
        requireReady()
        val wasBonded = phoneBonded()
        // No bond on the phone: Android shows its pairing dialog, the user types the
        // 6-digit passkey shown on the device.
        if (!wasBonded) gatt(GattOp.PAIRING) { pair() }
        // A bond on the phone does not prove the device still has its half. "{}" changes
        // nothing but needs the encrypted, authenticated link, so it proves both.
        try {
            gatt(GattOp.PAIRING) { writeConfig(PROBE) }
        } catch (e: DeviceError.Att) {
            if (!e.needsPairing) throw e
            throw if (wasBonded) DeviceError.StaleBond() else DeviceError.PairingFailed()
        } catch (e: DeviceError.StaleBond) {
            // The bond was made just now: these keys failed, not old ones.
            throw if (wasBonded) e else DeviceError.PairingFailed()
        }
        setBonded(true)
    }

    override suspend fun readSettings(): DeviceSettings {
        requireReady()
        return fetchSettings()
    }

    override suspend fun writeSettings(update: SettingsUpdate): DeviceSettings {
        requireReady()
        if (update.isEmpty) return fetchSettings()
        val drop = expectedDrop(update)
        withExpectedDrop(drop) { gatt(GattOp.CONFIG_WRITE) { writeConfig(update.toBytes()) } }
        val known = _settings.value
        // The link is closing: no read-back, the device stored exactly this.
        val stored = if (drop != null) merged(update, known) else storedSettings(update, known) { fetchSettings() }
        _settings.value = stored
        return stored
    }

    override suspend fun sendCommand(command: Command) {
        requireReady()
        withExpectedDrop(expectedDrop(command)) { gatt(GattOp.COMMAND) { writeCommand(command.encode()) } }
        // SET_MODE is stored in the settings (control.c); config is not notified.
        if (command is Command.SetMode) _settings.update { it?.copy(mode = command.mode) }
    }

    // A stopped transfer may still be sending ABORT: its clean-up (indications off) must not
    // land after the next one subscribed, whichever screen started that one.
    override fun startOta(image: FirmwareImage): Flow<OtaProgress> = flow {
        otaLock.withLock {
            requireReady()
            if (!manager.hasOta) throw OtaException("The device's firmware does not offer updates over Bluetooth.")
            // Indications only while updating; subscribed before START is written.
            gatt(GattOp.OTA) { setOtaIndications(true) }
            quietly { gatt(GattOp.OTA) { setHighPriority(true) } }
            var done = false
            try {
                OtaClient(transport).upload(image.bytes, image.sha256).collect { progress ->
                    if (progress.phase == OtaProgress.Phase.DONE) {
                        // The device reboots into the new image about 0.5 s after FINISH.
                        done = true
                        expectedDrop = DisconnectReason.FIRMWARE_UPDATED
                        manager.clearCacheOnDisconnect = true
                    }
                    emit(progress)
                }
            } finally {
                if (!done && manager.isConnected) {
                    withContext(NonCancellable) {
                        quietly { gatt(GattOp.OTA) { setOtaIndications(false) } }
                        quietly { gatt(GattOp.OTA) { setHighPriority(false) } }
                    }
                }
            }
        }
    }

    /**
     * The UI is gone for good (activity finished): ends the link and any reconnecting in
     * the background. The session stays usable for the next activity.
     */
    fun release() {
        scope.launch { disconnect() }
    }

    /** Ends the link and stops the BLE thread; the session is unusable afterwards. */
    fun close() {
        cancelReconnect()
        scope.cancel()
        _connection.value = ConnectionState.Disconnected()
        manager.close()
        thread.quitSafely()
    }

    // ---- link ----

    /** [retryCause]: a reconnect attempt; a failure keeps reporting why the link ended. */
    private suspend fun open(address: String, retryCause: DeviceError.Link? = null) = linkLock.withLock {
        if (!BlePermissions.canConnect(context)) throw DeviceError.MissingPermission()
        val adapter = BlePermissions.adapter(context) ?: throw DeviceError.Link(DisconnectReason.NOT_SUPPORTED)
        if (!adapter.isEnabled) throw DeviceError.Link(DisconnectReason.BLUETOOTH_OFF)
        val now = _connection.value
        if (now is ConnectionState.Connected && now.address == address && manager.isReady) return@withLock
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            throw DeviceError.InvalidData("\"$address\" is not a Bluetooth address.")
        }
        scanner?.stop()
        expectedDrop = null
        keysLost = false
        this.device = device
        bondedAtConnect = phoneBonded()
        val connecting = ConnectionState.Connecting(address)
        _connection.value = connecting
        if (manager.isConnected) closeLink()
        try {
            manager.connect(device)
                .useAutoConnect(false)
                .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
                .timeout(CONNECT_TIMEOUT_MS)
                .suspend()
            setUp()
        } catch (e: Throwable) {
            // Not Connecting any more: disconnect() was called meanwhile and set the state.
            val cancelled = e is CancellationException || _connection.value !== connecting
            val error = if (cancelled) null else GattErrors.connectError(toDeviceError(e, GattOp.CONNECT), keysLost)
            if (!cancelled) {
                // A stale bond ends the reconnecting: every attempt would bring up Android's dialog.
                _connection.value = if (retryCause != null && error !is DeviceError.StaleBond) {
                    ConnectionState.Disconnected(retryCause, reconnecting = true)
                } else {
                    ConnectionState.Disconnected(error as? DeviceError)
                }
            }
            withContext(NonCancellable) { closeLink() }
            throw error ?: (e as? CancellationException ?: CancellationException("Connect cancelled by disconnect()"))
        }
        val connected = ConnectionState.Connected(address, deviceName(device), phoneBonded())
        if (!_connection.compareAndSet(connecting, connected)) throw CancellationException("Connect cancelled by disconnect()")
        // A drop just before the line above found no session to report; report it now.
        if (!manager.isConnected) onLinkClosed(ConnectionObserver.REASON_LINK_LOSS)
        try {
            lastDevice?.set(address)
        } catch (e: IOException) {
            Log.w(TAG, "remembering the device failed", e)
        }
    }

    // Everything connect() promises before it returns: subscribed, status, battery,
    // device information and settings read.
    private suspend fun setUp() {
        gatt(GattOp.SUBSCRIBE) { subscribe() }
        _status.value = decode(gatt(GattOp.READ) { readStatus() }, "status") { StatusPacket.decode(it) }
        gatt(GattOp.READ) { readBattery() }?.let { _battery.value = decodeBatteryLevel(it).getOrNull() }
        gatt(GattOp.READ) { readDeviceInfo() }?.let { _info.value = it }
        fetchSettings()
    }

    private suspend fun fetchSettings(): DeviceSettings {
        val raw = gatt(GattOp.READ) { readConfig() }
        return decode(raw, "settings") { DeviceSettings.parse(it) }.also { _settings.value = it }
    }

    private suspend fun closeLink() {
        try {
            manager.disconnect().suspend()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: $e")   // already gone
        }
    }

    /**
     * The link ended (handler thread). Only an established session that ended by itself
     * is reported here: disconnect() and a reconnecting open() change the state before
     * they close the link, and a failing connect() reports to its caller.
     */
    private fun onLinkClosed(reason: Int) {
        clearLive()
        while (true) {
            val was = _connection.value as? ConnectionState.Connected ?: return
            val error = GattErrors.disconnectError(reason, expectedDrop, BlePermissions.isBluetoothEnabled(context))
            val retry = ReconnectPolicy.retries(error.reason)
            // Retried if setBonded() changed the state meanwhile.
            if (!_connection.compareAndSet(was, ConnectionState.Disconnected(error, retry))) continue
            expectedDrop = null
            Log.i(TAG, "link closed, reason $reason: ${error.message}")
            if (retry) startReconnect(was.address, error)
            return
        }
    }

    // The settings' mode follows the angle packets: a mode change on the device is stored
    // as the start mode (control.c change_mode), and the config is not notified.
    private fun syncMode(a: AnglePacket) = _settings.update { s ->
        if (s == null || s.mode == a.mode) s else s.copy(mode = a.mode)
    }

    // The settings' calibration state follows the status flags (same source on the device).
    private fun syncCal(st: StatusPacket) = _settings.update { s ->
        val cal = CalState(mount = st.mountCalValid, accel = st.accelCalValid)
        if (s == null || s.cal == cal) s else s.copy(cal = cal)
    }

    private fun clearLive() {
        _angle.value = null
        _status.value = null
        _battery.value = null
    }

    private fun startReconnect(address: String, cause: DeviceError.Link) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0
            while (true) {
                delay(ReconnectPolicy.delayMs(cause.reason, attempt++) ?: break)
                try {
                    // A failed attempt keeps showing why the link ended, not its own error.
                    open(address, retryCause = cause)
                    return@launch
                } catch (e: DeviceError) {
                    Log.i(TAG, "reconnect attempt $attempt: ${e.message}")
                    if (e is DeviceError.MissingPermission || e is DeviceError.StaleBond) break
                    if (e is DeviceError.Link && !ReconnectPolicy.retries(e.reason)) break
                }
            }
            // Given up: the user decides. Not if connect() or disconnect() took over.
            _connection.compareAndSet(ConnectionState.Disconnected(cause, reconnecting = true), ConnectionState.Disconnected(cause))
        }
        reconnectJob.getAndSet(job)?.cancel()
        job.start()
    }

    private fun cancelReconnect() {
        reconnectJob.getAndSet(null)?.cancel()
    }

    // ---- helpers ----

    private fun requireReady() {
        if (_connection.value !is ConnectionState.Connected || !manager.isReady) throw DeviceError.NotConnected()
    }

    /** Runs a GATT operation and maps Nordic's exceptions to DeviceError. */
    private suspend fun <T> gatt(op: GattOp, block: suspend DrillBalancerManager.() -> T): T {
        if (!manager.isConnected) throw DeviceError.NotConnected()
        try {
            return manager.block()
        } catch (e: Exception) {
            val error = toDeviceError(e, op)
            if (error is DeviceError.StaleBond) setBonded(false)
            throw error
        }
    }

    private fun toDeviceError(e: Throwable, op: GattOp): Throwable = when (e) {
        is DeviceError, is CancellationException -> e
        is RequestFailedException -> GattErrors.requestError(e.status, op, manager.isConnected, phoneBonded())
        is DeviceDisconnectedException ->
            GattErrors.requestError(FailCallback.REASON_DEVICE_DISCONNECTED, op, false, phoneBonded())
        is BluetoothDisabledException -> DeviceError.Link(DisconnectReason.BLUETOOTH_OFF)
        // A missing characteristic, or the link went away before the request ran.
        is InvalidRequestException ->
            if (manager.isConnected) DeviceError.Link(DisconnectReason.NOT_SUPPORTED) else DeviceError.NotConnected()
        is SecurityException -> DeviceError.MissingPermission()   // permission revoked meanwhile
        else -> e
    }

    /**
     * Runs a write after which the device closes the link on purpose ([drop] non-null).
     * The close can overtake the write's answer, so a lost link then counts as success.
     */
    private suspend fun withExpectedDrop(drop: DisconnectReason?, write: suspend () -> Unit) {
        if (drop != null) expectedDrop = drop
        try {
            write()
        } catch (e: DeviceError) {
            if (drop != null && e is DeviceError.Link && !manager.isConnected) return
            if (drop != null) expectedDrop = null
            throw e
        }
    }

    private fun setBonded(bonded: Boolean) {
        _connection.update { if (it is ConnectionState.Connected) it.copy(bonded = bonded) else it }
    }

    private fun phoneBonded(): Boolean = device?.let { BlePermissions.isBonded(context, it) } == true

    private fun deviceName(device: BluetoothDevice): String? {
        if (!BlePermissions.canConnect(context)) return null
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: DeviceError) {
            Log.w(TAG, "ignored: ${e.message}")
        }
    }

    private fun <T> decode(raw: ByteArray, what: String, decoder: (ByteArray) -> Decoded<T>): T =
        when (val d = decoder(raw)) {
            is Decoded.Ok -> d.value
            is Decoded.Invalid -> throw DeviceError.InvalidData("The device sent unreadable $what (${raw.size} bytes): ${d.reason}")
        }

    private companion object {
        const val TAG = "BleDeviceSession"
        const val CONNECT_RETRIES = 3
        const val CONNECT_RETRY_DELAY_MS = 300
        const val CONNECT_TIMEOUT_MS = 15_000L
        val PROBE = "{}".encodeToByteArray()
    }
}

/**
 * The settings after a write the device stored: [read] back, or, if only that read fails,
 * [update] applied to the [known] ones (the device stored exactly the merged keys). The
 * real values come with the next read or reconnect.
 */
internal suspend fun storedSettings(
    update: SettingsUpdate,
    known: DeviceSettings?,
    read: suspend () -> DeviceSettings,
): DeviceSettings = try {
    read()
} catch (_: DeviceError) {
    merged(update, known)
}

/** [update] applied to the [known] settings, or to the defaults before any were read. */
private fun merged(update: SettingsUpdate, known: DeviceSettings?): DeviceSettings =
    update.applyTo(known ?: DeviceSettings.DEFAULTS)
