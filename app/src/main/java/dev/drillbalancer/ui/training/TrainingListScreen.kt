package dev.drillbalancer.ui.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.formatBytes
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

class TrainingListActions(
    val newRecording: () -> Unit = {},
    val openSession: () -> Unit = {},
    val reload: () -> Unit = {},
)

/** The recordings on the phone, and the way to a new one. Works without a device. */
@Composable
fun TrainingListScreen(state: TrainingListUiState, actions: TrainingListActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { MessageBanner(UiMessage(it), actionLabel = "Try again", onAction = actions.reload) }
        if (state.session.active) {
            MessageBanner(
                UiMessage(
                    if (state.session == RecordPhase.RECORDING) "A take is being recorded." else "A recording session is open.",
                    UiMessage.Kind.INFO,
                ),
                actionLabel = "Open", onAction = actions.openSession,
            )
        } else {
            ActionButton("New recording", actions.newRecording, Modifier.fillMaxWidth(), enabled = state.recordBlocker == null)
            state.recordBlocker?.let { Hint(it) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("On this phone", Modifier.weight(1f))
            if (!state.loading) {
                Text(
                    buildString {
                        append(formatBytes(state.usedBytes))
                        state.freeBytes?.let { append(" · ").append(formatBytes(it)).append(" free") }
                    },
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.rows.isEmpty() -> Panel {
                Text("No recordings yet.", style = MaterialTheme.typography.titleMedium)
                Hint(
                    "A recording is one take: the drill's motion and sound while you drill, with the material and bit " +
                        "you set before. Takes stay on the phone until they are reviewed and uploaded.",
                )
            }
            else -> for (row in state.rows) RecordingRowItem(row)
        }
    }
}

@Composable
private fun RecordingRowItem(row: RecordingRow) {
    Panel(Modifier.heightIn(min = 72.dp), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spacing = 6.dp) {
        Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(row.stage.label, stageColor(row.stage))
            Badge(row.upload.label, Grey)
        }
    }
}

private fun stageColor(s: LocalStage): Color = when (s) {
    LocalStage.RECORDING -> Red
    LocalStage.INTERRUPTED -> Amber
    LocalStage.NEEDS_REVIEW -> Blue
    LocalStage.SAVED -> Green
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, color)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge, color = color)
    }
}
