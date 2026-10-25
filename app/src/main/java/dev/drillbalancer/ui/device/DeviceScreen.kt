package dev.drillbalancer.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.PowerState
import dev.drillbalancer.ui.LOW_BATTERY_PCT
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.InfoRow
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.displayBatteryPct
import dev.drillbalancer.ui.formatFixed
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

class DeviceActions(
    val send: (Command) -> Unit = {},
    val disconnect: () -> Unit = {},
    val openFirmwareUpdate: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/** The power commands that need a confirmation, with its text. */
private enum class Confirm(val command: Command, val title: String, val text: String, val button: String) {
    SLEEP(Command.Sleep, "Put to sleep?", "The connection ends. Move the device to wake it up.", "Sleep"),
    POWER_OFF(Command.PowerOff, "Power off?", "The connection ends. Press the PWR key to switch it on again.", "Power off"),
    REBOOT(Command.Reboot, "Reboot?", "The device restarts; the app reconnects by itself.", "Reboot"),
}

@Composable
fun DeviceScreen(state: DeviceUiState, actions: DeviceActions, modifier: Modifier = Modifier) {
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { MessageBanner(it, onDismiss = actions.dismissMessage) }

        SectionTitle("Device")
        Panel {
            InfoRow("Name", state.name)
            InfoRow("Address", state.address)
            InfoRow("Paired", if (state.bonded) "Yes" else "No", valueColor = if (state.bonded) Green else Amber)
            state.info?.let {
                InfoRow("Model", it.model)
                InfoRow("Maker", it.manufacturer)
                InfoRow("Firmware", it.firmwareRevision)
            }
        }

        SectionTitle("Battery")
        Panel {
            val st = state.status
            val pct = displayBatteryPct(st, state.batteryPct)
            InfoRow("Charge", pct?.let { "$it %" } ?: "Unknown", valueColor = if (pct != null && pct < LOW_BATTERY_PCT) Red else Color.Unspecified)
            if (st != null && st.batteryValid) InfoRow("Voltage", formatFixed(st.batteryMv / 1000f, 2, suffix = " V"))
            if (st != null) {
                InfoRow(
                    "Charging",
                    when {
                        st.chargeDone -> "Charged"
                        st.charging -> "Charging"
                        st.vbus -> "USB connected"
                        else -> "No"
                    },
                )
                InfoRow("Power state", st.powerState.title)
            }
        }

        SectionTitle("Firmware")
        ActionButton("Firmware update", actions.openFirmwareUpdate, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)

        SectionTitle("Power")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Sleep", { confirm = Confirm.SLEEP }, Modifier.weight(1f), !state.busy, ButtonKind.SECONDARY)
            ActionButton("Reboot", { confirm = Confirm.REBOOT }, Modifier.weight(1f), !state.busy, ButtonKind.SECONDARY)
        }
        ActionButton("Power off", { confirm = Confirm.POWER_OFF }, Modifier.fillMaxWidth(), !state.busy, ButtonKind.SECONDARY)
        ActionButton("Disconnect", actions.disconnect, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)

        SectionTitle("Reset")
        Text(
            "Factory reset deletes all settings, both calibrations and all pairings on the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionButton("Factory reset", { reset = true }, Modifier.fillMaxWidth(), !state.busy, ButtonKind.DANGER)
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton({
                    confirm = null
                    actions.send(c.command)
                }, Modifier.heightIn(min = TouchHeight)) { Text(c.button) }
            },
            dismissButton = { TextButton({ confirm = null }, Modifier.heightIn(min = TouchHeight)) { Text("Cancel") } },
        )
    }
    if (reset) {
        FactoryResetDialog(
            onConfirm = {
                reset = false
                actions.send(Command.FactoryReset)
            },
            onDismiss = { reset = false },
        )
    }
}

/** Typed confirmation: the reset cannot be undone and breaks the phone's pairing. */
@Composable
fun FactoryResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Factory reset?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "All settings and both calibrations are lost, and the device forgets every phone it was paired with.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Afterwards, remove the DrillBalancer in this phone's Bluetooth settings, then pair again.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Amber,
                )
                OutlinedTextField(
                    typed, { typed = it },
                    label = { Text("Type $RESET_WORD to confirm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
                )
            }
        },
        confirmButton = {
            TextButton(
                onConfirm, Modifier.heightIn(min = TouchHeight),
                enabled = typed.trim().equals(RESET_WORD, ignoreCase = true),
            ) { Text("Reset", color = if (typed.trim().equals(RESET_WORD, true)) Red else Color.Unspecified) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Cancel") } },
    )
}

const val RESET_WORD = "RESET"

private val PowerState.title: String
    get() = when (this) {
        PowerState.ACTIVE -> "Active"
        PowerState.DIM -> "Dimmed"
        PowerState.SLEEP -> "Asleep"
        PowerState.OFF -> "Off"
    }
