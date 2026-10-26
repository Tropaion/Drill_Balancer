package dev.drillbalancer.ui.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drillbalancer.recorder.LivePreview
import dev.drillbalancer.ui.components.numberStyle
import dev.drillbalancer.ui.formatFixed
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

/**
 * |a| over the last seconds as a line, scaled to at least 2 g, with a dashed 1 g line (a
 * drill held still reads 1 g; vibration spreads around it).
 */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, capacity: Int = LivePreview.POINTS) {
    val top = maxOf(2f, values.maxOrNull() ?: 0f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "Acceleration, peak ${formatFixed(values.maxOrNull() ?: 0f, 1)} g" },
    ) {
        val stroke = 2.dp.toPx()
        fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * (size.height - stroke) - stroke / 2
        drawLine(Dark, Offset(0f, y(1f)), Offset(size.width, y(1f)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (values.size < 2) return@Canvas
        // Newest at the right edge; a window not yet full starts further right.
        val dx = size.width / (capacity - 1)
        val x0 = size.width - (values.size - 1) * dx
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x0, y(v)) else path.lineTo(x0 + i * dx, y(v)) }
        drawPath(path, Green, style = Stroke(stroke))
    }
}

/** A microphone's level: a bar over -60..0 dBFS, green, amber above -12, red above -3 or when it clipped. */
@Composable
fun LevelMeter(label: String, db: Float?, clipped: Boolean, modifier: Modifier = Modifier) {
    val frac = if (db == null) 0f else ((db + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        clipped || (db != null && db > -3f) -> Red
        db != null && db > -12f -> Amber
        else -> Green
    }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label ${if (db == null) "no signal" else "${formatFixed(db, 0)} dB"}"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.weight(1f).height(20.dp)) {
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(Dark, size = size, cornerRadius = r)
            if (frac > 0f) drawRoundRect(color, size = Size(size.width * frac, size.height), cornerRadius = r)
        }
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (db == null) "--" else "${formatFixed(db, 0)} dB",
                style = numberStyle(MaterialTheme.typography.bodyLarge.fontSize),
                color = if (clipped) Red else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** The red recording dot. */
@Composable
fun RecDot(modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) { drawCircle(Red) }
}

/** Grey secondary text in a panel. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = Grey)
}

/** A scrolling body over a bar with the screen's main actions (the setup and record screens). */
@Composable
internal fun ScreenWithBar(
    modifier: Modifier,
    barSpacing: Dp,
    bar: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, Dark)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(barSpacing),
                content = bar,
            )
        }
    }
}
