package dev.drillbalancer.ui.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.protocol.OtaClient
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
    val versionBefore: String? = null,   // what the device ran when the update started
    val unconfirmed: Boolean = false,    // FINISH went out, its answer was lost: the reconnect decides
) {
    enum class Phase { IDLE, TRANSFERRING, REBOOTING, DONE, FAILED }

    /** What the version the device came back with says about the update. */
    enum class Outcome {
        INSTALLED,      // it runs the image's version
        RESTARTED,      // FINISH answered, but the device did not come back to say which version
        UNKNOWN,        // FINISH not answered, and the device did not come back or runs the same version as before
        ROLLED_BACK,    // FINISH answered, another version came back (rolled back, or not bumped)
        NOT_INSTALLED,  // FINISH not answered, another version came back: the update failed
    }

    data class OtaError(val title: String, val text: String)

    val canStart: Boolean get() = image != null && (phase == Phase.IDLE || phase == Phase.FAILED)
    val canPick: Boolean get() = phase != Phase.TRANSFERRING && phase != Phase.REBOOTING
    val sameVersion: Boolean get() = image != null && image.version == deviceVersion

    /** DONE: the outcome shown. */
    val outcome: Outcome get() = outcomeOf(newVersion, image?.version, unconfirmed, versionBefore)

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

    companion object {
        /** [before]: the version the device ran when the update started. */
        fun outcomeOf(newVersion: String?, expected: String?, unconfirmed: Boolean, before: String?): Outcome = when {
            newVersion == null -> if (unconfirmed) Outcome.UNKNOWN else Outcome.RESTARTED
            // Unanswered, and the image's version is the old one: a rejected image looks the same.
            newVersion == expected -> if (unconfirmed && expected == before) Outcome.UNKNOWN else Outcome.INSTALLED
            unconfirmed -> Outcome.NOT_INSTALLED
            else -> Outcome.ROLLED_BACK
        }
    }
}

class FirmwareViewModel(
    private val session: DeviceSession,
    private val gate: PairingGate,
) : ViewModel() {
    private val _state = MutableStateFlow(FirmwareUiState(deviceVersion = session.info.value?.firmwareRevision))
    val state: StateFlow<FirmwareUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.info.collect { info -> if (info != null) _state.update { it.copy(deviceVersion = info.firmwareRevision) } }
        }
    }

    /** A picked file, parsed (the route reads it from the Storage Access Framework). */
    fun onFile(name: String?, parsed: FirmwareParseResult) {
        if (!_state.value.canPick) return
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
        // deviceVersion follows the session, so after the reboot it is the new one.
        val before = s.deviceVersion
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = FirmwareUiState.Phase.TRANSFERRING, progress = null, error = null, newVersion = null,
                    versionBefore = before, unconfirmed = false,
                )
            }
            // A stopped transfer may still be sending ABORT: the session starts this one after it.
            var unconfirmed = false
            try {
                gate.secure { session.startOta(image).collect { p -> _state.update { it.copy(progress = p) } } }
            } catch (_: PairingCancelled) {
                _state.update { it.copy(phase = FirmwareUiState.Phase.IDLE) }
                return@launch
            } catch (e: OtaException) {
                if (!e.unconfirmed) {
                    fail(otaError(e))
                    return@launch
                }
                unconfirmed = true
            } catch (e: DeviceError) {
                fail(FirmwareUiState.OtaError("Update failed", e.message ?: "Error"))
                return@launch
            }
            _state.update { it.copy(phase = FirmwareUiState.Phase.REBOOTING, unconfirmed = unconfirmed) }
            val version = awaitNewVersion()
            if (FirmwareUiState.outcomeOf(version, image.version, unconfirmed, before) == FirmwareUiState.Outcome.NOT_INSTALLED) {
                val text = "The device's answer was lost while it checked the image. It now reports firmware $version, " +
                    "so the update did not go through."
                fail(FirmwareUiState.OtaError("Update failed", text))
                return@launch
            }
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

    // The phone sees the reboot's drop within OtaClient.REBOOT_WINDOW of the end of the
    // update; the session then reconnects by itself.
    // No time limit here: the session's reconnecting is bounded (ReconnectPolicy, up to
    // about 110 s) and ends Connected or Disconnected without reconnecting.
    private suspend fun awaitNewVersion(): String? {
        withTimeoutOrNull(OtaClient.REBOOT_WINDOW) { session.connection.first { it !is ConnectionState.Connected } }
        val back = session.connection.first {
            it is ConnectionState.Connected || it is ConnectionState.Disconnected && !it.reconnecting
        }
        if (back !is ConnectionState.Connected) return null
        return session.info.value?.firmwareRevision
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
