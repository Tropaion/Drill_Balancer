package dev.drillbalancer.ui.settings

import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.protocol.SettingsLimits
import dev.drillbalancer.ui.formatDuration
import dev.drillbalancer.ui.formatFixed
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// The numeric settings: steps like the device's settings screen (ui_settings_step in
// ui_logic.c) and exact limits from settings.h. Every step and every typed value goes
// through fixup(), so the edited settings always pass the firmware's validation.

/** The numeric fields the settings screen edits, in its order. */
val NUMERIC_FIELDS = listOf(
    SettingsField.TOL, SettingsField.NEAR, SettingsField.TARGET,
    SettingsField.BRIGHT, SettingsField.DIM_BRIGHT,
    SettingsField.DIM_S, SettingsField.SLEEP_S, SettingsField.OFF_MIN,
    SettingsField.STALE_S,
)

val SettingsField.label: String
    get() = when (this) {
        SettingsField.TOL -> "Tolerance (green)"
        SettingsField.NEAR -> "Near band (amber)"
        SettingsField.TARGET -> "Target angle"
        SettingsField.MODE -> "Mode at start"
        SettingsField.BRIGHT -> "Brightness"
        SettingsField.DIM_BRIGHT -> "Dimmed brightness"
        SettingsField.DIM_S -> "Dim after"
        SettingsField.SLEEP_S -> "Sleep after"
        SettingsField.OFF_MIN -> "Power off after sleep"
        SettingsField.BLE -> "Bluetooth"
        SettingsField.STALE_S -> "Yaw drift warning after"
        SettingsField.DOT_INV -> "Invert dot"
    }

val SettingsField.help: String
    get() = when (this) {
        SettingsField.TOL -> "Shown green within this deviation."
        SettingsField.NEAR -> "Shown amber within this deviation. At least the tolerance."
        SettingsField.TARGET -> "Angle mode: the elevation to drill at."
        SettingsField.MODE -> ""
        SettingsField.BRIGHT -> ""
        SettingsField.DIM_BRIGHT -> "At most the brightness."
        SettingsField.DIM_S -> "Without touch or movement."
        SettingsField.SLEEP_S -> "At least the dim time. Movement wakes the device."
        SettingsField.OFF_MIN -> "Time asleep before it powers off."
        SettingsField.BLE -> "Lets this app connect."
        SettingsField.STALE_S -> "After a zero the yaw slowly drifts; the level then asks to re-zero."
        SettingsField.DOT_INV -> "Mirrors the device's crosshair: the dot and arrows show which way to move, not where the bit points; on the floor the bubble moves to the lower side."
    }

/** Whether the field holds whole numbers (all but the angles). */
val SettingsField.isInteger: Boolean
    get() = this != SettingsField.TOL && this != SettingsField.NEAR && this != SettingsField.TARGET

/** The unit typed values are in. */
val SettingsField.unit: String
    get() = when (this) {
        SettingsField.TOL, SettingsField.NEAR, SettingsField.TARGET -> "°"
        SettingsField.BRIGHT, SettingsField.DIM_BRIGHT -> "%"
        SettingsField.DIM_S, SettingsField.SLEEP_S, SettingsField.STALE_S -> "s"
        SettingsField.OFF_MIN -> "min"
        SettingsField.MODE, SettingsField.BLE, SettingsField.DOT_INV -> ""
    }

/** The value as the device's settings screen shows it, e.g. "0.50°", "30 s", "Never". */
fun DeviceSettings.display(field: SettingsField): String = when (field) {
    SettingsField.TOL -> formatFixed(toleranceDeg, 2, suffix = "°")
    SettingsField.NEAR -> formatFixed(nearDeg, 2, suffix = "°")
    SettingsField.TARGET -> formatFixed(targetDeg, 1, suffix = "°")
    SettingsField.BRIGHT -> "$brightnessPct %"
    SettingsField.DIM_BRIGHT -> "$dimBrightnessPct %"
    SettingsField.DIM_S -> formatDuration(dimAfterS)
    SettingsField.SLEEP_S -> formatDuration(sleepAfterS)
    SettingsField.OFF_MIN -> if (offAfterMin == 0) "Never" else formatDuration(offAfterMin * 60)
    SettingsField.STALE_S -> formatDuration(yawStaleS)
    SettingsField.MODE -> mode.key
    SettingsField.BLE -> if (bleEnabled) "On" else "Off"
    SettingsField.DOT_INV -> if (dotInvert) "On" else "Off"
}

/** The plain number for the text field ("0.5", "30"). */
fun DeviceSettings.rawValue(field: SettingsField): Float = when (field) {
    SettingsField.TOL -> toleranceDeg
    SettingsField.NEAR -> nearDeg
    SettingsField.TARGET -> targetDeg
    SettingsField.BRIGHT -> brightnessPct.toFloat()
    SettingsField.DIM_BRIGHT -> dimBrightnessPct.toFloat()
    SettingsField.DIM_S -> dimAfterS.toFloat()
    SettingsField.SLEEP_S -> sleepAfterS.toFloat()
    SettingsField.OFF_MIN -> offAfterMin.toFloat()
    SettingsField.STALE_S -> yawStaleS.toFloat()
    SettingsField.MODE, SettingsField.BLE, SettingsField.DOT_INV -> 0f
}

/**
 * The values [field] may take with the other fields as they are: the settings.h limits,
 * where the dependent field of a cross-field rule is bounded by the other one (near >= tol,
 * dim_bright <= bright, sleep >= dim). The other direction is repaired by fixup().
 */
fun DeviceSettings.range(field: SettingsField): ClosedFloatingPointRange<Float> = with(SettingsLimits) {
    when (field) {
        SettingsField.TOL -> TOL_MIN_DEG..TOL_MAX_DEG
        SettingsField.NEAR -> toleranceDeg..NEAR_MAX_DEG
        SettingsField.TARGET -> -TARGET_MAX_DEG..TARGET_MAX_DEG
        SettingsField.BRIGHT -> BRIGHT_MIN_PCT.toFloat()..BRIGHT_MAX_PCT.toFloat()
        SettingsField.DIM_BRIGHT -> DIM_BRIGHT_MIN_PCT.toFloat()..brightnessPct.toFloat()
        SettingsField.DIM_S -> DIM_MIN_S.toFloat()..DIM_MAX_S.toFloat()
        SettingsField.SLEEP_S -> dimAfterS.toFloat()..SLEEP_MAX_S.toFloat()
        SettingsField.OFF_MIN -> 0f..OFF_MAX_MIN.toFloat()
        SettingsField.STALE_S -> STALE_MIN_S.toFloat()..STALE_MAX_S.toFloat()
        SettingsField.MODE, SettingsField.BLE, SettingsField.DOT_INV -> 0f..0f
    }
}

/**
 * [v] on the grid the screen shows: 0.01° for tol and near, 0.1° for target, rounded like
 * formatFixed() (half away from zero), so a stored value is the one displayed.
 */
fun SettingsField.round(v: Float): Float {
    val scale = when (this) {
        SettingsField.TOL, SettingsField.NEAR -> 100f
        SettingsField.TARGET -> 10f
        else -> return v
    }
    val n = (abs(v) * scale).roundToLong()
    return (if (v < 0f) -n else n) / scale
}

/** Sets [field] to [v] (already in [range]) and repairs the cross-field rules. */
fun DeviceSettings.with(field: SettingsField, v: Float): DeviceSettings = when (field) {
    SettingsField.TOL -> copy(toleranceDeg = field.round(v))
    SettingsField.NEAR -> copy(nearDeg = field.round(v))
    SettingsField.TARGET -> copy(targetDeg = field.round(v))
    SettingsField.BRIGHT -> copy(brightnessPct = v.roundToInt())
    SettingsField.DIM_BRIGHT -> copy(dimBrightnessPct = v.roundToInt())
    SettingsField.DIM_S -> copy(dimAfterS = v.roundToInt())
    SettingsField.SLEEP_S -> copy(sleepAfterS = v.roundToInt())
    SettingsField.OFF_MIN -> copy(offAfterMin = v.roundToInt())
    SettingsField.STALE_S -> copy(yawStaleS = v.roundToInt())
    SettingsField.MODE, SettingsField.BLE, SettingsField.DOT_INV -> this
}.fixup()

/** The text a typed value is checked against; null if it is fine. */
fun DeviceSettings.entryError(field: SettingsField, text: String): String? {
    val v = parseEntry(text) ?: return "Enter a number."
    if (field.isInteger && v != v.roundToInt().toFloat()) return "Enter a whole number."
    // Checked as displayed: the typed value and the bounds on the screen's grid. A bound
    // that is off the grid (set by another client) is repaired by fixup().
    val r = range(field)
    if (field.round(v) !in field.round(r.start)..field.round(r.endInclusive)) return "Enter a value from ${rangeText(field, r)}."
    return null
}

/** Accepts "0.5" and "0,5". */
fun parseEntry(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

fun rangeText(field: SettingsField, r: ClosedFloatingPointRange<Float>) =
    "${plainValue(field, r.start)} to ${plainValue(field, r.endInclusive)} ${field.unit}"

/** A value as typed: "0.50", "45.0", "30". */
fun plainValue(field: SettingsField, v: Float): String = when (field) {
    SettingsField.TOL, SettingsField.NEAR -> formatFixed(v, 2)
    SettingsField.TARGET -> formatFixed(v, 1)
    else -> v.roundToInt().toString()
}

// ---- steps (ui_settings_step) ----

// The first three are ui_logic.c's (FirmwareConformanceTest checks them); the yaw drift
// warning is not on the device's screen.
internal val DIM_PRESETS_S = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600)
internal val SLEEP_PRESETS_S = intArrayOf(15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600)
internal val OFF_PRESETS_MIN = intArrayOf(0, 15, 30, 60, 120, 240, 480, 720, 1440)
private val STALE_PRESETS_S = intArrayOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600)

/** One step up ([dir] > 0) or down, within the limits, then fixup(). */
fun DeviceSettings.step(field: SettingsField, dir: Int): DeviceSettings = with(SettingsLimits) {
    val up = dir > 0
    val d = if (up) 1 else -1
    when (field) {
        SettingsField.TOL -> {
            // Fine steps where drilling tolerances usually are, coarse above 1°
            val t = (toleranceDeg * 100f).roundToInt()
            val step = if (if (up) t < 100 else t <= 100) 5 else 25
            val n = gridStep(t, step, d).coerceIn((TOL_MIN_DEG * 100f).roundToInt(), (TOL_MAX_DEG * 100f).roundToInt())
            copy(toleranceDeg = n / 100f)
        }
        SettingsField.NEAR -> {
            val t = (nearDeg * 100f).roundToInt()
            val step = if (if (up) t < 500 else t <= 500) 25 else 100
            copy(nearDeg = minOf(NEAR_MAX_DEG, maxOf(toleranceDeg, gridStep(t, step, d) / 100f)))
        }
        SettingsField.TARGET -> {
            val max = (TARGET_MAX_DEG * 10f).roundToInt()
            copy(targetDeg = gridStep((targetDeg * 10f).roundToInt(), 10, d).coerceIn(-max, max) / 10f)
        }
        SettingsField.BRIGHT -> copy(brightnessPct = gridStep(brightnessPct, 10, d).coerceIn(BRIGHT_MIN_PCT, BRIGHT_MAX_PCT))
        // Not on the device's screen: steps of 5 %
        SettingsField.DIM_BRIGHT ->
            copy(dimBrightnessPct = gridStep(dimBrightnessPct, 5, d).coerceIn(DIM_BRIGHT_MIN_PCT, brightnessPct))
        SettingsField.DIM_S -> copy(dimAfterS = presetStep(DIM_PRESETS_S, dimAfterS, up, DIM_MIN_S, DIM_MAX_S))
        SettingsField.SLEEP_S -> copy(sleepAfterS = presetStep(SLEEP_PRESETS_S, sleepAfterS, up, dimAfterS, SLEEP_MAX_S))
        SettingsField.OFF_MIN -> copy(offAfterMin = presetStep(OFF_PRESETS_MIN, offAfterMin, up, 0, OFF_MAX_MIN))
        SettingsField.STALE_S -> copy(yawStaleS = presetStep(STALE_PRESETS_S, yawStaleS, up, STALE_MIN_S, STALE_MAX_S))
        SettingsField.MODE, SettingsField.BLE, SettingsField.DOT_INV -> this@step
    }.fixup()
}

// Next grid value strictly above / below v, so off-grid values snap onto the grid.
private fun gridStep(v: Int, step: Int, dir: Int): Int =
    if (dir > 0) Math.floorDiv(v, step) * step + step else -Math.floorDiv(-v, step) * step - step

private fun presetStep(list: IntArray, cur: Int, up: Boolean, lo: Int, hi: Int): Int {
    var out = cur
    if (up) {
        list.firstOrNull { it > cur && it <= hi }?.let { out = it }
    } else {
        out = if (lo < cur) lo else cur
        list.lastOrNull { it < cur }?.let { out = maxOf(it, lo) }
    }
    return out.coerceIn(lo, hi)
}
