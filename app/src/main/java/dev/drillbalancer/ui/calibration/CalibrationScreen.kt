package dev.drillbalancer.ui.calibration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.FitNumber
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

class CalibrationActions(
    val startSensor: () -> Unit = {},
    val startMount: () -> Unit = {},
    val finish: () -> Unit = {},
    val cancel: () -> Unit = {},
    val done: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

@Composable
fun CalibrationScreen(state: CalibrationUiState, actions: CalibrationActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.message?.let { MessageBanner(it, onDismiss = actions.dismissMessage) }
        val finished = state.finished
        when {
            !state.known -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.running == CalKind.ACCEL -> SensorRunning(state, actions)
            state.running == CalKind.MOUNT -> MountRunning(state, actions)
            finished != null -> Result(finished, state.busy, actions)
            else -> Overview(state, actions)
        }
    }
}

@Composable
private fun Overview(state: CalibrationUiState, actions: CalibrationActions) {
    Text(
        "Calibrate the sensor first, then the mount. A new sensor calibration resets the mount calibration.",
        style = MaterialTheme.typography.bodyLarge,
    )
    CalCard(
        number = 1,
        title = "Sensor calibration",
        done = state.sensorDone,
        text = "Corrects the motion sensor. Take the device off the drill and put it on a flat, steady " +
            "surface with each of its six sides facing up in turn.",
        button = "Start sensor calibration",
        enabled = !state.busy,
        onStart = actions.startSensor,
    )
    CalCard(
        number = 2,
        title = "Mount calibration",
        done = state.mountDone,
        text = "Measures how the device sits on the drill. Put the bit into a drilled hole, horizontal " +
            "or tilted but not vertical, then tap Start and roll the drill slowly around the bit, at " +
            "least 45° each way, keeping the bit in the hole.",
        button = "Start mount calibration",
        enabled = !state.busy,
        onStart = actions.startMount,
        warning = if (!state.sensorDone) "Do the sensor calibration first for the best result." else null,
    )
    Text(
        "A calibration started from the app is cancelled if the Bluetooth connection drops. Keep the phone close.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CalCard(
    number: Int,
    title: String,
    done: Boolean,
    text: String,
    button: String,
    enabled: Boolean,
    onStart: () -> Unit,
    warning: String? = null,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$number", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (done) Icon(DbIcons.Check, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
            Text(
                if (done) "Calibrated" else "Not calibrated",
                style = MaterialTheme.typography.labelLarge,
                color = if (done) Green else Amber,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (warning != null) Text(warning, style = MaterialTheme.typography.bodyMedium, color = Amber)
        ActionButton(button, onStart, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = enabled)
    }
}

@Composable
private fun SensorRunning(state: CalibrationUiState, actions: CalibrationActions) {
    Title("Sensor calibration", "Hold still in each position")
    Text(
        "Put the device on a flat, steady surface, off the drill. Turn it so each side faces up in turn and " +
            "hold it still for about a second until its tile turns green. For screen down, turn it back over " +
            "to check.",
        style = MaterialTheme.typography.bodyLarge,
    )
    // Same tiles and order as the device (ui_poses)
    CoverageDir.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { dir -> PoseTile(dir.label, dir in state.coverage, Modifier.weight(1f)) }
        }
    }
    Text(
        "${state.coverage.size} / 6 sides · ${samplesText(state.samples)} ${if (state.samples == 1) "pose" else "poses"} kept",
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = Grey,
    )
    RunningButtons(state, finishEnabled = state.coverageComplete, actions)
    if (!state.coverageComplete) {
        Text(
            "Finish becomes available once all six sides are captured.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PoseTile(label: String, covered: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier
            .heightIn(min = 64.dp)
            .semantics { stateDescription = if (covered) "captured" else "not captured" },
        shape = RoundedCornerShape(16.dp),
        color = if (covered) Green else MaterialTheme.colorScheme.surface,
        border = if (covered) null else BorderStroke(1.5.dp, Grey),
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                color = if (covered) Color.Black else Color.White,
            )
        }
    }
}

@Composable
private fun MountRunning(state: CalibrationUiState, actions: CalibrationActions) {
    Title("Mount calibration", "Bit in a horizontal or tilted hole")
    Panel {
        Text(
            "Roll the drill slowly back and forth, at least 45° each way. Keep the bit in the hole.",
            style = MaterialTheme.typography.headlineSmall,
        )
    }
    FitNumber(samplesText(state.samples), Color.White, Modifier.fillMaxWidth(), maxSize = 88.sp, template = "888+")
    Text(
        "samples", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center, color = Grey,
    )
    RunningButtons(state, finishEnabled = true, actions)
    Text(
        "The first second after Start and the last half second before Finish are not used, so tapping does not " +
            "disturb the result.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RunningButtons(state: CalibrationUiState, finishEnabled: Boolean, actions: CalibrationActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(
            "Cancel", actions.cancel, Modifier.weight(1f),
            enabled = !state.busy && !state.finishing, kind = ButtonKind.SECONDARY,
        )
        ActionButton(
            if (state.finishing) "Solving…" else "Finish", actions.finish, Modifier.weight(1f),
            enabled = finishEnabled && !state.busy && !state.finishing,
        )
    }
}

@Composable
private fun Result(finished: FinishedCal, busy: Boolean, actions: CalibrationActions) {
    val sensor = finished.kind == CalKind.ACCEL
    val ok = finished.result.success
    Title(if (sensor) "Sensor calibration" else "Mount calibration", if (ok) "Done" else "Not calibrated")
    Panel(border = if (ok) Green else Red) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (ok) DbIcons.Check else DbIcons.Warning, contentDescription = null,
                tint = if (ok) Green else Red, modifier = Modifier.size(32.dp),
            )
            Text(finished.result.message, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        if (finished.result == CalResult.SensorOkMountReset) {
            Text(
                "The mount calibration was measured with the old sensor correction and has been reset.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    when {
        sensor && ok -> {
            ActionButton("Start mount calibration", actions.startMount, Modifier.fillMaxWidth(), enabled = !busy)
            ActionButton("Done", actions.done, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
        ok -> ActionButton("Done", actions.done, Modifier.fillMaxWidth())
        else -> {
            ActionButton(
                "Try again", if (sensor) actions.startSensor else actions.startMount, Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            ActionButton("Done", actions.done, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = Grey)
    }
}

// The status packet's count saturates at 255.
private fun samplesText(n: Int) = if (n >= 255) "255+" else "$n"
