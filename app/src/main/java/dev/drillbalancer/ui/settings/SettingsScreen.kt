package dev.drillbalancer.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.InfoRow
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

class SettingsActions(
    val step: (SettingsField, Int) -> Unit = { _, _ -> },
    val set: (SettingsField, Float) -> Unit = { _, _ -> },
    val setMode: (LevelMode) -> Unit = {},
    val setBle: (Boolean) -> Unit = {},
    val save: () -> Unit = {},
    val discard: () -> Unit = {},
    val reload: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val s = state.edited
    var entry by rememberSaveable { mutableStateOf<SettingsField?>(null) }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { MessageBanner(it, onDismiss = actions.dismissMessage) }
            if (s == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) CircularProgressIndicator() else Text("No settings read yet.")
                }
                return@Column
            }
            SectionTitle("Level")
            for (f in listOf(SettingsField.TOL, SettingsField.NEAR, SettingsField.TARGET)) {
                NumberRow(f, s, state, actions) { entry = f }
            }
            ModeRow(s.mode, SettingsField.MODE in state.changed, actions.setMode)
            NumberRow(SettingsField.STALE_S, s, state, actions) { entry = SettingsField.STALE_S }

            SectionTitle("Display and power")
            for (f in listOf(
                SettingsField.BRIGHT, SettingsField.DIM_BRIGHT, SettingsField.DIM_S, SettingsField.SLEEP_S,
                SettingsField.OFF_MIN,
            )) {
                NumberRow(f, s, state, actions) { entry = f }
            }

            SectionTitle("Connection")
            BleRow(s.bleEnabled, SettingsField.BLE in state.changed, actions.setBle)

            SectionTitle("Calibration (read-only)")
            Panel {
                InfoRow("Sensor", if (s.cal.accel) "Calibrated" else "Not calibrated", valueColor = if (s.cal.accel) Green else Amber)
                InfoRow("Mount", if (s.cal.mount) "Calibrated" else "Not calibrated", valueColor = if (s.cal.mount) Green else Amber)
                InfoRow("Settings version", "${s.schemaVersion}")
            }
        }
        Bar(state, actions)
    }

    val field = entry
    if (field != null && s != null) {
        ValueEntryDialog(
            field, s,
            onConfirm = { v ->
                actions.set(field, v)
                entry = null
            },
            onDismiss = { entry = null },
        )
    }
}

@Composable
private fun Bar(state: SettingsUiState, actions: SettingsActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, Dark)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val n = state.changed.size
            if (n > 0) {
                ActionButton("Discard", actions.discard, Modifier.weight(1f), enabled = !state.saving, kind = ButtonKind.SECONDARY)
            } else {
                ActionButton("Reload", actions.reload, Modifier.weight(1f), enabled = !state.loading, kind = ButtonKind.SECONDARY)
            }
            ActionButton(
                when {
                    state.saving -> "Saving…"
                    n > 0 -> "Save ($n)"
                    else -> "Save"
                },
                actions.save, Modifier.weight(1f), enabled = state.canSave,
            )
        }
    }
}

@Composable
private fun NumberRow(
    field: SettingsField,
    s: DeviceSettings,
    state: SettingsUiState,
    actions: SettingsActions,
    onEdit: () -> Unit,
) {
    val changed = field in state.changed
    val invalid = field in state.invalid
    val range = s.range(field)
    Panel(border = if (invalid) Red else if (changed) Amber else Dark) {
        Label(field.label, changed)
        if (field.help.isNotEmpty()) {
            Text(field.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", "Decrease ${field.label}", enabled = !state.saving) { actions.step(field, -1) }
            // Tap the value to type an exact one
            Surface(
                onClick = onEdit,
                enabled = !state.saving,
                modifier = Modifier.weight(1f).heightIn(min = TouchHeight),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Grey),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        s.display(field),
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (invalid) Red else Color.Unspecified,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            StepButton("+", "Increase ${field.label}", enabled = !state.saving) { actions.step(field, 1) }
        }
        if (invalid) {
            Text(
                "Out of range: ${rangeText(field, range)}",
                style = MaterialTheme.typography.bodyMedium, color = Red,
            )
        }
    }
}

@Composable
private fun Label(text: String, changed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
        if (changed) Text("changed", style = MaterialTheme.typography.labelMedium, color = Amber)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.size(TouchHeight).semantics { contentDescription = description }, enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Grey),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeRow(mode: LevelMode, changed: Boolean, onMode: (LevelMode) -> Unit) {
    Panel(border = if (changed) Amber else Dark) {
        Label(SettingsField.MODE.label, changed)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LevelMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, LevelMode.entries.size),
                    modifier = Modifier.heightIn(min = TouchHeight),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = Color.Black,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Text(m.key.replaceFirstChar { it.uppercase() }, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BleRow(enabled: Boolean, changed: Boolean, onChange: (Boolean) -> Unit) {
    Panel(border = if (changed) Amber else Dark) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(SettingsField.BLE.label, changed)
                Text(
                    "Lets this app connect.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                enabled, onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    uncheckedThumbColor = Grey,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                    uncheckedBorderColor = Grey,
                ),
            )
        }
        if (!enabled) {
            Text(
                "Saving this disconnects the app. To connect again, turn Bluetooth on in the device's own settings.",
                style = MaterialTheme.typography.bodyMedium, color = Amber,
            )
        }
    }
}

/** Typing an exact value; the range shown is what the firmware accepts right now. */
@Composable
fun ValueEntryDialog(
    field: SettingsField,
    settings: DeviceSettings,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
    initialText: String? = null,
) {
    // Saveable like the open dialog itself: a rotation must not put back the stored value.
    var text by rememberSaveable(field) { mutableStateOf(initialText ?: plainValue(field, settings.rawValue(field))) }
    val error = settings.entryError(field, text)
    val value = parseEntry(text)
    // What fixup() would also change, so nothing moves unannounced.
    val effects = if (error == null && value != null) {
        val next = settings.with(field, value)
        NUMERIC_FIELDS.filter { it != field && next.rawValue(it) != settings.rawValue(it) }
            .joinToString("\n") { "${it.label} becomes ${next.display(it)}." }
    } else {
        ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    suffix = { Text(field.unit) },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.isInteger) KeyboardType.Number else KeyboardType.Decimal,
                    ),
                    supportingText = { Text(error ?: "Range ${rangeText(field, settings.range(field))}") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
                )
                if (effects.isNotEmpty()) Text(effects, style = MaterialTheme.typography.bodyMedium, color = Amber)
            }
        },
        confirmButton = {
            TextButton({ value?.let(onConfirm) }, Modifier.heightIn(min = TouchHeight), enabled = error == null) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Cancel") }
        },
    )
}
