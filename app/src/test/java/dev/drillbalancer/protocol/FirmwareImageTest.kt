package dev.drillbalancer.protocol

import dev.drillbalancer.ui.firmwareBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class FirmwareImageTest {
    private fun image(
        size: Int = 4096,
        magic: Int = 0xE9,
        chip: Int = 0x0009,
        descMagic: Long = 0xABCD5432L,
        project: String = "drillbalancer",
        version: String = "1.2.3",
    ): ByteArray = firmwareBytes(version, project, size, magic, chip, descMagic)

    private fun ok(bytes: ByteArray) = (FirmwareImage.parse(bytes) as FirmwareParseResult.Ok).image

    @Test
    fun parsesAppDescriptor() {
        val bytes = image()
        val img = ok(bytes)
        assertEquals("1.2.3", img.version)
        assertEquals("drillbalancer", img.projectName)
        assertEquals("v5.5.1", img.idfVersion)
        assertEquals("Oct 25 2026", img.buildDate)
        assertEquals("21:07:13", img.buildTime)
        assertEquals(4096, img.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), img.sha256)
        assertEquals(64, img.sha256Hex.length)
    }

    @Test
    fun fullLengthStringsWithoutNul() {
        val v = "v".repeat(32)
        assertEquals(v, ok(image(version = v)).version)
    }

    @Test
    fun rejectsWrongFiles() {
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(ByteArray(0)))
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(image(magic = 0x7F)))
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(image().copyOf(23)))
        assertEquals(FirmwareParseResult.NoAppDescriptor, FirmwareImage.parse(image(size = 32 + 255)))
        assertEquals(FirmwareParseResult.NoAppDescriptor, FirmwareImage.parse(image(descMagic = 0x12345678)))
        assertEquals(FirmwareParseResult.WrongChip(0), FirmwareImage.parse(image(chip = 0)))
        assertEquals(FirmwareParseResult.WrongProject("other"), FirmwareImage.parse(image(project = "other")))
    }

    @Test
    fun sizeLimitIsTheOtaSlot() {
        val max = FirmwareImage.OTA_SLOT_SIZE
        assertEquals(6 * 1024 * 1024, max)
        ok(image(size = max))
        assertEquals(FirmwareParseResult.TooLarge(max + 1, max), FirmwareImage.parse(image(size = max + 1)))
    }

    // A valid layout: two segments (the first opens with the app descriptor), zero padding
    // up to the checksum byte that ends a 16-byte block, then the 32-byte SHA-256.
    private fun segmented(seg0: Int = 400, seg1: Int = 1000): ByteArray {
        val body = 24 + 8 + seg0 + 8 + seg1
        val total = (body / 16 + 1) * 16 + 32
        val bytes = image(size = total)
        bytes[1] = 2
        bytes[23] = 1                                   // hash_appended
        bytes.put32(24 + 4, seg0.toLong())
        bytes.fill(0, 24 + 8 + seg0, 24 + 8 + seg0 + 8)
        bytes.put32(24 + 8 + seg0 + 4, seg1.toLong())
        return bytes
    }

    @Test
    fun segmentTableGivesTheImageLength() {
        val bytes = segmented()
        assertEquals(1488, bytes.size)                  // 1440 + 15 bytes of padding + checksum + hash
        ok(bytes)
        ok(bytes + ByteArray(100))                      // esp_image_verify() reads no further
        assertEquals(FirmwareParseResult.Truncated(1487, 1488), FirmwareImage.parse(bytes.copyOf(1487)))
        // Cut inside the last segment, and inside its segment header.
        assertEquals(FirmwareParseResult.Truncated(1000, 1488), FirmwareImage.parse(bytes.copyOf(1000)))
        assertEquals(FirmwareParseResult.Truncated(436, 440), FirmwareImage.parse(bytes.copyOf(436)))
        val noHash = segmented().copyOf(1456).also { it[23] = 0 }
        ok(noHash)
    }

    @Test
    fun rejectsABrokenSegmentTable() {
        val none = segmented().also { it[1] = 0 }
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(none))
        val many = segmented().also { it[1] = 17 }      // ESP_IMAGE_MAX_SEGMENTS is 16
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(many))
        val huge = segmented().also { it.put32(24 + 4, 0xFFFFFFF0L) }
        assertEquals(FirmwareParseResult.NotEspImage, FirmwareImage.parse(huge))
    }

    @Test
    fun realBuildIfPresent() {
        val bin = File(FirmwareSources.dir, "build/drillbalancer.bin")
        assumeTrue("no firmware build at $bin", bin.isFile)
        val bytes = bin.readBytes()
        val img = ok(bytes)
        assertEquals("drillbalancer", img.projectName)
        assertEquals("0.1.0", img.version)
        assertEquals(true, img.idfVersion.startsWith("v5."))
        // A copy cut off at 600000 bytes: the device would only find out after the transfer.
        // The cut is in a segment whose successors' headers are gone: a lower bound.
        val cut = FirmwareImage.parse(bytes.copyOf(600_000)) as FirmwareParseResult.Truncated
        assertEquals(600_000, cut.size)
        assertTrue(cut.needed in 600_001..bytes.size)
    }
}
