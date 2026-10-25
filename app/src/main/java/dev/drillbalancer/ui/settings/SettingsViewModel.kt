package dev.drillbalancer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.attempt
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val base: DeviceSettings? = null,       // as last read from or written to the device
    val edited: DeviceSettings? = null,     // what the screen shows
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: UiMessage? = null,
) {
    /** Only the fields the user changed: the device merges a write onto what it has. */
    val update: SettingsUpdate? get() = if (base != null && edited != null) SettingsUpdate.diff(base, edited) else null
    val changed: Set<SettingsField> get() = update?.fields.orEmpty()
    val invalid: Set<SettingsField> get() = edited?.invalidFields().orEmpty()
    val canSave: Boolean get() = changed.isNotEmpty() && invalid.isEmpty() && !saving && !loading
}

class SettingsViewModel(private val session: DeviceSession, private val gate: PairingGate) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(base = session.settings.value, edited = session.settings.value))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Changes from elsewhere (mode tab, device screen) show up; the user's edits stay.
        viewModelScope.launch {
            session.settings.collect { s -> if (s != null) rebase(s) }
        }
        reload()
    }

    fun step(field: SettingsField, dir: Int) = edit { it.step(field, dir) }

    /** A typed value, already checked with entryError(). */
    fun set(field: SettingsField, value: Float) = edit { it.with(field, value) }

    fun setMode(mode: LevelMode) = edit { it.copy(mode = mode) }

    fun setBle(enabled: Boolean) = edit { it.copy(bleEnabled = enabled) }

    fun discard() = _state.update { it.copy(edited = it.base, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * Reads the settings again. The config is not notified, so this is how changes made on
     * the device show up; the user's edits stay on top of them.
     */
    fun reload() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val s = session.readSettings()
                _state.update { it.copy(loading = false) }
                rebase(s)
            } catch (e: DeviceError) {
                _state.update { it.copy(loading = false, message = UiMessage(e.message ?: "Could not read the settings.")) }
            }
        }
    }

    fun save() {
        val now = _state.value
        val update = now.update ?: return
        val sent = now.edited ?: return
        if (!now.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, message = null) }
            var conflict = false
            val stored = try {
                gate.attempt({ m -> _state.update { it.copy(message = m) } }) {
                    try {
                        session.writeSettings(update)
                    } catch (e: DeviceError.Att) {
                        if (e.code == AttError.JSON_RANGE.code) conflict = true
                        throw e
                    }
                }
            } finally {
                _state.update { it.copy(saving = false) }
            }
            if (stored != null) {
                // Edits made while saving stay.
                _state.update { s ->
                    val edited = SettingsUpdate.diff(sent, s.edited ?: sent).applyTo(stored).fixup()
                    s.copy(base = stored, edited = edited, message = UiMessage("Saved.", UiMessage.Kind.SUCCESS))
                }
            } else if (conflict) {
                // Most likely changed on the device since the last read: read what it has now.
                // The edits stay on top, and fixup() adds the field the rule needs.
                reload()
            }
        }
    }

    private fun edit(change: (DeviceSettings) -> DeviceSettings) = _state.update { s ->
        val e = s.edited ?: return@update s
        // A success message is stale once anything changes again.
        val keep = s.message?.takeIf { it.kind != UiMessage.Kind.SUCCESS }
        s.copy(edited = change(e).fixup(), message = keep)
    }

    // The device's settings changed: keep the user's edits on top of the new values.
    private fun rebase(device: DeviceSettings) = _state.update { s ->
        val base = s.base
        val edited = s.edited
        if (base == null || edited == null) return@update s.copy(base = device, edited = device)
        if (base == device) return@update s
        s.copy(base = device, edited = SettingsUpdate.diff(base, edited).applyTo(device).fixup())
    }
}
