package dev.drillbalancer.ui.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.drillbalancer.protocol.RecOpcode
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.Taxonomy
import dev.drillbalancer.recorder.TaxonomyItem
import dev.drillbalancer.recorder.net.WifiBlock
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.SegmentedSelector
import dev.drillbalancer.ui.components.StepButton
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.formatDuration
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey

class TrainingSetupActions(
    val change: (SessionSetup) -> Unit = {},
    val start: () -> Unit = {},
    val fixBlock: () -> Unit = {},       // the app settings or the location settings, for a WifiBlock
)

/**
 * The session's labels (what is drilled, with what) and the recording settings, remembered
 * for the next session. [permissionNote]: what Start will ask Android for. [wifiBlock]: why
 * the last Start could not join the device's Wi-Fi, with the way to fix it. The fields show
 * exactly what Start sends: the number fields take the remembered setup once it has loaded,
 * and Start waits while one holds something that is not a number.
 */
@Composable
fun TrainingSetupScreen(
    state: TrainingSetupUiState,
    actions: TrainingSetupActions,
    modifier: Modifier = Modifier,
    permissionNote: String? = null,
    wifiBlock: WifiBlock? = null,
) {
    val s = state.setup
    val set = actions.change
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    // Their texts, keyed on the load: made before it, they would stay blank while the loaded
    // values are sent.
    var thickness by rememberSaveable(state.loaded) { mutableStateOf(numberText(s.thicknessMm)) }
    var diameter by rememberSaveable(state.loaded) { mutableStateOf(numberText(s.bitDiameterMm)) }
    var speed by rememberSaveable(state.loaded) { mutableStateOf(numberText(s.speedSetting)) }
    val diameterMax = SessionSetup.BIT_DIAMETER_MAX_MM
    val badNumber = !numberOk(thickness) || !numberOk(diameter, diameterMax) || !numberOk(speed)
    val blocker = state.blocker ?: if (badNumber) "A number field holds something that is not a number above 0." else null
    ScreenWithBar(modifier, barSpacing = 4.dp, bar = { StartBar(wifiBlock, blocker, actions) }) {
        SectionTitle("Workpiece")
        PickerField("Material", Taxonomy.label(Taxonomy.MATERIALS, s.material, s.materialOther)) { picker = "material" }
        if (s.material == Taxonomy.OTHER) {
            TextEntry("Which material", s.materialOther, Taxonomy.OTHER_TEXT_MAX) { set(s.copy(materialOther = it)) }
        }
        NumberEntry("Thickness", thickness, "mm") {
            thickness = it
            set(s.copy(thicknessMm = parseNumber(it)))
        }

        SectionTitle("Drill")
        Labeled("Mode") {
            SegmentedSelector(Taxonomy.MODES.map { it.id to it.label }, s.mode) { set(s.copy(mode = it)) }
        }
        PickerField("Bit", Taxonomy.label(Taxonomy.BIT_TYPES, s.bitType, s.bitOther)) { picker = "bit" }
        if (s.bitType == Taxonomy.OTHER) {
            TextEntry("Which bit", s.bitOther, Taxonomy.OTHER_TEXT_MAX) { set(s.copy(bitOther = it)) }
        }
        NumberEntry("Bit diameter", diameter, "mm", diameterMax) {
            diameter = it
            set(s.copy(bitDiameterMm = parseNumber(it, diameterMax)))
        }
        TextEntry("Drill model", s.drillModel, SessionSetup.DRILL_MODEL_MAX, words = true) { set(s.copy(drillModel = it)) }
        Labeled("Gear") {
            SegmentedSelector(listOf(null to "?", 1 to "1", 2 to "2", 3 to "3"), s.gear) { set(s.copy(gear = it)) }
        }
        NumberEntry("Speed setting", speed, "") {
            speed = it
            set(s.copy(speedSetting = parseNumber(it)))
        }
        TextEntry("Notes", s.notes, SessionSetup.NOTES_MAX, singleLine = false) { set(s.copy(notes = it)) }
        Hint("No names or places in the notes: they go into the dataset.")

        SectionTitle("Recording")
        Panel {
            Text("Motion sensor", style = MaterialTheme.typography.titleMedium)
            Text(s.profile.label, style = MaterialTheme.typography.bodyLarge)
            Hint("Hammer drilling records ±16 g so the blows are not clipped; everything else ±4 g.")
        }
        Labeled("Microphones") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MicChip("Mic 1", s.micMask and RecOpcode.MIC_1 != 0, Modifier.weight(1f)) { set(s.copy(micMask = s.micMask xor RecOpcode.MIC_1)) }
                MicChip("Mic 2", s.micMask and RecOpcode.MIC_2 != 0, Modifier.weight(1f)) { set(s.copy(micMask = s.micMask xor RecOpcode.MIC_2)) }
            }
            if (s.micMask == 0) Text("No audio: the take records motion only.", style = MaterialTheme.typography.bodyMedium, color = Amber)
        }
        Labeled("Microphone gain") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton("−", "Less gain", s.gainDb > 0) { set(s.copy(gainDb = s.gainDb - 1)) }
                Text(
                    "${s.gainDb} dB", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                )
                StepButton("+", "More gain", s.gainDb < RecOpcode.GAIN_MAX_DB) { set(s.copy(gainDb = s.gainDb + 1)) }
            }
        }
        Labeled("Longest take") {
            SegmentedSelector(SessionSetup.MAX_S_PRESETS.map { it to formatDuration(it) }, s.maxS) { set(s.copy(maxS = it)) }
        }
        permissionNote?.let { Hint(it) }
    }

    when (picker) {
        "material" -> OptionDialog("Material", Taxonomy.choices(Taxonomy.MATERIALS), s.material, onDismiss = { picker = null }) {
            set(s.copy(material = it))
            picker = null
        }
        "bit" -> OptionDialog("Bit", Taxonomy.choices(Taxonomy.BIT_TYPES), s.bitType, onDismiss = { picker = null }) {
            set(s.copy(bitType = it))
            picker = null
        }
    }
}

@Composable
private fun StartBar(wifiBlock: WifiBlock?, blocker: String?, actions: TrainingSetupActions) {
    wifiBlock?.let { b ->
        val (text, fix) = when (b) {
            WifiBlock.PERMISSION ->
                "The app may not reach the device over its Wi-Fi. Allow Nearby devices in the app's settings." to "App settings"
            WifiBlock.LOCATION_OFF ->
                "Location is off: Android 10 to 12 finds the device's Wi-Fi only with it on. The app does not use your location." to
                    "Location settings"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Amber)
        ActionButton(fix, actions.fixBlock, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
    ActionButton("Start recording session", actions.start, Modifier.fillMaxWidth(), enabled = blocker == null)
    blocker?.let { Hint(it, Modifier.fillMaxWidth()) }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Panel {
        Text(label, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Dark),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            Icon(DbIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A list to pick one from, rows glove-sized. */
@Composable
private fun OptionDialog(title: String, options: List<TaxonomyItem>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (o in options) {
                    val on = o.id == selected
                    Surface(
                        onClick = { onPick(o.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchHeight),
                        color = Color.Transparent,
                        contentColor = if (on) Green else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (on) Icon(DbIcons.Check, contentDescription = "Selected", tint = Green)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = TouchHeight)) { Text("Cancel") } },
    )
}

@Composable
private fun MicChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = on, onClick = onClick,
        label = { Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) },
        modifier = modifier.heightIn(min = TouchHeight),
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.Black,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = Grey),
    )
}

@Composable
private fun TextEntry(
    label: String,
    value: String,
    max: Int,
    singleLine: Boolean = true,
    words: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        supportingText = if (singleLine) null else ({ Text("${value.length} / $max") }),
        keyboardOptions = KeyboardOptions(capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
    )
}

/** A number field's text: blank (unknown) or a number above 0 is taken; anything else stops Start. */
@Composable
private fun NumberEntry(label: String, text: String, unit: String, max: Double = Double.MAX_VALUE, onChange: (String) -> Unit) {
    val bad = !numberOk(text, max)
    OutlinedTextField(
        value = text,
        onValueChange = { onChange(it.take(8)) },
        label = { Text(label) },
        singleLine = true,
        isError = bad,
        suffix = if (unit.isEmpty()) null else ({ Text(unit) }),
        supportingText = if (!bad) {
            null
        } else {
            { Text(if (max < Double.MAX_VALUE) "A number above 0, at most ${plain(max)}, or empty for unknown" else "A number above 0, or empty for unknown") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Grey),
    )
}

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private fun numberText(v: Double?): String = v?.let(::plain).orEmpty()

/** A number field's value: a number above 0 up to [max] (comma or point), else null (blank: unknown). */
internal fun parseNumber(text: String, max: Double = Double.MAX_VALUE): Double? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= max }

internal fun numberOk(text: String, max: Double = Double.MAX_VALUE): Boolean = text.isBlank() || parseNumber(text, max) != null
