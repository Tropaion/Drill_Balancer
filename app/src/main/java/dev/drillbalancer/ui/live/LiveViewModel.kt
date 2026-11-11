package dev.drillbalancer.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.runCommand
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * What the live screen lays out by. Only changes when the view or a flag changes, not with
 * every angle packet; the numbers come straight from [LiveViewModel.angle].
 */
data class LiveUiState(
    val mode: LevelMode = LevelMode.WALL,   // the view the angle packets report; WALL without them
    val hasAngle: Boolean = false,
    val imuOk: Boolean = true,
    val zeroSet: Boolean = false,
    val yawValid: Boolean = false,
    val yawStale: Boolean = false,
    val toleranceDeg: Float = DeviceSettings.DEFAULTS.toleranceDeg,
    val nearDeg: Float = DeviceSettings.DEFAULTS.nearDeg,
    val dotInvert: Boolean = DeviceSettings.DEFAULTS.dotInvert,   // as on the device: the way to correct
    val busy: Boolean = false,
    val message: UiMessage? = null,
)

class LiveViewModel(private val session: DeviceSession, private val gate: PairingGate) : ViewModel() {
    /**
     * The 10 Hz packets, read by the leaf composables only; null once they stop coming.
     * The link can stay up for seconds after that (supervision timeout), and an old ON
     * TARGET must not stay green. The device sends no reading older than 500 ms; the
     * longer limit here allows for Android delivering packets in bursts.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // transformLatest
    val angle: StateFlow<AnglePacket?> = session.angle
        .transformLatest { a ->
            emit(a)
            if (a != null) {
                delay(STALE_MS)
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.angle.value)

    private data class Layout(
        val mode: LevelMode,
        val imuOk: Boolean,
        val zeroSet: Boolean,
        val yawValid: Boolean,
        val yawStale: Boolean,
    )

    private val layout = angle
        .map { a -> a?.let { Layout(it.mode, it.imuOk, it.zeroSet, it.yawValid, it.yawStale) } }
        .distinctUntilChanged()
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiMessage?>(null)

    val state: StateFlow<LiveUiState> = combine(layout, session.settings, busy, message) { l, s, b, m ->
        val cfg = s ?: DeviceSettings.DEFAULTS
        LiveUiState(
            mode = l?.mode ?: LevelMode.WALL,
            hasAngle = l != null,
            imuOk = l?.imuOk ?: true,
            zeroSet = l?.zeroSet == true,
            yawValid = l?.yawValid == true,
            yawStale = l?.yawStale == true,
            toleranceDeg = cfg.toleranceDeg,
            nearDeg = cfg.nearDeg,
            dotInvert = cfg.dotInvert,
            busy = b,
            message = m,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUiState())

    fun zero() = command(Command.Zero)

    fun clearZero() = command(Command.ClearZero)

    fun dismissMessage() {
        message.value = null
    }

    private fun command(command: Command) {
        if (busy.value) return
        viewModelScope.launch { gate.runCommand(command, { busy.value = it }, { message.value = it }) }
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
