package dev.drillbalancer.recorder.stream

/**
 * Loss and gap counters of one take, from its records in stream order (a SESSION starts the
 * take over). Missing records follow dbdata.dbrec Take.missing_records: seq jumps (dropped on
 * the device's full ring, or lost to a CRC error), records before the first one received and,
 * once END is in, after the last one.
 */
data class StreamStats(
    val records: Long = 0,
    val missingRecords: Long = 0,
    val imuSamples: Long = 0,
    val imuGapSamples: Long = 0,       // ts_first jumps without DISCONT: FIFO losses and lost IMU records
    val imuResyncs: Long = 0,          // DISCONT records after the first (the driver lost count)
    val audioFrames: Long = 0,
    val audioGapFrames: Long = 0,      // sample_index jumps (DMA queue overflows, lost records)
    val events: Long = 0,
    val unknown: Long = 0,
    val device: StreamRecord.Stats? = null,   // the latest STATS
    val end: StreamRecord.End? = null,
    val session: StreamRecord.Session? = null,
    val firstHostUs: Long? = null,
    val lastHostUs: Long? = null,
) {
    /** Device time covered so far, µs (from the SESSION start). */
    val elapsedUs: Long get() = (lastHostUs ?: 0L) - (session?.startUs ?: firstHostUs ?: lastHostUs ?: 0L)

    /** Measured IMU rate (STATS), Hz; null until the device has one. */
    val imuRateHz: Double? get() = device?.imuPeriodNs?.takeIf { it > 0 }?.let { 1e9 / it }

    /** Measured audio rate (STATS), Hz; null until the device has one. */
    val audioRateHz: Double? get() = device?.audioRateMhz?.takeIf { it > 0 }?.let { it / 1000.0 }
}

/** Builds [StreamStats] record by record. Not thread-safe. */
class StreamStatsTracker {
    var stats = StreamStats()
        private set

    private var lastSeq = -1L
    private var lastImu: StreamRecord.Imu? = null
    private var lastAudio: StreamRecord.Audio? = null

    fun reset() {
        stats = StreamStats()
        lastSeq = -1
        lastImu = null
        lastAudio = null
    }

    fun add(r: StreamRecord) {
        if (r is StreamRecord.HelloOk) return     // the connection's, not the take's
        if (r is StreamRecord.Session || (lastSeq >= 0 && r.seq <= lastSeq)) reset()
        var s = stats
        val missingBefore = if (lastSeq < 0) r.seq else maxOf(0L, r.seq - lastSeq - 1)
        lastSeq = r.seq
        s = s.copy(records = s.records + 1, missingRecords = s.missingRecords + missingBefore)
        when (r) {
            is StreamRecord.Session -> s = s.copy(session = r, firstHostUs = r.startUs, lastHostUs = r.startUs)
            is StreamRecord.Imu -> {
                val prev = lastImu
                var gap = 0L
                var resync = 0L
                if (prev != null) {
                    if (r.discont) {
                        resync = 1
                    } else {
                        val d = (r.tsFirst - (prev.tsFirst + prev.n)) and 0xFFFF_FFFFL
                        if (d in 1 until 0x8000_0000L) gap = d
                    }
                }
                lastImu = r
                s = s.copy(imuSamples = s.imuSamples + r.n, imuGapSamples = s.imuGapSamples + gap, imuResyncs = s.imuResyncs + resync)
                s = s.time(r.hostUs)
            }
            is StreamRecord.Audio -> {
                val prev = lastAudio
                val gap = if (prev == null) 0L else maxOf(0L, r.sampleIndex - (prev.sampleIndex + prev.n))
                lastAudio = r
                s = s.copy(audioFrames = s.audioFrames + r.n, audioGapFrames = s.audioGapFrames + gap).time(r.hostUs)
            }
            is StreamRecord.Event -> s = s.copy(events = s.events + 1).time(r.hostUs)
            is StreamRecord.Level -> s = s.time(r.hostUs)
            is StreamRecord.Stats -> s = s.copy(device = r).time(r.hostUs)
            is StreamRecord.End -> s = s.copy(end = r, missingRecords = s.missingRecords + maxOf(0L, r.records - 1 - r.seq)).time(r.hostUs)
            is StreamRecord.Unknown -> s = s.copy(unknown = s.unknown + 1)
            is StreamRecord.HelloOk -> Unit
        }
        stats = s
    }

    private fun StreamStats.time(hostUs: Long) = copy(
        firstHostUs = firstHostUs ?: hostUs,
        lastHostUs = if (lastHostUs == null || hostUs > lastHostUs) hostUs else lastHostUs,
    )
}
