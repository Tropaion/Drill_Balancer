package dev.drillbalancer.ui.training.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.Phase
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.theme.White
import kotlin.math.roundToInt

/** The phase colours of the bands behind the lanes (and the legend). */
fun phaseColor(p: Phase): Color = when (p) {
    Phase.OFF -> Color.Transparent
    Phase.SPINUP -> Blue.copy(alpha = 0.22f)
    Phase.FREE_RUN -> Green.copy(alpha = 0.14f)
    Phase.DRILLING -> Amber.copy(alpha = 0.22f)
    Phase.BREAKTHROUGH -> Red.copy(alpha = 0.32f)
    Phase.SPINDOWN -> Grey.copy(alpha = 0.18f)
    Phase.UNKNOWN -> Color(0x33FF4081)
}

/** A marker type's colour. */
fun markerColor(type: String): Color = when (type) {
    "motor_on", "motor_off" -> Blue
    "contact" -> Amber
    "breakthrough" -> Red
    "retract" -> Green
    "kickback" -> Color(0xFFE040FB)
    else -> Grey
}

private val LaneHeight = 64.dp
private val HandleSize = TouchHeight
private val HandleGap = 4.dp

private class PlacedHandle(val marker: MarkerView, val left: Float, val row: Int)

/**
 * Handles left to right, each in the first of two rows where it does not cover the one before
 * it (else the row whose last handle ends first): close markers stay tappable.
 */
private fun handleRows(markers: List<MarkerView>, from: Long, span: Double, widthPx: Float, handlePx: Float): List<PlacedHandle> {
    val end = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    return markers.sortedBy { it.tUs }.map { m ->
        val x = ((m.tUs - from) / span * widthPx).toFloat()
        val left = (x - handlePx / 2).coerceIn(0f, (widthPx - handlePx).coerceAtLeast(0f))
        val row = (0 until 2).firstOrNull { end[it] <= left } ?: if (end[0] <= end[1]) 0 else 1
        end[row] = left + handlePx
        PlacedHandle(m, left, row)
    }
}

/**
 * The review's plot: a row of 56 dp marker handles (tap: select or confirm; drag: move) over
 * the stacked lanes (min/max envelopes) with the phase bands behind them, the marker lines, the
 * spans along the bottom and the cursor (a tap on the lanes sets it).
 */
@Composable
fun SignalPlot(state: ReviewUiState, actions: ReviewActions, modifier: Modifier = Modifier) {
    val from = state.fromUs
    val to = state.toUs
    val span = (to - from).coerceAtLeast(1).toDouble()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val handlePx = with(LocalDensity.current) { HandleSize.toPx() }
            val gapPx = with(LocalDensity.current) { HandleGap.toPx() }
            val placed = handleRows(state.markers.filter { it.tUs in from until to }, from, span, widthPx, handlePx)
            val rows = (placed.maxOfOrNull { it.row } ?: 0) + 1
            Box(Modifier.fillMaxWidth().height(HandleSize * rows + HandleGap * (rows - 1))) {
                for (h in placed) {
                    val m = h.marker
                    // keyed, with plain values: the handles that did not change skip a drag's recomposition
                    key(m.id) {
                        MarkerHandle(
                            m, state.selected?.marker?.id == m.id, h.left.roundToInt(), (h.row * (handlePx + gapPx)).roundToInt(),
                            usPerPx = span / widthPx, actions = actions,
                        )
                    }
                }
            }
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Grey)
        val lanes = state.lanes
        val height = LaneHeight * lanes.size.coerceAtLeast(1) + 12.dp
        val tap by rememberUpdatedState(actions.setCursor)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .border(BorderStroke(1.dp, Dark), RoundedCornerShape(4.dp))
                .pointerInput(from, to) { detectTapGestures { p -> tap(from + (p.x / size.width * span).toLong()) } }
                .semantics {
                    contentDescription = "Signals from ${secs(state, from)} to ${secs(state, to)}, cursor at ${secs(state, state.cursorUs)}"
                    // the cursor without a tap on a position (TalkBack, Switch Access): a tenth of the view a step
                    val step = (span / 10).toLong()
                    customActions = listOf(
                        CustomAccessibilityAction("Cursor back") { tap((state.cursorUs - step).coerceAtLeast(from)); true },
                        CustomAccessibilityAction("Cursor forward") { tap((state.cursorUs + step).coerceAtMost(to - 1)); true },
                    )
                },
        ) {
            fun x(t: Long) = ((t - from) / span * size.width).toFloat()
            val laneH = LaneHeight.toPx()
            val bottom = laneH * lanes.size
            for (b in state.bands) {
                val c = phaseColor(b.phase)
                if (c.alpha == 0f) continue
                val a = x(b.t0Us).coerceIn(0f, size.width)
                val e = x(b.t1Us).coerceIn(0f, size.width)
                drawRect(c, Offset(a, 0f), Size(e - a, bottom))
            }
            for ((i, lane) in lanes.withIndex()) {
                val top = laneH * i
                if (i > 0) drawLine(Dark, Offset(0f, top), Offset(size.width, top), 1.dp.toPx())
                val n = lane.min.size
                val bw = size.width / n
                val range = (lane.hi - lane.lo).takeIf { it > 0f } ?: 1f
                fun y(v: Float) = top + laneH - 4.dp.toPx() - (v - lane.lo) / range * (laneH - 18.dp.toPx())
                for (k in 0 until n) {
                    val mn = lane.min[k]
                    val mx = lane.max[k]
                    if (mn.isNaN()) continue
                    val y0 = y(mx)
                    val y1 = maxOf(y(mn), y0 + 1.5.dp.toPx())
                    drawRect(White.copy(alpha = 0.85f), Offset(k * bw, y0), Size(maxOf(bw, 1f), y1 - y0))
                }
                val label = measurer.measure("${lane.name} (${lane.unit})", labelStyle)
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(2.dp.toPx(), top + 2.dp.toPx()), Size(label.size.width + 4.dp.toPx(), label.size.height.toFloat()))
                drawText(label, topLeft = Offset(4.dp.toPx(), top + 2.dp.toPx()))
            }
            // spans along the bottom strip
            for (s in state.spans) {
                val a = x(s.t0Us).coerceIn(0f, size.width)
                val e = x(s.t1Us).coerceIn(0f, size.width)
                if (e <= a) continue
                drawRect(spanColor(s.type), Offset(a, bottom + 2.dp.toPx()), Size(e - a, 8.dp.toPx()))
            }
            // markers
            for (m in state.markers) {
                if (m.tUs !in from until to) continue
                val px = x(m.tUs)
                val selected = state.selected?.marker?.id == m.id
                val color = if (m.needsConfirm) Amber else markerColor(m.type)
                drawLine(
                    if (selected) White else color, Offset(px, 0f), Offset(px, bottom), (if (selected) 3.dp else 2.dp).toPx(),
                    pathEffect = if (m.needsConfirm) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                )
            }
            if (state.cursorUs in from until to) {
                val cx = x(state.cursorUs)
                drawLine(Grey, Offset(cx, 0f), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(secs(state, from), style = MaterialTheme.typography.labelMedium, color = Grey, modifier = Modifier.weight(1f))
            Text("cursor ${secs(state, state.cursorUs)}", style = MaterialTheme.typography.labelMedium, color = Grey, modifier = Modifier.weight(1f))
            Text(secs(state, to), style = MaterialTheme.typography.labelMedium, color = Grey)
        }
    }
}

private fun spanColor(type: String): Color = when (type) {
    "stuck" -> Red
    "wobble", "wander" -> Amber
    "ignore" -> Grey
    else -> Blue
}

private fun secs(state: ReviewUiState, t: Long): String = LabelEdits.formatTime(state.c0Us, t)

/** A 56 dp handle: tap selects (and confirms an uncertain marker), a horizontal drag moves it. */
@Composable
private fun MarkerHandle(m: MarkerView, selected: Boolean, leftPx: Int, topPx: Int, usPerPx: Double, actions: ReviewActions) {
    val color = if (m.needsConfirm) Amber else markerColor(m.type)
    val tap by rememberUpdatedState(actions.tapMarker)
    val drag by rememberUpdatedState(actions.drag)
    val scale by rememberUpdatedState(usPerPx)
    val now by rememberUpdatedState(m.tUs)
    var startT by remember { mutableLongStateOf(0L) }
    var dx by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .offset { IntOffset(leftPx, topPx) }
            .zIndex(if (selected) 2f else 1f)
            .size(HandleSize)
            .background(if (selected) color else MaterialTheme.colorScheme.surfaceContainer, shape)
            .then(
                if (m.needsConfirm && !selected) {
                    Modifier.drawBehind {
                        val w = 2.dp.toPx()
                        drawRoundRect(
                            color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                } else {
                    Modifier.border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) White else color), shape)
                },
            )
            .pointerInput(m.id) { detectTapGestures { tap(m.id) } }
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        startT = now
                        dx = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                    drag(m.id, startT + (dx * scale).toLong())
                }
            }
            .semantics {
                contentDescription = "${LabelCheck.name(m.type)} ${m.label}${if (m.needsConfirm) ", uncertain, tap to confirm" else ""}"
                role = Role.Button
                this.selected = selected
                onClick(label = if (m.needsConfirm) "Confirm" else "Select") {
                    tap(m.id)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            m.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (selected) Color.Black else color, modifier = Modifier.padding(2.dp), maxLines = 1,
        )
    }
}
