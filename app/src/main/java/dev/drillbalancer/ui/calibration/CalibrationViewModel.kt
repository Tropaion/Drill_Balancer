package dev.drillbalancer.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.runCommand
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A calibration that finished while the screen watched, with what the device said. */
data class FinishedCal(val kind: CalKind, val result: CalResult)

data class CalibrationUiState(
    val known: Boolean = false,                 // a status packet has arrived
    val running: CalKind = CalKind.IDLE,
    val samples: Int = 0,                       // mount: samples; sensor: poses kept (both saturate at 255)
    val coverage: Set<CoverageDir> = emptySet(),
    val sensorDone: Boolean = false,
    val mountDone: Boolean = false,
    val finishing: Boolean = false,             // Finish sent, the device is solving
    val finished: FinishedCal? = null,
    val busy: Boolean = false,
    val message: UiMessage? = null,
) {
    val coverageComplete: Boolean get() = coverage.size == CoverageDir.entries.size
}

/**
 * Both calibrations run on the device; this follows them through the status packet. Its
 * cal_last_result does not say which calibration produced it, so the kind that was running
 * just before the device went back to IDLE is remembered here.
 */
class CalibrationViewModel(private val session: DeviceSession, private val gate: PairingGate) : ViewModel() {
    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    // The running calibration was started here, over BLE.
    private var startedHere = false

    init {
        viewModelScope.launch {
            var lastKind = CalKind.IDLE
            var dropped = false   // a calibration started here was running when the link went
            session.status.collect { st ->
                if (st == null) {
                    // Link gone: the device cancels a calibration started over BLE (one started
                    // on its own screen goes on and shows up again after the reconnect).
                    if (startedHere) dropped = true
                    startedHere = false
                    lastKind = CalKind.IDLE
                    _state.update { it.copy(known = false, running = CalKind.IDLE, finishing = false) }
                    return@collect
                }
                if (dropped) {
                    dropped = false
                    if (st.calKind == CalKind.IDLE) _state.update { it.copy(message = UiMessage(CANCELLED_BY_DROP)) }
                }
                val ended = lastKind != CalKind.IDLE && st.calKind == CalKind.IDLE
                if (ended) startedHere = false
                val result = if (ended) st.lastResult(lastKind) else null
                _state.update { s ->
                    s.copy(
                        known = true,
                        running = st.calKind,
                        samples = st.calSamples,
                        coverage = st.coverage,
                        sensorDone = st.accelCalValid,
                        mountDone = st.mountCalValid,
                        finishing = s.finishing && st.calKind != CalKind.IDLE,
                        // -1 after a cancel: nothing to report
                        finished = when {
                            result != null && result != CalResult.None -> FinishedCal(lastKind, result)
                            st.calKind != CalKind.IDLE -> null
                            else -> s.finished
                        },
                    )
                }
                lastKind = st.calKind
            }
        }
    }

    fun startSensor() = run(Command.AccelCalStart, onOk = { startedHere = true })

    fun startMount() = run(Command.MountCalStart, onOk = { startedHere = true })

    fun finish() {
        val cmd = when (_state.value.running) {
            CalKind.ACCEL -> Command.AccelCalFinish
            CalKind.MOUNT -> Command.MountCalFinish
            CalKind.IDLE -> return
        }
        _state.update { it.copy(finishing = true) }
        run(cmd, onFail = { _state.update { it.copy(finishing = false) } })
    }

    fun cancel() = run(Command.CalCancel)

    /** Back to the overview after a result. */
    fun done() = _state.update { it.copy(finished = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun run(command: Command, onFail: () -> Unit = {}, onOk: () -> Unit = {}) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val ok = gate.runCommand(
                command,
                setBusy = { b -> _state.update { it.copy(busy = b) } },
                show = { m -> _state.update { it.copy(message = m) } },
            )
            if (ok == null) onFail() else onOk()
        }
    }

    companion object {
        const val CANCELLED_BY_DROP = "The calibration was cancelled because the connection dropped. Start it again."
    }
}
