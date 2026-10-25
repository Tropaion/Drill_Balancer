package dev.drillbalancer.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceInfo
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.StatusPacket
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.runCommand
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceUiState(
    val name: String = "DrillBalancer",
    val address: String = "",
    val bonded: Boolean = false,
    val info: DeviceInfo? = null,
    val status: StatusPacket? = null,
    val batteryPct: Int? = null,
    val busy: Boolean = false,
    val message: UiMessage? = null,
)

class DeviceViewModel(private val session: DeviceSession, private val gate: PairingGate) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiMessage?>(null)

    val state: StateFlow<DeviceUiState> = combine(
        session.connection, session.info, session.status, session.batteryPct, combine(busy, message, ::Pair),
    ) { c, info, status, battery, (b, m) ->
        val connected = c as? ConnectionState.Connected
        DeviceUiState(
            name = connected?.name ?: "DrillBalancer",
            address = connected?.address.orEmpty(),
            bonded = connected?.bonded == true,
            info = info,
            status = status,
            batteryPct = battery,
            busy = b,
            message = m,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceUiState())

    /** Sleep, power off, reboot and factory reset end the link; the connect screen says why. */
    fun send(command: Command) {
        if (busy.value) return
        viewModelScope.launch { gate.runCommand(command, { busy.value = it }, { message.value = it }) }
    }

    fun dismissMessage() {
        message.value = null
    }
}
