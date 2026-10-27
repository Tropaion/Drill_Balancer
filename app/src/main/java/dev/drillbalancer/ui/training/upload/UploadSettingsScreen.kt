package dev.drillbalancer.ui.training.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.ConfirmDialog
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.SwitchRow
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.training.Hint
import dev.drillbalancer.upload.UploadCounts

class UploadSettingsActions(
    val editRepo: (String) -> Unit = {},
    val saveRepo: () -> Unit = {},
    val openTokenDialog: () -> Unit = {},
    val closeTokenDialog: () -> Unit = {},
    val setToken: (String) -> Unit = {},
    val removeToken: () -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val setKeepLocal: (Boolean) -> Unit = {},
    val setMuteAudio: (Boolean) -> Unit = {},
    val testConnection: () -> Unit = {},
    val uploadNow: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/** Where token-making on GitHub is, in one line. */
const val TOKEN_HELP = "Create it on GitHub > Settings > Developer settings > Fine-grained tokens: only this repo, Contents read and write."

/**
 * Where reviewed recordings go: the private data repository, the token (write-only: the screen
 * shows only whether one is set), the network and privacy options, Test connection and the queue.
 */
@Composable
fun UploadSettingsScreen(state: UploadSettingsUiState, actions: UploadSettingsActions, modifier: Modifier = Modifier) {
    var confirmRemove by remember { mutableStateOf(false) }
    // until the settings are read: neither the defaults (no token, Wi-Fi only) nor controls that would change them
    if (!state.loaded) {
        Row(
            modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Blue, strokeWidth = 2.dp)
            Text("Reading the upload settings…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { MessageBanner(it, onDismiss = actions.dismissMessage) }
        Hint("Saved reviews are uploaded to a private GitHub repository, one commit per recording.")

        SectionTitle("Repository")
        Panel {
            OutlinedTextField(
                value = state.repoText,
                onValueChange = actions.editRepo,
                label = { Text("Owner/name") },
                singleLine = true,
                isError = state.repoError != null,
                supportingText = state.repoError?.let { e -> { Text(e) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
            )
            if (state.repoChanged) ActionButton("Save repository", actions.saveRepo, Modifier.fillMaxWidth())
        }

        SectionTitle("Token")
        Panel(border = if (state.token.rejected) Red else Dark) {
            val (text, color) = when {
                !state.token.set -> "No token set" to Grey
                state.token.rejected -> "Token rejected by GitHub. Replace it." to Red
                else -> "Token set" to Green
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = color)
            // how to make one, until one is set (the dialog shows it again for Replace)
            if (!state.token.set || state.token.rejected) Hint(TOKEN_HELP)
            if (!state.token.set) {
                ActionButton("Set token", actions.openTokenDialog, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Replace", actions.openTokenDialog, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                    ActionButton("Remove", { confirmRemove = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                }
            }
        }

        ActionButton("Test connection", actions.testConnection, Modifier.fillMaxWidth(), enabled = state.canTest, kind = ButtonKind.SECONDARY)
        if (state.repoChanged) Hint("Save the repository first: the test asks GitHub about the saved one.")
        TestResult(state.test)

        SectionTitle("Options")
        SwitchPanel("Wi-Fi only", "Uploads wait for Wi-Fi (no mobile data).", state.settings.wifiOnly, actions.setWifiOnly)
        SwitchPanel(
            "Keep local copies",
            "Off: a take's raw stream is deleted after its upload. The dataset files stay, so its labels can still be edited.",
            state.settings.keepLocal, actions.setKeepLocal,
        )
        SwitchPanel(
            "Mute audio while the motor is off",
            "Silences the sound before the motor starts and after it has stopped (voices), on the phone too. " +
                "Muted sound cannot be brought back.",
            state.settings.muteAudio, actions.setMuteAudio,
        )

        SectionTitle("Queue")
        Panel {
            val c = state.counts ?: UploadCounts()
            Text(
                "${c.queued + c.notQueued} waiting · ${c.uploading} uploading · ${c.failed} failed · ${c.done} uploaded",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (c.needsReview > 0) Hint(if (c.needsReview == 1) "1 recording needs its review first." else "${c.needsReview} recordings need their review first.")
            ActionButton("Upload now", actions.uploadNow, Modifier.fillMaxWidth(), enabled = state.canUploadNow)
            if (state.token.rejected) {
                Hint("Replace the token first: GitHub rejected it.")
            } else if (state.nothingToSend) {
                Hint("Nothing waits to be sent.")
            }
        }
    }
    if (state.tokenDialog) TokenDialog(state.token.set, state.tokenError, actions.setToken, actions.closeTokenDialog)
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove the token?",
            text = "Uploads stop until a token is set again. The token itself stays valid on GitHub; revoke it there if you no longer need it.",
            confirmLabel = "Remove",
            confirmColor = Red,
            onConfirm = actions.removeToken,
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun TestResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Panel(border = Blue) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Blue, strokeWidth = 2.dp)
                Text("Asking GitHub…", style = MaterialTheme.typography.bodyLarge)
            }
        }
        is ConnectionTest.Done -> Panel(border = if (test.warnings.isEmpty()) Green else Amber) {
            Text(if (test.warnings.isEmpty()) "Ready to upload" else "Connected, with a problem", style = MaterialTheme.typography.titleMedium)
            for (g in test.good) Line(g, Green, ok = true)
            for (w in test.warnings) Line(w, Amber, ok = false)
        }
        is ConnectionTest.Failed -> Panel(border = Red) {
            Text("Test failed", style = MaterialTheme.typography.titleMedium)
            Line(test.message, Red, ok = false)
        }
    }
}

@Composable
private fun Line(text: String, color: Color, ok: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (ok) DbIcons.Check else DbIcons.Warning, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A setting with its switch in a panel (SwitchRow: title, hint, on/off). */
@Composable
private fun SwitchPanel(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Panel {
        SwitchRow(checked, onChange, Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(hint)
            }
        }
    }
}

/**
 * The token, typed or pasted: hidden as it is typed, kept in this dialog only (remember, not
 * saveable: never in the saved instance state) until Set hands it to the vault.
 */
@Composable
private fun TokenDialog(replace: Boolean, error: String?, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replace) "Replace the token" else "Set the token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(TOKEN_HELP, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Token") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
                )
            }
        },
        confirmButton = {
            TextButton({ onSet(text) }, Modifier.heightIn(min = TouchHeight), enabled = text.isNotBlank()) { Text("Set") }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Cancel") } },
    )
}
