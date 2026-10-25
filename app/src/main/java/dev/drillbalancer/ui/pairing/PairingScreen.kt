package dev.drillbalancer.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.drillbalancer.ui.PairingPrompt
import dev.drillbalancer.ui.PairingPrompt.Stage
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.theme.Blue

/**
 * Shown while an action waits for the bonded link. Explains the passkey before anything is
 * sent; Pair starts ensureBonded(), which brings up Android's pairing dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    prompt: PairingPrompt,
    deviceName: String,
    onPair: () -> Unit,
    onCancel: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Pairing") },
                navigationIcon = {
                    IconButton(onCancel, Modifier.size(56.dp)) { Icon(DbIcons.Back, contentDescription = "Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(DbIcons.Bluetooth, contentDescription = null, tint = Blue, modifier = Modifier.size(40.dp))
                Text(
                    if (prompt.stage == Stage.STALE_BOND) "Pair again" else "Pair with $deviceName",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            when (prompt.stage) {
                Stage.EXPLAIN, Stage.FAILED -> Explain(prompt, onPair, onCancel)
                Stage.PAIRING -> Waiting(onCancel)
                Stage.STALE_BOND -> StaleBond(deviceName, onOpenBluetoothSettings, onPair, onCancel)
            }
        }
    }
}

@Composable
private fun Explain(prompt: PairingPrompt, onPair: () -> Unit, onCancel: () -> Unit) {
    Text(
        "Changing settings, zeroing and calibrating need a secure connection. Pair once; the phone " +
            "remembers the DrillBalancer.",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (prompt.stage == Stage.FAILED) {
        MessageBanner(UiMessage(prompt.error ?: "Pairing failed."))
    }
    Panel {
        Step(1, "Tap Pair.")
        Step(2, "The DrillBalancer shows a 6-digit code on its screen.")
        Step(3, "Enter the 6-digit code shown on the DrillBalancer screen in the Android pairing dialog.")
    }
    ActionButton(if (prompt.stage == Stage.FAILED) "Try again" else "Pair", onPair, Modifier.fillMaxWidth())
    ActionButton("Not now", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
}

@Composable
private fun Waiting(onCancel: () -> Unit) {
    Panel(border = Blue) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Blue)
            Text("Waiting for pairing…", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Enter the 6-digit code shown on the DrillBalancer screen in the Android pairing dialog.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "No dialog? Look for a pairing notification at the top of the phone's screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ActionButton("Cancel", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
}

@Composable
private fun StaleBond(deviceName: String, onOpenSettings: () -> Unit, onPair: () -> Unit, onCancel: () -> Unit) {
    MessageBanner(
        UiMessage(
            "The DrillBalancer no longer knows this phone, for example after a factory reset. " +
                "The phone's old pairing has to be removed first.",
            UiMessage.Kind.WARNING,
        ),
    )
    Panel {
        Step(1, "Open the phone's Bluetooth settings.")
        Step(2, "Find $deviceName under paired devices and choose Forget (or Unpair).")
        Step(3, "Come back here and tap Pair again.")
    }
    ActionButton("Open Bluetooth settings", onOpenSettings, Modifier.fillMaxWidth())
    ActionButton("Pair again", onPair, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    ActionButton("Not now", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "$n", Modifier.widthIn(min = 20.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Blue,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
