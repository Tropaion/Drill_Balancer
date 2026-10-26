package dev.drillbalancer.recorder.export

import dev.drillbalancer.recorder.stream.RecStream
import dev.drillbalancer.recorder.stream.StreamRecord
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * A take's records turned into the dataset tables: the algorithms of dbdata.dbrec (split_takes,
 * imu_records, imu_track, audio_track, common_span), step by step and with the same float
 * operations in the same order (Np), so the finalizer writes what `dbrec export` writes.
 * Audio samples are not held here: the audio track is a plan of where each AUDIO record's
 * samples go, and the finalizer reads them from the journal a second time.
 */

/** imu.csv.gz flags (dbdata.io FLAG_*). */
internal object ImuFlags {
    const val FILLED = 1 shl 0
    const val ACCEL_CLIP = 1 shl 1
    const val GYRO_CLIP = 1 shl 2
    const val GAP_BEFORE = 1 shl 3
}

/** SESSION ... END; [session] or [end] null when the stream lost them. */
internal class Take(val session: StreamRecord.Session?) {
    val records = ArrayList<StreamRecord>()
    var end: StreamRecord.End? = null

    inline fun <reified T : StreamRecord> of(): List<T> = records.filterIsInstance<T>()
}

/**
 * Groups records into takes (dbrec.split_takes): a SESSION (or a seq restarting at or below
 * the last one) starts a take, END closes it; HELLO_OK belongs to the connection.
 */
internal fun splitTakes(records: List<StreamRecord>): List<Take> {
    val takes = ArrayList<Take>()
    var cur: Take? = null
    for (r in records) {
        if (r.type == RecStream.T_HELLO_OK) continue
        if (r.type == RecStream.T_SESSION || (cur != null && r.seq <= cur.records.last().seq)) {
            cur = Take(r as? StreamRecord.Session).also { it.records += r }
            takes += cur
            if (r.type == RecStream.T_SESSION) continue
        } else if (cur == null) {
            cur = Take(null).also { it.records += r }
            takes += cur
        } else {
            cur.records += r
        }
        if (r.type == RecStream.T_END) {
            cur.end = r as? StreamRecord.End
            cur = null
        }
    }
    return takes
}

/** t_us = a + b * frame (dbdata.io.SyncFit, adc_delay_us 0). */
internal class SyncFit(val a: Double, val b: Double, val residualRms: Double = 0.0) {
    val rateHz: Double get() = 1e6 / b
    fun tOf(frame: Double) = a + b * frame
    fun frameOf(t: Double) = (t - a) / b

    companion object {
        /** The least-squares line through (frame, t_us) pairs, two or more. */
        fun fit(frame: LongArray, t: LongArray): SyncFit {
            require(frame.size >= 2) { "a sync fit needs at least two blocks" }
            val f = DoubleArray(frame.size) { frame[it].toDouble() }
            val y = DoubleArray(t.size) { t[it].toDouble() }
            val fm = Np.mean(f)
            val tm = Np.mean(y)
            val df = DoubleArray(f.size) { f[it] - fm }
            val dt = DoubleArray(y.size) { y[it] - tm }
            val b = Np.sum(DoubleArray(f.size) { df[it] * dt[it] }) / Np.sum(DoubleArray(f.size) { df[it] * df[it] })
            val a = tm - b * fm
            val res = DoubleArray(f.size) { dt[it] - b * df[it] }
            return SyncFit(a, b, sqrt(Np.mean(DoubleArray(res.size) { res[it] * res[it] })))
        }
    }
}

/** The IMU samples of a take: seq, t_us, counts (n x 6), flags, transport gaps [t_before, t_after]. */
internal class ImuTrack(
    val seq: LongArray,
    val tUs: LongArray,
    val counts: ShortArray,
    val flags: IntArray,
    val transportGaps: List<LongArray>,
    val segments: Int,
) {
    val size: Int get() = seq.size
}

/**
 * The take's IMU records in SESSION.profile (dbrec.imu_records): only those whose latest
 * PROFILE event names SESSION's profile, or all of them in a take without PROFILE events.
 */
internal fun imuRecords(take: Take): List<StreamRecord.Imu> {
    val s = take.session
    var use = s == null || take.records.none { it is StreamRecord.Event && it.code == RecStream.EV_PROFILE }
    val out = ArrayList<StreamRecord.Imu>()
    for (r in take.records) {
        if (r is StreamRecord.Event && r.code == RecStream.EV_PROFILE && s != null) {
            use = r.arg == s.profile
        } else if (r is StreamRecord.Imu && r.n > 0 && use) {
            out += r
        }
    }
    return out
}

private fun wrap32(d: Long): Long = Math.floorMod(d + (1L shl 31), 1L shl 32) - (1L shl 31)

/** A DISCONT jump that host_us contradicts by more than this (plus 2 % of the time between) is an IMU reset. */
private const val EPOCH_TOL_US = 50000
private const val EPOCH_TOL_REL = 0.02
private const val IMU_GAP_MAX = 32       // imu_frame.h: larger FIFO gaps are resynchronised (DISCONT)

private class Segment(val lostBefore: Boolean) {
    val seqs = LongList()
    val counts = ShortList()
    val flags = IntList()
    val pairs = LinkedHashMap<Long, Long>()        // chip_ts -> host_us, one per FIFO read (insertion order kept)
    val gaps = ArrayList<LongArray>()              // (seq before, seq after) of transport gaps

    fun xy(): Pair<DoubleArray, DoubleArray> =
        DoubleArray(pairs.size).also { x -> pairs.keys.forEachIndexed { i, k -> x[i] = k.toDouble() } } to
            DoubleArray(pairs.size).also { y -> pairs.values.forEachIndexed { i, v -> y[i] = v.toDouble() } }
}

/**
 * dbrec.imu_track: FIFO losses in read mode (a timestamp jump of at most 32 without DISCONT and
 * nothing missing between) are filled by linear interpolation (FILLED); a DISCONT record or one
 * after missing records keeps the jump (GAP_BEFORE). An IMU reset (EPOCH, a timestamp going
 * back, a DISCONT jump host_us contradicts) starts a new clock segment; t_us follows one slope
 * for the take through the reads' (chip_ts, host_us) pairs and an offset per segment. Null
 * without IMU records.
 */
internal fun imuTrack(take: Take): ImuTrack? {
    val recs = imuRecords(take)
    if (recs.isEmpty()) return null
    val s = take.session
    val bNominal = if (s != null && s.odrMhz != 0L) 1e9 / s.odrMhz.toDouble() else 1e6 / 448.4

    // Seqs of the take that arrived: a record missing between two IMU records may have held samples.
    val present = LongArray(take.records.size) { take.records[it].seq }.also { it.sort() }
    fun missingBetween(a: Long, b: Long): Boolean {
        val arrived = lowerBound(present, b) - upperBound(present, a)
        return b - a - 1 > arrived
    }

    val segs = ArrayList<Segment>()
    var lastRaw = 0L
    var lastExt = 0L
    var lastTs = 0L
    var lastChip = 0L
    var lastHost = 0L
    var lastRecSeq: Long? = null
    val lastFrame = DoubleArray(6)
    for (d in recs) {
        var isNew = segs.isEmpty()
        var ts0 = 0L
        var chip = 0L
        if (!isNew) {
            // The 32-bit counter only wraps after 111 days at 448 Hz, but a take may still cross it.
            ts0 = lastExt + wrap32(d.tsFirst - lastRaw)
            chip = ts0 + wrap32(d.chipTs - d.tsFirst)
            val hostDt = d.hostUs - lastHost
            isNew = d.epoch || ts0 < lastTs + 1 ||
                (d.discont && abs((chip - lastChip).toDouble() * bNominal - hostDt.toDouble()) > EPOCH_TOL_US + EPOCH_TOL_REL * abs(hostDt).toDouble())
        }
        val prevSeq = lastRecSeq
        val lostRecords = prevSeq != null && missingBetween(prevSeq, d.seq)
        if (isNew) {
            ts0 = d.tsFirst
            chip = ts0 + wrap32(d.chipTs - d.tsFirst)
            segs += Segment(lostRecords)
        }
        val seg = segs.last()
        val fl = IntArray(d.n)
        if (!isNew) {
            val jump = ts0 - (lastTs + 1)
            if (jump > 0 && !d.discont && !lostRecords && jump <= IMU_GAP_MAX) {
                val j = jump.toInt()
                for (i in 1..j) {
                    val t = i.toDouble() / (j + 1).toDouble()
                    seg.seqs.add(lastTs + i)
                    for (a in 0 until 6) seg.counts.add(Math.rint(lastFrame[a] + (d.frames[a].toDouble() - lastFrame[a]) * t).toInt().toShort())
                    seg.flags.add(ImuFlags.FILLED)
                }
            } else if (jump > 0) {
                fl[0] = fl[0] or ImuFlags.GAP_BEFORE
                if (lostRecords) seg.gaps += longArrayOf(lastTs, ts0)
            }
        }
        for (i in 0 until d.n) seg.seqs.add(ts0 + i)
        seg.counts.addAll(d.frames)
        for (f in fl) seg.flags.add(f)
        seg.pairs[chip] = d.hostUs
        lastRaw = d.tsFirst
        lastExt = ts0
        lastTs = ts0 + d.n - 1
        lastChip = chip
        lastHost = d.hostUs
        lastRecSeq = d.seq
        for (a in 0 until 6) lastFrame[a] = d.frames[(d.n - 1) * 6 + a].toDouble()
    }

    // One slope from the variation within the segments, an offset per segment.
    var sxy = 0.0
    var sxx = 0.0
    for (seg in segs) {
        val (x, y) = seg.xy()
        val xm = Np.mean(x)
        val ym = Np.mean(y)
        sxy += Np.sum(DoubleArray(x.size) { (x[it] - xm) * (y[it] - ym) })
        sxx += Np.sum(DoubleArray(x.size) { (x[it] - xm) * (x[it] - xm) })
    }
    var b = if (sxx > 0) sxy / sxx else bNominal
    if (!(0.5 * bNominal < b && b < 2 * bNominal)) b = bNominal

    val n = segs.sumOf { it.seqs.size }
    val seq = LongArray(n)
    val times = DoubleArray(n)
    val counts = ShortArray(n * 6)
    val flags = IntArray(n)
    val gapSpans = ArrayList<LongArray>()
    var at = 0
    for (seg in segs) {
        val (x, y) = seg.xy()
        var c = Np.mean(DoubleArray(x.size) { y[it] - b * x[it] })     // t_us = c + b * local seq
        val local = seg.seqs.toArray()
        val fl = seg.flags.toArray()
        var off = 0L
        if (at > 0) {
            val tLast = times[at - 1]
            val tFirst = c + b * local[0].toDouble()
            val missing = maxOf(0L, pyRoundLong((tFirst - tLast) / b) - 1)
            if (tFirst < tLast + b / 2) c += tLast + b - tFirst      // never backwards in time
            off = seq[at - 1] + 1 + missing - local[0]
            if (missing != 0L) fl[0] = fl[0] or ImuFlags.GAP_BEFORE
            if (seg.lostBefore) gapSpans += longArrayOf(pyRoundLong(tLast), pyRoundLong(c + b * local[0].toDouble()))
        }
        for (g in seg.gaps) gapSpans += longArrayOf(pyRoundLong(c + b * g[0].toDouble()), pyRoundLong(c + b * g[1].toDouble()))
        for (i in local.indices) {
            seq[at + i] = local[i] + off
            times[at + i] = c + b * local[i].toDouble()
            flags[at + i] = fl[i]
        }
        seg.counts.copyInto(counts, at * 6)
        at += local.size
    }
    val tUs = LongArray(n) { Math.rint(times[it]).toLong() }
    for (i in 0 until n) {
        var accel = false
        var gyro = false
        for (a in 0 until 6) {
            val v = counts[i * 6 + a].toInt()
            if (v < 32767 && v > -32767) continue
            if (a < 3) accel = true else gyro = true
        }
        if (accel) flags[i] = flags[i] or ImuFlags.ACCEL_CLIP
        if (gyro) flags[i] = flags[i] or ImuFlags.GYRO_CLIP
    }
    // seq is a uint32 column: a take across the wrap starts over at 0.
    if (seq.max() > 0xFFFF_FFFFL || seq.min() < 0) {
        val base = seq[0]
        for (i in seq.indices) seq[i] -= base
    }
    gapSpans.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }))
    return ImuTrack(seq, tUs, counts, flags, gapSpans, segs.size)
}

/** Where one AUDIO record's samples go: frames [skip, skip + count) of it at output frame [at]. */
internal class Placement(val at: Long, val skip: Int, val count: Int)

/**
 * dbrec.audio_track without the samples: the blocks' placements (by record offset in the
 * stream), silence where sample_index jumps, a sync row (frame just after the block, host_us)
 * per block. [from], [to]: the frames kept (common span); sync rows, gaps and [frames] count
 * from [from].
 */
internal class AudioPlan(
    val channels: Int,
    val placements: Map<Long, Placement>,
    val syncFrame: LongArray,
    val syncT: LongArray,
    val gapFrames: List<LongArray>,
    val overflows: Int,
    val from: Long,
    val to: Long,
) {
    val frames: Long get() = to - from

    /** Whether it is exported (audio.flac with its sync rows: dbrec._has_audio). */
    val exported: Boolean get() = syncFrame.isNotEmpty() && channels > 0
}

internal fun audioPlan(take: Take): AudioPlan? {
    val recs = take.records.filterIsInstance<StreamRecord.Audio>().filter { it.n > 0 }
    if (recs.isEmpty()) return null
    val ch = recs[0].channels
    val base = recs[0].sampleIndex
    val placements = HashMap<Long, Placement>()
    val frames = LongList()
    val times = LongList()
    val gaps = ArrayList<LongArray>()
    var pos = 0L
    for (a in recs) {
        if (a.channels != ch) continue
        val start = a.sampleIndex - base
        if (start > pos) {
            gaps += longArrayOf(pos, start)
            pos = start
        }
        val skip = pos - start          // overlap with what is there: the earlier samples stay
        if (skip >= a.n) continue
        placements[a.offset] = Placement(pos, skip.toInt(), (a.n - skip).toInt())
        pos = start + a.n
        frames.add(pos)
        times.add(a.hostUs)
    }
    return AudioPlan(ch, placements, frames.toArray(), times.toArray(), gaps, recs.count { it.overflowBefore }, 0, pos)
}

/** dbrec.audio_fit: the blocks' line, or the nominal rate through a single block. */
internal fun audioFit(audio: AudioPlan, s: StreamRecord.Session?): SyncFit {
    if (audio.syncFrame.size >= 2) return SyncFit.fit(audio.syncFrame, audio.syncT)
    val b = 1e6 / (if (s != null && s.audioRateHz != 0L) s.audioRateHz.toDouble() else 16000.0)
    return SyncFit(audio.syncT[0].toDouble() - b * audio.syncFrame[0].toDouble(), b)
}

/**
 * dbrec.common_span: IMU and audio cut to the time both cover. Unchanged when the overlap
 * holds fewer than two IMU samples or two audio blocks.
 */
internal fun commonSpan(imu: ImuTrack, audio: AudioPlan?, s: StreamRecord.Session?): Pair<ImuTrack, AudioPlan?> {
    if (audio == null || !audio.exported) return imu to audio
    val fit = audioFit(audio, s)
    val n = audio.frames
    val t0 = maxOf(imu.tUs[0].toDouble(), fit.tOf(0.0))
    val t1 = minOf(imu.tUs[imu.size - 1].toDouble(), fit.tOf(n.toDouble()))
    // the samples with t0 <= t_us <= t1: one run, t_us never goes back (imuTrack)
    val i0 = if (t0.isNaN() || t1.isNaN()) 0 else lowerBound(imu.tUs, ceil(t0).toLong())
    val i1 = if (t0.isNaN() || t1.isNaN()) 0 else upperBound(imu.tUs, floor(t1).toLong())
    val kept = maxOf(0, i1 - i0)
    val f0 = ceil(fit.frameOf(t0) - 1e-6).coerceAtLeast(0.0).coerceAtMost(n.toDouble()).toLong()
    val f1 = floor(fit.frameOf(t1) + 1e-6).coerceAtLeast(f0.toDouble()).coerceAtMost(n.toDouble()).toLong()
    val rows = audio.syncFrame.indices.filter { audio.syncFrame[it] > f0 && audio.syncFrame[it] <= f1 }
    if (kept < 2 || rows.size < 2) return imu to audio

    val fl = imu.flags.copyOfRange(i0, i1)
    fl[0] = fl[0] and ImuFlags.GAP_BEFORE.inv()          // nothing before it any more
    val t = imu.tUs.copyOfRange(i0, i1)
    val counts = imu.counts.copyOfRange(i0 * 6, i1 * 6)
    val gaps = imu.transportGaps.filter { it[0] >= t[0] && it[1] <= t[t.size - 1] }
    val cutImu = ImuTrack(imu.seq.copyOfRange(i0, i1), t, counts, fl, gaps, imu.segments)
    val agaps = audio.gapFrames.mapNotNull { (g0, g1) ->
        if (minOf(g1, f1) > maxOf(g0, f0)) longArrayOf(maxOf(g0, f0) - f0, minOf(g1, f1) - f0) else null
    }
    val cutAudio = AudioPlan(
        audio.channels, audio.placements,
        LongArray(rows.size) { audio.syncFrame[rows[it]] - f0 }, LongArray(rows.size) { audio.syncT[rows[it]] },
        agaps, audio.overflows, audio.from + f0, audio.from + f1,
    )
    return cutImu to cutAudio
}

// ---- small helpers ----

/** The first index with a[i] >= v (np.searchsorted side="left"). */
internal fun lowerBound(a: LongArray, v: Long): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < v) lo = mid + 1 else hi = mid
    }
    return lo
}

/** The first index with a[i] > v (np.searchsorted side="right"). */
internal fun upperBound(a: LongArray, v: Long): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] <= v) lo = mid + 1 else hi = mid
    }
    return lo
}

internal class LongList {
    private var a = LongArray(64)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): LongArray = a.copyOf(size)
}

internal class IntList {
    private var a = IntArray(64)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    fun toArray(): IntArray = a.copyOf(size)
}

internal class ShortList {
    private var a = ShortArray(384)
    var size = 0
        private set

    fun add(v: Short) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun addAll(v: ShortArray) {
        if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size))
        v.copyInto(a, size)
        size += v.size
    }

    fun copyInto(dest: ShortArray, at: Int) = a.copyInto(dest, at, 0, size)
}
