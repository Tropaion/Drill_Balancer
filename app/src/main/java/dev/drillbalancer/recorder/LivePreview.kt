package dev.drillbalancer.recorder

import dev.drillbalancer.protocol.RecOpcode
import dev.drillbalancer.recorder.stream.StreamRecord
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What the record screen shows of the data while it arrives: the |a| sparkline of the last
 * [windowS] seconds and the microphones' levels. Fed every record of the take; pure and
 * not thread-safe (the read loop owns it). The audio channels are the microphones of the
 * take's mic_mask in bit order: with Mic 2 alone, channel 0 is Mic 2.
 */
class LivePreview(private val windowS: Int = WINDOW_S, private val pointsPerS: Int = POINTS_PER_S) {
    private val points = FloatArray(windowS * pointsPerS)
    private var count = 0               // points filled (up to points.size)
    private var head = 0                // next slot
    private var lsbPerG = 8192f
    private var binSamples = 18         // samples per point (448.4 Hz / 25)
    private var binFill = 0
    private var binMax = 0f
    private val sumSq = DoubleArray(2)
    private val peak = IntArray(2)
    private var audioFrames = 0
    private var channels = 0
    private var mics = intArrayOf(0, 1)     // the microphone (0: Mic 1, 1: Mic 2) of each channel

    fun reset() {
        count = 0
        head = 0
        binFill = 0
        binMax = 0f
        takeLevels()
    }

    fun add(r: StreamRecord) {
        when (r) {
            is StreamRecord.Session -> {
                reset()
                lsbPerG = r.accelLsbPerG.toFloat().takeIf { it > 0f } ?: 8192f
                val hz = r.odrMhz / 1000.0
                binSamples = (hz / pointsPerS).toInt().coerceAtLeast(1)
                mics = micsOf(r.micMask)
            }
            is StreamRecord.Imu -> for (i in 0 until r.n) {
                val ax = r.sample(i, 0).toFloat()
                val ay = r.sample(i, 1).toFloat()
                val az = r.sample(i, 2).toFloat()
                val g = sqrt(ax * ax + ay * ay + az * az) / lsbPerG
                if (g > binMax) binMax = g
                if (++binFill >= binSamples) push()
            }
            is StreamRecord.Audio -> if (r.channels > 0) {
                for (i in 0 until r.n) {
                    for (c in 0 until minOf(2, r.channels)) {
                        val v = r.samples[i * r.channels + c].toInt()
                        sumSq[c] += v.toDouble() * v
                        val a = if (v < 0) -v else v
                        if (a > peak[c]) peak[c] = a
                    }
                }
                audioFrames += r.n
                channels = r.channels
            }
            else -> Unit
        }
    }

    private fun push() {
        points[head] = binMax
        head = (head + 1) % points.size
        if (count < points.size) count++
        binFill = 0
        binMax = 0f
    }

    /** |a| in g, oldest first, at most [windowS] seconds. */
    fun sparkline(): List<Float> = List(count) { points[(head - count + it + points.size) % points.size] }

    /** RMS level per microphone since the last call (dBFS, null when it sent nothing), and whether it clipped. */
    fun takeLevels(): AudioLevels {
        val n = audioFrames
        val db = arrayOfNulls<Float>(2)
        val clipped = BooleanArray(2)
        for (c in 0 until minOf(channels, mics.size)) {
            if (n > 0) db[mics[c]] = dbfs(sumSq[c] / n)
            clipped[mics[c]] = peak[c] >= CLIP
        }
        val out = AudioLevels(db[0], db[1], clipped[0], clipped[1])
        sumSq.fill(0.0)
        peak.fill(0)
        audioFrames = 0
        return out
    }

    private fun dbfs(meanSq: Double): Float =
        if (meanSq <= 0.0) FLOOR_DB else (10 * log10(meanSq / (32768.0 * 32768.0))).toFloat().coerceAtLeast(FLOOR_DB)

    companion object {
        const val WINDOW_S = 5
        const val POINTS_PER_S = 25

        /** The points of a full sparkline with the default window. */
        const val POINTS = WINDOW_S * POINTS_PER_S

        const val FLOOR_DB = -90f
        const val CLIP = 32767

        /** The microphones of [micMask] in channel order (REC_MIC_* bit order). */
        fun micsOf(micMask: Int): IntArray =
            listOf(RecOpcode.MIC_1, RecOpcode.MIC_2).withIndex().filter { (_, bit) -> micMask and bit != 0 }.map { it.index }.toIntArray()
    }
}

/** The microphones' levels (dBFS, null when not recorded) and whether each clipped. */
data class AudioLevels(
    val mic1Db: Float? = null,
    val mic2Db: Float? = null,
    val mic1Clipped: Boolean = false,
    val mic2Clipped: Boolean = false,
) {
    val any: Boolean get() = mic1Db != null || mic2Db != null
}
