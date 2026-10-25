package dev.drillbalancer.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Field limits, inclusive (SETTINGS_* in settings.h). */
object SettingsLimits {
    const val SCHEMA_VERSION = 1
    const val TOL_MIN_DEG = 0.05f
    const val TOL_MAX_DEG = 5.0f
    const val NEAR_MAX_DEG = 20.0f       // minimum is the tolerance
    const val TARGET_MAX_DEG = 90.0f     // target is -max .. max
    const val BRIGHT_MIN_PCT = 10
    const val BRIGHT_MAX_PCT = 100
    const val DIM_BRIGHT_MIN_PCT = 1     // maximum is the brightness
    const val DIM_MIN_S = 5
    const val DIM_MAX_S = 600
    const val SLEEP_MAX_S = 3600         // minimum is dim_s
    const val OFF_MAX_MIN = 1440         // 0 = never
    const val STALE_MIN_S = 10
    const val STALE_MAX_S = 600
}

/** The writable keys of the config JSON. */
enum class SettingsField(val key: String) {
    TOL("tol"),
    NEAR("near"),
    TARGET("target"),
    MODE("mode"),
    BRIGHT("bright"),
    DIM_BRIGHT("dim_bright"),
    DIM_S("dim_s"),
    SLEEP_S("sleep_s"),
    OFF_MIN("off_min"),
    BLE("ble"),
    STALE_S("stale_s"),
}

/** Calibrations the device has stored (read-only "cal" object). */
@Serializable
data class CalState(val mount: Boolean = false, val accel: Boolean = false)

/**
 * The device settings as the config characteristic reads them (settings.h). [cal] and
 * [schemaVersion] are read-only: the device ignores them on write.
 */
@Serializable
data class DeviceSettings(
    @SerialName("tol") val toleranceDeg: Float,
    @SerialName("near") val nearDeg: Float,
    @SerialName("target") val targetDeg: Float,
    @SerialName("mode") val mode: LevelMode,
    @SerialName("bright") val brightnessPct: Int,
    @SerialName("dim_bright") val dimBrightnessPct: Int,
    @SerialName("dim_s") val dimAfterS: Int,
    @SerialName("sleep_s") val sleepAfterS: Int,
    @SerialName("off_min") val offAfterMin: Int,
    @SerialName("ble") val bleEnabled: Boolean,
    @SerialName("stale_s") val yawStaleS: Int,
    @SerialName("cal") val cal: CalState = CalState(),
    @SerialName("ver") val schemaVersion: Int = SettingsLimits.SCHEMA_VERSION,
) {
    /**
     * The fields settings_validate() would reject: each range, and for a broken
     * cross-field rule the dependent field (near < tol: NEAR, dim_bright > bright:
     * DIM_BRIGHT, sleep < dim: SLEEP_S).
     */
    fun invalidFields(): Set<SettingsField> = with(SettingsLimits) {
        buildSet {
            if (!inRange(toleranceDeg, TOL_MIN_DEG, TOL_MAX_DEG)) add(SettingsField.TOL)
            if (!inRange(nearDeg, toleranceDeg, NEAR_MAX_DEG)) add(SettingsField.NEAR)
            if (!inRange(targetDeg, -TARGET_MAX_DEG, TARGET_MAX_DEG)) add(SettingsField.TARGET)
            if (brightnessPct !in BRIGHT_MIN_PCT..BRIGHT_MAX_PCT) add(SettingsField.BRIGHT)
            if (dimBrightnessPct !in DIM_BRIGHT_MIN_PCT..brightnessPct) add(SettingsField.DIM_BRIGHT)
            if (dimAfterS !in DIM_MIN_S..DIM_MAX_S) add(SettingsField.DIM_S)
            if (sleepAfterS !in dimAfterS..SLEEP_MAX_S) add(SettingsField.SLEEP_S)
            if (offAfterMin !in 0..OFF_MAX_MIN) add(SettingsField.OFF_MIN)
            if (yawStaleS !in STALE_MIN_S..STALE_MAX_S) add(SettingsField.STALE_S)
        }
    }

    val isValid: Boolean get() = invalidFields().isEmpty()

    /**
     * settings_fixup(): pushes the dependent field of each cross-field rule (near up to
     * tol, dim_bright down to bright, sleep up to dim). Consistent fields are left alone.
     */
    fun fixup(): DeviceSettings = copy(
        nearDeg = if (nearDeg < toleranceDeg) toleranceDeg else nearDeg,
        dimBrightnessPct = if (dimBrightnessPct > brightnessPct) brightnessPct else dimBrightnessPct,
        sleepAfterS = if (sleepAfterS < dimAfterS) dimAfterS else sleepAfterS,
    )

    companion object {
        /** settings_defaults() */
        val DEFAULTS = DeviceSettings(
            toleranceDeg = 0.5f,
            nearDeg = 2.0f,
            targetDeg = 45.0f,
            mode = LevelMode.WALL,
            brightnessPct = 100,
            dimBrightnessPct = 20,
            dimAfterS = 30,
            sleepAfterS = 120,
            offAfterMin = 60,
            bleEnabled = true,
            yawStaleS = 60,
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the config characteristic (all writable keys required, unknown keys ignored). */
        fun parse(text: String): Decoded<DeviceSettings> = try {
            Decoded.Ok(json.decodeFromString(serializer(), text.trimEnd('\u0000')))
        } catch (e: SerializationException) {
            Decoded.Invalid("settings JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Decoded.Invalid("settings JSON: ${e.message}")
        }

        fun parse(value: ByteArray): Decoded<DeviceSettings> = parse(value.decodeToString())
    }
}

// Like the firmware: an inclusive range that also rejects NaN.
private fun inRange(v: Float, lo: Float, hi: Float) = v >= lo && v <= hi

/**
 * A settings write: only [fields], with their values from [values]. The device applies a
 * write atomically onto its stored settings, so a save carries only what the user changed
 * and never overwrites fields changed on the device in the meantime. A write that breaks a
 * cross-field rule with the device's current values fails with ATT 0x82.
 */
class SettingsUpdate(fields: Set<SettingsField>, val values: DeviceSettings) {
    val fields: Set<SettingsField> = fields.sortedBy { it.ordinal }.toSet()

    val isEmpty: Boolean get() = fields.isEmpty()

    /** Compact JSON object of the fields, e.g. {"tol":0.3,"near":2.5}. */
    fun toJson(): String = fields.joinToString(",", "{", "}") { "\"${it.key}\":${it.json(values)}" }

    fun toBytes(): ByteArray = toJson().encodeToByteArray()

    /** What the device stores for this write: [fields] copied onto [current]. */
    fun applyTo(current: DeviceSettings): DeviceSettings = fields.fold(current) { s, f -> f.copy(values, s) }

    override fun toString() = "SettingsUpdate(${toJson()})"

    companion object {
        /** The fields [edited] changes against [base] (floats compared with ==, like settings_equal()). */
        fun diff(base: DeviceSettings, edited: DeviceSettings) =
            SettingsUpdate(SettingsField.entries.filterTo(LinkedHashSet()) { it.differs(base, edited) }, edited)
    }
}

private fun SettingsField.differs(a: DeviceSettings, b: DeviceSettings): Boolean = when (this) {
    SettingsField.TOL -> a.toleranceDeg != b.toleranceDeg
    SettingsField.NEAR -> a.nearDeg != b.nearDeg
    SettingsField.TARGET -> a.targetDeg != b.targetDeg
    SettingsField.MODE -> a.mode != b.mode
    SettingsField.BRIGHT -> a.brightnessPct != b.brightnessPct
    SettingsField.DIM_BRIGHT -> a.dimBrightnessPct != b.dimBrightnessPct
    SettingsField.DIM_S -> a.dimAfterS != b.dimAfterS
    SettingsField.SLEEP_S -> a.sleepAfterS != b.sleepAfterS
    SettingsField.OFF_MIN -> a.offAfterMin != b.offAfterMin
    SettingsField.BLE -> a.bleEnabled != b.bleEnabled
    SettingsField.STALE_S -> a.yawStaleS != b.yawStaleS
}

private fun SettingsField.json(s: DeviceSettings): String = when (this) {
    SettingsField.TOL -> formatFloat(s.toleranceDeg)
    SettingsField.NEAR -> formatFloat(s.nearDeg)
    SettingsField.TARGET -> formatFloat(s.targetDeg)
    SettingsField.MODE -> "\"${s.mode.key}\""
    SettingsField.BRIGHT -> s.brightnessPct.toString()
    SettingsField.DIM_BRIGHT -> s.dimBrightnessPct.toString()
    SettingsField.DIM_S -> s.dimAfterS.toString()
    SettingsField.SLEEP_S -> s.sleepAfterS.toString()
    SettingsField.OFF_MIN -> s.offAfterMin.toString()
    SettingsField.BLE -> s.bleEnabled.toString()
    SettingsField.STALE_S -> s.yawStaleS.toString()
}

private fun SettingsField.copy(from: DeviceSettings, to: DeviceSettings): DeviceSettings = when (this) {
    SettingsField.TOL -> to.copy(toleranceDeg = from.toleranceDeg)
    SettingsField.NEAR -> to.copy(nearDeg = from.nearDeg)
    SettingsField.TARGET -> to.copy(targetDeg = from.targetDeg)
    SettingsField.MODE -> to.copy(mode = from.mode)
    SettingsField.BRIGHT -> to.copy(brightnessPct = from.brightnessPct)
    SettingsField.DIM_BRIGHT -> to.copy(dimBrightnessPct = from.dimBrightnessPct)
    SettingsField.DIM_S -> to.copy(dimAfterS = from.dimAfterS)
    SettingsField.SLEEP_S -> to.copy(sleepAfterS = from.sleepAfterS)
    SettingsField.OFF_MIN -> to.copy(offAfterMin = from.offAfterMin)
    SettingsField.BLE -> to.copy(bleEnabled = from.bleEnabled)
    SettingsField.STALE_S -> to.copy(yawStaleS = from.yawStaleS)
}

/**
 * Shortest decimal that reads back as the same float, without exponent ("0.3", "45",
 * "0.0001"), like the firmware's format_float(). Never goes through the float's double
 * value, which would give 0.30000001192092896. Non-finite values give "null", as on the
 * device (JSON has no NaN).
 */
fun formatFloat(v: Float): String {
    if (!v.isFinite()) return "null"
    if (v == 0f) return "0"
    val exact = BigDecimal(v.toDouble())   // the float's exact value
    for (digits in 1..9) {
        val s = exact.round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
        // cJSON reads a double that settings.c narrows to float: check that path too.
        if (s.toFloat() == v && s.toDouble().toFloat() == v) return s
    }
    return exact.toPlainString()   // not reached: 9 significant digits always round-trip
}
