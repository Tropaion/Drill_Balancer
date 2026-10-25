package dev.drillbalancer.protocol

/** DB_CMD_* opcodes of the command characteristic: u8 opcode, then args. */
object CommandOpcode {
    const val ZERO = 0x01
    const val CLEAR_ZERO = 0x02
    const val SET_MODE = 0x03            // u8 mode
    const val MOUNT_CAL_START = 0x10
    const val MOUNT_CAL_FINISH = 0x11
    const val ACCEL_CAL_START = 0x12
    const val ACCEL_CAL_FINISH = 0x13
    const val CAL_CANCEL = 0x14
    const val SLEEP = 0x20
    const val POWER_OFF = 0x21
    const val REBOOT = 0x22
    const val FACTORY_RESET = 0x23       // u8 FACTORY_RESET_CONFIRM
    const val FACTORY_RESET_CONFIRM = 0xA5
}

/**
 * A command write. Needs a bonded link. A calibration started over BLE is cancelled
 * when the link drops; any calibration after a long idle time (sleep_s + 5 min).
 */
sealed interface Command {
    data object Zero : Command
    data object ClearZero : Command
    data class SetMode(val mode: LevelMode) : Command
    data object MountCalStart : Command
    data object MountCalFinish : Command
    data object AccelCalStart : Command
    data object AccelCalFinish : Command
    data object CalCancel : Command
    data object Sleep : Command
    data object PowerOff : Command
    data object Reboot : Command

    /** Settings and calibration back to defaults; also deletes all bonds and drops the link. */
    data object FactoryReset : Command

    fun encode(): ByteArray = when (this) {
        Zero -> op(CommandOpcode.ZERO)
        ClearZero -> op(CommandOpcode.CLEAR_ZERO)
        is SetMode -> op(CommandOpcode.SET_MODE, mode.wire)
        MountCalStart -> op(CommandOpcode.MOUNT_CAL_START)
        MountCalFinish -> op(CommandOpcode.MOUNT_CAL_FINISH)
        AccelCalStart -> op(CommandOpcode.ACCEL_CAL_START)
        AccelCalFinish -> op(CommandOpcode.ACCEL_CAL_FINISH)
        CalCancel -> op(CommandOpcode.CAL_CANCEL)
        Sleep -> op(CommandOpcode.SLEEP)
        PowerOff -> op(CommandOpcode.POWER_OFF)
        Reboot -> op(CommandOpcode.REBOOT)
        FactoryReset -> op(CommandOpcode.FACTORY_RESET, CommandOpcode.FACTORY_RESET_CONFIRM)
    }
}

private fun op(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }
