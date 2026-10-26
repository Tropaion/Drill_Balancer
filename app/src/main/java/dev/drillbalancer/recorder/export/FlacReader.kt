package dev.drillbalancer.recorder.export

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One channel of a 16-bit FLAC file. */
class FlacChannel(val sampleRate: Int, val channels: Int, val samples: ShortArray)

/**
 * Reads one channel of a 16-bit FLAC file (RFC 9639) as a stream, for the auto-detector: every
 * subframe type (CONSTANT, VERBATIM, FIXED, LPC), wasted bits, partitioned Rice with escapes,
 * every stereo decorrelation; fixed or variable block sizes. Each frame's CRC-8 and CRC-16 are
 * checked (the MD5 is not: the tests' FlacDecoder checks it). Holds the one channel only (a
 * 20-minute take: 38 MB), never the file. IOException for anything that is not such a file.
 */
object FlacReader {
    fun readChannel(file: File, channel: Int = 0): FlacChannel = file.inputStream().use { readChannel(it, channel) }

    fun readChannel(input: InputStream, channel: Int = 0): FlacChannel {
        val r = Bits(input)
        if (r.bits(32) != 0x664C6143L) throw IOException("no fLaC marker")
        var rate = 0
        var channels = 0
        var bps = 0
        var total = 0L
        var seenInfo = false
        while (true) {
            val last = r.bits(1) == 1L
            val type = r.bits(7).toInt()
            val len = r.bits(24).toInt()
            if (type == 0) {
                if (len != 34) throw IOException("STREAMINFO of $len bytes")
                r.bits(16)
                r.bits(16)
                r.bits(24)
                r.bits(24)
                rate = r.bits(20).toInt()
                channels = r.bits(3).toInt() + 1
                bps = r.bits(5).toInt() + 1
                total = r.bits(36)
                r.skipBytes(16)
                seenInfo = true
            } else {
                if (!seenInfo) throw IOException("STREAMINFO is not first")
                r.skipBytes(len)
            }
            if (last) break
        }
        if (bps != 16) throw IOException("$bps-bit FLAC (16 expected)")
        if (channel !in 0 until channels) throw IOException("no channel $channel in $channels")
        var out = ShortArray(if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 1 shl 16)
        var count = 0
        var sub = Array(channels) { IntArray(4096) }
        var resid = LongArray(4096)
        while (true) {
            r.startFrame()
            val sync = try {
                r.bits(14)
            } catch (_: EOFException) {
                break
            }
            if (sync != 0x3FFEL) throw IOException("no frame sync after $count samples")
            r.bits(1)
            r.bits(1)                         // fixed / variable block size: the numbers are not checked
            val bsCode = r.bits(4).toInt()
            val rateCode = r.bits(4).toInt()
            val chanCode = r.bits(4).toInt()
            val sizeCode = r.bits(3).toInt()
            r.bits(1)
            r.utf8()
            val n = when (bsCode) {
                1 -> 192
                in 2..5 -> 576 shl (bsCode - 2)
                6 -> r.bits(8).toInt() + 1
                7 -> r.bits(16).toInt() + 1
                in 8..15 -> 256 shl (bsCode - 8)
                else -> throw IOException("block size code $bsCode")
            }
            when (rateCode) {
                12 -> r.bits(8)
                13, 14 -> r.bits(16)
                15 -> throw IOException("rate code 15")
            }
            if (sizeCode != 0 && sizeCode != 4) throw IOException("frame sample size code $sizeCode")
            val crc8 = r.crc8
            if (r.bits(8).toInt() != crc8) throw IOException("frame header CRC-8 after $count samples")
            val ch = if (chanCode < 8) chanCode + 1 else 2
            if (ch != channels) throw IOException("frame of $ch channels")
            if (sub[0].size < n) {
                sub = Array(channels) { IntArray(n) }
                resid = LongArray(n)
            }
            for (c in 0 until channels) {
                val extra = if ((chanCode == 8 && c == 1) || (chanCode == 9 && c == 0) || (chanCode == 10 && c == 1)) 1 else 0
                subframe(r, n, 16 + extra, sub[c], resid)
            }
            r.align()
            val crc16 = r.crc16
            if (r.bits(16).toInt() != crc16) throw IOException("frame CRC-16 after $count samples")
            if (out.size < count + n) out = out.copyOf(maxOf(out.size * 2, count + n))
            val a = sub[0]
            val b = if (channels > 1) sub[1] else a
            for (i in 0 until n) {
                val v = when (chanCode) {
                    8 -> if (channel == 0) a[i] else a[i] - b[i]
                    9 -> if (channel == 0) a[i] + b[i] else b[i]
                    10 -> {
                        val side = b[i]
                        val mid = (a[i] shl 1) or (side and 1)
                        if (channel == 0) (mid + side) shr 1 else (mid - side) shr 1
                    }
                    else -> sub[channel][i]
                }
                out[count + i] = v.toShort()
            }
            count += n
        }
        if (total != 0L && total != count.toLong()) throw IOException("STREAMINFO says $total frames, read $count")
        return FlacChannel(rate, channels, if (out.size == count) out else out.copyOf(count))
    }

    private fun subframe(r: Bits, n: Int, bpsIn: Int, x: IntArray, resid: LongArray) {
        if (r.bits(1) != 0L) throw IOException("subframe padding")
        val type = r.bits(6).toInt()
        var wasted = 0
        if (r.bits(1) == 1L) wasted = r.unary() + 1
        val bps = bpsIn - wasted
        when {
            type == 0 -> {
                val v = r.signed(bps).toInt()
                for (i in 0 until n) x[i] = v
            }
            type == 1 -> for (i in 0 until n) x[i] = r.signed(bps).toInt()
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) resid[i] = r.signed(bps)
                residual(r, n, order, resid)
                for (i in order until n) {
                    resid[i] += when (order) {
                        0 -> 0L
                        1 -> resid[i - 1]
                        2 -> 2 * resid[i - 1] - resid[i - 2]
                        3 -> 3 * resid[i - 1] - 3 * resid[i - 2] + resid[i - 3]
                        else -> 4 * resid[i - 1] - 6 * resid[i - 2] + 4 * resid[i - 3] - resid[i - 4]
                    }
                }
                for (i in 0 until n) x[i] = resid[i].toInt()
            }
            type >= 32 -> {
                val order = type - 31
                for (i in 0 until order) resid[i] = r.signed(bps)
                val precision = r.bits(4).toInt() + 1
                if (precision == 16) throw IOException("LPC precision code 15")
                val shift = r.signed(5).toInt()
                if (shift < 0) throw IOException("negative LPC shift")
                val coef = LongArray(order) { r.signed(precision) }
                residual(r, n, order, resid)
                for (i in order until n) {
                    var sum = 0L
                    for (j in 0 until order) sum += coef[j] * resid[i - 1 - j]
                    resid[i] += sum shr shift
                }
                for (i in 0 until n) x[i] = resid[i].toInt()
            }
            else -> throw IOException("reserved subframe type $type")
        }
        if (wasted > 0) for (i in 0 until n) x[i] = x[i] shl wasted
    }

    private fun residual(r: Bits, n: Int, order: Int, x: LongArray) {
        val method = r.bits(2).toInt()
        if (method > 1) throw IOException("reserved residual coding $method")
        val paramBits = if (method == 0) 4 else 5
        val escape = (1 shl paramBits) - 1
        val po = r.bits(4).toInt()
        val parts = 1 shl po
        if (n % parts != 0 || (n shr po) < order) throw IOException("partition order $po for $n samples")
        var i = order
        for (p in 0 until parts) {
            val count = (n shr po) - if (p == 0) order else 0
            val k = r.bits(paramBits).toInt()
            if (k == escape) {
                val raw = r.bits(5).toInt()
                repeat(count) { x[i++] = r.signed(raw) }
            } else {
                repeat(count) {
                    val u = (r.unary().toLong() shl k) or r.bits(k)
                    x[i++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
    }

    /**
     * A big-endian bit reader that takes one byte at a time from its own buffer (so a frame's
     * CRCs cover exactly its bytes).
     */
    private class Bits(private val input: InputStream) {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var limit = 0
        private var cache = 0L
        private var nbits = 0
        var crc8 = 0
            private set
        var crc16 = 0
            private set

        fun startFrame() {
            crc8 = 0
            crc16 = 0
        }

        private fun next(): Long {
            if (pos == limit) {
                val n = input.read(buf)
                if (n <= 0) throw EOFException()
                pos = 0
                limit = n
            }
            val b = buf[pos++].toInt() and 0xFF
            crc8 = FlacEncoder.CRC8[crc8 xor b]
            crc16 = ((crc16 shl 8) xor FlacEncoder.CRC16[((crc16 ushr 8) xor b) and 0xFF]) and 0xFFFF
            return b.toLong()
        }

        fun bits(n: Int): Long {
            if (n == 0) return 0
            while (nbits < n) {
                cache = (cache shl 8) or next()
                nbits += 8
            }
            nbits -= n
            return (cache ushr nbits) and ((1L shl n) - 1)
        }

        fun signed(n: Int): Long {
            if (n == 0) return 0
            val v = bits(n)
            return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v
        }

        fun unary(): Int {
            var q = 0
            while (true) {
                if (nbits == 0) {
                    cache = next()
                    nbits = 8
                }
                val v = cache and ((1L shl nbits) - 1)
                if (v == 0L) {
                    q += nbits
                    nbits = 0
                    continue
                }
                val lead = nbits - (64 - java.lang.Long.numberOfLeadingZeros(v))
                q += lead
                nbits -= lead + 1
                return q
            }
        }

        fun align() {
            if (nbits % 8 != 0) bits(nbits % 8)
        }

        fun utf8() {
            val first = bits(8).toInt()
            if (first and 0x80 == 0) return
            var len = 0
            while (len < 8 && (first shl len) and 0x80 != 0) len++
            if (len !in 2..7) throw IOException("bad coded number")
            repeat(len - 1) { bits(8) }
        }

        fun skipBytes(n: Int) = repeat(n) { bits(8) }
    }
}
