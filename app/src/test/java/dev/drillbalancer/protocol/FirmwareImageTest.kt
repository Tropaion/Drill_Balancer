package dev.drillbalancer.protocol

import dev.drillbalancer.ui.firmwareBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun realBuildIfPresent() {
        val bin = File(FirmwareSources.dir, "build/drillbalancer.bin")
        assumeTrue("no firmware build at $bin", bin.isFile)
        val img = ok(bin.readBytes())
        assertEquals("drillbalancer", img.projectName)
        assertEquals("0.1.0", img.version)
        assertEquals(true, img.idfVersion.startsWith("v5."))
    }
}
