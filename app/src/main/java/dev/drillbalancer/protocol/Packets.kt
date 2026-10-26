package dev.drillbalancer.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

// Angle and status packets of the DrillBalancer service (ble_protocol.h).
// Angles are int16 centi-degrees on the wire.

/** level_mode_t; [key] is the settings JSON name. */
@Serializable
enum class LevelMode(val wire: Int, val key: String) {
    @SerialName("wall") WALL(0, "wall"),     // horizontal hole: elevation 0 plus relative yaw
    @SerialName("floor") FLOOR(1, "floor"),  // vertical hole (floor or ceiling): 2D bubble
    @SerialName("angle") ANGLE(2, "angle");  // elevation against the target angle

    companion object {
        fun fromWire(v: Int): LevelMode? = entries.firstOrNull { it.wire == v }
    }
}

/** level_status_t: the band the deviation falls in. */
enum class LevelStatus { ON_TARGET, NEAR, OFF }

/** app_power_t */
enum class PowerState(val wire: Int) {
    ACTIVE(0), DIM(1), SLEEP(2), OFF(3);

    companion object {
        fun fromWire(v: Int): PowerState? = entries.firstOrNull { it.wire == v }
    }
}

/** app_cal_kind_t */
enum class CalKind(val wire: Int) {
    IDLE(0), MOUNT(1), ACCEL(2);

    companion object {
        fun fromWire(v: Int): CalKind? = entries.firstOrNull { it.wire == v }
    }
}

/** DB_ANGLE_FLAG_* */
object AngleFlags {
    const val ON_TARGET = 1 shl 0
    const val NEAR = 1 shl 1
    const val YAW_VALID = 1 shl 2
    const val YAW_STALE = 1 shl 3
    const val ZERO_SET = 1 shl 4    // any mode, also with the bit vertical (YAW_VALID clear)
    const val STILL = 1 shl 5
    const val IMU_OK = 1 shl 6      // without it only ZERO_SET can be set; angles meaningless
}

/** DB_STATUS_FLAG_* */
object StatusFlags {
    const val BATTERY_VALID = 1 shl 0
    const val CHARGING = 1 shl 1
    const val CHARGE_DONE = 1 shl 2
    const val VBUS = 1 shl 3
    const val MOUNT_CAL = 1 shl 4
    const val ACCEL_CAL = 1 shl 5
    const val CALIBRATING = 1 shl 6
    const val RECORDING = 1 shl 7   // a recorder session is open
}

/** db_angle_packet_t (14 bytes), notified at 10 Hz. Angles are kept as on the wire. */
data class AnglePacket(
    val mode: LevelMode,
    val flags: Int,
    val seq: Int,
    val elevationCdeg: Int,
    val yawCdeg: Int,
    val bubbleXCdeg: Int,
    val bubbleYCdeg: Int,
    val deviationCdeg: Int,
) {
    val elevationDeg: Float get() = elevationCdeg / 100f   // bit above horizontal is positive
    val yawDeg: Float get() = yawCdeg / 100f               // turned right since zero is positive
    val bubbleXDeg: Float get() = bubbleXCdeg / 100f
    val bubbleYDeg: Float get() = bubbleYCdeg / 100f
    val deviationDeg: Float get() = deviationCdeg / 100f   // what the mode judges

    val onTarget: Boolean get() = has(AngleFlags.ON_TARGET)
    val near: Boolean get() = has(AngleFlags.NEAR)
    val yawValid: Boolean get() = has(AngleFlags.YAW_VALID)
    val yawStale: Boolean get() = has(AngleFlags.YAW_STALE)
    val zeroSet: Boolean get() = has(AngleFlags.ZERO_SET)
    val still: Boolean get() = has(AngleFlags.STILL)
    val imuOk: Boolean get() = has(AngleFlags.IMU_OK)

    /** The band of the deviation, or null without IMU_OK (then nothing may show green). */
    val status: LevelStatus?
        get() = when {
            !imuOk -> null
            onTarget -> LevelStatus.ON_TARGET
            near -> LevelStatus.NEAR
            else -> LevelStatus.OFF
        }

    private fun has(bit: Int) = flags and bit != 0

    fun encode(): ByteArray = ByteArray(SIZE).also {
        it[0] = VERSION.toByte()
        it[1] = mode.wire.toByte()
        it[2] = flags.toByte()
        it[3] = seq.toByte()
        it.put16(4, elevationCdeg)
        it.put16(6, yawCdeg)
        it.put16(8, bubbleXCdeg)
        it.put16(10, bubbleYCdeg)
        it.put16(12, deviationCdeg)
    }

    companion object {
        const val VERSION = 1
        const val SIZE = 14

        fun decode(value: ByteArray): Decoded<AnglePacket> {
            if (value.size != SIZE) return Decoded.Invalid("angle packet is ${value.size} bytes, expected $SIZE")
            if (value.u8(0) != VERSION) return Decoded.Invalid("angle packet version ${value.u8(0)}, expected $VERSION")
            val mode = LevelMode.fromWire(value.u8(1)) ?: return Decoded.Invalid("unknown mode ${value.u8(1)}")
            return Decoded.Ok(
                AnglePacket(
                    mode = mode,
                    flags = value.u8(2),
                    seq = value.u8(3),
                    elevationCdeg = value.s16(4),
                    yawCdeg = value.s16(6),
                    bubbleXCdeg = value.s16(8),
                    bubbleYCdeg = value.s16(10),
                    deviationCdeg = value.s16(12),
                ),
            )
        }

        /** Builds a packet from degrees, rounded like the firmware (for fakes and tests). */
        fun of(
            mode: LevelMode,
            flags: Int,
            seq: Int = 0,
            elevationDeg: Float = 0f,
            yawDeg: Float = 0f,
            bubbleXDeg: Float = 0f,
            bubbleYDeg: Float = 0f,
            deviationDeg: Float = 0f,
        ) = AnglePacket(
            mode, flags, seq and 0xFF,
            cdeg(elevationDeg), cdeg(yawDeg), cdeg(bubbleXDeg), cdeg(bubbleYDeg), cdeg(deviationDeg),
        )

        /** ble_codec_cdeg(): rounded half away from zero, saturated to int16, NaN gives 0. */
        fun cdeg(deg: Float): Int {
            if (deg.isNaN()) return 0
            val c = deg * 100f
            val r = Math.round(abs(c)).toLong()   // half up on the magnitude, saturates at Int
            return (if (c < 0f) -r else r).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
        }
    }
}

/** db_status_packet_t (10 bytes): read, notified on change and every second. */
data class StatusPacket(
    val batteryPct: Int,          // 0 while the gauge reading is invalid
    val batteryMv: Int,
    val flags: Int,
    val powerState: PowerState,
    val calKind: CalKind,         // the calibration running now (IDLE once finished)
    val calSamples: Int,          // mount: samples so far; accel: poses kept (saturates at 255)
    val calCoverage: Int,         // CoverageDir bits captured by the sensor calibration
    val calLastResult: Int,       // raw; see lastResult()
) {
    val batteryValid: Boolean get() = has(StatusFlags.BATTERY_VALID)
    val charging: Boolean get() = has(StatusFlags.CHARGING)
    val chargeDone: Boolean get() = has(StatusFlags.CHARGE_DONE)
    val vbus: Boolean get() = has(StatusFlags.VBUS)
    val mountCalValid: Boolean get() = has(StatusFlags.MOUNT_CAL)
    val accelCalValid: Boolean get() = has(StatusFlags.ACCEL_CAL)
    val calibrating: Boolean get() = has(StatusFlags.CALIBRATING)

    val coverage: Set<CoverageDir> get() = CoverageDir.fromBits(calCoverage)

    /**
     * The result of the last finished calibration. The packet does not say which kind
     * that was, so the caller passes it (the last non-IDLE [calKind] it saw).
     */
    fun lastResult(finishedKind: CalKind): CalResult = CalResult.decode(calLastResult, finishedKind)

    private fun has(bit: Int) = flags and bit != 0

    fun encode(): ByteArray = ByteArray(SIZE).also {
        it[0] = VERSION.toByte()
        it[1] = batteryPct.toByte()
        it.put16(2, batteryMv)
        it[4] = flags.toByte()
        it[5] = powerState.wire.toByte()
        it[6] = calKind.wire.toByte()
        it[7] = calSamples.toByte()
        it[8] = calCoverage.toByte()
        it[9] = calLastResult.toByte()
    }

    companion object {
        const val VERSION = 1
        const val SIZE = 10

        fun decode(value: ByteArray): Decoded<StatusPacket> {
            if (value.size != SIZE) return Decoded.Invalid("status packet is ${value.size} bytes, expected $SIZE")
            if (value.u8(0) != VERSION) return Decoded.Invalid("status packet version ${value.u8(0)}, expected $VERSION")
            val power = PowerState.fromWire(value.u8(5)) ?: return Decoded.Invalid("unknown power state ${value.u8(5)}")
            val kind = CalKind.fromWire(value.u8(6)) ?: return Decoded.Invalid("unknown calibration kind ${value.u8(6)}")
            return Decoded.Ok(
                StatusPacket(
                    batteryPct = value.u8(1),
                    batteryMv = value.u16(2),
                    flags = value.u8(4),
                    powerState = power,
                    calKind = kind,
                    calSamples = value.u8(7),
                    calCoverage = value.u8(8),
                    calLastResult = value.s8(9),
                ),
            )
        }
    }
}

/** Battery Level 0x2A19: one byte, 0..100 (0 while the gauge reading is invalid). */
fun decodeBatteryLevel(value: ByteArray): Decoded<Int> = when {
    value.size != 1 -> Decoded.Invalid("battery level is ${value.size} bytes, expected 1")
    value.u8(0) > 100 -> Decoded.Invalid("battery level ${value.u8(0)} % out of range")
    else -> Decoded.Ok(value.u8(0))
}
