package dev.drillbalancer.protocol

// Calibration results and sensor-calibration coverage (level.h, accel_cal.h,
// app_state.h). Messages follow the device's result screens (ui_logic.c).

/** level_cal_result_t: mount calibration. */
enum class MountCalResult(val code: Int, val message: String) {
    OK(0, "Mount calibrated."),
    TOO_FEW_SAMPLES(1, "Too few samples. Roll a little longer."),
    TOO_LITTLE_ROLL(2, "Not enough roll. Roll 45° each way, in a level or tilted hole."),
    NOT_ON_CONE(3, "The bit moved. Keep it steady in the hole."),
    OFFSET_TOO_LARGE(4, "Mount is too crooked. Re-seat the device."),
}

/** accel_cal_result_t: six-position sensor calibration. */
enum class SensorCalResult(val code: Int, val message: String) {
    // OK means there was no mount calibration to reset (else the result is -3).
    OK(0, "Sensor calibrated. Now run the mount calibration."),
    POOR_COVERAGE(1, "Not all six sides captured."),
    FIT_FAILED(2, "Fit failed. Hold still and try again."),
    OUT_OF_RANGE(3, "Sensor error too large. Try again; if it repeats, the sensor may be faulty."),
}

/** The status packet's cal_last_result, read for the kind of calibration that finished. */
sealed interface CalResult {
    val success: Boolean
    val message: String

    /** -1: no result yet, or cancelled. */
    data object None : CalResult {
        override val success = false
        override val message = "No result. Please try again."
    }

    /** -2: solved, but saving failed; the old calibration stays in use. */
    data object NotStored : CalResult {
        override val success = false
        override val message = "Calibrated, but not saved. Please try again."
    }

    /** -3: sensor calibration solved and saved; the mount calibration was reset. */
    data object SensorOkMountReset : CalResult {
        override val success = true
        override val message = "Sensor calibrated. Now redo the mount calibration."
    }

    data class Mount(val result: MountCalResult) : CalResult {
        override val success get() = result == MountCalResult.OK
        override val message get() = result.message
    }

    data class Sensor(val result: SensorCalResult) : CalResult {
        override val success get() = result == SensorCalResult.OK
        override val message get() = result.message
    }

    data class Unknown(val code: Int) : CalResult {
        override val success = false
        override val message = "Unknown result."
    }

    companion object {
        // APP_CAL_RESULT_*
        const val NONE = -1
        const val NOT_STORED = -2
        const val OK_MOUNT_DROPPED = -3

        fun decode(code: Int, finishedKind: CalKind): CalResult = when {
            code == NONE -> None
            code == NOT_STORED -> NotStored
            code == OK_MOUNT_DROPPED -> SensorOkMountReset
            code < 0 -> Unknown(code)
            finishedKind == CalKind.MOUNT ->
                MountCalResult.entries.firstOrNull { it.code == code }?.let(::Mount) ?: Unknown(code)
            finishedKind == CalKind.ACCEL ->
                SensorCalResult.entries.firstOrNull { it.code == code }?.let(::Sensor) ?: Unknown(code)
            else -> Unknown(code)
        }
    }
}

/**
 * ACCEL_CAL_DIR_*: the board direction facing up in a sensor-calibration pose. Declared in
 * the order of the device's pose tiles (ui_poses in ui_logic.c), with the same labels.
 */
enum class CoverageDir(val bit: Int, val label: String) {
    POS_Z(1 shl 4, "Screen up"),
    NEG_Z(1 shl 5, "Screen down"),
    POS_Y(1 shl 2, "Top edge up"),
    NEG_Y(1 shl 3, "Bottom edge up"),
    NEG_X(1 shl 1, "Left edge up"),
    POS_X(1 shl 0, "Right edge up");

    companion object {
        const val ALL = 0x3F

        fun fromBits(bits: Int): Set<CoverageDir> = entries.filterTo(LinkedHashSet()) { bits and it.bit != 0 }
    }
}
