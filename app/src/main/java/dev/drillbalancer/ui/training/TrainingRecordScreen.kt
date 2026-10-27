package dev.drillbalancer.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drillbalancer.recorder.LiveView
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingState
import dev.drillbalancer.recorder.TakeSummary
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.BusyPanel
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.FitNumber
import dev.drillbalancer.ui.components.InfoRow
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.formatBytes
import dev.drillbalancer.ui.formatFixed
import dev.drillbalancer.ui.formatMinSec
import dev.drillbalancer.ui.formatRate
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.theme.White

class RecordActions(
    val startTake: () -> Unit = {},
    val stopTake: () -> Unit = {},
    val finish: () -> Unit = {},          // close the session
    val retry: () -> Unit = {},
    val done: () -> Unit = {},            // DONE / FAILED: back to the list
    val openWifiSettings: () -> Unit = {},
    val review: (id: String) -> Unit = {},  // the last take's review
)

/** One recording session: joining the device's Wi-Fi, then takes with a live view. */
@Composable
fun TrainingRecordScreen(state: RecordUiState, actions: RecordActions, modifier: Modifier = Modifier) {
    val r = state.rec
    ScreenWithBar(modifier, barSpacing = 8.dp, bar = { Bar(r, actions) }) {
        if (!r.bleLinked && r.phase.active) {
            MessageBanner(
                UiMessage(
                    "Bluetooth to the device is lost. The stream goes on; without Bluetooth the device ends the take after 2 minutes.",
                    UiMessage.Kind.WARNING,
                ),
            )
        }
        r.notice?.let { MessageBanner(UiMessage(it.text, if (it.error) UiMessage.Kind.ERROR else UiMessage.Kind.INFO)) }
        when (r.phase) {
            RecordPhase.IDLE, RecordPhase.STARTING_DEVICE -> BusyPanel("Starting the recorder on the device…") {
                Hint("The device opens its own Wi-Fi for the recording.")
            }
            RecordPhase.JOINING_WIFI -> if (r.manualJoin) ManualJoin(r, actions) else BusyPanel("Joining the device's Wi-Fi…") {
                r.ssid?.let { Hint("Network $it") }
            }
            RecordPhase.WAITING_APPROVAL -> BusyPanel("Waiting for your approval") {
                Text(
                    "Android asks whether DrillBalancer may connect to ${r.ssid ?: "the device"}. Tap Connect. " +
                        "The phone keeps its mobile data meanwhile.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            RecordPhase.CONNECTING -> BusyPanel("Connecting to the recorder…") {
                Hint(if (r.lan) "Over the network the phone is on (simulator)." else "On ${r.ssid ?: "the device's Wi-Fi"}.")
            }
            RecordPhase.READY -> Ready(state)
            RecordPhase.RECORDING, RecordPhase.STOPPING -> Recording(state)
            RecordPhase.DONE -> Panel(border = Green) {
                Text("Session finished", style = MaterialTheme.typography.headlineSmall)
                Text(
                    doneText(r.takes, r.deleted),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            RecordPhase.FAILED -> Panel(border = Red) {
                Text(r.error?.title ?: "Recording failed", style = MaterialTheme.typography.headlineSmall, color = Red)
                r.error?.text?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (r.sessionId != null) Hint("Try again joins the device's session again; Close ends it.")
            }
        }
        if (r.phase == RecordPhase.READY || r.phase == RecordPhase.DONE || r.phase == RecordPhase.FAILED) {
            r.lastTake?.let { LastTake(it, r.takes) { actions.review(it.recordingId) } }
        }
    }
}

/** What DONE says of the session's [takes], [deleted] of them deleted since (the review's Delete take). */
internal fun doneText(takes: Int, deleted: Int): String {
    val kept = takes - deleted
    return when {
        takes == 0 -> "No take was recorded."
        deleted == 0 -> if (takes == 1) "1 take recorded. It is in the list of recordings." else "$takes takes recorded. They are in the list of recordings."
        kept == 0 -> if (takes == 1) "1 take recorded, deleted since." else "$takes takes recorded, all deleted since."
        else -> "$takes takes recorded, $deleted deleted since. " + if (kept == 1) "The other is in the list of recordings." else "The others are in the list of recordings."
    }
}

@Composable
private fun Ready(state: RecordUiState) {
    val r = state.rec
    Panel(border = Green) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready", style = MaterialTheme.typography.headlineSmall, color = Green, modifier = Modifier.weight(1f))
            r.setup?.profile?.let { Text(it.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (state.title.isNotEmpty()) Text(state.title, style = MaterialTheme.typography.titleMedium)
        Hint(
            if (r.lan) "Stream connected over the network (simulator)." else "Stream connected on ${r.ssid ?: "the device's Wi-Fi"}.",
        )
        Hint("Start a take just before you drill and stop it after the drill has stopped. One take per hole works best.")
    }
}

@Composable
private fun Recording(state: RecordUiState) {
    val r = state.rec
    val live = r.live
    val stopping = r.phase == RecordPhase.STOPPING
    Panel(border = if (stopping) Dark else Red) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!stopping) RecDot()
            Text(
                if (stopping) "Stopping…" else "REC", style = MaterialTheme.typography.titleMedium,
                color = if (stopping) MaterialTheme.colorScheme.onSurfaceVariant else Red, modifier = Modifier.weight(1f),
            )
            r.setup?.profile?.let { Text(it.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        FitNumber(formatMinSec(live.elapsedS.toLong(), padMinutes = true), White, Modifier.fillMaxWidth(), maxSize = 96.sp, template = "88:88")
        if (state.title.isNotEmpty()) Text(state.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel {
        Text("Acceleration |a|, last 5 s", style = MaterialTheme.typography.titleMedium)
        Sparkline(live.sparkline)
        LevelMeter("Mic 1", live.levels.mic1Db, live.levels.mic1Clipped)
        LevelMeter("Mic 2", live.levels.mic2Db, live.levels.mic2Clipped)
    }
    Counters(live, r.stalled)
}

@Composable
private fun Counters(live: LiveView, stalled: Boolean) {
    SectionTitle("Stream")
    Panel(border = if (stalled) Amber else Dark) {
        InfoRow("Data", "${formatFixed(live.kbPerS, 0)} kB/s · ${formatBytes(live.bytes)}")
        InfoRow("IMU rate", live.imuHz?.let { formatRate(it, "Hz") } ?: "measuring…")
        InfoRow("Audio rate", live.audioHz?.let { formatRate(it, "Hz") } ?: "measuring…")
        InfoRow("Lost records", "${live.missingRecords}", valueColor = warnIf(live.missingRecords > 0))
        InfoRow("IMU gaps", "${live.imuGapSamples} samples")
        InfoRow("Audio gaps", "${live.audioGapFrames} frames", valueColor = warnIf(live.audioGapFrames > 0))
        InfoRow("Device drops", "${live.deviceDropped}", valueColor = warnIf(live.deviceDropped > 0))
        InfoRow(
            "Last data", if (live.quietMs < 1_000) "now" else "${formatFixed(live.quietMs / 1000f, 0)} s ago",
            valueColor = if (stalled) Amber else Color.Unspecified,
        )
    }
}

private fun warnIf(b: Boolean) = if (b) Amber else Color.Unspecified

@Composable
private fun LastTake(t: TakeSummary, takes: Int, onReview: () -> Unit) {
    SectionTitle(if (takes > 1) "Last take ($takes this session)" else "Last take")
    Panel(border = if (t.complete) Dark else Amber) {
        InfoRow("Length", formatMinSec(t.durationS.toLong()))
        InfoRow("Size", formatBytes(t.bytes))
        InfoRow("Ended", t.endText, valueColor = if (t.complete) Color.Unspecified else Amber)
        InfoRow("Lost records", "${t.missingRecords}", valueColor = warnIf(t.missingRecords > 0))
        ActionButton("Review", onReview, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun ManualJoin(r: RecordingState, actions: RecordActions) {
    Panel(border = Amber) {
        Text("Join the device's Wi-Fi", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This Android version cannot join it for the app. Join it in the Wi-Fi settings, then come back: the app connects by itself.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Hint("Network")
        Text(r.ssid.orEmpty(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
        Hint("Password")
        Text(r.pass.orEmpty(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
        ActionButton("Wi-Fi settings", actions.openWifiSettings, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun Bar(r: RecordingState, actions: RecordActions) {
    val big = Modifier.fillMaxWidth().heightIn(min = 72.dp)
    when (r.phase) {
        RecordPhase.READY -> {
            ActionButton("Start take", actions.startTake, big)
            ActionButton("Finish session", actions.finish, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
        RecordPhase.RECORDING -> ActionButton("Stop take", actions.stopTake, big, kind = ButtonKind.DANGER)
        RecordPhase.STOPPING -> ActionButton("Stop take", {}, big, enabled = false, kind = ButtonKind.DANGER)
        RecordPhase.DONE -> ActionButton("Done", actions.done, Modifier.fillMaxWidth())
        RecordPhase.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Close", actions.done, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            ActionButton("Try again", actions.retry, Modifier.weight(1f))
        }
        else -> ActionButton("Cancel", actions.finish, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
}
