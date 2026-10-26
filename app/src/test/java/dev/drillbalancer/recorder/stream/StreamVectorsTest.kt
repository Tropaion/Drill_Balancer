package dev.drillbalancer.recorder.stream

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.put16
import dev.drillbalancer.protocol.put32
import dev.drillbalancer.protocol.put64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// The golden stream of firmware/test_host/recorder/test_rec_golden.c, decoded field by field.
// The expected values are those firmware/tools/dataset/tests/test_dbrec.py checks with
// dbdata.dbrec (the independent Python decoder), so the two decoders agree.

private const val T = 1203345000L
private const val TS0 = 0x00FFFFFAL

private fun imuHost(chipTs: Long) = T + 100000 + (chipTs - TS0) * 2230

/** test_rec_golden.c imu_frame(): sample k = ts - TS0. */
private fun imuPattern(k: Long) = longArrayOf(100 + k, 8192 - k, -50 + 2 * k, k, -k, 3 * k)

private fun audioPattern(block: Int, i: Int): Pair<Int, Int> {
    val idx = block * 256L + i
    return ((idx * 37) % 2001 - 1000).toInt() to ((idx * 53) % 4001 - 2000).toInt()
}

class StreamVectorsTest {
    private val golden = FirmwareSources.bytes("test_host/recorder/vectors/golden_stream.bin")
    private val decoded = StreamDecoder.decodeAll(golden)
    private val records = decoded.first
    private val bySeq = records.associateBy { it.seq }

    @Test
    fun framing() {
        assertEquals(listOf<Long>(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 15, 16, 17), records.map { it.seq })
        val s = decoded.second
        assertEquals(DecodeStats(records = 16, bytes = golden.size.toLong(), skippedBytes = 29, resyncs = 2, unknown = 1, malformed = 0), s)
        val types = records.groupingBy { if (it is StreamRecord.Unknown) "0x%02x".format(it.type) else it::class.simpleName }.eachCount()
        assertEquals(
            mapOf("Session" to 1, "Level" to 2, "Imu" to 5, "Audio" to 3, "Event" to 2, "0x7e" to 1, "Stats" to 1, "End" to 1),
            types,
        )
        // Records follow each other; the junk sits before seq 13, the bad record before 15.
        for ((a, b) in records.zipWithNext()) assertEquals("before ${b.seq}", mapOf(13L to 5L, 15L to 24L)[b.seq] ?: 0L, b.offset - a.end)
        assertEquals(golden.size.toLong(), records.last().end)
    }

    @Test
    fun anyChunkingGivesTheSame() {
        val want = records.map { Triple(it.seq, it.type, it.offset) }
        val chunks = (1..64) + listOf(100, 255, 1500, 4096, golden.size)
        for (chunk in chunks) {
            val (recs, st) = StreamDecoder.decodeAll(golden, chunk)
            assertEquals("chunk $chunk", want, recs.map { Triple(it.seq, it.type, it.offset) })
            assertEquals("chunk $chunk", decoded.second, st)
        }
        // Random cuts, as TCP delivers them.
        val rnd = Random(7)
        repeat(50) {
            val d = StreamDecoder()
            val got = ArrayList<StreamRecord>()
            var i = 0
            while (i < golden.size) {
                val n = minOf(1 + rnd.nextInt(600), golden.size - i)
                got += d.feed(golden, i, n)
                i += n
            }
            got += d.finish()
            assertEquals(want, got.map { Triple(it.seq, it.type, it.offset) })
            assertEquals(decoded.second, d.stats)
        }
    }

    @Test
    fun session() {
        val s = bySeq.getValue(0) as StreamRecord.Session
        assertEquals(42L, s.takeId)
        assertEquals(T, s.startUs)
        assertEquals("b3c9e1a2", s.boardId)
        assertEquals("0.1.0-g0e5105e", s.fw)
        assertEquals(listOf(1, 0x14, 0x54, 0x00), listOf(s.profile, s.ctrl2, s.ctrl3, s.ctrl5))
        assertEquals(448400L, s.odrMhz)
        assertEquals(8192 to 64, s.accelLsbPerG to s.gyroLsbPerDps)
        assertEquals(listOf(1 to 1, 0 to -1, 2 to 1), s.axis)
        assertEquals(16000L, s.audioRateHz)
        assertEquals(listOf(2, 16, 3, 6, 256, 300), listOf(s.audioChannels, s.audioBits, s.micMask, s.gainDb, s.audioBlockFrames, s.maxS))
        assertTrue(s.tail.startsWith("{\"settings\":{\"tol\":0.5,\"near\":2.0,\"target\":0.0,\"mode\":\"wall\"},"))
        assertTrue("\"gyro_bias_rad_s\":[0.0011,-0.0004,0.0002]}}" in s.tail)
    }

    @Test
    fun level() {
        val a = bySeq.getValue(1) as StreamRecord.Level
        val b = bySeq.getValue(13) as StreamRecord.Level
        assertEquals(StreamRecord.Level(1, a.offset, a.size, T + 50000, 0, 0x60, 40, 0, 0, 0, 40, 0, 0, 0), a)
        assertEquals(StreamRecord.Level(13, b.offset, b.size, T + 150000, 2, 0x50, 120, -30, 5, -5, 120, 1234, 10, -20), b)
        assertEquals(RecStream.HDR_LEN + RecStream.LEVEL_LEN + RecStream.CRC_LEN, a.size)
    }

    @Test
    fun imu() {
        val want = mapOf(
            2L to longArrayOf(TS0, 8, TS0 + 7, 1), 4L to longArrayOf(TS0 + 8, 3, TS0 + 16, 0),
            5L to longArrayOf(TS0 + 13, 4, TS0 + 16, 0), 10L to longArrayOf(TS0 + 22, 5, TS0 + 26, 0),
            15L to longArrayOf(TS0 + 70, 4, TS0 + 73, 1),
        )
        for ((seq, w) in want) {
            val d = bySeq.getValue(seq) as StreamRecord.Imu
            assertEquals("seq $seq", w.toList(), listOf(d.tsFirst, d.n.toLong(), d.chipTs, d.flags.toLong()))
            assertEquals(imuHost(d.chipTs), d.hostUs)
            assertEquals(w[3] == 1L, d.discont)
            assertFalse(d.epoch)
            for (i in 0 until d.n) {
                val k = d.tsFirst + i - TS0
                assertArrayEquals("seq $seq sample $i", imuPattern(k), LongArray(6) { d.sample(i, it).toLong() })
            }
        }
    }

    @Test
    fun audio() {
        for ((seq, block, flags) in listOf(Triple(3L, 0, 0), Triple(7L, 1, 0), Triple(9L, 3, 1))) {
            val d = bySeq.getValue(seq) as StreamRecord.Audio
            assertEquals(listOf(block * 256L, T + 90000 + (block + 1) * 16000L, 256L, flags.toLong()), listOf(d.sampleIndex, d.hostUs, d.n.toLong(), d.flags.toLong()))
            assertEquals(flags == 1, d.overflowBefore)
            assertEquals(2, d.channels)
            for (i in 0 until 256) {
                assertEquals(audioPattern(block, i), d.samples[2 * i].toInt() to d.samples[2 * i + 1].toInt())
            }
        }
    }

    @Test
    fun eventsAndUnknown() {
        val m = bySeq.getValue(6) as StreamRecord.Event
        val t = bySeq.getValue(11) as StreamRecord.Event
        assertEquals(listOf(T + 125000, 1L, 7L), listOf(m.hostUs, m.code.toLong(), m.arg.toLong()))
        assertEquals("MARK" to "hole 1 – oak", m.codeName to m.text)
        assertEquals(listOf(T + 151000, 2L, 0L), listOf(t.hostUs, t.code.toLong(), t.arg.toLong()))
        assertEquals("TOUCH" to "", t.codeName to t.text)
        val u = bySeq.getValue(12) as StreamRecord.Unknown
        assertEquals(listOf(0x7E, 1), listOf(u.type, u.ver))
        assertEquals("future", u.payload.decodeToString())
        assertFalse(u.malformed)
    }

    @Test
    fun statsAndEnd() {
        val s = bySeq.getValue(16) as StreamRecord.Stats
        assertEquals(
            StreamRecord.Stats(16, s.offset, s.size, T + 210000, 24, 45, 2230000, 1024, 1, 16000500, 0, 1234, 4L shl 20, 1, 92, 3987, 76, RecStream.STATS_BATTERY_VALID),
            s,
        )
        val e = bySeq.getValue(17) as StreamRecord.End
        assertEquals(StreamRecord.End(17, e.offset, e.size, T + 220000, RecReason.USER.wire, 18, 1, 92, 24, 45, 1024, 1), e)
    }

    @Test
    fun takeStats() {
        val tracker = StreamStatsTracker()
        records.forEach(tracker::add)
        val st = tracker.stats
        assertEquals(16L, st.records)
        assertEquals("seq 8 dropped, seq 14 corrupted", 2L, st.missingRecords)
        assertEquals(24L, st.imuSamples)
        assertEquals("2 lost in read mode, 5 in the dropped record", 7L, st.imuGapSamples)
        assertEquals("the resynchronised run", 1L, st.imuResyncs)
        assertEquals(768L to 256L, st.audioFrames to st.audioGapFrames)
        assertEquals(2L, st.events)
        assertEquals(1L, st.unknown)
        assertEquals("the latest host time: the last IMU read", imuHost(TS0 + 73) - T, st.elapsedUs)
        assertEquals(1e9 / 2230000, st.imuRateHz!!, 1e-9)
        assertEquals(16000.5, st.audioRateHz!!, 1e-9)
        assertEquals(RecReason.USER.wire, st.end!!.reason)
        assertEquals(42L, st.session!!.takeId)
    }

    // ---- built streams ----

    private fun rec(type: Int, seq: Long, payload: ByteArray, ver: Int = 1) = RecStream.encode(type, ver, seq, payload)

    private fun event(seq: Long, code: Int = RecStream.EV_MARK, text: String = "") =
        rec(RecStream.T_EVENT, seq, ByteArray(RecStream.EVENT_LEN).also { it.put64(0, 1000 + seq); it.put16(8, code) } + text.encodeToByteArray())

    private fun imu(seq: Long, tsFirst: Long, n: Int, flags: Int = 0) = rec(
        RecStream.T_IMU, seq,
        ByteArray(RecStream.IMU_LEN + 12 * n).also {
            it.put32(0, tsFirst)
            it.put32(4, tsFirst + n - 1)
            it.put64(8, 5000 + tsFirst)
            it.put16(16, n)
            it.put16(18, flags)
        },
    )

    @Test
    fun corruptCrcResynchronisesOnTheNextRecord() {
        val a = event(0, text = "a")
        val b = event(1, text = "b").also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val c = event(2, text = "c")
        for (chunk in listOf(1, 3, 17, 1000)) {
            val (recs, st) = StreamDecoder.decodeAll(a + b + c, chunk)
            assertEquals("chunk $chunk", listOf(0L, 2L), recs.map { it.seq })
            assertEquals(b.size.toLong(), st.skippedBytes)
            assertEquals(1L, st.resyncs)
            assertEquals((a.size + b.size).toLong(), recs[1].offset)
        }
    }

    @Test
    fun garbageAndATruncatedTail() {
        val junk = byteArrayOf(0, 0, 0, 0, 0x7F, 0x01, 0xFF.toByte(), 0xFF.toByte(), 9, 9, 9, 9, 9)
        val a = event(0, text = "first")
        val half = event(1, text = "never complete").copyOf(15)
        val d = StreamDecoder()
        val got = d.feed(junk + a + half).toMutableList()
        assertEquals(listOf(0L), got.map { it.seq })
        assertEquals("the incomplete record waits", half.size, d.pending)
        got += d.finish()
        assertEquals(listOf(0L), got.map { it.seq })
        assertEquals((junk.size + half.size).toLong(), d.stats.skippedBytes)
        assertEquals(2L, d.stats.resyncs)
        assertEquals(0, d.pending)
        assertTrue(StreamDecoder().also { it.feed(ByteArray(0)) }.finish().isEmpty())
    }

    @Test
    fun anImplausibleHeaderDoesNotHoldBackTheRecordsBehindIt() {
        // A header that claims 4000 bytes of payload, then whole records: while
        // resynchronising, the decoder does not wait for the 4000 bytes.
        val fake = byteArrayOf(0x02, 0x01, 0xA0.toByte(), 0x0F, 0, 0, 0, 0)
        val good = event(5, text = "x") + event(6)
        val d = StreamDecoder()
        val got = d.feed(event(4) + byteArrayOf(0x55) + fake + good)
        assertEquals(listOf(4L, 5L, 6L), got.map { it.seq })
        assertEquals((1 + fake.size).toLong(), d.stats.skippedBytes)
    }

    @Test
    fun seqGapsAndSamplesCount() {
        val stream = rec(RecStream.T_SESSION, 0, ByteArray(RecStream.SESSION_LEN)) +
            imu(1, 100, 10) + imu(2, 110, 10) + /* 3, 4 dropped */ imu(5, 150, 5) + imu(6, 400, 3, RecStream.IMU_DISCONT)
        val t = StreamStatsTracker()
        StreamDecoder.decodeAll(stream).first.forEach(t::add)
        assertEquals(2L, t.stats.missingRecords)
        assertEquals("150 - 120", 30L, t.stats.imuGapSamples)
        assertEquals(1L, t.stats.imuResyncs)
        assertEquals(28L, t.stats.imuSamples)
        // A new SESSION (the next take) starts the counters over.
        StreamDecoder.decodeAll(rec(RecStream.T_SESSION, 0, ByteArray(RecStream.SESSION_LEN)) + imu(1, 7, 2)).first.forEach(t::add)
        assertEquals(StreamStats(records = 2, imuSamples = 2, session = t.stats.session, firstHostUs = 0, lastHostUs = 5007), t.stats)
    }

    @Test
    fun unknownTypesVersionsAndMalformedPassThrough() {
        val stream = rec(0x7E, 0, "future".encodeToByteArray()) +
            rec(RecStream.T_EVENT, 1, ByteArray(20), ver = 2) +
            rec(RecStream.T_IMU, 2, ByteArray(RecStream.IMU_LEN).also { it.put16(16, 3) }) +   // n = 3, no frames
            rec(RecStream.T_END, 3, ByteArray(10)) +
            rec(RecStream.T_LEVEL, 4, ByteArray(RecStream.LEVEL_LEN + 6))                           // longer: fine
        val (recs, st) = StreamDecoder.decodeAll(stream)
        assertEquals(5, recs.size)
        val u = recs.take(4).map { it as StreamRecord.Unknown }
        assertEquals(listOf(false, false, true, true), u.map { it.malformed })
        assertEquals(listOf(0x7E, RecStream.T_EVENT, RecStream.T_IMU, RecStream.T_END), u.map { it.type })
        assertEquals(2, u[1].ver)
        assertTrue(recs[4] is StreamRecord.Level)
        assertEquals(2L to 2L, st.unknown to st.malformed)
        assertEquals(0L, st.skippedBytes)
    }

    @Test
    fun audioWithoutChannelsAndOddBodies() {
        val empty = ByteArray(RecStream.AUDIO_LEN)
        val odd = ByteArray(RecStream.AUDIO_LEN + 5).also { it.put16(16, 2) }
        val mono = ByteArray(RecStream.AUDIO_LEN + 4).also { it.put16(16, 2); it.put16(20, 0xFFFF) }
        val recs = StreamDecoder.decodeAll(rec(RecStream.T_AUDIO, 0, empty) + rec(RecStream.T_AUDIO, 1, odd) + rec(RecStream.T_AUDIO, 2, mono)).first
        assertEquals(0, (recs[0] as StreamRecord.Audio).channels)
        assertTrue((recs[1] as StreamRecord.Unknown).malformed)
        val m = recs[2] as StreamRecord.Audio
        assertEquals(1 to -1, m.channels to m.samples[0].toInt())
    }
}
