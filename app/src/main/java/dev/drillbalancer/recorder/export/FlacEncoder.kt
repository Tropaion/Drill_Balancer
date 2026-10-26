package dev.drillbalancer.recorder.export

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A small FLAC encoder for 16-bit PCM (audio.flac of the dataset contract), pure Kotlin:
 * fixed blocks of [blockSize] frames; per block the best of independent, left/side,
 * right/side and mid/side stereo; per channel a CONSTANT, VERBATIM or FIXED (order 0-4)
 * subframe, the FIXED residual in partitioned Rice codes (4- or 5-bit parameters, partition
 * order up to 8). Frame headers carry CRC-8, frames CRC-16. STREAMINFO holds the total
 * frames, given up front, and the MD5 of the samples, which is only known at the end:
 * [finish] returns the final STREAMINFO block and [writeFile] patches it into the file.
 *
 * Feed interleaved samples with [write] (any number of frames per call). Not thread-safe.
 */
class FlacEncoder(
    private val out: OutputStream,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val blockSize: Int = DEFAULT_BLOCK,
) : Closeable {
    private val md5 = MessageDigest.getInstance("MD5")
    private val block = Array(channels) { IntArray(blockSize) }
    private var fill = 0
    private var frameNumber = 0L
    private var framesIn = 0L
    private var minFrameBytes = Int.MAX_VALUE
    private var maxFrameBytes = 0
    private val bits = BitWriter()
    private val pcm = ByteArray(blockSize * channels * 2)
    private var finished = false

    init {
        require(channels in 1..8) { "$channels channels" }
        require(sampleRate in 1 until (1 shl 20)) { "sample rate $sampleRate" }
        require(blockSize in 16..65535) { "block size $blockSize" }
        require(totalFrames in 0 until (1L shl 36)) { "$totalFrames frames" }
        out.write(streamInfo(ByteArray(16), 0, 0))
    }

    /** [frames] frames of interleaved samples from [samples] at [offset] (in samples). */
    fun write(samples: ShortArray, offset: Int = 0, frames: Int = (samples.size - offset) / channels) {
        check(!finished) { "finished" }
        require(offset >= 0 && offset + frames * channels <= samples.size) { "out of range" }
        var p = offset
        var left = frames
        while (left > 0) {
            val n = minOf(left, blockSize - fill)
            for (i in 0 until n) for (c in 0 until channels) block[c][fill + i] = samples[p++].toInt()
            fill += n
            left -= n
            if (fill == blockSize) flushBlock()
        }
    }

    /** [frames] frames of silence. */
    fun writeSilence(frames: Long) {
        var left = frames
        while (left > 0) {
            val n = minOf(left, (blockSize - fill).toLong()).toInt()
            for (c in 0 until channels) block[c].fill(0, fill, fill + n)
            fill += n
            left -= n
            if (fill == blockSize) flushBlock()
        }
    }

    /**
     * Encodes what is left and returns the final 42-byte header ("fLaC" + STREAMINFO), to put
     * at the start of the output in place of the one written first. Fails unless exactly
     * [totalFrames] frames came in.
     */
    fun finish(): ByteArray {
        check(!finished) { "finished" }
        if (fill > 0) flushBlock()
        finished = true
        check(framesIn == totalFrames) { "$framesIn frames written, $totalFrames announced" }
        return streamInfo(md5.digest(), if (frameNumber == 0L) 0 else minFrameBytes, maxFrameBytes)
    }

    override fun close() = out.close()

    private fun streamInfo(md5: ByteArray, minFrame: Int, maxFrame: Int): ByteArray {
        val w = BitWriter()
        w.bytes(MAGIC)
        w.bits(0x80, 8)                  // last metadata block, type 0 STREAMINFO
        w.bits(STREAMINFO_LEN, 24)
        w.bits(blockSize, 16)
        w.bits(blockSize, 16)
        w.bits(minFrame, 24)
        w.bits(maxFrame, 24)
        w.bits(sampleRate, 20)
        w.bits(channels - 1, 3)
        w.bits(BPS - 1, 5)
        w.bits((totalFrames ushr 32).toInt(), 4)
        w.bits(totalFrames.toInt(), 32)
        w.bytes(md5)
        return w.toByteArray()
    }

    private fun flushBlock() {
        val n = fill
        var k = 0
        for (i in 0 until n) for (c in 0 until channels) {
            val v = block[c][i]
            pcm[k++] = v.toByte()
            pcm[k++] = (v shr 8).toByte()
        }
        md5.update(pcm, 0, k)

        val w = bits
        w.reset()
        // Frame header: sync, fixed block size, then the codes (FLAC format, FRAME_HEADER).
        w.bits(0xFFF8, 16)
        val bsCode = when {
            n == blockSize && blockSize == 4096 -> 12
            n <= 256 -> 6
            else -> 7
        }
        w.bits(bsCode, 4)
        w.bits(RATE_CODES[sampleRate] ?: 0, 4)
        val plans = choose(n)
        w.bits(plans.assignment, 4)
        w.bits(4, 3)                      // 16 bits per sample
        w.bits(0, 1)
        utf8(w, frameNumber)
        if (bsCode == 6) w.bits(n - 1, 8) else if (bsCode == 7) w.bits(n - 1, 16)
        w.bits(crc8(w.buffer, 0, w.byteCount), 8)
        for (p in plans.subframes) p.write(w)
        w.align()
        val crc = crc16(w.buffer, 0, w.byteCount)
        w.bits(crc, 16)
        out.write(w.buffer, 0, w.byteCount)

        minFrameBytes = minOf(minFrameBytes, w.byteCount)
        maxFrameBytes = maxOf(maxFrameBytes, w.byteCount)
        frameNumber++
        framesIn += n
        fill = 0
    }

    private class Plans(val assignment: Int, val subframes: List<Subframe>)

    /** The channel assignment with the fewest bits and its subframes. */
    private fun choose(n: Int): Plans {
        if (channels != 2) return Plans(channels - 1, List(channels) { Subframe.plan(block[it], n, BPS) })
        val l = block[0]
        val r = block[1]
        val side = IntArray(n) { l[it] - r[it] }
        val mid = IntArray(n) { (l[it] + r[it]) shr 1 }
        val pl = Subframe.plan(l, n, BPS)
        val pr = Subframe.plan(r, n, BPS)
        val ps = Subframe.plan(side, n, BPS + 1)
        val pm = Subframe.plan(mid, n, BPS)
        val options = listOf(
            Plans(1, listOf(pl, pr)),          // independent
            Plans(8, listOf(pl, ps)),          // left/side
            Plans(9, listOf(ps, pr)),          // side/right
            Plans(10, listOf(pm, ps)),         // mid/side
        )
        return options.minBy { o -> o.subframes.sumOf { it.bits } }
    }

    /** One channel's subframe: how it is coded and an estimate of its size. */
    private class Subframe(
        val x: IntArray,
        val n: Int,
        val bps: Int,
        val kind: Int,                    // 0 constant, 1 verbatim, 2 fixed
        val order: Int,
        val rice: Rice?,
        val bits: Long,
    ) {
        fun write(w: BitWriter) {
            when (kind) {
                CONSTANT -> {
                    w.bits(0, 8)
                    w.signed(x[0], bps)
                }
                VERBATIM -> {
                    w.bits(0b00000010, 8)
                    for (i in 0 until n) w.signed(x[i], bps)
                }
                else -> {
                    w.bits((0b001000 or order) shl 1, 8)
                    for (i in 0 until order) w.signed(x[i], bps)
                    rice!!.write(w, residual(x, n, order))
                }
            }
        }

        companion object {
            const val CONSTANT = 0
            const val VERBATIM = 1
            const val FIXED = 2

            fun plan(x: IntArray, n: Int, bps: Int): Subframe {
                val first = x[0]
                if ((1 until n).all { x[it] == first }) return Subframe(x, n, bps, CONSTANT, 0, null, 8L + bps)
                val verbatim = 8L + n.toLong() * bps
                val order = bestOrder(x, n)
                val res = residual(x, n, order)
                val rice = Rice.plan(res, n, order)
                val fixed = 8L + order * bps + rice.bits
                return if (fixed < verbatim) {
                    Subframe(x, n, bps, FIXED, order, rice, fixed)
                } else {
                    Subframe(x, n, bps, VERBATIM, 0, null, verbatim)
                }
            }

            /** The fixed predictor order (0-4) with the smallest sum of |residual|. */
            private fun bestOrder(x: IntArray, n: Int): Int {
                val maxOrder = minOf(MAX_FIXED_ORDER, n - 1)
                val sums = LongArray(maxOrder + 1)
                for (i in 0 until n) {
                    val e0 = x[i].toLong()
                    sums[0] += Math.abs(e0)
                    if (maxOrder >= 1 && i >= 1) sums[1] += Math.abs(e0 - x[i - 1])
                    if (maxOrder >= 2 && i >= 2) sums[2] += Math.abs(e0 - 2L * x[i - 1] + x[i - 2])
                    if (maxOrder >= 3 && i >= 3) sums[3] += Math.abs(e0 - 3L * x[i - 1] + 3L * x[i - 2] - x[i - 3])
                    if (maxOrder >= 4 && i >= 4) sums[4] += Math.abs(e0 - 4L * x[i - 1] + 6L * x[i - 2] - 4L * x[i - 3] + x[i - 4])
                }
                // Compared per residual sample, so a higher order is not favoured for having fewer of them.
                var best = 0
                for (o in 1..maxOrder) if (sums[o] * (n - best) < sums[best] * (n - o)) best = o
                return best
            }

            fun residual(x: IntArray, n: Int, order: Int): IntArray = IntArray(n - order) { j ->
                val i = j + order
                when (order) {
                    0 -> x[i]
                    1 -> x[i] - x[i - 1]
                    2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                    3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                    else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
                }
            }
        }
    }

    /** Partitioned Rice coding of a residual: partition order and a parameter per partition. */
    private class Rice(val method: Int, val partitionOrder: Int, val params: IntArray, val bits: Long, val predictorOrder: Int) {
        fun write(w: BitWriter, res: IntArray) {
            w.bits(method, 2)
            w.bits(partitionOrder, 4)
            val parts = 1 shl partitionOrder
            val blockN = res.size + predictorOrder
            var j = 0
            for (p in 0 until parts) {
                val count = (blockN shr partitionOrder) - if (p == 0) predictorOrder else 0
                val k = params[p]
                w.bits(k, if (method == 0) 4 else 5)
                for (i in 0 until count) {
                    val v = res[j++]
                    val u = (v shl 1) xor (v shr 31)
                    w.unary(u ushr k)
                    if (k > 0) w.bits(u and ((1 shl k) - 1), k)
                }
            }
        }

        companion object {
            /** The cheapest partition order (by the estimate n(k+1) + sum >> k per partition). */
            fun plan(res: IntArray, n: Int, order: Int): Rice {
                var maxOrder = 0
                while (maxOrder < MAX_PARTITION_ORDER && n % (1 shl (maxOrder + 1)) == 0 && (n shr (maxOrder + 1)) > order) maxOrder++
                // Sums of the zigzag values over the finest partitions, merged pairwise for coarser ones.
                var sums = LongArray(1 shl maxOrder)
                val size = n shr maxOrder
                for (j in res.indices) {
                    val v = res[j]
                    val u = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFF_FFFFL
                    sums[(j + order) / size] += u
                }
                var best: Rice? = null
                var po = maxOrder
                while (true) {
                    val parts = 1 shl po
                    val params = IntArray(parts)
                    var total = 0L
                    for (p in 0 until parts) {
                        val count = (n shr po) - if (p == 0) order else 0
                        val (k, b) = param(sums[p], count)
                        params[p] = k
                        total += b
                    }
                    val method = if (params.any { it > RICE1_MAX }) 1 else 0
                    total += 6 + parts.toLong() * (if (method == 0) 4 else 5)
                    if (best == null || total <= best.bits) best = Rice(method, po, params, total, order)
                    if (po == 0) break
                    sums = LongArray(parts / 2) { sums[2 * it] + sums[2 * it + 1] }
                    po--
                }
                return best
            }

            /** The Rice parameter for [count] values summing to [sum], and its estimated bits. */
            private fun param(sum: Long, count: Int): Pair<Int, Long> {
                if (count == 0) return 0 to 0L
                val mean = sum / count
                val k0 = if (mean <= 0) 0 else 63 - java.lang.Long.numberOfLeadingZeros(mean)
                var bestK = 0
                var bestBits = Long.MAX_VALUE
                for (k in maxOf(0, k0 - 1)..minOf(RICE2_MAX, k0 + 1)) {
                    val b = count.toLong() * (k + 1) + (sum shr k)
                    if (b < bestBits) {
                        bestBits = b
                        bestK = k
                    }
                }
                return bestK to bestBits
            }
        }
    }

    companion object {
        const val DEFAULT_BLOCK = 4096
        const val BPS = 16
        private const val STREAMINFO_LEN = 34
        const val HEADER_LEN = 4 + 4 + STREAMINFO_LEN
        private const val MAX_FIXED_ORDER = 4
        private const val MAX_PARTITION_ORDER = 8
        private const val RICE1_MAX = 14      // 15 is the escape code
        private const val RICE2_MAX = 30      // 31 is the escape code
        private val MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
        private val RATE_CODES = mapOf(
            88200 to 1, 176400 to 2, 192000 to 3, 8000 to 4, 16000 to 5, 22050 to 6, 24000 to 7,
            32000 to 8, 44100 to 9, 48000 to 10, 96000 to 11,
        )

        /** All of [samples] (interleaved) as a FLAC file in memory. */
        fun encode(samples: ShortArray, channels: Int, sampleRate: Int, blockSize: Int = DEFAULT_BLOCK): ByteArray {
            val buf = ByteArrayOutputStream()
            val e = FlacEncoder(buf, sampleRate, channels, (samples.size / channels).toLong(), blockSize)
            e.write(samples)
            val head = e.finish()
            return buf.toByteArray().also { head.copyInto(it) }
        }

        /** Writes [file]: [fill] feeds the encoder, then the final STREAMINFO is patched in. */
        fun writeFile(file: File, sampleRate: Int, channels: Int, totalFrames: Long, fill: (FlacEncoder) -> Unit) {
            val head = file.outputStream().buffered(1 shl 16).use { out ->
                val e = FlacEncoder(out, sampleRate, channels, totalFrames)
                fill(e)
                e.finish()
            }
            RandomAccessFile(file, "rw").use { it.write(head) }
        }

        /** CRC-8, polynomial x^8 + x^2 + x + 1 (frame header). */
        fun crc8(b: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) crc = CRC8[(crc xor b[i].toInt()) and 0xFF]
            return crc
        }

        /** CRC-16, polynomial x^16 + x^15 + x^2 + 1 (whole frame). */
        fun crc16(b: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) crc = ((crc shl 8) xor CRC16[((crc shr 8) xor b[i].toInt()) and 0xFF]) and 0xFFFF
            return crc
        }

        /** CRC-8 and CRC-16 tables (FlacReader checks frames with them too). */
        internal val CRC8 = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            c
        }
        internal val CRC16 = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            c
        }

        /** The frame number in FLAC's UTF-8-like code. */
        private fun utf8(w: BitWriter, v: Long) {
            if (v < 0x80) return w.bits(v.toInt(), 8)
            var len = 2
            while (len < 7 && v >= (1L shl (5 * len + 1))) len++
            w.bits(((0xFF00 shr len) and 0xFF) or (v ushr (6 * (len - 1))).toInt(), 8)
            for (i in len - 2 downTo 0) w.bits(0x80 or ((v ushr (6 * i)).toInt() and 0x3F), 8)
        }
    }
}

/** MSB-first bit writer over a growing byte buffer. */
internal class BitWriter {
    var buffer = ByteArray(1 shl 16)
        private set
    var byteCount = 0
        private set
    private var acc = 0L
    private var accBits = 0

    fun reset() {
        byteCount = 0
        acc = 0
        accBits = 0
    }

    /** The low [n] (0-32) bits of [v]. */
    fun bits(v: Int, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (v.toLong() and ((1L shl n) - 1))
        accBits += n
        while (accBits >= 8) {
            accBits -= 8
            put((acc ushr accBits).toInt())
        }
        acc = acc and ((1L shl accBits) - 1)
    }

    fun signed(v: Int, n: Int) = bits(v, n)

    /** [q] zeros and a one. */
    fun unary(q: Int) {
        var left = q
        while (left >= 24) {
            bits(0, 24)
            left -= 24
        }
        bits(1, left + 1)
    }

    fun bytes(b: ByteArray) = b.forEach { bits(it.toInt(), 8) }

    /** Zero bits up to the next byte. */
    fun align() {
        if (accBits > 0) bits(0, 8 - accBits)
    }

    fun toByteArray(): ByteArray {
        align()
        return buffer.copyOf(byteCount)
    }

    private fun put(b: Int) {
        if (byteCount == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[byteCount++] = b.toByte()
    }
}
