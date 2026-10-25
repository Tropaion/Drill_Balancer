package dev.drillbalancer.ui

import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the pairing screen shows; null in [PairingGate.prompt] hides it. */
data class PairingPrompt(val stage: Stage, val error: String? = null) {
    enum class Stage {
        EXPLAIN,      // why, and what the user will have to type; nothing sent yet
        PAIRING,      // ensureBonded() running: the system dialog asks for the passkey
        FAILED,       // wrong code, cancelled dialog, timeout: try again
        STALE_BOND,   // the phone's bond is dead: remove it in Bluetooth settings first
    }
}

/** The user closed the pairing screen; the action that needed it is dropped quietly. */
class PairingCancelled : Exception("Pairing cancelled")

/**
 * Runs actions that need the bonded link (settings writes, commands, OTA). Without a bond,
 * or when the device answers that it wants one, the action waits while the pairing screen
 * explains the passkey and pairs; then it runs (again). One gate per session, shared by
 * all screens; [scope] runs the pairing itself.
 */
class PairingGate(internal val session: DeviceSession, private val scope: CoroutineScope) {
    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val prompt: StateFlow<PairingPrompt?> = _prompt.asStateFlow()

    private var waiting: CompletableDeferred<Boolean>? = null

    init {
        // A pairing cannot outlive its link.
        scope.launch {
            session.connection.collect { if (it !is ConnectionState.Connected) linkLost() }
        }
    }

    /**
     * Runs [block] on the bonded link, pairing first if needed. Throws [PairingCancelled]
     * if the user backs out, DeviceError.PairingFailed if the link dropped after Pair was
     * tapped, else whatever [block] throws.
     */
    suspend fun <T> secure(block: suspend () -> T): T {
        val c = session.connection.value
        if (c is ConnectionState.Connected && !c.bonded) awaitPairing(stale = false)
        return try {
            block()
        } catch (e: DeviceError) {
            val needsPairing = e is DeviceError.StaleBond || (e is DeviceError.Att && e.needsPairing)
            if (!needsPairing) throw e
            awaitPairing(stale = e is DeviceError.StaleBond)
            block()
        }
    }

    /** Pairing screen: the user tapped Pair (again). */
    fun pair() {
        if (waiting == null) return
        _prompt.value = PairingPrompt(PairingPrompt.Stage.PAIRING)
        scope.launch {
            try {
                session.ensureBonded()
                _prompt.value = null
                waiting?.complete(true)
                waiting = null
            } catch (e: DeviceError.StaleBond) {
                // Not if the screen was closed meanwhile: nothing would be left to pair for.
                if (waiting != null) _prompt.value = PairingPrompt(PairingPrompt.Stage.STALE_BOND, e.message)
            } catch (e: DeviceError) {
                if (waiting != null) _prompt.value = PairingPrompt(PairingPrompt.Stage.FAILED, e.message)
            }
        }
    }

    /** Pairing screen closed, or the link went away. */
    fun cancel() {
        _prompt.value = null
        waiting?.complete(false)
        waiting = null
    }

    // Android drops the link after a cancelled pairing dialog (BlueZ after a wrong code
    // too): once Pair was tapped, the action fails visibly instead of vanishing.
    private fun linkLost() {
        val stage = _prompt.value?.stage
        if (stage != PairingPrompt.Stage.PAIRING && stage != PairingPrompt.Stage.FAILED) return cancel()
        _prompt.value = null
        waiting?.completeExceptionally(DeviceError.PairingFailed())
        waiting = null
    }

    private suspend fun awaitPairing(stale: Boolean) {
        val wait = waiting ?: CompletableDeferred<Boolean>().also {
            waiting = it
            _prompt.value = PairingPrompt(if (stale) PairingPrompt.Stage.STALE_BOND else PairingPrompt.Stage.EXPLAIN)
        }
        if (!wait.await()) throw PairingCancelled()
    }
}

/**
 * Runs a secure action for a screen: null if it failed (the error went to [onError]) or the
 * user cancelled pairing.
 */
suspend fun <T> PairingGate.attempt(onError: (UiMessage) -> Unit, block: suspend () -> T): T? = try {
    secure(block)
} catch (_: PairingCancelled) {
    null
} catch (e: DeviceError) {
    onError(UiMessage(e.message ?: "Error"))
    null
}

/**
 * A screen's command button: busy while it runs, the previous message cleared, errors to
 * [show]. Null if it failed or the user cancelled pairing.
 */
suspend fun PairingGate.runCommand(
    command: Command,
    setBusy: (Boolean) -> Unit,
    show: (UiMessage?) -> Unit,
): Unit? {
    setBusy(true)
    show(null)
    return try {
        attempt(show) { session.sendCommand(command) }
    } finally {
        setBusy(false)
    }
}
