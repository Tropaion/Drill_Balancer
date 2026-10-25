package dev.drillbalancer.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.ble.ScanException
import dev.drillbalancer.ble.ScannedDevice
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectUiState(
    val devices: List<ScannedDevice> = emptyList(),
    val scanning: Boolean = false,
    val scanError: String? = null,
    val lastAddress: String? = null,
    val connecting: String? = null,        // address of the connect in progress
    val message: UiMessage? = null,        // why the last connect failed or the link ended
    val removeBond: Boolean = false,       // [message] asks to remove the phone's pairing
) {
    /** The last-used device as the scan sees it, null if not in range. */
    val lastDevice: ScannedDevice? get() = devices.firstOrNull { it.address == lastAddress }
    val others: List<ScannedDevice> get() = devices.filter { it.address != lastAddress }
}

/**
 * Scan list and connecting. [scan] is DeviceScanner.scan (cold; collected while the
 * screen is visible), [lastAddress] the LastDeviceStore.
 */
class ConnectViewModel(
    private val session: DeviceSession,
    private val scan: () -> Flow<List<ScannedDevice>>,
    lastAddress: Flow<String?>,
) : ViewModel() {
    private data class Local(
        val devices: List<ScannedDevice> = emptyList(),
        val scanning: Boolean = false,
        val scanError: String? = null,
        val connecting: String? = null,
        val connectError: DeviceError? = null,
        val dismissed: List<DeviceError> = emptyList(),
    )

    private val local = MutableStateFlow(Local())
    private var wantScan = false
    private var scanJob: Job? = null
    private var connectJob: Job? = null

    val state: StateFlow<ConnectUiState> = combine(
        local, session.connection, lastAddress.stateIn(viewModelScope, SharingStarted.Eagerly, null),
    ) { l, c, last ->
        val linkError = (c as? ConnectionState.Disconnected)?.error
        val error = (l.connectError ?: linkError)?.takeIf { e -> l.dismissed.none { it === e } }
        ConnectUiState(
            devices = l.devices,
            scanning = l.scanning,
            scanError = l.scanError,
            lastAddress = last,
            connecting = (c as? ConnectionState.Connecting)?.address ?: l.connecting,
            message = error?.let { UiMessage(it.message ?: "Error", kindOf(it)) },
            removeBond = error is DeviceError.StaleBond ||
                (error is DeviceError.Link && error.reason == DisconnectReason.FACTORY_RESET),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectUiState())

    /** Screen visible and Bluetooth ready: scan until [stopScan]. */
    fun startScan() {
        wantScan = true
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            local.update { it.copy(scanning = true, scanError = null) }
            try {
                scan().collect { list -> local.update { it.copy(devices = list) } }
            } catch (e: ScanException) {
                local.update { it.copy(scanError = e.message) }
            } catch (_: SecurityException) {
                local.update { it.copy(scanError = "The app may not scan for Bluetooth devices.") }
            } finally {
                local.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        wantScan = false
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(address: String) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            local.update { it.copy(connecting = address, connectError = null, dismissed = emptyList()) }
            try {
                session.connect(address)
            } catch (e: DeviceError) {
                local.update { it.copy(connectError = e) }
            } finally {
                local.update { it.copy(connecting = null) }
                // Connecting stops the scan; keep looking if we are still here.
                if (wantScan && session.connection.value !is ConnectionState.Connected) startScan()
            }
        }
    }

    fun cancelConnect() {
        viewModelScope.launch { session.disconnect() }
        connectJob?.cancel()
    }

    fun dismissMessage() {
        val linkError = (session.connection.value as? ConnectionState.Disconnected)?.error
        local.update { it.copy(connectError = null, dismissed = listOfNotNull(it.connectError, linkError)) }
    }

    private fun kindOf(e: DeviceError): UiMessage.Kind = when {
        e is DeviceError.StaleBond -> UiMessage.Kind.WARNING
        e is DeviceError.Link && e.reason == DisconnectReason.FACTORY_RESET -> UiMessage.Kind.WARNING
        e is DeviceError.Link && e.reason in EXPECTED -> UiMessage.Kind.INFO
        else -> UiMessage.Kind.ERROR
    }

    private companion object {
        // Drops that follow a command or an update: information, not an error.
        val EXPECTED = setOf(
            DisconnectReason.SLEEP, DisconnectReason.POWER_OFF, DisconnectReason.REBOOT,
            DisconnectReason.FIRMWARE_UPDATED, DisconnectReason.FACTORY_RESET, DisconnectReason.BLE_DISABLED,
        )
    }
}
