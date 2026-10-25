package dev.drillbalancer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.StatusPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The frame around the connected screens: top bar contents and which UI to show. */
data class ShellState(
    val showMain: Boolean = false,        // false: the connect screen
    val deviceName: String = "DrillBalancer",
    val bonded: Boolean = false,
    val reconnecting: DisconnectReason? = null,   // main UI kept while the link comes back
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val chargeDone: Boolean = false,
)

/**
 * Activity-wide: owns the [PairingGate] and decides between the connect screen and the
 * connected screens. While the session reconnects by itself after a drop (link loss,
 * reboot, firmware update) the connected screens stay with a "reconnecting" bar, so for
 * example the firmware screen can wait for the new version.
 */
class SessionViewModel(val session: DeviceSession) : ViewModel() {
    val gate = PairingGate(session, viewModelScope)

    private val _shell = MutableStateFlow(ShellState())
    val shell: StateFlow<ShellState> = _shell.asStateFlow()

    /**
     * Holds the connected screens' ViewModels. A fresh one per connection: the old one is
     * cleared when the app goes back to the connect screen, so no screen of an ended
     * session keeps collecting. Lives here to survive configuration changes.
     */
    var screens = ViewModelStore()
        private set

    private var holding: DisconnectReason? = null
    private var inputs = Inputs(session.connection.value, session.status.value, session.batteryPct.value)

    private data class Inputs(val connection: ConnectionState, val status: StatusPacket?, val battery: Int?)

    init {
        publish()
        // One collector decides holding and publishes, so the shell never shows the
        // connect screen for a moment before a hold starts.
        viewModelScope.launch {
            combine(session.connection, session.status, session.batteryPct, ::Inputs).collect { next ->
                val c = next.connection
                when (c) {
                    is ConnectionState.Connected -> holding = null
                    is ConnectionState.Connecting -> Unit   // a reconnect attempt keeps what is shown
                    is ConnectionState.Disconnected -> {
                        val reason = (c.error as? DeviceError.Link)?.reason
                        holding = if (_shell.value.showMain && c.reconnecting && reason != null) reason else null
                    }
                }
                inputs = next
                publish()
            }
        }
    }

    /** Ends the session on purpose (also stops reconnecting). */
    fun disconnect() {
        holding = null
        publish()
        viewModelScope.launch { session.disconnect() }
    }

    override fun onCleared() {
        screens.clear()
    }

    private fun publish() {
        val (c, status, battery) = inputs
        val connected = c as? ConnectionState.Connected
        val showMain = connected != null || holding != null
        if (_shell.value.showMain && !showMain) {
            val ended = screens
            screens = ViewModelStore()
            ended.clear()
        }
        _shell.value = ShellState(
            showMain = showMain,
            deviceName = connected?.name ?: _shell.value.deviceName,
            bonded = connected?.bonded == true,
            reconnecting = if (connected == null) holding else null,
            // Nothing stale while the link is down
            batteryPct = if (connected == null) null else displayBatteryPct(status, battery),
            charging = connected != null && status?.charging == true,
            chargeDone = connected != null && status?.chargeDone == true,
        )
    }
}
