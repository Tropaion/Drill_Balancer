package dev.drillbalancer.device

import dev.drillbalancer.protocol.AngleFlags
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.CalState
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.MountCalResult
import dev.drillbalancer.protocol.OtaClient
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaStatus
import dev.drillbalancer.protocol.PowerState
import dev.drillbalancer.protocol.SensorCalResult
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.protocol.StatusFlags
import dev.drillbalancer.protocol.StatusPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An in-memory device for screenshot tests and ViewModel tests. It
 * publishes all state at once on construction (no coroutines), applies commands and
 * settings writes like the firmware does, and enforces the bond for secure operations.
 * [simulate] adds live motion and calibration progress. Not thread-safe: use it from one
 * thread (the main thread, or a test dispatcher).
 */
class FakeDeviceSession(
    settings: DeviceSettings = DeviceSettings.DEFAULTS,
    connected: Boolean = true,
    bonded: Boolean = true,
    info: DeviceInfo = DEFAULT_INFO,
    batteryPct: Int = 87,
    private var address: String = "F4:12:FA:00:1A:2B",
    private val name: String = "DrillBalancer-1A2B",
) : DeviceSession {
    // Test knobs
    var pairingSucceeds = true
    var mountCalResult = MountCalResult.OK          // what the next mount FINISH yields
    var otaFailure: OtaStatus? = null               // FINISH fails with this
    var otaStepMs = 50L                             // fake transfer: 10 steps of this
    var otaReplyLost = false                        // the link drops after FINISH, before its reply
    var otaCleanup: CompletableDeferred<Unit>? = null   // a stopped transfer's clean-up waits for this
    var failNext: DeviceError? = null               // the next suspend call throws this

    val commands: List<Command> get() = sentCommands
    var otaStarts = 0; private set                  // startOta collections that got the link
    private val otaLock = Mutex()
    val updates: List<SettingsUpdate> get() = writtenUpdates
    private val sentCommands = mutableListOf<Command>()
    private val writtenUpdates = mutableListOf<SettingsUpdate>()

    private var bonded = bonded
    private var stored = settings
    private var battery = batteryPct
    private var charging = false
    private var imuOk = true

    // Absolute orientation; the packet reports it relative to the zero.
    private var elevation = 0.3f
    private var yaw = 0f
    private var bubbleX = 0.2f
    private var bubbleY = -0.1f
    private var wobble = 0f
    private var zero: FloatArray? = null            // elevation, yaw, bubble x, bubble y
    private var seq = 0

    private var calKind = CalKind.IDLE
    private var calFromBle = false                  // control.c s_cal_from_ble
    private var calSamples = 0
    private var calCoverage = 0
    private var calLastResult = CalResult.NONE

    private val _connection = MutableStateFlow<ConnectionState>(
        if (connected) ConnectionState.Connected(address, name, bonded) else ConnectionState.Disconnected(),
    )
    private val _angle = MutableStateFlow<AnglePacket?>(null)
    private val _status = MutableStateFlow<StatusPacket?>(null)
    private val _battery = MutableStateFlow<Int?>(null)
    private val _info = MutableStateFlow<DeviceInfo?>(null)
    private val _settings = MutableStateFlow<DeviceSettings?>(null)
    private var deviceInfo = info

    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val angle: StateFlow<AnglePacket?> = _angle.asStateFlow()
    override val status: StateFlow<StatusPacket?> = _status.asStateFlow()
    override val batteryPct: StateFlow<Int?> = _battery.asStateFlow()
    override val info: StateFlow<DeviceInfo?> = _info.asStateFlow()
    override val settings: StateFlow<DeviceSettings?> = _settings.asStateFlow()

    init {
        if (connected) publishAll()
    }

    // ---- DeviceSession ----

    override suspend fun connect(address: String) {
        takeFailure()
        this.address = address
        _connection.value = ConnectionState.Connecting(address)
        // Like the BLE session: Device Information is read before the link counts as connected.
        _info.value = deviceInfo
        _connection.value = ConnectionState.Connected(address, name, bonded)
        publishAll()
    }

    override suspend fun disconnect() {
        dropLink(null)
    }

    override suspend fun ensureBonded() {
        requireLink()
        if (bonded) return
        if (!pairingSucceeds) throw DeviceError.PairingFailed()
        bonded = true
        _connection.value = ConnectionState.Connected(address, name, true)
    }

    override suspend fun readSettings(): DeviceSettings {
        requireLink()
        return stored.also { _settings.value = it }
    }

    override suspend fun writeSettings(update: SettingsUpdate): SettingsWrite {
        requireLink(secure = true)
        val next = update.applyTo(stored)
        if (!next.isValid) throw DeviceError.Att(AttError.JSON_RANGE.code)
        writtenUpdates += update
        if (next.mode.isFloor != stored.mode.isFloor) zero = null
        val bleOff = stored.bleEnabled && !next.bleEnabled
        stored = next
        publishAngle()
        _settings.value = stored
        // ble=false: the device stores it, answers, then closes the link.
        if (bleOff) dropLink(DeviceError.Link(DisconnectReason.BLE_DISABLED))
        return SettingsWrite(stored)
    }

    override suspend fun sendCommand(command: Command) {
        requireLink(secure = true)
        sentCommands += command
        when (command) {
            Command.Zero -> zero = floatArrayOf(elevation + wobble, yaw, bubbleX + wobble, bubbleY)
            Command.ClearZero -> zero = null
            is Command.SetMode -> {
                if (command.mode.isFloor != stored.mode.isFloor) zero = null
                stored = stored.copy(mode = command.mode)
                _settings.value = stored
            }
            Command.MountCalStart -> startCal(CalKind.MOUNT, fromBle = true)
            Command.AccelCalStart -> startCal(CalKind.ACCEL, fromBle = true)
            Command.MountCalFinish -> if (calKind == CalKind.MOUNT) {
                if (mountCalResult == MountCalResult.OK) {
                    stored = stored.copy(cal = stored.cal.copy(mount = true))
                    zero = null   // a new drill axis clears the zero
                }
                finishCal(mountCalResult.code)
            }
            Command.AccelCalFinish -> if (calKind == CalKind.ACCEL) {
                if (calCoverage and CoverageDir.ALL != CoverageDir.ALL) {
                    finishCal(SensorCalResult.POOR_COVERAGE.code)
                } else {
                    // A new sensor calibration resets the mount calibration (control.c).
                    val dropped = stored.cal.mount
                    stored = stored.copy(cal = CalState(mount = false, accel = true))
                    zero = null
                    finishCal(if (dropped) CalResult.OK_MOUNT_DROPPED else SensorCalResult.OK.code)
                }
            }
            Command.CalCancel -> cancelCal()
            Command.Sleep -> dropLink(DeviceError.Link(DisconnectReason.SLEEP))
            Command.PowerOff -> dropLink(DeviceError.Link(DisconnectReason.POWER_OFF))
            Command.Reboot -> dropLink(DeviceError.Link(DisconnectReason.REBOOT), reconnecting = true)
            Command.FactoryReset -> {
                stored = DeviceSettings.DEFAULTS
                zero = null
                bonded = false   // the reset deletes all bonds
                dropLink(DeviceError.Link(DisconnectReason.FACTORY_RESET))
            }
        }
        publishAngle()
        publishStatus()
    }

    override fun startOta(image: FirmwareImage): Flow<OtaProgress> = flow { otaLock.withLock { ota(image) } }

    // Like BleDeviceSession: one transfer at a time, the next after the stopped one's clean-up.
    private suspend fun FlowCollector<OtaProgress>.ota(image: FirmwareImage) {
        requireLink(secure = true)
        otaStarts++
        val total = image.size
        emit(OtaProgress(OtaProgress.Phase.STARTING, 0, total, 0f))
        try {
            for (i in 1..10) {
                delay(otaStepMs)
                val done = (total.toLong() * i / 10).toInt()
                emit(OtaProgress(OtaProgress.Phase.SENDING, done, total, done / (i * otaStepMs / 1000f)))
            }
        } catch (e: CancellationException) {
            // Like BleDeviceSession: ABORT and indications off before the flow ends.
            otaCleanup?.let { withContext(NonCancellable) { it.await() } }
            throw e
        }
        val rate = total / (10 * otaStepMs / 1000f)
        emit(OtaProgress(OtaProgress.Phase.VERIFYING, total, total, rate))
        delay(otaStepMs)
        if (otaReplyLost) {
            // The device installs the image unless its check fails; the app cannot tell.
            if (otaFailure == null) deviceInfo = deviceInfo.copy(firmwareRevision = image.version)
            // Like BleDeviceSession: the reboot is announced once FINISH is out.
            dropLink(DeviceError.Link(DisconnectReason.FIRMWARE_UPDATED), reconnecting = true)
            throw OtaException(OtaClient.UNCONFIRMED, unconfirmed = true)
        }
        otaFailure?.let { throw OtaException("FINISH failed: ${it.message}", it) }
        emit(OtaProgress(OtaProgress.Phase.DONE, total, total, rate))
        deviceInfo = deviceInfo.copy(firmwareRevision = image.version)
        dropLink(DeviceError.Link(DisconnectReason.FIRMWARE_UPDATED), reconnecting = true)
    }

    // ---- simulation and test hooks ----

    /**
     * Runs until cancelled: the angle wobbles and is notified every [periodMs] (10 Hz like
     * the device); a mount calibration collects 5 samples per tick, a sensor calibration
     * captures one more side every 10 ticks.
     */
    suspend fun simulate(periodMs: Long = 100) {
        var ticks = 0L
        while (true) {
            delay(periodMs)
            ticks++
            if (_connection.value !is ConnectionState.Connected) continue
            wobble = 0.25f * sin(ticks * periodMs / 700.0).toFloat()
            yaw += 0.002f
            when (calKind) {
                CalKind.MOUNT -> calSamples = minOf(calSamples + 5, 6000)
                CalKind.ACCEL -> if (ticks % 10 == 0L) {
                    CoverageDir.entries.firstOrNull { calCoverage and it.bit == 0 }?.let {
                        calCoverage = calCoverage or it.bit
                        calSamples++
                    }
                }
                CalKind.IDLE -> Unit
            }
            seq = (seq + 1) and 0xFF
            publishAngle()
            publishStatus()
        }
    }

    /** Sets the absolute orientation (degrees) and notifies it. */
    fun setOrientation(elevationDeg: Float, yawDeg: Float = 0f, bubbleXDeg: Float = 0f, bubbleYDeg: Float = 0f) {
        elevation = elevationDeg
        yaw = yawDeg
        bubbleX = bubbleXDeg
        bubbleY = bubbleYDeg
        wobble = 0f
        publishAngle()
    }

    /** A calibration started on the device's own screen (not cancelled by a drop). */
    fun startCalOnDevice(kind: CalKind) {
        startCal(kind, fromBle = false)
        publishStatus()
    }

    /**
     * A change made in the device's own settings menu. The config characteristic is not
     * notified: [settings] keeps the old values until the app reads again.
     */
    fun changeOnDevice(change: (DeviceSettings) -> DeviceSettings) {
        stored = change(stored)
        publishAngle()
    }

    fun setImuOk(ok: Boolean) {
        imuOk = ok
        publishAngle()
    }

    fun setBattery(pct: Int, charging: Boolean = false) {
        battery = pct
        this.charging = charging
        if (_connection.value is ConnectionState.Connected) _battery.value = pct
        publishStatus()
    }

    /**
     * Ends the link as the device or the radio would; [error] null means on purpose.
     * [reconnecting] plays the BLE session's auto-reconnect: a test then calls [connect]
     * (it came back) or drops again without it (it gave up).
     */
    fun dropLink(error: DeviceError?, reconnecting: Boolean = false) {
        // A calibration started over BLE is cancelled when the link drops; nobody hears
        // about it until the next status after a reconnect.
        if (calFromBle && calKind != CalKind.IDLE) {
            calKind = CalKind.IDLE
            calLastResult = CalResult.NONE
        }
        // Like BleDeviceSession: the live values go with the link.
        _angle.value = null
        _status.value = null
        _battery.value = null
        _connection.value = ConnectionState.Disconnected(error, reconnecting)
    }

    // ---- internals ----

    private fun requireLink(secure: Boolean = false) {
        takeFailure()
        if (_connection.value !is ConnectionState.Connected) throw DeviceError.NotConnected()
        if (secure && !bonded) throw DeviceError.Att(AttError.INSUFFICIENT_AUTHENTICATION.code)
    }

    private fun takeFailure() {
        failNext?.let {
            failNext = null
            throw it
        }
    }

    private fun startCal(kind: CalKind, fromBle: Boolean) {
        calKind = kind
        calFromBle = fromBle
        calSamples = 0
        calCoverage = 0
    }

    private fun finishCal(result: Int) {
        calKind = CalKind.IDLE
        calLastResult = result
        _settings.value = stored
    }

    private fun cancelCal() {
        if (calKind == CalKind.IDLE) return
        calKind = CalKind.IDLE
        calLastResult = CalResult.NONE
        publishStatus()
    }

    private fun publishAll() {
        _info.value = deviceInfo
        _battery.value = battery
        _settings.value = stored
        publishAngle()
        publishStatus()
    }

    private fun publishAngle() {
        if (_connection.value !is ConnectionState.Connected) return
        val z = zero
        val el = elevation + wobble - (z?.get(0) ?: 0f)
        val yw = yaw - (z?.get(1) ?: 0f)
        val bx = bubbleX + wobble - (z?.get(2) ?: 0f)
        val by = bubbleY - (z?.get(3) ?: 0f)
        val s = stored
        val deviation = when (s.mode) {
            LevelMode.WALL -> el
            LevelMode.ANGLE -> el - s.targetDeg
            LevelMode.FLOOR -> hypot(bx, by)
        }
        var flags = 0
        val yawValid = z != null && !s.mode.isFloor
        if (imuOk) {
            flags = flags or AngleFlags.IMU_OK or AngleFlags.STILL
            flags = flags or when {
                abs(deviation) <= s.toleranceDeg -> AngleFlags.ON_TARGET
                abs(deviation) <= s.nearDeg -> AngleFlags.NEAR
                else -> 0
            }
            if (yawValid) flags = flags or AngleFlags.YAW_VALID
        }
        if (z != null) flags = flags or AngleFlags.ZERO_SET
        _angle.value = AnglePacket.of(s.mode, flags, seq, el, if (yawValid) yw else 0f, bx, by, deviation)
    }

    private fun publishStatus() {
        if (_connection.value !is ConnectionState.Connected) return
        var flags = StatusFlags.BATTERY_VALID
        if (charging) flags = flags or StatusFlags.CHARGING or StatusFlags.VBUS
        if (stored.cal.mount) flags = flags or StatusFlags.MOUNT_CAL
        if (stored.cal.accel) flags = flags or StatusFlags.ACCEL_CAL
        if (calKind != CalKind.IDLE) flags = flags or StatusFlags.CALIBRATING
        _status.value = StatusPacket(
            batteryPct = battery,
            batteryMv = 3500 + battery * 7,
            flags = flags,
            powerState = PowerState.ACTIVE,
            calKind = calKind,
            calSamples = minOf(calSamples, 255),
            calCoverage = calCoverage,
            calLastResult = calLastResult,
        )
    }

    companion object {
        val DEFAULT_INFO = DeviceInfo(
            manufacturer = "DrillBalancer",
            model = "DrillBalancer (ESP32-S3-Touch-AMOLED-1.75)",
            firmwareRevision = "0.1.0",
        )
    }
}

// FLOOR judges the bubble, WALL and ANGLE the elevation: a change between the two
// families clears the zero (level_set_mode()).
private val LevelMode.isFloor get() = this == LevelMode.FLOOR
