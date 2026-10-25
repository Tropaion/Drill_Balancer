package dev.drillbalancer.ui.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.ui.PairingCancelled
import dev.drillbalancer.ui.PairingGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

data class FirmwareUiState(
    val deviceVersion: String? = null,
    val fileName: String? = null,
    val image: FirmwareImage? = null,
    val fileError: String? = null,       // the file cannot be installed (FirmwareImage.parse)
    val phase: Phase = Phase.IDLE,
    val progress: OtaProgress? = null,
    val error: OtaError? = null,
    val newVersion: String? = null,      // DONE: what the device reports after the reboot
) {
    enum class Phase { IDLE, TRANSFERRING, REBOOTING, DONE, FAILED }

    data class OtaError(val title: String, val text: String)

    val canStart: Boolean get() = image != null && (phase == Phase.IDLE || phase == Phase.FAILED)
    val canPick: Boolean get() = phase != Phase.TRANSFERRING && phase != Phase.REBOOTING
    val sameVersion: Boolean get() = image != null && image.version == deviceVersion

    /** Until FINISH goes out: after it the device installs the image whatever the app does. */
    val canCancel: Boolean
        get() = phase == Phase.TRANSFERRING &&
            progress?.phase != OtaProgress.Phase.VERIFYING && progress?.phase != OtaProgress.Phase.DONE

    /** Seconds left at the current rate, while sending. */
    val etaSeconds: Long?
        get() {
            val p = progress ?: return null
            if (phase != Phase.TRANSFERRING || p.phase != OtaProgress.Phase.SENDING || p.bytesPerSecond <= 0f) return null
            return ((p.totalBytes - p.bytesDone) / p.bytesPerSecond).roundToLong()
        }
}

class FirmwareViewModel(
    private val session: DeviceSession,
    private val gate: PairingGate,
    private val reconnectTimeoutMs: Long = 90_000,
) : ViewModel() {
    private val _state = MutableStateFlow(FirmwareUiState(deviceVersion = session.info.value?.firmwareRevision))
    val state: StateFlow<FirmwareUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.info.collect { info -> if (info != null) _state.update { it.copy(deviceVersion = info.firmwareRevision) } }
        }
    }

    /** A picked file's contents (the route reads it from the Storage Access Framework). */
    fun onFile(name: String?, bytes: ByteArray) {
        if (!_state.value.canPick) return
        val parsed = FirmwareImage.parse(bytes)
        _state.update {
            it.copy(
                fileName = name,
                image = (parsed as? FirmwareParseResult.Ok)?.image,
                fileError = (parsed as? FirmwareParseResult.Error)?.message,
                phase = FirmwareUiState.Phase.IDLE,
                progress = null,
                error = null,
            )
        }
    }

    /** The file could not be read at all. */
    fun onFileError(name: String?, message: String) {
        if (!_state.value.canPick) return
        _state.update { it.copy(fileName = name, image = null, fileError = message, phase = FirmwareUiState.Phase.IDLE) }
    }

    fun start() {
        val s = _state.value
        val image = s.image ?: return
        if (!s.canStart) return
        job = viewModelScope.launch {
            _state.update { it.copy(phase = FirmwareUiState.Phase.TRANSFERRING, progress = null, error = null, newVersion = null) }
            try {
                gate.secure { session.startOta(image).collect { p -> _state.update { it.copy(progress = p) } } }
            } catch (_: PairingCancelled) {
                _state.update { it.copy(phase = FirmwareUiState.Phase.IDLE) }
                return@launch
            } catch (e: OtaException) {
                fail(otaError(e))
                return@launch
            } catch (e: DeviceError) {
                fail(FirmwareUiState.OtaError("Update failed", e.message ?: "Error"))
                return@launch
            }
            _state.update { it.copy(phase = FirmwareUiState.Phase.REBOOTING) }
            val version = awaitNewVersion()
            _state.update { it.copy(phase = FirmwareUiState.Phase.DONE, newVersion = version) }
        }
    }

    /** Stops the transfer; the session sends ABORT and the device keeps its firmware. */
    fun cancel() {
        // Also for a stop dialog opened before FINISH went out and confirmed after.
        if (!_state.value.canCancel) return
        job?.cancel()
        _state.update { it.copy(phase = FirmwareUiState.Phase.IDLE, progress = null) }
    }

    private fun fail(error: FirmwareUiState.OtaError) =
        _state.update { it.copy(phase = FirmwareUiState.Phase.FAILED, error = error) }

    // The device reboots about 0.5 s after FINISH; the session then reconnects by itself.
    private suspend fun awaitNewVersion(): String? {
        withTimeoutOrNull(DROP_TIMEOUT_MS) { session.connection.first { it !is ConnectionState.Connected } }
        val back = withTimeoutOrNull(reconnectTimeoutMs) {
            session.connection.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected && !it.reconnecting }
        }
        if (back !is ConnectionState.Connected) return null
        return session.info.value?.firmwareRevision
    }

    private companion object {
        const val DROP_TIMEOUT_MS = 10_000L
    }
}

/** "Update failed (HASH_MISMATCH)" with the client's message and the status' explanation. */
fun otaError(e: OtaException): FirmwareUiState.OtaError {
    val status = e.status
    val text = e.message ?: "Update failed."
    val detail = status?.message?.takeIf { it !in text }
    return FirmwareUiState.OtaError(
        title = if (status != null) "Update failed (${status.name})" else "Update failed",
        text = if (detail != null) "$text\n$detail" else text,
    )
}
