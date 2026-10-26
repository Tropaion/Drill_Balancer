package dev.drillbalancer.recorder.stream

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.protocol.RecOpcode
import dev.drillbalancer.protocol.RecProfile
import dev.drillbalancer.protocol.RecReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The stream decoder's constants and record layouts against rec_stream.h (like dbrec's
// self_check), so a change of the wire format fails here first. The stream's reasons,
// profiles and microphones are RecReason, RecProfile and RecOpcode's (rec_state, rec_ctrl).

class RecStreamConformanceTest {
    private val header = "components/recorder/include/rec_stream.h"
    private val source = "components/recorder/src/rec_stream.c"

    @Test
    fun everyDefine() {
        val versions = RecStream.VERSIONS
        val ours = mapOf(
            "REC_STREAM_PORT" to RecStream.PORT, "REC_STREAM_VERSION" to RecStream.VERSION,
            "REC_HELLO_TOKEN_LEN" to RecStream.HELLO_TOKEN_LEN, "REC_HELLO_LEN" to RecStream.HELLO_LEN,
            "REC_HDR_LEN" to RecStream.HDR_LEN, "REC_CRC_LEN" to RecStream.CRC_LEN, "REC_MAX_PAYLOAD" to RecStream.MAX_PAYLOAD,
            "REC_T_SESSION" to RecStream.T_SESSION, "REC_T_IMU" to RecStream.T_IMU, "REC_T_AUDIO" to RecStream.T_AUDIO,
            "REC_T_EVENT" to RecStream.T_EVENT, "REC_T_STATS" to RecStream.T_STATS, "REC_T_LEVEL" to RecStream.T_LEVEL,
            "REC_T_HELLO_OK" to RecStream.T_HELLO_OK, "REC_T_END" to RecStream.T_END,
            "REC_V_SESSION" to versions.getValue(RecStream.T_SESSION), "REC_V_IMU" to versions.getValue(RecStream.T_IMU),
            "REC_V_AUDIO" to versions.getValue(RecStream.T_AUDIO), "REC_V_EVENT" to versions.getValue(RecStream.T_EVENT),
            "REC_V_STATS" to versions.getValue(RecStream.T_STATS), "REC_V_LEVEL" to versions.getValue(RecStream.T_LEVEL),
            "REC_V_HELLO_OK" to versions.getValue(RecStream.T_HELLO_OK), "REC_V_END" to versions.getValue(RecStream.T_END),
            "REC_PROFILE_COUNT" to RecProfile.entries.size,
            "REC_IMU_DISCONT" to RecStream.IMU_DISCONT, "REC_IMU_EPOCH" to RecStream.IMU_EPOCH,
            "REC_IMU_MAX_FRAMES" to RecStream.IMU_MAX_FRAMES, "REC_IMU_FRAME_LEN" to RecStream.IMU_FRAME_LEN,
            "REC_AUDIO_OVF_BEFORE" to RecStream.AUDIO_OVF_BEFORE, "REC_MIC_1" to RecOpcode.MIC_1, "REC_MIC_2" to RecOpcode.MIC_2,
            "REC_EV_MARK" to RecStream.EV_MARK, "REC_EV_TOUCH" to RecStream.EV_TOUCH, "REC_EV_BLE_LOST" to RecStream.EV_BLE_LOST,
            "REC_EV_BLE_BACK" to RecStream.EV_BLE_BACK, "REC_EV_IMU_LOST" to RecStream.EV_IMU_LOST,
            "REC_EV_IMU_BACK" to RecStream.EV_IMU_BACK, "REC_EV_VIB_START" to RecStream.EV_VIB_START,
            "REC_EV_VIB_STOP" to RecStream.EV_VIB_STOP, "REC_EV_PROFILE" to RecStream.EV_PROFILE,
            "REC_EVENT_TEXT_MAX" to RecStream.EVENT_TEXT_MAX,
            "REC_STATS_BATTERY_VALID" to RecStream.STATS_BATTERY_VALID, "REC_STATS_VBUS" to RecStream.STATS_VBUS,
            "REC_STATS_CHARGING" to RecStream.STATS_CHARGING,
            "REC_FW_LEN" to RecStream.FW_LEN,
        ) + RecReason.entries.associate { "REC_REASON_${it.name}" to it.wire } + RecProfile.entries.associate { "REC_PROFILE_${it.name}" to it.wire }
        val kotlin = ours.mapValues { it.value.toLong() }
        val c = FirmwareSources.intDefines(header).filterKeys { it.startsWith("REC_") }
        assertEquals("REC_* (C header vs Kotlin)", c, kotlin)

        // The two defines that are expressions, and every define named at all.
        val code = FirmwareSources.code(header)
        assertTrue("#define REC_MIC_ALL (REC_MIC_1 | REC_MIC_2)" in code)
        assertTrue("#define REC_EVENT_MAX (sizeof(rec_event_t) + REC_EVENT_TEXT_MAX)" in code)
        val named = Regex("""^\s*#define\s+(REC_\w+)""", RegexOption.MULTILINE).findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(c.keys + setOf("REC_MIC_ALL", "REC_EVENT_MAX"), named)
    }

    @Test
    fun eventNamesFollowTheCodes() {
        val codes = FirmwareSources.intDefines(header).filterKeys { it.startsWith("REC_EV_") }
        assertEquals(codes.map { it.key.removePrefix("REC_EV_") to it.value.toInt() }.sortedBy { it.second }.map { it.first }, RecStream.EVENT_NAMES)
        assertEquals((1..RecStream.EVENT_NAMES.size).toList(), codes.values.map { it.toInt() }.sorted())
    }

    @Test
    fun recordSizesFromStaticAsserts() {
        val asserted = Regex("""_Static_assert\(sizeof\((rec_\w+_t)\)\s*==\s*(\w+)""")
            .findAll(FirmwareSources.code(source))
            .associate { m -> m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: FirmwareSources.intDefines(header).getValue(m.groupValues[2]).toInt()) }
        val kotlin = mapOf(
            "rec_hdr_t" to RecStream.HDR_LEN, "rec_axis_t" to 2, "rec_session_t" to RecStream.SESSION_LEN,
            "rec_imu_t" to RecStream.IMU_LEN, "rec_audio_t" to RecStream.AUDIO_LEN, "rec_event_t" to RecStream.EVENT_LEN,
            "rec_level_t" to RecStream.LEVEL_LEN, "rec_stats_t" to RecStream.STATS_LEN, "rec_end_t" to RecStream.END_LEN,
        )
        assertEquals(kotlin, asserted)
        // The header's own field lists add up to the same sizes, and its comments say so.
        for ((name, size) in kotlin) {
            assertEquals("sizeof($name) from its fields", size, layout(name).second)
        }
        val text = FirmwareSources.text(header)
        for (name in listOf("rec_session_t", "rec_imu_t", "rec_audio_t", "rec_event_t", "rec_level_t", "rec_stats_t", "rec_end_t")) {
            assertTrue("$name comment", Regex("""\}\s*$name;\s*//\s*${kotlin.getValue(name)} bytes""").containsMatchIn(text))
        }
    }

    @Test
    fun recordLayouts() {
        // The offsets StreamDecoder.parsePayload reads, field by field.
        assertEquals(
            listOf("uint8_t type" at 0, "uint8_t ver" at 1, "uint16_t len" at 2, "uint32_t seq" at 4),
            layout("rec_hdr_t").first,
        )
        assertEquals(
            listOf(
                "uint32_t take_id" at 0, "uint64_t start_us" at 4, "uint8_t board_id[4]" at 12, "char fw[32]" at 16,
                "uint8_t profile" at 48, "uint8_t ctrl2" at 49, "uint8_t ctrl3" at 50, "uint8_t ctrl5" at 51,
                "uint32_t odr_mhz" at 52, "uint16_t accel_lsb_per_g" at 56, "uint16_t gyro_lsb_per_dps" at 58,
                "rec_axis_t axis[3]" at 60, "uint32_t audio_rate_hz" at 66, "uint8_t audio_channels" at 70,
                "uint8_t audio_bits" at 71, "uint8_t mic_mask" at 72, "uint8_t gain_db" at 73,
                "uint16_t audio_block_frames" at 74, "uint16_t max_s" at 76,
            ),
            layout("rec_session_t").first,
        )
        assertEquals(listOf("uint8_t src" at 0, "int8_t sign" at 1), layout("rec_axis_t").first)
        assertEquals(
            listOf("uint32_t ts_first" at 0, "uint32_t chip_ts" at 4, "uint64_t host_us" at 8, "uint16_t n" at 16, "uint16_t flags" at 18),
            layout("rec_imu_t").first,
        )
        assertEquals(
            listOf("uint64_t sample_index" at 0, "uint64_t host_us" at 8, "uint16_t n" at 16, "uint16_t flags" at 18),
            layout("rec_audio_t").first,
        )
        assertEquals(listOf("uint64_t host_us" at 0, "uint16_t code" at 8, "uint16_t arg" at 10), layout("rec_event_t").first)
        assertEquals(
            listOf(
                "uint64_t host_us" at 0, "uint8_t mode" at 8, "uint8_t flags" at 9, "int16_t elevation_cdeg" at 10,
                "int16_t yaw_cdeg" at 12, "int16_t bubble_x_cdeg" at 14, "int16_t bubble_y_cdeg" at 16,
                "int16_t deviation_cdeg" at 18, "int16_t zero_elevation_cdeg" at 20, "int16_t zero_bubble_x_cdeg" at 22,
                "int16_t zero_bubble_y_cdeg" at 24,
            ),
            layout("rec_level_t").first,
        )
        assertEquals(
            listOf(
                "uint64_t host_us" at 0, "uint32_t imu_frames" at 8, "uint32_t imu_lost" at 12, "uint32_t imu_period_ns" at 16,
                "uint32_t audio_frames" at 20, "uint32_t audio_overflows" at 24, "uint32_t audio_rate_mhz" at 28,
                "uint32_t audio_clipped" at 32, "uint32_t ring_high_water" at 36, "uint32_t ring_size" at 40,
                "uint32_t dropped_records" at 44, "uint32_t dropped_bytes" at 48, "uint16_t battery_mv" at 52,
                "uint8_t battery_pct" at 54, "uint8_t power_flags" at 55,
            ),
            layout("rec_stats_t").first,
        )
        assertEquals(
            listOf(
                "uint64_t host_us" at 0, "uint8_t reason" at 8, "uint32_t records" at 9, "uint32_t dropped_records" at 13,
                "uint32_t dropped_bytes" at 17, "uint32_t imu_frames" at 21, "uint32_t imu_lost" at 25,
                "uint32_t audio_frames" at 29, "uint32_t audio_overflows" at 33,
            ),
            layout("rec_end_t").first,
        )
    }

    @Test
    fun helloAndCrc() {
        // rec_crc32(0, "123456789", 9) is 0xCBF43926 (rec_stream.h).
        val check = "123456789".encodeToByteArray()
        assertTrue("check value in the header", "0xCBF43926" in FirmwareSources.text(header))
        assertEquals(0xCBF43926L, RecStream.crc32(check, 0, check.size))
        val token = ByteArray(16) { (0xF0 + it).toByte() }
        val hello = RecStream.hello(token)
        assertEquals(RecStream.HELLO_LEN, hello.size)
        assertEquals(listOf(1, 0), hello.takeLast(2).map { it.toInt() })
        // The device's answer, byte for byte as test_rec_stream.c and db_client.py have it.
        val ok = RecStream.encode(RecStream.T_HELLO_OK, 1, 0, ByteArray(0))
        assertEquals("0701000000000000c4dd90c9", ok.joinToString("") { "%02x".format(it) })
        val (records, stats) = StreamDecoder.decodeAll(ok)
        assertEquals(listOf(StreamRecord.HelloOk(0, 0, 12)), records)
        assertEquals(0L, stats.unknown)
    }

    private infix fun String.at(offset: Int) = "$this @$offset"

    private fun layout(struct: String) = FirmwareSources.layout(header, struct, mapOf("rec_axis_t" to 2))
}
