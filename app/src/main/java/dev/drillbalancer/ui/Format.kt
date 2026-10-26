package dev.drillbalancer.ui

import dev.drillbalancer.protocol.LevelStatus
import dev.drillbalancer.protocol.StatusPacket
import kotlin.math.abs
import kotlin.math.roundToLong

// Number formatting and small judgements shared by the screens. They follow the
// device's ui_logic.c, so the phone and the drill show the same text.

private val POW10 = intArrayOf(1, 10, 100, 1000)
private const val MAX_MAGNITUDE = 9999

/** ui_fmt_fixed(): the sign follows the rounded value (never "-0.0"); saturates at 9999. */
fun formatFixed(v: Float, decimals: Int, showPlus: Boolean = false, suffix: String = ""): String {
    if (!v.isFinite()) return "--$suffix"
    val d = decimals.coerceIn(0, 3)
    val scale = POW10[d]
    val n = minOf(abs(v) * scale, (MAX_MAGNITUDE * scale).toFloat()).roundToLong()
    val sign = when {
        n == 0L -> ""
        v < 0f -> "-"
        showPlus -> "+"
        else -> ""
    }
    if (d == 0) return "$sign$n$suffix"
    return "$sign${n / scale}.${(n % scale).toString().padStart(d, '0')}$suffix"
}

/** One decimal and a degree sign, e.g. "-12.3°" (ui_fmt_deg). */
fun formatDeg(deg: Float, showPlus: Boolean = false): String = formatFixed(deg, 1, showPlus, "°")

/** Tenths of a degree as displayed (ui_tenths). */
fun tenths(deg: Float): Int {
    if (!deg.isFinite()) return 0
    // lroundf: half away from zero
    val n = minOf(abs(deg) * 10f, MAX_MAGNITUDE * 10f).roundToLong().toInt()
    return if (deg < 0f) -n else n
}

/** Wall mode arrow (ui_wall_arrow): +1 bit above horizontal, -1 below, 0 none. */
fun wallArrow(elevationDeg: Float, status: LevelStatus?): Int {
    if (status == LevelStatus.ON_TARGET) return 0
    val t = tenths(elevationDeg)
    return if (t > 0) 1 else if (t < 0) -1 else 0
}

/** "R 0.1°" / "L 6.4°" / "0.0°": yaw since zero, right positive (ui_level.c). */
fun formatYaw(yawDeg: Float): String {
    val t = tenths(yawDeg)
    val deg = formatDeg(abs(yawDeg))
    return if (t > 0) "R $deg" else if (t < 0) "L $deg" else deg
}

/** level_status_for(): the band an error falls in. */
fun levelStatusFor(errorDeg: Float, toleranceDeg: Float, nearDeg: Float): LevelStatus {
    val e = abs(errorDeg)
    return when {
        e <= toleranceDeg -> LevelStatus.ON_TARGET
        e <= nearDeg -> LevelStatus.NEAR
        else -> LevelStatus.OFF
    }
}

/** "30 s", "2 min", "1 h" (fmt_duration_s in ui_logic.c). */
fun formatDuration(seconds: Int): String = when {
    seconds != 0 && seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds != 0 && seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}

/** Battery icon level 0 (empty) .. 4 (full), as ui_battery_level(). */
fun batteryLevel(percent: Int): Int = when {
    percent >= 88 -> 4
    percent >= 63 -> 3
    percent >= 38 -> 2
    percent >= LOW_BATTERY_PCT -> 1
    else -> 0
}

const val LOW_BATTERY_PCT = 10

/**
 * The charge to show: the status packet's while there is one (null when it says the gauge
 * reading is invalid; Battery Service then reads 0), else Battery Service's [battery].
 */
fun displayBatteryPct(status: StatusPacket?, battery: Int?): Int? =
    if (status != null) status.batteryPct.takeIf { status.batteryValid } else battery

/** "1.2 MB" / "640 KB" for file sizes and transfer counts (1 KB = 1024 bytes); "12.0 GB" for free storage. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> formatFixed(bytes / (1024f * 1024f * 1024f), 1, suffix = " GB")
    bytes >= 1024 * 1024 -> formatFixed(bytes / (1024f * 1024f), 1, suffix = " MB")
    bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "$bytes B"
}

/** A rate with one decimal and no saturation, e.g. "16000.4 Hz" (formatFixed stops at 9999). */
fun formatRate(v: Double, unit: String): String = if (v.isFinite()) "%.1f %s".format(java.util.Locale.ROOT, v, unit) else "-- $unit"

/**
 * "1:05" minutes and seconds, "1:02:03" past an hour; [padMinutes] gives a clock of steady
 * width, "01:05" (the elapsed take time, the recording notification).
 */
fun formatMinSec(seconds: Long, padMinutes: Boolean = false): String = when {
    seconds >= 3600 -> "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    padMinutes -> "%02d:%02d".format(seconds / 60, seconds % 60)
    else -> "%d:%02d".format(seconds / 60, seconds % 60)
}
