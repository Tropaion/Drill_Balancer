package dev.drillbalancer.protocol


/**
 * An ESP32-S3 app image (build/drillbalancer.bin) checked before an OTA, so a wrong file
 * fails here instead of after the transfer. The device checks the same app descriptor
 * (ota_update.c: magic word, project_name equal to the running firmware's), and at FINISH
 * esp_ota_end() walks the segment table: a file cut off in copying fails here too.
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

        // esp_image_header_t: segment_count at 1, hash_appended at 23; ESP_IMAGE_MAX_SEGMENTS.
        private const val SEGMENT_COUNT_OFFSET = 1
        private const val HASH_APPENDED_OFFSET = 23
        private const val SEGMENT_HEADER_SIZE = 8
        private const val MAX_SEGMENTS = 16
        private const val HASH_SIZE = 32

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
            val segments = bytes.u8(SEGMENT_COUNT_OFFSET)
            if (segments == 0 || segments > MAX_SEGMENTS) return FirmwareParseResult.NotEspImage
            val needed = imageLength(bytes, segments)
            if (needed > maxSize) return FirmwareParseResult.NotEspImage
            if (needed > bytes.size) return FirmwareParseResult.Truncated(bytes.size, needed.toInt())
            return FirmwareParseResult.Ok(
                FirmwareImage(
                    bytes = bytes,
                    version = bytes.cString(DESC_OFFSET + VERSION_OFFSET, 32),
                    projectName = project,
                    idfVersion = bytes.cString(DESC_OFFSET + IDF_OFFSET, 32),
                    buildDate = bytes.cString(DESC_OFFSET + DATE_OFFSET, 16),
                    buildTime = bytes.cString(DESC_OFFSET + TIME_OFFSET, 16),
                    sha256 = bytes.sha256(),
                ),
            )
        }

        /**
         * The image's length from its segment table, as esp_image_verify() walks it: the
         * segments, zero padding up to the checksum byte that ends a 16-byte block, then the
         * SHA-256 if hash_appended. A table cut off in a segment header gives the length up
         * to that header's end, the least the file would need.
         */
        private fun imageLength(bytes: ByteArray, segments: Int): Long {
            var pos = IMAGE_HEADER_SIZE.toLong()
            repeat(segments) {
                if (pos + SEGMENT_HEADER_SIZE > bytes.size) return pos + SEGMENT_HEADER_SIZE
                pos += SEGMENT_HEADER_SIZE + bytes.u32(pos.toInt() + 4)
            }
            pos += 16 - pos % 16
            return if (bytes.u8(HASH_APPENDED_OFFSET) != 0) pos + HASH_SIZE else pos
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

    /** Shorter than its segment table says ([needed] is a lower bound if the table itself is cut). */
    data class Truncated(val size: Int, val needed: Int) : Error {
        override val message get() =
            "The file is cut off: it has $size bytes, but the image needs at least $needed. " +
                "Copy build/drillbalancer.bin to the phone again."
    }

    data class WrongProject(val projectName: String) : Error {
        override val message get() = "The image is for project '$projectName', not DrillBalancer."
    }
}
