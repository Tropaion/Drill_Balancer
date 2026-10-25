package dev.drillbalancer.protocol

import java.security.MessageDigest

/**
 * An ESP32-S3 app image (build/drillbalancer.bin) checked before an OTA, so a wrong file
 * fails here instead of after the transfer. The device checks the same app descriptor
 * (ota_update.c: magic word, project_name equal to the running firmware's).
 */
class FirmwareImage private constructor(
    val bytes: ByteArray,
    val version: String,        // PROJECT_VER, e.g. "0.1.0"
    val projectName: String,
    val idfVersion: String,
    val buildDate: String,      // __DATE__, e.g. "Oct 24 2026"
    val buildTime: String,      // __TIME__
    val sha256: ByteArray,
) {
    val size: Int get() = bytes.size
    val sha256Hex: String get() = sha256.toHex()

    override fun toString() = "FirmwareImage($projectName $version, $size bytes, sha256 $sha256Hex)"

    companion object {
        const val IMAGE_MAGIC = 0xE9
        const val CHIP_ID_ESP32S3 = 0x0009
        const val PROJECT_NAME = "drillbalancer"

        // ota_0 / ota_1 in partitions.csv: 6M each.
        const val OTA_SLOT_SIZE = 6 * 1024 * 1024

        // esp_image_header_t (24 bytes) + esp_image_segment_header_t (8): the app
        // descriptor opens the first segment (ota_update.c DESC_OFFSET).
        const val IMAGE_HEADER_SIZE = 24
        const val DESC_OFFSET = IMAGE_HEADER_SIZE + 8
        const val DESC_SIZE = 256
        const val APP_DESC_MAGIC = 0xABCD5432L

        // esp_app_desc_t field offsets
        private const val CHIP_ID_OFFSET = 12
        private const val VERSION_OFFSET = 16
        private const val PROJECT_OFFSET = 48
        private const val TIME_OFFSET = 80
        private const val DATE_OFFSET = 96
        private const val IDF_OFFSET = 112

        fun parse(bytes: ByteArray, maxSize: Int = OTA_SLOT_SIZE): FirmwareParseResult {
            if (bytes.size < IMAGE_HEADER_SIZE || bytes.u8(0) != IMAGE_MAGIC) return FirmwareParseResult.NotEspImage
            if (bytes.size > maxSize) return FirmwareParseResult.TooLarge(bytes.size, maxSize)
            val chip = bytes.u16(CHIP_ID_OFFSET)
            if (chip != CHIP_ID_ESP32S3) return FirmwareParseResult.WrongChip(chip)
            if (bytes.size < DESC_OFFSET + DESC_SIZE || bytes.u32(DESC_OFFSET) != APP_DESC_MAGIC) {
                return FirmwareParseResult.NoAppDescriptor
            }
            val project = bytes.cString(DESC_OFFSET + PROJECT_OFFSET, 32)
            if (project != PROJECT_NAME) return FirmwareParseResult.WrongProject(project)
            return FirmwareParseResult.Ok(
                FirmwareImage(
                    bytes = bytes,
                    version = bytes.cString(DESC_OFFSET + VERSION_OFFSET, 32),
                    projectName = project,
                    idfVersion = bytes.cString(DESC_OFFSET + IDF_OFFSET, 32),
                    buildDate = bytes.cString(DESC_OFFSET + DATE_OFFSET, 16),
                    buildTime = bytes.cString(DESC_OFFSET + TIME_OFFSET, 16),
                    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes),
                ),
            )
        }
    }
}

sealed interface FirmwareParseResult {
    data class Ok(val image: FirmwareImage) : FirmwareParseResult

    sealed interface Error : FirmwareParseResult {
        val message: String
    }

    data object NotEspImage : Error {
        override val message = "Not an ESP32 app image. Use build/drillbalancer.bin."
    }

    data class TooLarge(val size: Int, val maxSize: Int) : Error {
        override val message get() = "The image is $size bytes, larger than the device's $maxSize-byte update slot."
    }

    data class WrongChip(val chipId: Int) : Error {
        override val message get() = "The image is for another chip (id $chipId), not the ESP32-S3."
    }

    data object NoAppDescriptor : Error {
        override val message = "The image has no app descriptor."
    }

    data class WrongProject(val projectName: String) : Error {
        override val message get() = "The image is for project '$projectName', not DrillBalancer."
    }
}

// A NUL-padded char[len] field.
private fun ByteArray.cString(offset: Int, len: Int): String {
    var end = offset
    while (end < offset + len && this[end] != 0.toByte()) end++
    return copyOfRange(offset, end).decodeToString()
}
