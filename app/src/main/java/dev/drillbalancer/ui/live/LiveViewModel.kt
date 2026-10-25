package dev.drillbalancer.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.attempt
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
 * What the live screen lays out by. Only changes when the mode or a flag changes, not with
 * every angle packet; the numbers come straight from [LiveViewModel.angle].
 */
data class LiveUiState(
    val mode: LevelMode = LevelMode.WALL,   // the selected tab: a pending request, else the device's
    val hasAngle: Boolean = false,
    val imuOk: Boolean = true,
    val zeroSet: Boolean = false,
    val yawValid: Boolean = false,
    val yawStale: Boolean = false,
    val toleranceDeg: Float = DeviceSettings.DEFAULTS.toleranceDeg,
    val nearDeg: Float = DeviceSettings.DEFAULTS.nearDeg,
    val targetDeg: Float = DeviceSettings.DEFAULTS.targetDeg,
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
    private val pendingMode = MutableStateFlow<LevelMode?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiMessage?>(null)

    val state: StateFlow<LiveUiState> = combine(layout, session.settings, pendingMode, busy, message) { l, s, p, b, m ->
        val cfg = s ?: DeviceSettings.DEFAULTS
        LiveUiState(
            mode = p ?: l?.mode ?: cfg.mode,
            hasAngle = l != null,
            imuOk = l?.imuOk ?: true,
            zeroSet = l?.zeroSet == true,
            yawValid = l?.yawValid == true,
            yawStale = l?.yawStale == true,
            toleranceDeg = cfg.toleranceDeg,
            nearDeg = cfg.nearDeg,
            targetDeg = cfg.targetDeg,
            busy = b,
            message = m,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUiState())

    init {
        // The tab follows the device again once it reports the requested mode.
        viewModelScope.launch {
            layout.collect { l -> if (l != null && l.mode == pendingMode.value) pendingMode.value = null }
        }
    }

    fun setMode(mode: LevelMode) {
        if (mode == state.value.mode) return
        pendingMode.value = mode
        viewModelScope.launch {
            val sent = gate.attempt(::show) { session.sendCommand(Command.SetMode(mode)) }
            if (sent == null) {
                pendingMode.value = null
                return@launch
            }
            delay(MODE_WAIT_MS)
            if (pendingMode.value == mode) pendingMode.value = null
        }
    }

    fun zero() = command(Command.Zero)

    fun clearZero() = command(Command.ClearZero)

    fun dismissMessage() {
        message.value = null
    }

    private fun command(command: Command) {
        if (busy.value) return
        viewModelScope.launch { gate.runCommand(command, { busy.value = it }, { message.value = it }) }
    }

    private fun show(m: UiMessage) {
        message.value = m
    }

    private companion object {
        const val MODE_WAIT_MS = 3_000L
        const val STALE_MS = 1_000L
    }
}
