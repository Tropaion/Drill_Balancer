package dev.drillbalancer.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.update.NoteBlock

/** An update notice, from its offer to a failed download; What's new opens the release's notes. */
@Composable
fun UpdateBanner(
    notice: UpdateNotice,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit = {},
) {
    val name = "${notice.kind.label} ${notice.version}"
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val hasNotes = notice.notes.isNotEmpty()
    // The message and its button (label, tap); a stage with a button can also be dismissed.
    val (message, action) = when (val stage = notice.stage) {
        UpdateStage.Available -> UiMessage("$name available", UiMessage.Kind.INFO) to ("Download" to onDownload)
        is UpdateStage.Downloading ->
            UiMessage("Downloading ${name.replaceFirstChar(Char::lowercase)}: ${stage.percent} %", UiMessage.Kind.INFO) to null
        UpdateStage.Ready -> UiMessage("$name downloaded", UiMessage.Kind.SUCCESS) to ("Install" to onInstall)
        is UpdateStage.Failed -> UiMessage(stage.message) to ("Retry" to onDownload)
    }
    MessageBanner(
        message, modifier, onDismiss.takeIf { action != null }, action?.first, action?.second,
        leading = if (hasNotes) ({ TextButton({ notesOpen = true }, Modifier.heightIn(min = TouchHeight)) { Text("What's new") } }) else null,
    )
    if (notesOpen && hasNotes) ReleaseNotesDialog(name, notice.notes) { notesOpen = false }
}

/** A release's notes under its name ([title]), as plain text that scrolls. */
@Composable
fun ReleaseNotesDialog(title: String, notes: List<NoteBlock>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (block in notes) {
                        when (block) {
                            is NoteBlock.Heading -> SectionTitle(block.text)
                            is NoteBlock.Bullet -> Row {
                                Text(block.marker, Modifier.width(28.dp))
                                Text(block.text)
                            }
                            is NoteBlock.Paragraph -> Text(block.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Close") } },
    )
}
