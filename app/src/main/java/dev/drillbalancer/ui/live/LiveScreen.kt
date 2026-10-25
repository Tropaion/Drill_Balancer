package dev.drillbalancer.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.LevelStatus
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.FitNumber
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.ModeSelector
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.formatDeg
import dev.drillbalancer.ui.formatYaw
import dev.drillbalancer.ui.levelStatusFor
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.DrillBalancerTheme
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.StatusColors
import dev.drillbalancer.ui.wallArrow
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The level as on the drill. [angle] is read only inside the small composables and draw
 * blocks that show it, so a 10 Hz packet redraws the numbers, not the screen.
 */
@Composable
fun LiveScreen(
    state: LiveUiState,
    angle: () -> AnglePacket?,
    onMode: (LevelMode) -> Unit,
    onZero: () -> Unit,
    onClearZero: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeTabs(state.mode, onMode)
        state.message?.let { MessageBanner(it, onDismiss = onDismissMessage) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !state.hasAngle -> Waiting()
                !state.imuOk -> NoSensor()
                state.mode == LevelMode.WALL -> Wall(state, angle)
                state.mode == LevelMode.FLOOR -> Floor(state, angle)
                else -> AngleMode(state, angle)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Zero", onZero, Modifier.weight(1f), enabled = !state.busy && state.hasAngle)
            ActionButton(
                "Clear zero", onClearZero, Modifier.weight(1f),
                enabled = !state.busy && state.zeroSet, kind = ButtonKind.SECONDARY,
            )
        }
    }
}

@Composable
private fun ModeTabs(selected: LevelMode, onMode: (LevelMode) -> Unit) {
    ModeSelector(selected, onMode, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.typography.titleMedium)
}

fun StatusColors.of(status: LevelStatus?): Color = when (status) {
    LevelStatus.ON_TARGET -> onTarget
    LevelStatus.NEAR -> near
    LevelStatus.OFF -> off
    null -> Grey
}

// ---- wall: elevation with arrow, yaw since zero ----

@Composable
private fun Wall(state: LiveUiState, angle: () -> AnglePacket?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine(angle)
        WallNumber(angle)
        when {
            state.yawValid -> {
                YawBar(state, angle)
                YawText(state, angle)
                if (state.yawStale) Hint("Yaw drifts: tap Zero to re-zero.", Amber)
            }
            !state.zeroSet -> Hint("No zero set. Hold the drill straight on the mark and tap Zero to also see left/right.")
            else -> Hint("Left/right needs a new zero. Tap Zero.", Amber)
        }
    }
}

@Composable
private fun WallNumber(angle: () -> AnglePacket?) {
    val a = angle() ?: return
    val color = DrillBalancerTheme.status.of(a.status)
    val arrow = wallArrow(a.elevationDeg, a.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The device's arrow: which way the bit points from horizontal
        Box(Modifier.size(48.dp)) {
            if (arrow != 0) {
                Icon(
                    if (arrow > 0) DbIcons.ArrowUp else DbIcons.ArrowDown,
                    contentDescription = if (arrow > 0) "Bit points up" else "Bit points down",
                    tint = color, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        FitNumber(formatDeg(abs(a.elevationDeg)), color, Modifier.weight(1f, fill = false), template = "88.8°")
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun YawBar(state: LiveUiState, angle: () -> AnglePacket?) {
    val colors = DrillBalancerTheme.status
    Canvas(Modifier.fillMaxWidth(0.8f).height(28.dp)) {
        val a = angle() ?: return@Canvas
        val mid = size.height / 2
        val half = size.width / 2 - 8.dp.toPx()
        drawLine(Dark, Offset(center.x - half, mid), Offset(center.x + half, mid), 6.dp.toPx(), StrokeCap.Round)
        drawLine(Grey, Offset(center.x, mid - 10.dp.toPx()), Offset(center.x, mid + 10.dp.toPx()), 2.dp.toPx())
        // ui_yaw_offset(): near maps to a third of the half width
        val x = (a.yawDeg * half / 3f / saneNear(state.nearDeg)).coerceIn(-half, half)
        val c = colors.of(levelStatusFor(a.yawDeg, state.toleranceDeg, state.nearDeg))
        drawLine(c, Offset(center.x + x, 2.dp.toPx()), Offset(center.x + x, size.height - 2.dp.toPx()), 10.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun YawText(state: LiveUiState, angle: () -> AnglePacket?) {
    val a = angle() ?: return
    val c = DrillBalancerTheme.status.of(levelStatusFor(a.yawDeg, state.toleranceDeg, state.nearDeg))
    Text(formatYaw(a.yawDeg), style = MaterialTheme.typography.headlineMedium, color = c)
}

// ---- floor: bubble from vertical ----

@Composable
private fun Floor(state: LiveUiState, angle: () -> AnglePacket?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight * 0.62f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            StatusLine(angle)
            Spacer(Modifier.height(8.dp))
            Bubble(state, angle, Modifier.size(side))
            Deviation(angle)
            BubbleXY(angle)
        }
    }
}

@Composable
private fun Bubble(state: LiveUiState, angle: () -> AnglePacket?, modifier: Modifier) {
    val colors = DrillBalancerTheme.status
    Canvas(modifier) {
        val r = size.minDimension / 2 - 2.dp.toPx()
        val bubbleR = r * 0.12f
        val ppd = r / 3f / saneNear(state.nearDeg)   // ui_bubble_ppd(): near at a third of the radius
        val thin = Stroke(1.5.dp.toPx())
        drawCircle(Grey, r, style = thin)
        drawLine(Dark, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.5.dp.toPx())
        drawLine(Dark, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.5.dp.toPx())
        drawCircle(colors.near.copy(alpha = 0.7f), state.nearDeg * ppd + bubbleR, style = thin)
        drawCircle(colors.onTarget.copy(alpha = 0.7f), state.toleranceDeg * ppd + bubbleR, style = thin)
        val a = angle() ?: return@Canvas
        // ui_bubble_offset(): board +Y is up on the screen; pinned to the rim, direction kept
        var x = a.bubbleXDeg * ppd
        var y = -a.bubbleYDeg * ppd
        val lim = maxOf(0f, r - bubbleR - 2.dp.toPx())
        val d = hypot(x, y)
        if (d > lim) {
            x *= lim / d
            y *= lim / d
        }
        drawCircle(colors.of(a.status), bubbleR, Offset(center.x + x, center.y + y))
    }
}

@Composable
private fun Deviation(angle: () -> AnglePacket?) {
    val a = angle() ?: return
    FitNumber(formatDeg(a.deviationDeg), DrillBalancerTheme.status.of(a.status), maxSize = 88.sp, template = "88.8°")
}

@Composable
private fun BubbleXY(angle: () -> AnglePacket?) {
    val a = angle() ?: return
    Text(
        "X ${formatDeg(a.bubbleXDeg, showPlus = true)}   Y ${formatDeg(a.bubbleYDeg, showPlus = true)}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- angle: elevation against the target ----

@Composable
private fun AngleMode(state: LiveUiState, angle: () -> AnglePacket?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine(angle)
        Text(
            "TARGET  ${formatDeg(state.targetDeg)}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AngleNumbers(angle)
    }
}

@Composable
private fun AngleNumbers(angle: () -> AnglePacket?) {
    val a = angle() ?: return
    val c = DrillBalancerTheme.status.of(a.status)
    FitNumber(formatDeg(a.elevationDeg), c)
    Text(
        "${formatDeg(a.deviationDeg, showPlus = true)} from target",
        style = MaterialTheme.typography.headlineMedium,
        color = c,
        textAlign = TextAlign.Center,
    )
}

// ---- shared ----

@Composable
private fun StatusLine(angle: () -> AnglePacket?) {
    val a = angle() ?: return
    val status = a.status
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            when (status) {
                LevelStatus.ON_TARGET -> "ON TARGET"
                LevelStatus.NEAR -> "NEAR"
                LevelStatus.OFF, null -> "OFF TARGET"
            },
            style = MaterialTheme.typography.titleLarge,
            color = DrillBalancerTheme.status.of(status),
        )
        if (a.still) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).semantics { contentDescription = "Still" }) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(Grey) }
                }
                Text("Still", style = MaterialTheme.typography.titleMedium, color = Grey)
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color = Grey) {
    Text(
        text, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge,
        color = color, textAlign = TextAlign.Center,
    )
}

@Composable
private fun Waiting() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Hint("Waiting for the level…")
    }
}

@Composable
private fun NoSensor() {
    Panel(border = Amber) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(DbIcons.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(40.dp))
            Text("NO SENSOR", style = MaterialTheme.typography.headlineMedium, color = Amber)
        }
        Text(
            "The level's motion sensor is not answering, so there are no angles to show. " +
                "If this does not clear in a few seconds, reboot the device.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("--.-°", style = MaterialTheme.typography.displayMedium, color = Grey, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

// Settings are validated, but a zero would divide by zero (ui_logic.c sane_near).
private fun saneNear(near: Float) = if (near > 0.01f) near else 1f
