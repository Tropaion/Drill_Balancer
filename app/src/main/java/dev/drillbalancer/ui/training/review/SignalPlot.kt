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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/** [m]'s colour: amber while it is uncertain, else its type's. */
fun markerColor(m: MarkerView): Color = if (m.needsConfirm) Amber else markerColor(m.type)

/** [m] as its handle says it: "Contact C1". */
fun MarkerView.spokenName(): String = "${LabelCheck.name(type)} $label"

private val LaneHeight = 64.dp
private val HandleSize = TouchHeight
private val HandleGap = 4.dp
private val LeaderHeight = 12.dp

/**
 * The review's plot: rows of 56 dp marker handles (tap: select or confirm; drag: move), each
 * with a leader to its marker, where markers are too close for their handles a count handle
 * (tap: zoom in on them; MarkerLayout), over the stacked lanes (min/max envelopes) with the
 * phase bands behind them, the marker lines, the spans along the bottom, the cursor (a tap on
 * the lanes sets it) and the lanes' titles over it all.
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
            // at the shortest view the markers cannot be zoomed apart: every one keeps its handle
            val maxRows = if (to - from > ReviewViewModel.MIN_SPAN_US) MarkerLayout.MAX_ROWS else Int.MAX_VALUE
            val selectedId = state.selected?.marker?.id
            // the marker being dragged and the row its handle had when the drag began: it keeps it
            var pin by remember { mutableStateOf<Pair<String, Int>?>(null) }
            val placed = remember(state.markers, from, to, widthPx, handlePx, gapPx, selectedId, maxRows, pin) {
                MarkerLayout.layout(state.markers, from, to, widthPx, handlePx, gapPx, selectedId, maxRows, pin)
            }
            // during a drag the handles' rows only grow: the lanes do not jump up and down under it
            var held by remember { mutableIntStateOf(0) }
            val rows = maxOf((placed.maxOfOrNull { it.row } ?: 0) + 1, held)
            val shown by rememberUpdatedState(rows)
            val hold = remember {
                { dragged: Pair<String, Int>? ->
                    held = if (dragged != null) maxOf(held, shown) else 0
                    pin = dragged
                }
            }
            Box(Modifier.fillMaxWidth().height(HandleSize * rows + HandleGap * (rows - 1) + LeaderHeight)) {
                Leaders(placed, from, span, handlePx, gapPx, selectedId)
                for (h in placed) {
                    val top = (h.row * (handlePx + gapPx)).roundToInt()
                    // keyed outside the branches: a handle stays the same one (and keeps its drag) when
                    // its place in the list changes; with plain values the others skip a drag's recomposition
                    key(h.key) {
                        when (h) {
                            is MarkerLayout.Handle -> MarkerHandle(
                                h.marker, selectedId == h.marker.id, h.left.roundToInt(), h.row, top, usPerPx = span / widthPx,
                                tapMarker = actions.tapMarker, dragTo = actions.drag, hold = hold,
                            )
                            is MarkerLayout.Cluster -> CountHandle(h, h.left.roundToInt(), top, actions.zoomTo)
                        }
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
                val color = markerColor(m)
                drawLine(
                    if (selected) White else color, Offset(px, 0f), Offset(px, bottom), (if (selected) 3.dp else 2.dp).toPx(),
                    pathEffect = if (m.needsConfirm) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                )
            }
            if (state.cursorUs in from until to) {
                val cx = x(state.cursorUs)
                drawLine(Grey, Offset(cx, 0f), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
            // the lanes' titles last, on their own dark backing: no marker line strikes them through
            for ((i, lane) in lanes.withIndex()) {
                val top = laneH * i
                val label = measurer.measure("${lane.name} (${lane.unit})", labelStyle)
                drawRect(Color.Black.copy(alpha = 0.8f), Offset(2.dp.toPx(), top + 2.dp.toPx()), Size(label.size.width + 4.dp.toPx(), label.size.height.toFloat()))
                drawText(label, topLeft = Offset(4.dp.toPx(), top + 2.dp.toPx()))
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

/**
 * Behind the handles: a leader from each handle down to its marker's place at the bottom (the
 * top of the plot), a count handle's to a bracket over its markers.
 */
@Composable
private fun Leaders(placed: List<MarkerLayout.Item>, from: Long, span: Double, handlePx: Float, gapPx: Float, selected: String?) {
    Canvas(Modifier.fillMaxSize()) {
        fun x(t: Long) = ((t - from) / span * size.width).toFloat()
        val w = 1.5.dp.toPx()
        for (h in placed) {
            val start = Offset(h.left + handlePx / 2, h.row * (handlePx + gapPx) + handlePx / 2)
            when (h) {
                is MarkerLayout.Handle -> {
                    val m = h.marker
                    val color = if (m.id == selected) White else markerColor(m)
                    drawLine(color.copy(alpha = 0.8f), start, Offset(x(m.tUs), size.height), w)
                }
                is MarkerLayout.Cluster -> {
                    val y = size.height - w
                    val a = x(h.t0Us)
                    val b = maxOf(x(h.t1Us), a + w)
                    drawLine(Grey, start, Offset((a + b) / 2, y), w)
                    drawLine(Grey, Offset(a, y), Offset(b, y), 2 * w)
                }
            }
        }
    }
}

/** A count handle: [c]'s markers are too close for a handle each; a tap zooms in on them. */
@Composable
private fun CountHandle(c: MarkerLayout.Cluster, leftPx: Int, topPx: Int, zoomTo: (t0Us: Long, t1Us: Long) -> Unit) {
    val zoom by rememberUpdatedState(zoomTo)
    val shape = RoundedCornerShape(12.dp)
    val n = c.markers.size
    // the names as their handles say them; the box is read once (its texts are not stops of their own)
    val names = c.markers.joinToString(", ") { it.spokenName() }
    Box(
        Modifier
            .offset { IntOffset(leftPx, topPx) }
            .zIndex(1f)
            .size(HandleSize)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(BorderStroke(1.dp, Grey), shape)
            .pointerInput(c.t0Us, c.t1Us) { detectTapGestures { zoom(c.t0Us, c.t1Us) } }
            .clearAndSetSemantics {
                contentDescription = "$n markers ($names)"
                role = Role.Button
                onClick(label = "Zoom in on them") {
                    zoom(c.t0Us, c.t1Us)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$n", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = White, maxLines = 1)
            Text("zoom", style = MaterialTheme.typography.labelSmall, color = Grey, maxLines = 1)
        }
    }
}

/**
 * A 56 dp handle in [row]: tap selects (and confirms an uncertain marker), a horizontal drag
 * moves it; [hold] is told while a drag goes on (the marker and the row it began in, at each
 * step) and when it ends (null).
 */
@Composable
private fun MarkerHandle(
    m: MarkerView,
    selected: Boolean,
    leftPx: Int,
    row: Int,
    topPx: Int,
    usPerPx: Double,
    tapMarker: (id: String) -> Unit,
    dragTo: (id: String, tUs: Long) -> Unit,
    hold: (Pair<String, Int>?) -> Unit,
) {
    val color = markerColor(m)
    val tap by rememberUpdatedState(tapMarker)
    val drag by rememberUpdatedState(dragTo)
    val scale by rememberUpdatedState(usPerPx)
    val now by rememberUpdatedState(m.tUs)
    val holding by rememberUpdatedState(hold)
    val inRow by rememberUpdatedState(row)
    var startT by remember { mutableLongStateOf(0L) }
    var startRow by remember { mutableIntStateOf(0) }
    var dx by remember { mutableFloatStateOf(0f) }
    // a handle dragged out of the view goes without its drag's end: it lets go then
    var dragging by remember { mutableStateOf(false) }
    val release = {
        dragging = false
        holding(null)
    }
    DisposableEffect(Unit) { onDispose { if (dragging) release() } }
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
                        startRow = inRow
                        dx = 0f
                        dragging = true
                        holding(m.id to startRow)
                    },
                    onDragEnd = release,
                    onDragCancel = release,
                ) { change, amount ->
                    change.consume()
                    dx += amount
                    holding(m.id to startRow)
                    drag(m.id, startT + (dx * scale).toLong())
                }
            }
            .clearAndSetSemantics {
                contentDescription = m.spokenName() + if (m.needsConfirm) ", uncertain, tap to confirm" else ""
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
