package dev.drillbalancer.protocol

// The recorder service (ble_protocol.h DB_SVC_REC): rec_ctrl writes and the rec_state packet.

/** DB_REC_STATE_*: the device's recorder session. */
enum class RecState(val wire: Int) {
    IDLE(0),
    AP_STARTING(1),
    WAIT_CLIENT(2),   // AP up, credentials valid: join and connect
    READY(3),         // stream client connected
    RECORDING(4),
    DRAINING(5),      // take stopped, the rest and END go out
    CLOSING(6);

    companion object {
        fun fromWire(v: Int): RecState? = entries.firstOrNull { it.wire == v }
    }
}

/** DB_REC_REASON_* (rec_stream.h REC_REASON_*): why the last take or session ended. */
enum class RecReason(val wire: Int, val text: String) {
    NONE(0, "no reason"),
    USER(1, "stopped"),
    MAX_S(2, "the time limit was reached"),
    CLIENT_LOST(3, "the stream connection dropped"),
    BLE_LOST(4, "Bluetooth was gone for 2 minutes"),
    BATTERY(5, "the device's battery is low"),
    IMU(6, "the motion sensor stopped working"),
    SHUTDOWN(7, "the device went to sleep, powered off or rebooted"),
    AP_FAIL(8, "the device could not start its Wi-Fi"),
    NO_CLIENT(9, "the phone did not connect in time"),
    IDLE(10, "no take for 15 minutes");

    companion object {
        fun fromWire(v: Int): RecReason? = entries.firstOrNull { it.wire == v }
    }
}

/** DB_REC_PROFILE_* (imu_profile_t): the IMU setting of a take. */
enum class RecProfile(val wire: Int, val label: String) {
    LEVEL(0, "Level (filtered)"),
    RAW448(1, "Raw 448 Hz ±4 g"),
    RAW448_16G(2, "Raw 448 Hz ±16 g"),
    RAW897(3, "Raw 897 Hz ±4 g (experimental)");

    companion object {
        fun fromWire(v: Int): RecProfile? = entries.firstOrNull { it.wire == v }
    }
}

/** DB_REC_FLAG_* */
object RecFlags {
    const val NO_AP = 1 shl 0         // no soft-AP: connect over the current network (simulator)
    const val CLIENT = 1 shl 1        // a stream client is connected
    const val AUDIO = 1 shl 2         // the microphones are recording
    const val IMU_LOST = 1 shl 3
    const val BATTERY_LOW = 1 shl 4   // below the take-start limit without USB
}

/** rec_ctrl opcodes and argument limits (DB_REC_OP_*, DB_REC_*). */
object RecOpcode {
    const val OPEN = 0x01
    const val CLOSE = 0x02
    const val TAKE_START = 0x03       // u8 profile, u8 mic_mask, u8 gain_db, u16 max_s, u32 take_id
    const val STOP = 0x04
    const val MARK = 0x05             // u16 code, UTF-8 text
    const val TAKE_START_LEN = 10
    const val MARK_TEXT_MAX = 40
    const val GAIN_MAX_DB = 37
    const val MAX_S_DEFAULT = 300
    const val MAX_S_LIMIT = 1200
    const val MIC_1 = 1 shl 0
    const val MIC_2 = 1 shl 1
    const val MIC_ALL = MIC_1 or MIC_2
}

/** TAKE_START's arguments. [maxS] 0 means the device's default (300 s). */
data class TakeRequest(
    val profile: RecProfile,
    val micMask: Int = RecOpcode.MIC_ALL,
    val gainDb: Int = 6,
    val maxS: Int = RecOpcode.MAX_S_DEFAULT,
    val takeId: Long,
) {
    init {
        require(micMask and RecOpcode.MIC_ALL.inv() == 0) { "mic_mask $micMask" }
        require(gainDb in 0..RecOpcode.GAIN_MAX_DB) { "gain_db $gainDb" }
        require(maxS in 0..RecOpcode.MAX_S_LIMIT) { "max_s $maxS" }
        require(takeId in 0..0xFFFF_FFFFL) { "take_id $takeId" }
    }
}

/** A rec_ctrl write. Needs the bonded, authenticated link. */
sealed interface RecCtrl {
    data object Open : RecCtrl
    data object Close : RecCtrl
    data class TakeStart(val request: TakeRequest) : RecCtrl
    data object Stop : RecCtrl

    /** An EVENT MARK in the stream. Throws IllegalArgumentException for text the device refuses. */
    data class Mark(val code: Int, val text: String = "") : RecCtrl {
        init {
            require(code in 0..0xFFFF) { "mark code $code" }
            require(markTextOk(text)) { "mark text: at most ${RecOpcode.MARK_TEXT_MAX} bytes of printable UTF-8" }
        }
    }

    fun encode(): ByteArray = when (this) {
        Open -> byteArrayOf(RecOpcode.OPEN.toByte())
        Close -> byteArrayOf(RecOpcode.CLOSE.toByte())
        Stop -> byteArrayOf(RecOpcode.STOP.toByte())
        is TakeStart -> ByteArray(RecOpcode.TAKE_START_LEN).also {
            it[0] = RecOpcode.TAKE_START.toByte()
            it[1] = request.profile.wire.toByte()
            it[2] = request.micMask.toByte()
            it[3] = request.gainDb.toByte()
            it.put16(4, request.maxS)
            it.put32(6, request.takeId)
        }
        is Mark -> {
            val t = text.encodeToByteArray()
            ByteArray(3 + t.size).also {
                it[0] = RecOpcode.MARK.toByte()
                it.put16(1, code)
                t.copyInto(it, 3)
            }
        }
    }

    companion object {
        /** ble_codec.c mark_text_ok(): well-formed, without C0/C1 controls or DEL, <= 40 bytes. */
        fun markTextOk(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
                    i += 2
                    continue
                }
                if (c.isLowSurrogate() || c.code < 0x20 || c.code in 0x7F..0x9F) return false
                i++
            }
            return text.encodeToByteArray().size <= RecOpcode.MARK_TEXT_MAX
        }
    }
}

/**
 * db_rec_state_packet_t (86 bytes): read with an authenticated link, notified on change and
 * every second during a take. [ip], [port], [ssid], [pass] and [token] are zero unless the
 * session is reachable (WAIT_CLIENT .. DRAINING); [ssid]/[pass] are empty with NO_AP.
 * [read] is false for [NOT_READ], the placeholder before the first secured read.
 */
data class RecorderStatus(
    val state: RecState,
    val reasonCode: Int,
    val flags: Int,
    val takeId: Long,
    val elapsedS: Int,
    val maxS: Int,
    val sentKib: Long,
    val dropped: Long,
    val ringPct: Int,
    val profileCode: Int,
    val channel: Int,
    val port: Int,
    val ip: String,
    val ssid: String,
    val pass: String,
    val token: String,     // 32 hex digits
    val read: Boolean = true,
) {
    val reason: RecReason? get() = RecReason.fromWire(reasonCode)
    val profile: RecProfile? get() = RecProfile.fromWire(profileCode)
    val noAp: Boolean get() = flags and RecFlags.NO_AP != 0
    val clientConnected: Boolean get() = flags and RecFlags.CLIENT != 0
    val audio: Boolean get() = flags and RecFlags.AUDIO != 0
    val imuLost: Boolean get() = flags and RecFlags.IMU_LOST != 0
    val batteryLow: Boolean get() = flags and RecFlags.BATTERY_LOW != 0

    /** The stream endpoint and credentials are valid. */
    val reachable: Boolean
        get() = port != 0 && state in setOf(RecState.WAIT_CLIENT, RecState.READY, RecState.RECORDING, RecState.DRAINING)

    fun tokenBytes(): ByteArray = token.hexToByteArray()

    fun encode(): ByteArray = ByteArray(SIZE).also {
        it[0] = VERSION.toByte()
        it[1] = state.wire.toByte()
        it[2] = reasonCode.toByte()
        it[3] = flags.toByte()
        it.put32(4, takeId)
        it.put16(8, elapsedS)
        it.put16(10, maxS)
        it.put32(12, sentKib)
        it.put32(16, dropped)
        it[20] = ringPct.toByte()
        it[21] = profileCode.toByte()
        it[22] = channel.toByte()
        it.put16(24, port)
        if (ip.isNotEmpty()) ip.split('.').forEachIndexed { i, part -> it[26 + i] = part.toInt().toByte() }
        ssid.encodeToByteArray().copyInto(it, 30)
        pass.encodeToByteArray().copyInto(it, 46)
        tokenBytes().copyInto(it, 70)
    }

    companion object {
        const val VERSION = 1
        const val SIZE = 86
        const val TOKEN_LEN = 16

        /** Before the first secured read: nothing known, assume no session. */
        val NOT_READ = RecorderStatus(
            RecState.IDLE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", "", "", read = false,
        )

        fun decode(value: ByteArray): Decoded<RecorderStatus> {
            if (value.size != SIZE) return Decoded.Invalid("rec_state packet is ${value.size} bytes, expected $SIZE")
            if (value.u8(0) != VERSION) return Decoded.Invalid("rec_state version ${value.u8(0)}, expected $VERSION")
            val state = RecState.fromWire(value.u8(1)) ?: return Decoded.Invalid("unknown recorder state ${value.u8(1)}")
            val port = value.u16(24)
            val ipBytes = (26 until 30).map { value.u8(it) }
            return Decoded.Ok(
                RecorderStatus(
                    state = state,
                    reasonCode = value.u8(2),
                    flags = value.u8(3),
                    takeId = value.u32(4),
                    elapsedS = value.u16(8),
                    maxS = value.u16(10),
                    sentKib = value.u32(12),
                    dropped = value.u32(16),
                    ringPct = value.u8(20),
                    profileCode = value.u8(21),
                    channel = value.u8(22),
                    port = port,
                    ip = if (ipBytes.all { it == 0 }) "" else ipBytes.joinToString("."),
                    ssid = value.cString(30, 16),
                    pass = value.cString(46, 24),
                    token = value.copyOfRange(70, 86).toHex(),
                ),
            )
        }
    }
}
