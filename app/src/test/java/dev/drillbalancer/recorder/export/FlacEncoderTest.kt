package dev.drillbalancer.recorder.export

import dev.drillbalancer.protocol.FirmwareSources
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** FlacEncoder round trips through the test decoder (FlacDecoder), which checks every CRC and the MD5. */
class FlacEncoderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(samples: ShortArray, channels: Int, rate: Int = 16000, block: Int = FlacEncoder.DEFAULT_BLOCK): FlacDecoder.Result {
        val flac = FlacEncoder.encode(samples, channels, rate, block)
        val r = FlacDecoder.decode(flac)
        assertEquals(channels, r.info.channels)
        assertEquals(16, r.info.bitsPerSample)
        assertEquals(rate, r.info.sampleRate)
        assertEquals((samples.size / channels).toLong(), r.info.totalFrames)
        assertArrayEquals(IntArray(samples.size) { samples[it].toInt() }, r.samples)
        assertArrayEquals("STREAMINFO MD5", md5(samples), r.info.md5)
        return r
    }

    private fun md5(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[2 * i] = s[i].toInt().toByte()
            b[2 * i + 1] = (s[i].toInt() shr 8).toByte()
        }
        return MessageDigest.getInstance("MD5").digest(b)
    }

    private fun stereo(frames: Int, f: (Int) -> Pair<Int, Int>) = ShortArray(frames * 2).also { s ->
        for (i in 0 until frames) {
            val (l, r) = f(i)
            s[2 * i] = l.coerceIn(-32768, 32767).toShort()
            s[2 * i + 1] = r.coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun crcsHaveTheirCheckValues() {
        val nine = "123456789".toByteArray()
        assertEquals(0xF4, FlacEncoder.crc8(nine, 0, 9))      // CRC-8 (poly 0x07, init 0)
        assertEquals(0xFEE8, FlacEncoder.crc16(nine, 0, 9))   // CRC-16/UMTS (poly 0x8005, init 0)
    }

    @Test
    fun signalsOfEveryKindComeBackUnchanged() {
        val rnd = Random(7)
        // a tone with noise, both channels alike: mid/side or left/side wins
        val tone = roundTrip(stereo(20_000) { i ->
            val v = (9000 * sin(2 * PI * 440 * i / 16000.0)).roundToInt()
            v + rnd.nextInt(-30, 30) to v + rnd.nextInt(-30, 30)
        }, 2)
        assertEquals(5, tone.frameCount)                     // 4 x 4096 + 3616
        assertTrue(tone.subframeKinds.toString(), tone.subframeKinds.any { it.startsWith("fixed") })
        // white noise over the full range: VERBATIM is the smallest
        val noise = roundTrip(stereo(5000) { rnd.nextInt(-32768, 32768) to rnd.nextInt(-32768, 32768) }, 2)
        assertTrue("verbatim" in noise.subframeKinds)
        // silence and a constant: CONSTANT subframes
        assertTrue("constant" in roundTrip(ShortArray(2 * 4096), 2).subframeKinds)
        assertTrue("constant" in roundTrip(stereo(300) { -32768 to 32767 }, 2).subframeKinds)
        // the extremes, opposite on the two channels (a 17-bit side channel)
        roundTrip(stereo(4100) { i -> if (i % 2 == 0) 32767 to -32768 else -32768 to 32767 }, 2)
        roundTrip(stereo(4100) { i -> (i * 37 % 65536 - 32768) to -(i * 53 % 65536 - 32768) }, 2)
        // steps and ramps: high fixed orders
        val ramp = roundTrip(stereo(8192) { i -> (i * i / 4096 - 8000) to (i % 1000) * 30 }, 2)
        assertTrue(ramp.subframeKinds.toString(), ramp.subframeKinds.any { it == "fixed3" || it == "fixed4" || it == "fixed2" })
        // mono, and three channels
        roundTrip(ShortArray(7000) { (3000 * sin(it / 5.0)).roundToInt().toShort() }, 1)
        roundTrip(ShortArray(3 * 1500) { rnd.nextInt(-2000, 2000).toShort() }, 3)
    }

    @Test
    fun oddLengthsAndBlockSizes() {
        val rnd = Random(3)
        val s = stereo(5003) { i -> (2000 * sin(i / 3.0)).roundToInt() + rnd.nextInt(-5, 5) to rnd.nextInt(-100, 100) }
        for (block in listOf(16, 17, 100, 256, 1152, 4096, 5003, 8192)) {
            val r = roundTrip(s, 2, block = block)
            assertEquals((5003 + block - 1) / block, r.frameCount)
            assertEquals(block, r.info.maxBlock)
        }
        // frame numbers past 127 and 2047 take two and three bytes
        assertEquals(2501, roundTrip(stereo(40_016) { i -> i % 7 to -(i % 5) }, 2, block = 16).frameCount)
        // one frame, shorter than a block; nothing at all
        roundTrip(stereo(1) { 5 to -5 }, 2)
        roundTrip(stereo(15) { i -> i to -i }, 2)
        val empty = roundTrip(ShortArray(0), 2)
        assertEquals(0, empty.frameCount)
    }

    @Test
    fun otherRatesAndTheirCodes() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000, 11025, 12345)) {
            roundTrip(stereo(1000) { i -> i to i / 2 }, 2, rate = rate)
        }
    }

    @Test
    fun writeFileStreamsAndPatchesTheHeader() {
        val f = File(tmp.root, "a.flac")
        val s = stereo(10_000) { i -> (5000 * sin(i / 9.0)).roundToInt() to (3000 * sin(i / 4.0)).roundToInt() }
        FlacEncoder.writeFile(f, 16000, 2, 10_250) { e ->
            e.write(s, 0, 3000)
            e.writeSilence(250)
            e.write(s, 6000, 7000)
        }
        val r = FlacDecoder.decode(f.readBytes())
        val want = ShortArray(10_250 * 2)
        s.copyInto(want, 0, 0, 6000)
        s.copyInto(want, 6500, 6000, 20_000)
        assertArrayEquals(IntArray(want.size) { want[it].toInt() }, r.samples)
        assertArrayEquals(md5(want), r.info.md5)
        assertEquals(10_250L, r.info.totalFrames)
        assertTrue(r.info.minFrameBytes in 1..r.info.maxFrameBytes)
        // the same input, the same bytes
        val g = File(tmp.root, "b.flac")
        FlacEncoder.writeFile(g, 16000, 2, 10_250) { e ->
            e.write(s, 0, 3000)
            e.writeSilence(250)
            e.write(s, 6000, 7000)
        }
        assertArrayEquals(f.readBytes(), g.readBytes())
    }

    @Test(expected = IllegalStateException::class)
    fun fewerFramesThanAnnouncedFail() {
        FlacEncoder.writeFile(File(tmp.root, "c.flac"), 16000, 2, 100) { it.write(ShortArray(2 * 99)) }
    }

    /** The reference exporter's FLAC files (libsndfile / libFLAC, with LPC subframes) decode here too. */
    @Test
    fun theDatasetToolsFlacFilesDecode() {
        val roots = listOf("tools/dataset/fixtures/autodetect", "tools/dataset/fixtures/finalize")
        val files = roots.flatMap { r ->
            File(FirmwareSources.dir, r).listFiles().orEmpty().map { File(it, "audio.flac") }.filter { it.isFile }
        }
        assertTrue("found ${files.size}", files.size >= 9)
        for (f in files) {
            val r = FlacDecoder.decode(f.readBytes())
            assertEquals(f.path, 16, r.info.bitsPerSample)
            assertEquals(f.path, 16000, r.info.sampleRate)
            assertTrue(f.path, r.frames > 0)
            // the file ends within a block or two after the last sync row (the common span's cut)
            val sync = File(f.parentFile, "audio_sync.csv").readLines().drop(1).filter { it.isNotBlank() }
            val lastRow = sync.last().substringBefore(',').toLong()
            assertTrue(f.path, r.info.totalFrames - lastRow in 0L until 1024L)
        }
    }
}
