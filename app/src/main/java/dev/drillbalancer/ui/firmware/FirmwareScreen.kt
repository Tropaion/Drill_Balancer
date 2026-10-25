package dev.drillbalancer.ui.firmware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.BusyPanel
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.ConfirmDialog
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.InfoRow
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.components.numberStyle
import dev.drillbalancer.ui.formatBytes
import dev.drillbalancer.ui.firmware.FirmwareUiState.Outcome
import dev.drillbalancer.ui.formatMinSec
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Red

class FirmwareActions(
    val pick: () -> Unit = {},
    val start: () -> Unit = {},
    val cancel: () -> Unit = {},
    val done: () -> Unit = {},
)

@Composable
fun FirmwareScreen(state: FirmwareUiState, actions: FirmwareActions, modifier: Modifier = Modifier) {
    var askCancel by rememberSaveable { mutableStateOf(false) }
    val transferring = state.phase == FirmwareUiState.Phase.TRANSFERRING
    // Leaving would abort the update: ask first. While the device verifies there is nothing
    // left to stop, and leaving would still cancel the transfer: Back does nothing then.
    BackHandler(enabled = transferring) { if (state.canCancel) askCancel = true }
    LaunchedEffect(state.canCancel) { if (!state.canCancel) askCancel = false }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel {
            InfoRow("Device runs", state.deviceVersion ?: "Unknown")
        }
        when (state.phase) {
            FirmwareUiState.Phase.IDLE, FirmwareUiState.Phase.FAILED -> {
                state.error?.let { ErrorPanel(it) }
                FilePanel(state, actions)
            }
            FirmwareUiState.Phase.TRANSFERRING -> Transfer(state, onCancel = { askCancel = true })
            FirmwareUiState.Phase.REBOOTING -> Rebooting(state.unconfirmed)
            FirmwareUiState.Phase.DONE -> Done(state, actions.done)
        }
    }

    if (askCancel) {
        ConfirmDialog(
            "Stop the update?", "The device keeps its current firmware.", "Stop update",
            onConfirm = actions.cancel, onDismiss = { askCancel = false },
            confirmColor = Red, dismissLabel = "Continue",
        )
    }
}

@Composable
private fun FilePanel(state: FirmwareUiState, actions: FirmwareActions) {
    val image = state.image
    if (image == null && state.fileError == null) {
        Text(
            "Choose the firmware file from a DrillBalancer build (build/drillbalancer.bin). The app checks it " +
                "before anything is sent.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    state.fileError?.let {
        MessageBanner(UiMessage("${state.fileName ?: "This file"}: $it\nThe device would reject it, so it cannot be installed."))
    }
    if (image != null) ImageInfo(state.fileName, image)
    if (image != null && state.sameVersion) {
        MessageBanner(UiMessage("The device already runs version ${image.version}.", UiMessage.Kind.WARNING))
    }
    if (image != null) {
        ActionButton(
            if (state.phase == FirmwareUiState.Phase.FAILED) "Try again" else "Install ${image.version}",
            actions.start, Modifier.fillMaxWidth(), enabled = state.canStart,
        )
    }
    ActionButton(
        if (image == null && state.fileError == null) "Choose file" else "Choose another file",
        actions.pick, Modifier.fillMaxWidth(), enabled = state.canPick,
        kind = if (image == null) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
    )
}

@Composable
private fun ImageInfo(fileName: String?, image: FirmwareImage) {
    Panel {
        Text(fileName ?: "Firmware file", style = MaterialTheme.typography.titleMedium)
        InfoRow("Version", image.version)
        InfoRow("Project", image.projectName)
        InfoRow("Built", "${image.buildDate} ${image.buildTime}")
        InfoRow("ESP-IDF", image.idfVersion)
        InfoRow("Size", formatBytes(image.size.toLong()))
        InfoRow("SHA-256", image.sha256Hex.take(16) + "…")
    }
}

@Composable
private fun Transfer(state: FirmwareUiState, onCancel: () -> Unit) {
    val p = state.progress
    val phase = p?.phase ?: OtaProgress.Phase.STARTING
    Panel(border = Blue) {
        Text(
            when (phase) {
                OtaProgress.Phase.STARTING -> "Starting the update…"
                OtaProgress.Phase.SENDING -> "Sending ${state.image?.version.orEmpty()}"
                OtaProgress.Phase.VERIFYING -> "The device checks the image…"
                OtaProgress.Phase.DONE -> "Finished"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "${p?.percent ?: 0} %", Modifier.fillMaxWidth(),
            style = numberStyle(64.sp), textAlign = TextAlign.Center,
        )
        if (phase == OtaProgress.Phase.STARTING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp), color = Blue, trackColor = Dark)
        } else {
            LinearProgressIndicator(
                progress = { (p?.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = Blue,
                trackColor = Dark,
                drawStopIndicator = {},
            )
        }
        if (p != null) {
            InfoRow("Sent", "${formatBytes(p.bytesDone.toLong())} of ${formatBytes(p.totalBytes.toLong())}")
            if (p.bytesPerSecond > 0f) InfoRow("Speed", "${formatBytes(p.bytesPerSecond.toLong())}/s")
            state.etaSeconds?.let { InfoRow("Time left", formatMinSec(it)) }
        }
    }
    Text(
        "Keep the app open and the phone near the device. The screen stays on until the update is done.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionButton(
        "Cancel", onCancel, Modifier.fillMaxWidth(),
        enabled = state.canCancel, kind = ButtonKind.SECONDARY,
    )
}

@Composable
private fun Rebooting(unconfirmed: Boolean) {
    BusyPanel(
        if (unconfirmed) "Waiting for the device" else "Device is rebooting",
        titleStyle = MaterialTheme.typography.titleLarge,
    ) {
        Text(
            if (unconfirmed) {
                "The connection dropped while the device checked the image. The app reconnects by itself and " +
                    "checks which firmware the device runs."
            } else {
                "The image was checked and installed. The device restarts with it and the app reconnects by itself."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Done(state: FirmwareUiState, onDone: () -> Unit) {
    val v = state.newVersion
    val (border, text) = when (state.outcome) {
        Outcome.UNKNOWN ->
            Blue to "The connection dropped while the device checked the image. Connect again to check its version."
        Outcome.RESTARTED -> Green to "The device restarted with the new firmware. Connect again to check its version."
        Outcome.INSTALLED -> Green to "The device now runs firmware $v."
        // The VM shows NOT_INSTALLED as a failure; listed for completeness
        Outcome.ROLLED_BACK, Outcome.NOT_INSTALLED ->
            Red to "The device reports firmware $v, not ${state.image?.version}. It may have rolled back to the old image."
    }
    val unknown = state.outcome == Outcome.UNKNOWN
    Panel(border = border) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!unknown) Icon(DbIcons.Check, contentDescription = null, tint = Green, modifier = Modifier.size(32.dp))
            Text(if (unknown) "Update not confirmed" else "Update complete", style = MaterialTheme.typography.titleLarge)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
    ActionButton("Done", onDone, Modifier.fillMaxWidth())
}

@Composable
private fun ErrorPanel(error: FirmwareUiState.OtaError) {
    Panel(border = Red) {
        Text(error.title, style = MaterialTheme.typography.titleLarge, color = Red)
        Text(error.text, style = MaterialTheme.typography.bodyLarge)
        Text(
            "The device keeps its current firmware.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
