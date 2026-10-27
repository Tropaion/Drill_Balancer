package dev.drillbalancer.ui.training.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelTaxonomy
import dev.drillbalancer.recorder.label.Phase
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.BusyPanel
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.StepButton
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.training.Hint
import dev.drillbalancer.ui.training.ScreenWithBar

class ReviewActions(
    val tapMarker: (id: String) -> Unit = {},
    val drag: (id: String, tUs: Long) -> Unit = { _, _ -> },
    val nudge: (ms: Int) -> Unit = {},
    val setCursor: (tUs: Long) -> Unit = {},
    val add: (type: String) -> Unit = {},
    val delete: () -> Unit = {},
    val resetMarker: () -> Unit = {},
    val resetAll: () -> Unit = {},
    val selectHole: (hole: Int) -> Unit = {},
    val toggleTag: (tag: String) -> Unit = {},
    val toggleSuggestion: (id: String) -> Unit = {},
    val zoomIn: () -> Unit = {},
    val zoomOut: () -> Unit = {},
    val zoomTo: (t0Us: Long, t1Us: Long) -> Unit = { _, _ -> },
    val panBack: () -> Unit = {},
    val panForward: () -> Unit = {},
    val showAll: () -> Unit = {},
    val acceptAll: () -> Unit = {},
    val save: () -> Unit = {},
    val discard: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val retry: () -> Unit = {},
)

/**
 * The review of a recording's labels: the signals with the phases the labels give, markers to
 * drag or nudge (uncertain ones dashed amber until tapped), markers to add, tags per hole and
 * the detector's suggestions, then Accept all / Save / Discard. A review opened again says so
 * (and whether it is uploaded), and Save is its main action.
 */
@Composable
fun ReviewScreen(state: ReviewUiState, actions: ReviewActions, modifier: Modifier = Modifier) {
    when {
        state.error != null -> Box(modifier.fillMaxSize().padding(16.dp)) {
            Panel(border = Red) {
                Text("Cannot review", style = MaterialTheme.typography.headlineSmall, color = Red)
                Text(state.error, style = MaterialTheme.typography.bodyLarge)
                if (state.canRetry) ActionButton("Try again", actions.retry, Modifier.fillMaxWidth())
            }
        }
        state.loading -> Box(modifier.fillMaxSize().padding(16.dp)) {
            BusyPanel(if (state.waiting) "Preparing the recording…" else "Loading the labels…") {
                if (state.waiting) Hint("Its dataset files are being written and labelled automatically. This takes a few seconds.")
            }
        }
        else -> ScreenWithBar(modifier, barSpacing = 8.dp, bar = { Bar(state, actions) }) { Body(state, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(state: ReviewUiState, actions: ReviewActions) {
    state.message?.let { MessageBanner(it, actionLabel = "OK", onAction = actions.dismissMessage) }
    if (state.title.isNotEmpty()) Text(state.title, style = MaterialTheme.typography.titleMedium)
    if (state.tool.isNotEmpty()) Hint(state.tool)
    state.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (state.uploaded) Green else Blue) }
    SignalPlot(state, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Zoom out", state.toUs - state.fromUs < state.c1Us - state.c0Us, actions.zoomOut)
        StepButton("+", "Zoom in", state.toUs - state.fromUs > ReviewViewModel.MIN_SPAN_US, actions.zoomIn)
        StepButton("‹", "Back in time", state.fromUs > state.c0Us, actions.panBack)
        StepButton("›", "Forward in time", state.toUs < state.c1Us, actions.panForward)
        ActionButton("All", actions.showAll, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
    }
    Legend()
    if (state.errors.isNotEmpty()) {
        Panel(border = Red) {
            Text("The labels are not in a valid order", style = MaterialTheme.typography.titleMedium, color = Red)
            for (e in state.errors.take(3)) Text(e, style = MaterialTheme.typography.bodyMedium)
            if (state.errors.size > 3) Hint("and ${state.errors.size - 3} more")
        }
    }
    for (w in state.warnings) Hint(w)

    val sel = state.selected
    if (sel != null) {
        Panel(border = markerColor(sel.marker)) {
            Text(sel.title, style = MaterialTheme.typography.titleMedium)
            Hint(sel.detail)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((ms, text) in listOf(-100 to "−100", -10 to "−10", 10 to "+10", 100 to "+100")) {
                    ActionButton(text, { actions.nudge(ms) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                }
            }
            Hint("Nudge in milliseconds, or drag the marker's handle.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sel.marker.needsConfirm) ActionButton("Confirm", { actions.tapMarker(sel.marker.id) }, Modifier.weight(1f))
                if (sel.canReset) ActionButton("Reset", actions.resetMarker, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                ActionButton("Delete", actions.delete, Modifier.weight(1f), kind = ButtonKind.DANGER)
            }
        }
    } else {
        Hint(
            if (state.unconfirmed > 0) {
                "${state.unconfirmed} uncertain marker${if (state.unconfirmed == 1) "" else "s"} (dashed amber): tap ${if (state.unconfirmed == 1) "it" else "each"} to confirm. Tap the signals to place the cursor."
            } else {
                "Tap a marker to select it, or the signals to place the cursor."
            },
        )
    }

    SectionTitle("Add at the cursor")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in LabelTaxonomy.POINT_EVENTS) {
            ActionButton(LabelCheck.name(t), { actions.add(t) }, kind = ButtonKind.SECONDARY)
        }
    }

    if (state.holes.isNotEmpty()) {
        SectionTitle("Holes")
        if (state.holes.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in state.holes) Chip("Hole $h", h == state.hole, auto = false) { actions.selectHole(h) }
            }
        }
        Panel {
            Text(if (state.holes.size > 1) "Hole ${state.hole}" else "The hole", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in state.tags) Chip(t.label, t.on, t.auto) { actions.toggleTag(t.tag) }
                Chip("Off angle", state.offAngleTag, auto = true, enabled = false) {}
            }
            state.offAngle?.let { Hint("$it. The off-angle tag is set from the device's level.") }
        }
    }
    if (state.suggestions.isNotEmpty()) {
        SectionTitle("Suggestions")
        Hint("Problems the detector suggests. Keep the ones that happened.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in state.suggestions) Chip(s.label, s.on, s.auto) { actions.toggleSuggestion(s.id) }
        }
    }
    ActionButton("Reset all to auto", actions.resetAll, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
}

@Composable
private fun Bar(state: ReviewUiState, actions: ReviewActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Discard", actions.discard, Modifier.weight(1f), enabled = !state.busy, kind = ButtonKind.SECONDARY)
        // a new review ends with Accept all; one changed, or reviewed before, with Save
        val save = state.dirty || state.reviewed
        ActionButton("Save", actions.save, Modifier.weight(1f), enabled = !state.busy, kind = if (save) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
        ActionButton("Accept all", actions.acceptAll, Modifier.weight(1.2f), enabled = !state.busy, kind = if (save) ButtonKind.SECONDARY else ButtonKind.PRIMARY)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in listOf(Phase.SPINUP, Phase.FREE_RUN, Phase.DRILLING, Phase.BREAKTHROUGH, Phase.SPINDOWN, Phase.UNKNOWN)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(Modifier.size(12.dp), shape = RoundedCornerShape(3.dp), color = phaseColor(p).copy(alpha = 0.8f)) {}
                Text(phaseName(p), style = MaterialTheme.typography.labelMedium, color = Grey)
            }
        }
    }
}

private fun phaseName(p: Phase) = when (p) {
    Phase.OFF -> "Off"
    Phase.SPINUP -> "Spin-up"
    Phase.FREE_RUN -> "Free run"
    Phase.DRILLING -> "Drilling"
    Phase.BREAKTHROUGH -> "Breakthrough"
    Phase.SPINDOWN -> "Spin-down"
    Phase.UNKNOWN -> "Unknown"
}

/** A glove-sized filter chip, with an "auto" badge for what the detector set. */
@Composable
private fun Chip(label: String, on: Boolean, auto: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        enabled = enabled,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (auto) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, border = BorderStroke(1.dp, Blue)) {
                        Text("auto", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = Blue)
                    }
                }
            }
        },
        modifier = Modifier.heightIn(min = TouchHeight),
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            disabledSelectedContainerColor = Amber.copy(alpha = 0.25f),
            disabledLabelColor = Grey,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = on, borderColor = Dark, selectedBorderColor = MaterialTheme.colorScheme.primary),
    )
}
