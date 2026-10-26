package dev.drillbalancer.recorder.export

import java.security.MessageDigest

/**
 * A FLAC decoder for the tests (RFC 9639): every metadata block skipped but STREAMINFO, fixed and
 * variable block sizes, CONSTANT / VERBATIM / FIXED / LPC subframes, wasted bits, partitioned
 * Rice with escapes, every stereo decorrelation, 8-32 bits. It checks the frame header's CRC-8,
 * the frame's CRC-16, the frame numbers and the STREAMINFO MD5 and sample count, and throws on
 * any mismatch, so a file it decodes is a valid FLAC stream to the letter. Written apart from
 * [FlacEncoder] (only the CRC definitions are shared, and those are checked against known values).
 */
class FlacDecoder private constructor(private val b: ByteArray) {
    class Info(
        val minBlock: Int,
        val maxBlock: Int,
        val minFrameBytes: Int,
        val maxFrameBytes: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalFrames: Long,
        val md5: ByteArray,
    )

    /** The decoded file: interleaved samples, [Info], and how many FLAC frames held them. */
    class Result(val info: Info, val samples: IntArray, val frameCount: Int, val subframeKinds: Set<String>) {
        val frames: Int get() = samples.size / info.channels

        fun channel(c: Int): IntArray = IntArray(frames) { samples[it * info.channels + c] }
    }

    private var pos = 0L        // bit position
    private val kinds = HashSet<String>()

    private fun bit(): Int {
        val v = (b[(pos ushr 3).toInt()].toInt() ushr (7 - (pos and 7).toInt())) and 1
        pos++
        return v
    }

    private fun bits(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 1) or bit().toLong() }
        return v
    }

    private fun signed(n: Int): Long {
        if (n == 0) return 0
        val v = bits(n)
        return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v
    }

    private fun unary(): Long {
        var q = 0L
        while (bit() == 0) q++
        return q
    }

    private fun bytePos(): Int {
        check(pos and 7 == 0L) { "not byte aligned" }
        return (pos ushr 3).toInt()
    }

    private fun align() {
        while (pos and 7 != 0L) check(bit() == 0) { "non-zero padding" }
    }

    private fun decode(): Result {
        check(String(b, 0, 4, Charsets.US_ASCII) == "fLaC") { "no fLaC marker" }
        pos = 32
        var info: Info? = null
        while (true) {
            val last = bit() == 1
            val type = bits(7).toInt()
            val len = bits(24).toInt()
            val start = bytePos()
            if (type == 0) {
                check(info == null) { "two STREAMINFO blocks" }
                check(len == 34) { "STREAMINFO of $len bytes" }
                info = Info(
                    bits(16).toInt(), bits(16).toInt(), bits(24).toInt(), bits(24).toInt(), bits(20).toInt(),
                    bits(3).toInt() + 1, bits(5).toInt() + 1, bits(36), ByteArray(16) { bits(8).toByte() },
                )
            } else {
                check(info != null) { "STREAMINFO is not first" }
            }
            pos = (start + len).toLong() * 8
            if (last) break
        }
        val si = info!!
        val out = ArrayList<IntArray>()
        val md5 = MessageDigest.getInstance("MD5")
        var frameNo = 0L
        var sampleNo = 0L
        var total = 0
        var frameCount = 0
        while (bytePos() < b.size) {
            val frameStart = bytePos()
            check(bits(14) == 0x3FFEL) { "no frame sync at $frameStart" }
            check(bit() == 0) { "reserved bit" }
            val variable = bit() == 1
            val bsCode = bits(4).toInt()
            val rateCode = bits(4).toInt()
            val chanCode = bits(4).toInt()
            val sizeCode = bits(3).toInt()
            check(bit() == 0) { "reserved bit" }
            val number = utf8()
            if (variable) check(number == sampleNo) { "frame starts at sample $number, expected $sampleNo" }
            else check(number == frameNo) { "frame number $number, expected $frameNo" }
            val n = when (bsCode) {
                1 -> 192
                in 2..5 -> 576 shl (bsCode - 2)
                6 -> bits(8).toInt() + 1
                7 -> bits(16).toInt() + 1
                in 8..15 -> 256 shl (bsCode - 8)
                else -> error("block size code $bsCode")
            }
            when (rateCode) {
                12 -> bits(8)
                13, 14 -> bits(16)
                15 -> error("rate code 15")
            }
            val bps = when (sizeCode) {
                0 -> si.bitsPerSample
                1 -> 8
                2 -> 12
                4 -> 16
                5 -> 20
                6 -> 24
                7 -> 32
                else -> error("sample size code $sizeCode")
            }
            check(bps == si.bitsPerSample) { "frame of $bps bits, STREAMINFO says ${si.bitsPerSample}" }
            val headerEnd = bytePos()
            val crc8 = bits(8).toInt()
            check(crc8 == crc8(frameStart, headerEnd)) { "frame header CRC-8 at $frameStart" }
            val channels = if (chanCode < 8) chanCode + 1 else 2
            check(channels == si.channels) { "frame of $channels channels" }
            val sub = Array(channels) { c ->
                val extra = when {
                    chanCode == 8 && c == 1 -> 1        // left/side: side
                    chanCode == 9 && c == 0 -> 1        // side/right: side
                    chanCode == 10 && c == 1 -> 1       // mid/side: side
                    else -> 0
                }
                subframe(n, bps + extra)
            }
            align()
            val frameEnd = bytePos()
            val crc16 = bits(16).toInt()
            check(crc16 == crc16(frameStart, frameEnd)) { "frame CRC-16 at $frameStart" }
            when (chanCode) {
                8 -> for (i in 0 until n) sub[1][i] = sub[0][i] - sub[1][i]
                9 -> for (i in 0 until n) sub[0][i] = sub[0][i] + sub[1][i]
                10 -> for (i in 0 until n) {
                    val side = sub[1][i]
                    val mid = (sub[0][i] shl 1) or (side and 1)
                    sub[0][i] = (mid + side) shr 1
                    sub[1][i] = (mid - side) shr 1
                }
            }
            val pcm = IntArray(n * channels)
            for (i in 0 until n) for (c in 0 until channels) pcm[i * channels + c] = sub[c][i]
            val bytesPer = (bps + 7) / 8
            val raw = ByteArray(pcm.size * bytesPer)
            var k = 0
            for (v in pcm) for (j in 0 until bytesPer) raw[k++] = (v shr (8 * j)).toByte()
            md5.update(raw)
            out += pcm
            total += pcm.size
            frameNo++
            sampleNo += n
            frameCount++
        }
        check(si.totalFrames == 0L || si.totalFrames == sampleNo) { "STREAMINFO says ${si.totalFrames} frames, decoded $sampleNo" }
        val digest = md5.digest()
        check(si.md5.all { it == 0.toByte() } || si.md5.contentEquals(digest)) { "MD5 mismatch" }
        val all = IntArray(total)
        var at = 0
        for (p in out) {
            p.copyInto(all, at)
            at += p.size
        }
        return Result(si, all, frameCount, kinds)
    }

    private fun utf8(): Long {
        val first = bits(8).toInt()
        if (first and 0x80 == 0) return first.toLong()
        var len = 0
        while (len < 8 && (first shl len) and 0x80 != 0) len++
        check(len in 2..7) { "bad coded number" }
        var v = (first and (0xFF ushr (len + 1))).toLong()
        repeat(len - 1) {
            val c = bits(8).toInt()
            check(c and 0xC0 == 0x80) { "bad coded number" }
            v = (v shl 6) or (c and 0x3F).toLong()
        }
        return v
    }

    private fun subframe(n: Int, bpsIn: Int): IntArray {
        check(bit() == 0) { "subframe padding" }
        val type = bits(6).toInt()
        var wasted = 0
        if (bit() == 1) wasted = unary().toInt() + 1
        val bps = bpsIn - wasted
        val x = LongArray(n)
        when {
            type == 0 -> {
                kinds += "constant"
                val v = signed(bps)
                x.fill(v)
            }
            type == 1 -> {
                kinds += "verbatim"
                for (i in 0 until n) x[i] = signed(bps)
            }
            type in 8..12 -> {
                val order = type - 8
                kinds += "fixed$order"
                for (i in 0 until order) x[i] = signed(bps)
                residual(x, n, order)
                for (i in order until n) {
                    x[i] += when (order) {
                        0 -> 0
                        1 -> x[i - 1]
                        2 -> 2 * x[i - 1] - x[i - 2]
                        3 -> 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                        else -> 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
                    }
                }
            }
            type >= 32 -> {
                val order = type - 31
                kinds += "lpc"
                for (i in 0 until order) x[i] = signed(bps)
                val precision = bits(4).toInt() + 1
                check(precision != 16) { "LPC precision code 15" }
                val shift = signed(5).toInt()
                check(shift >= 0) { "negative LPC shift" }
                val coef = LongArray(order) { signed(precision) }
                residual(x, n, order)
                for (i in order until n) {
                    var sum = 0L
                    for (j in 0 until order) sum += coef[j] * x[i - 1 - j]
                    x[i] += sum shr shift
                }
            }
            else -> error("reserved subframe type $type")
        }
        return IntArray(n) { (x[it] shl wasted).toInt() }
    }

    /** The residual into x[order until n]. */
    private fun residual(x: LongArray, n: Int, order: Int) {
        val method = bits(2).toInt()
        check(method <= 1) { "reserved residual coding $method" }
        val paramBits = if (method == 0) 4 else 5
        val escape = (1 shl paramBits) - 1
        val po = bits(4).toInt()
        val parts = 1 shl po
        check(n % parts == 0 && (n shr po) >= order) { "partition order $po for $n samples, order $order" }
        var i = order
        for (p in 0 until parts) {
            val count = (n shr po) - if (p == 0) order else 0
            val k = bits(paramBits).toInt()
            if (k == escape) {
                val raw = bits(5).toInt()
                repeat(count) { x[i++] = signed(raw) }
            } else {
                repeat(count) {
                    val u = (unary() shl k) or bits(k)
                    x[i++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
    }

    private fun crc8(from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private fun crc16(from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    companion object {
        fun decode(bytes: ByteArray): Result = FlacDecoder(bytes).decode()
    }
}
