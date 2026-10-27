package dev.drillbalancer.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.batteryLevel
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red

/** Minimum height of every tappable control: the app is used in a workshop, with gloves. */
val TouchHeight: Dp = 56.dp

/** A short message the screens show above their content. */
@Immutable
data class UiMessage(val text: String, val kind: Kind = Kind.ERROR) {
    enum class Kind { ERROR, WARNING, INFO, SUCCESS }
}

/** [DANGER_OUTLINED]: a destructive action that is not the screen's next step (outlined in red). */
enum class ButtonKind { PRIMARY, SECONDARY, DANGER, DANGER_OUTLINED }

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.PRIMARY,
) {
    val shape = RoundedCornerShape(16.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    // One line that shrinks for large font scales instead of wrapping or clipping
    val label: @Composable () -> Unit = {
        Text(
            text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize),
        )
    }
    when (kind) {
        ButtonKind.PRIMARY -> Button(
            onClick, modifier.heightIn(min = TouchHeight), enabled, shape = shape, contentPadding = padding,
        ) { label() }
        ButtonKind.SECONDARY -> OutlinedButton(
            onClick, modifier.heightIn(min = TouchHeight), enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(1.dp, if (enabled) Grey else Dark),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { label() }
        ButtonKind.DANGER -> Button(
            onClick, modifier.heightIn(min = TouchHeight), enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.Black),
        ) { label() }
        ButtonKind.DANGER_OUTLINED -> OutlinedButton(
            onClick, modifier.heightIn(min = TouchHeight), enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(1.dp, if (enabled) Red else Dark),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
        ) { label() }
    }
}

/** A dark rounded panel like the device's buttons and tiles. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    border: Color = Dark,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

private val PanelShape = RoundedCornerShape(16.dp)

/** A [Panel] to tap, a row at least 72 dp high (a list entry, a picker): [content] padded 16 / 12 dp. */
@Composable
fun PanelRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Dark),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}

/**
 * A setting with its switch at the end: the whole row toggles it and is one node for TalkBack
 * ([content]: its label; on/off from the switch).
 */
@Composable
fun SwitchRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = horizontalArrangement,
    ) {
        content()
        Switch(
            checked, onCheckedChange = null, Modifier.minimumInteractiveComponentSize(),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                uncheckedThumbColor = Grey,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = Grey,
            ),
        )
    }
}

/** A blue panel with a spinner and [title], for states that wait on the device. */
@Composable
fun BusyPanel(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Panel(modifier, border = Blue) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Blue)
            Text(title, Modifier.weight(1f), style = titleStyle)
        }
        content()
    }
}

/** A question with glove-sized buttons. Confirm closes the dialog, then runs [onConfirm]. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = Color.Unspecified,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton({
                onDismiss()
                onConfirm()
            }, Modifier.heightIn(min = TouchHeight)) { Text(confirmLabel, color = confirmColor) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text(dismissLabel) } },
    )
}

val LevelMode.title: String
    get() = when (this) {
        LevelMode.WALL -> "Wall"
        LevelMode.FLOOR -> "Floor"
        LevelMode.ANGLE -> "Angle"
    }

/** The level mode as one segmented row. A null [textStyle] keeps the button's own label style. */
@Composable
fun ModeSelector(
    selected: LevelMode,
    onMode: (LevelMode) -> Unit,
    inactiveColor: Color,
    textStyle: TextStyle? = null,
) = SegmentedSelector(LevelMode.entries.map { it to it.title }, selected, inactiveColor, textStyle, onMode)

/** One of [options] (value to label) as a segmented row of glove-sized buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedSelector(
    options: List<Pair<T, String>>,
    selected: T,
    inactiveColor: Color = MaterialTheme.colorScheme.surface,
    textStyle: TextStyle? = null,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = TouchHeight),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = Color.Black,
                    inactiveContainerColor = inactiveColor,
                ),
            ) {
                Text(label, style = textStyle ?: LocalTextStyle.current, maxLines = 1)
            }
        }
    }
}

/** A glove-sized −/+ button; [description] is what a screen reader says. */
@Composable
fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.size(TouchHeight).semantics { contentDescription = description }, enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) Grey else Dark),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Opens a settings screen or the like; nothing if the phone has no activity for [intent]. */
fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun UiMessage.Kind.color(): Color = when (this) {
    UiMessage.Kind.ERROR -> Red
    UiMessage.Kind.WARNING -> Amber
    UiMessage.Kind.INFO -> Blue
    UiMessage.Kind.SUCCESS -> Green
}

/** A message with an accent colour, an optional action and an optional dismiss. */
@Composable
fun MessageBanner(
    message: UiMessage,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = message.kind.color()
    Panel(modifier, border = accent) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (message.kind == UiMessage.Kind.SUCCESS) DbIcons.Check
                else if (message.kind == UiMessage.Kind.INFO) DbIcons.Info else DbIcons.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.padding(top = 2.dp).size(24.dp),
            )
            Text(message.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        if (onAction != null || onDismiss != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) {
                    TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Dismiss") }
                }
                if (onAction != null && actionLabel != null) {
                    TextButton(onAction, Modifier.heightIn(min = TouchHeight)) { Text(actionLabel, color = accent) }
                }
            }
        }
    }
}

/** Battery icon with its level and an optional charging bolt, like the device's status bar. */
@Composable
fun BatteryIndicator(percent: Int?, charging: Boolean, chargeDone: Boolean, modifier: Modifier = Modifier) {
    val known = percent != null
    val level = if (percent != null) batteryLevel(percent) else 0
    val color = if (known && level == 0) Red else Grey
    val text = if (percent != null) "$percent%" else "--%"
    val description = buildString {
        append("Battery ").append(text)
        if (charging) append(", charging")
        if (chargeDone) append(", charged")
    }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
            val stroke = 1.5.dp.toPx()
            val nub = 2.5.dp.toPx()
            val body = Size(size.width - nub, size.height)
            drawRoundRect(color, size = body, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            drawRect(color, Offset(body.width, size.height * 0.3f), Size(nub, size.height * 0.4f))
            val inset = stroke + 1.dp.toPx()
            val full = body.width - 2 * inset
            if (known && level > 0) {
                drawRect(color, Offset(inset, inset), Size(full * level / 4f, body.height - 2 * inset))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        if (charging || chargeDone) {
            Icon(
                DbIcons.Bolt, contentDescription = null,
                tint = if (chargeDone) Green else Amber, modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Signal strength as 1..4 bars. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp).semantics { contentDescription = "Signal $bars of 4" }) {
        val w = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                if (i < bars) Color.White else Dark,
                topLeft = Offset(i * 2 * w, size.height - h),
                size = Size(w * 1.4f, h),
                cornerRadius = CornerRadius(w / 3),
            )
        }
    }
}

/**
 * A big number that shrinks to fit its width. The size is set from [template] (the widest
 * text it will show), so it does not jump while the value changes 10 times a second.
 */
@Composable
fun FitNumber(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 144.sp,
    template: String = "-88.8°",
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val base = numberStyle(maxSize)
        val fitted = remember(constraints, maxSize, template, density) {
            val measured = measurer.measure(template, base).size
            var scale = minOf(1f, constraints.maxWidth / measured.width.toFloat())
            if (constraints.hasBoundedHeight) scale = minOf(scale, constraints.maxHeight / measured.height.toFloat())
            if (scale >= 1f) maxSize else maxSize * scale * 0.98f
        }
        Text(text, color = color, style = numberStyle(fitted), maxLines = 1, softWrap = false)
    }
}

/** Digits with fixed widths, so changing values do not wobble. */
fun numberStyle(size: TextUnit) = TextStyle(
    fontSize = size,
    lineHeight = size * 1.1f,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

/** Keeps the display on while it is in the composition. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A label/value line of an info list. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value, Modifier.weight(1.8f), style = MaterialTheme.typography.bodyLarge, color = valueColor,
            textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis,
        )
    }
}
