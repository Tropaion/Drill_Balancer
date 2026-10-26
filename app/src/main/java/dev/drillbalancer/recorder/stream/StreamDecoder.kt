package dev.drillbalancer.recorder.stream

import dev.drillbalancer.protocol.cString
import dev.drillbalancer.protocol.s16
import dev.drillbalancer.protocol.s8
import dev.drillbalancer.protocol.toHex
import dev.drillbalancer.protocol.u16
import dev.drillbalancer.protocol.u32
import dev.drillbalancer.protocol.u64
import dev.drillbalancer.protocol.u8
import java.util.zip.CRC32

/** What the decoder has seen so far. */
data class DecodeStats(
    val records: Long = 0,          // framed with a good CRC
    val bytes: Long = 0,            // fed in
    val skippedBytes: Long = 0,     // dropped while resynchronising
    val resyncs: Long = 0,          // runs of skipped bytes
    val unknown: Long = 0,          // unknown type or version, passed through
    val malformed: Long = 0,        // known type, payload does not fit
)

/**
 * Incremental decoder (the algorithm of dbdata.dbrec.StreamDecoder, so both count alike):
 * [feed] bytes as they arrive, [finish] at the end of the stream. A record is taken when its
 * header is plausible (version not 0, len <= MAX_PAYLOAD) and its CRC matches; otherwise the
 * decoder moves one byte on (resynchronisation). While a plausible header waits for its
 * payload the bytes are kept, unless the decoder is resynchronising and a complete record
 * follows in what it has. Not thread-safe.
 *
 * Not [typed]: only the framing, for who needs no more than [lastEnd]; [feed] returns
 * nothing and the stats count no unknown or malformed records.
 */
class StreamDecoder(private val typed: Boolean = true) {
    private var buf = ByteArray(64 * 1024)
    private var len = 0
    private var base = 0L           // stream offset of buf[0]
    private var skipping = false
    private val crc = CRC32()

    var stats = DecodeStats()
        private set

    /** Bytes that are buffered and not yet decoded (an incomplete record). */
    val pending: Int get() = len

    /** Stream offset of the next byte fed. */
    val offset: Long get() = base + len

    /** Stream offset just after the last record taken (0: none yet). */
    var lastEnd = 0L
        private set

    fun feed(data: ByteArray, off: Int = 0, count: Int = data.size - off): List<StreamRecord> {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(data, off, buf, len, count)
        len += count
        stats = stats.copy(bytes = stats.bytes + count)
        return run(final = false)
    }

    fun finish(): List<StreamRecord> = run(final = true)

    private fun ver(p: Int) = buf.u8(p + 1)
    private fun payloadLen(p: Int) = buf.u16(p + 2)

    private fun crcOk(p: Int, length: Int): Boolean {
        crc.reset()
        crc.update(buf, p, RecStream.HDR_LEN + length)
        return buf.u32(p + RecStream.HDR_LEN + length) == crc.value
    }

    /** Offset of the first complete record with a good CRC from [start] on, or -1. */
    private fun findRecord(start: Int): Int {
        var q = start
        while (q <= len - RecStream.HDR_LEN - RecStream.CRC_LEN) {
            val length = payloadLen(q)
            if (ver(q) != 0 && length <= RecStream.MAX_PAYLOAD &&
                q + RecStream.HDR_LEN + length + RecStream.CRC_LEN <= len && crcOk(q, length)
            ) return q
            q++
        }
        return -1
    }

    private fun skip(n: Int) {
        if (n <= 0) return
        var s = stats
        if (!skipping) {
            s = s.copy(resyncs = s.resyncs + 1)
            skipping = true
        }
        stats = s.copy(skippedBytes = s.skippedBytes + n)
    }

    private fun run(final: Boolean): List<StreamRecord> {
        val out = ArrayList<StreamRecord>()
        var p = 0           // where a record may start
        var done = 0        // bytes taken by records or skipped
        while (len - p >= RecStream.HDR_LEN) {
            val v = ver(p)
            val length = payloadLen(p)
            if (v == 0 || length > RecStream.MAX_PAYLOAD) {
                p++
                continue
            }
            val size = RecStream.HDR_LEN + length + RecStream.CRC_LEN
            if (len - p < size) {
                if (final) {
                    p++
                    continue
                }
                // While resynchronising, a complete record further on shows that this
                // header was a false start.
                val q = if (skipping || p > done) findRecord(p + 1) else -1
                if (q < 0) break
                p = q
                continue
            }
            if (!crcOk(p, length)) {
                p++
                continue
            }
            skip(p - done)
            skipping = false
            if (typed) {
                val rec = parse(p, buf.u8(p), v, buf.u32(p + 4), length)
                stats = stats.copy(
                    records = stats.records + 1,
                    unknown = stats.unknown + if (rec is StreamRecord.Unknown && !rec.malformed) 1 else 0,
                    malformed = stats.malformed + if (rec is StreamRecord.Unknown && rec.malformed) 1 else 0,
                )
                out += rec
            } else {
                stats = stats.copy(records = stats.records + 1)
            }
            p += size
            done = p
            lastEnd = base + p
        }
        // What can no longer start a record (at the end: all that is left).
        val end = if (final) len else p
        skip(end - done)
        System.arraycopy(buf, end, buf, 0, len - end)
        len -= end
        base += end
        return out
    }

    /** The record at [p], read in place; only a record passed through keeps a copy of its payload. */
    private fun parse(p: Int, type: Int, ver: Int, seq: Long, length: Int): StreamRecord {
        val offset = base + p
        val size = RecStream.HDR_LEN + length + RecStream.CRC_LEN
        val at = p + RecStream.HDR_LEN
        if (RecStream.VERSIONS[type] != ver) return StreamRecord.Unknown(type, ver, seq, offset, size, buf.copyOfRange(at, at + length))
        return parsePayload(type, seq, offset, size, buf, at, length)
            ?: StreamRecord.Unknown(type, ver, seq, offset, size, buf.copyOfRange(at, at + length), malformed = true)
    }

    companion object {
        /**
         * The typed record of a known type and version, or null if the payload (the [len] bytes
         * of [b] from [at]) does not fit it. Fixed-size types accept a longer payload (fields
         * appended later are ignored).
         */
        fun parsePayload(type: Int, seq: Long, offset: Long, size: Int, b: ByteArray, at: Int = 0, len: Int = b.size - at): StreamRecord? =
            when (type) {
                RecStream.T_SESSION -> if (len < RecStream.SESSION_LEN) null else StreamRecord.Session(
                    seq, offset, size,
                    takeId = b.u32(at),
                    startUs = b.u64(at + 4),
                    boardId = b.copyOfRange(at + 12, at + 16).toHex(),
                    fw = b.cString(at + 16, RecStream.FW_LEN),
                    profile = b.u8(at + 48),
                    ctrl2 = b.u8(at + 49),
                    ctrl3 = b.u8(at + 50),
                    ctrl5 = b.u8(at + 51),
                    odrMhz = b.u32(at + 52),
                    accelLsbPerG = b.u16(at + 56),
                    gyroLsbPerDps = b.u16(at + 58),
                    axis = (0 until 3).map { b.u8(at + 60 + 2 * it) to b.s8(at + 61 + 2 * it) },
                    audioRateHz = b.u32(at + 66),
                    audioChannels = b.u8(at + 70),
                    audioBits = b.u8(at + 71),
                    micMask = b.u8(at + 72),
                    gainDb = b.u8(at + 73),
                    audioBlockFrames = b.u16(at + 74),
                    maxS = b.u16(at + 76),
                    tail = b.decodeToString(at + RecStream.SESSION_LEN, at + len),
                )
                RecStream.T_IMU -> {
                    val n = if (len >= RecStream.IMU_LEN) b.u16(at + 16) else -1
                    if (n < 0 || len != RecStream.IMU_LEN + n * RecStream.IMU_FRAME_LEN) null else StreamRecord.Imu(
                        seq, offset, size,
                        tsFirst = b.u32(at),
                        chipTs = b.u32(at + 4),
                        hostUs = b.u64(at + 8),
                        n = n,
                        flags = b.u16(at + 18),
                        frames = shorts(b, at + RecStream.IMU_LEN, n * 6),
                    )
                }
                RecStream.T_AUDIO -> {
                    if (len < RecStream.AUDIO_LEN) {
                        null
                    } else {
                        val n = b.u16(at + 16)
                        val body = len - RecStream.AUDIO_LEN
                        val channels = when {
                            n == 0 -> if (body == 0) 0 else -1
                            body % (2 * n) != 0 -> -1
                            else -> body / (2 * n)
                        }
                        if (channels < 0) null else StreamRecord.Audio(
                            seq, offset, size,
                            sampleIndex = b.u64(at),
                            hostUs = b.u64(at + 8),
                            n = n,
                            flags = b.u16(at + 18),
                            channels = channels,
                            samples = shorts(b, at + RecStream.AUDIO_LEN, n * channels),
                        )
                    }
                }
                RecStream.T_EVENT -> if (len < RecStream.EVENT_LEN) null else StreamRecord.Event(
                    seq, offset, size,
                    hostUs = b.u64(at),
                    code = b.u16(at + 8),
                    arg = b.u16(at + 10),
                    text = b.decodeToString(at + RecStream.EVENT_LEN, at + len),
                )
                RecStream.T_LEVEL -> if (len < RecStream.LEVEL_LEN) null else StreamRecord.Level(
                    seq, offset, size,
                    hostUs = b.u64(at),
                    mode = b.u8(at + 8),
                    flags = b.u8(at + 9),
                    elevationCdeg = b.s16(at + 10),
                    yawCdeg = b.s16(at + 12),
                    bubbleXCdeg = b.s16(at + 14),
                    bubbleYCdeg = b.s16(at + 16),
                    deviationCdeg = b.s16(at + 18),
                    zeroElevationCdeg = b.s16(at + 20),
                    zeroBubbleXCdeg = b.s16(at + 22),
                    zeroBubbleYCdeg = b.s16(at + 24),
                )
                RecStream.T_STATS -> if (len < RecStream.STATS_LEN) null else StreamRecord.Stats(
                    seq, offset, size,
                    hostUs = b.u64(at),
                    imuFrames = b.u32(at + 8),
                    imuLost = b.u32(at + 12),
                    imuPeriodNs = b.u32(at + 16),
                    audioFrames = b.u32(at + 20),
                    audioOverflows = b.u32(at + 24),
                    audioRateMhz = b.u32(at + 28),
                    audioClipped = b.u32(at + 32),
                    ringHighWater = b.u32(at + 36),
                    ringSize = b.u32(at + 40),
                    droppedRecords = b.u32(at + 44),
                    droppedBytes = b.u32(at + 48),
                    batteryMv = b.u16(at + 52),
                    batteryPct = b.u8(at + 54),
                    powerFlags = b.u8(at + 55),
                )
                RecStream.T_HELLO_OK -> StreamRecord.HelloOk(seq, offset, size)
                RecStream.T_END -> if (len < RecStream.END_LEN) null else StreamRecord.End(
                    seq, offset, size,
                    hostUs = b.u64(at),
                    reason = b.u8(at + 8),
                    records = b.u32(at + 9),
                    droppedRecords = b.u32(at + 13),
                    droppedBytes = b.u32(at + 17),
                    imuFrames = b.u32(at + 21),
                    imuLost = b.u32(at + 25),
                    audioFrames = b.u32(at + 29),
                    audioOverflows = b.u32(at + 33),
                )
                else -> null
            }

        private fun shorts(b: ByteArray, at: Int, count: Int) = ShortArray(count) { b.s16(at + 2 * it).toShort() }

        /** All records of [data] fed in chunks of [chunk] bytes, and the stats. */
        fun decodeAll(data: ByteArray, chunk: Int = data.size.coerceAtLeast(1)): Pair<List<StreamRecord>, DecodeStats> {
            val d = StreamDecoder()
            val out = ArrayList<StreamRecord>()
            var i = 0
            while (i < data.size) {
                val n = minOf(chunk, data.size - i)
                out += d.feed(data, i, n)
                i += n
            }
            out += d.finish()
            return out to d.stats
        }
    }
}
