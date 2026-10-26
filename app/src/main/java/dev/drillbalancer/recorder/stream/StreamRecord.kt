package dev.drillbalancer.recorder.stream

/**
 * One record as framed on the wire (rec_stream.h). [offset] is where it starts in the
 * stream, [size] its bytes including header and CRC. Times are the device's esp_timer µs.
 */
sealed class StreamRecord {
    abstract val type: Int
    abstract val seq: Long
    abstract val offset: Long
    abstract val size: Int

    val end: Long get() = offset + size

    /** SESSION: what the take was recorded with; [tail] is the settings/calibration JSON. */
    data class Session(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val takeId: Long,
        val startUs: Long,
        val boardId: String,          // 8 hex digits
        val fw: String,               // "0.1.0-g0e5105e"
        val profile: Int,             // REC_PROFILE_*
        val ctrl2: Int,
        val ctrl3: Int,
        val ctrl5: Int,
        val odrMhz: Long,
        val accelLsbPerG: Int,
        val gyroLsbPerDps: Int,
        val axis: List<Pair<Int, Int>>,   // board x, y, z = sign * chip[src]: (src, sign)
        val audioRateHz: Long,
        val audioChannels: Int,
        val audioBits: Int,
        val micMask: Int,
        val gainDb: Int,
        val audioBlockFrames: Int,
        val maxS: Int,
        val tail: String,
    ) : StreamRecord() {
        override val type get() = RecStream.T_SESSION
    }

    /** IMU: one timestamp run of n samples, AX AY AZ GX GY GZ int16 per sample. */
    class Imu(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val tsFirst: Long,
        val chipTs: Long,
        val hostUs: Long,
        val n: Int,
        val flags: Int,
        val frames: ShortArray,       // n x 6
    ) : StreamRecord() {
        override val type get() = RecStream.T_IMU
        val discont: Boolean get() = flags and RecStream.IMU_DISCONT != 0
        val epoch: Boolean get() = flags and RecStream.IMU_EPOCH != 0
        fun sample(i: Int, axis: Int): Int = frames[i * 6 + axis].toInt()
    }

    /** AUDIO: one DMA block, n frames of [channels] interleaved int16 samples. */
    class Audio(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val sampleIndex: Long,
        val hostUs: Long,
        val n: Int,
        val flags: Int,
        val channels: Int,
        val samples: ShortArray,      // n x channels
    ) : StreamRecord() {
        override val type get() = RecStream.T_AUDIO
        val overflowBefore: Boolean get() = flags and RecStream.AUDIO_OVF_BEFORE != 0
    }

    data class Event(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val hostUs: Long,
        val code: Int,
        val arg: Int,
        val text: String,
    ) : StreamRecord() {
        override val type get() = RecStream.T_EVENT
        val codeName: String? get() = RecStream.EVENT_NAMES.getOrNull(code - 1)
    }

    data class Level(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val hostUs: Long,
        val mode: Int,
        val flags: Int,
        val elevationCdeg: Int,
        val yawCdeg: Int,
        val bubbleXCdeg: Int,
        val bubbleYCdeg: Int,
        val deviationCdeg: Int,
        val zeroElevationCdeg: Int,
        val zeroBubbleXCdeg: Int,
        val zeroBubbleYCdeg: Int,
    ) : StreamRecord() {
        override val type get() = RecStream.T_LEVEL
    }

    /** STATS: once a second during a take; counters since the take start. */
    data class Stats(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val hostUs: Long,
        val imuFrames: Long,
        val imuLost: Long,
        val imuPeriodNs: Long,
        val audioFrames: Long,
        val audioOverflows: Long,
        val audioRateMhz: Long,
        val audioClipped: Long,
        val ringHighWater: Long,
        val ringSize: Long,
        val droppedRecords: Long,
        val droppedBytes: Long,
        val batteryMv: Int,
        val batteryPct: Int,
        val powerFlags: Int,
    ) : StreamRecord() {
        override val type get() = RecStream.T_STATS
    }

    /** END: the last record of a take. */
    data class End(
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val hostUs: Long,
        val reason: Int,              // REC_REASON_*
        val records: Long,            // of the take including this one (= seq + 1)
        val droppedRecords: Long,
        val droppedBytes: Long,
        val imuFrames: Long,
        val imuLost: Long,
        val audioFrames: Long,
        val audioOverflows: Long,
    ) : StreamRecord() {
        override val type get() = RecStream.T_END
    }

    /** HELLO_OK: the device took this connection (its first record, before any take). */
    data class HelloOk(override val seq: Long, override val offset: Long, override val size: Int) : StreamRecord() {
        override val type get() = RecStream.T_HELLO_OK
    }

    /** An unknown type or version (skipped by len, passed through), or a known one whose payload does not fit ([malformed]). */
    class Unknown(
        override val type: Int,
        val ver: Int,
        override val seq: Long,
        override val offset: Long,
        override val size: Int,
        val payload: ByteArray,
        val malformed: Boolean = false,
    ) : StreamRecord()
}
