#include <stddef.h>
#include <string.h>
#include "unity.h"
#include "imu.h"
#include "rec_stream.h"

void setUp(void) {}
void tearDown(void) {}

// Field offsets the decoders (dbdata.dbrec, the app) hard-code.
_Static_assert(offsetof(rec_hdr_t, len) == 2 && offsetof(rec_hdr_t, seq) == 4, "header");
_Static_assert(offsetof(rec_session_t, start_us) == 4, "session");
_Static_assert(offsetof(rec_session_t, board_id) == 12, "session");
_Static_assert(offsetof(rec_session_t, fw) == 16, "session");
_Static_assert(offsetof(rec_session_t, profile) == 48, "session");
_Static_assert(offsetof(rec_session_t, odr_mhz) == 52, "session");
_Static_assert(offsetof(rec_session_t, axis) == 60, "session");
_Static_assert(offsetof(rec_session_t, audio_rate_hz) == 66, "session");
_Static_assert(offsetof(rec_session_t, audio_block_frames) == 74, "session");
_Static_assert(offsetof(rec_session_t, max_s) == 76, "session");
_Static_assert(offsetof(rec_imu_t, host_us) == 8 && offsetof(rec_imu_t, flags) == 18, "imu");
_Static_assert(offsetof(rec_audio_t, host_us) == 8 && offsetof(rec_audio_t, flags) == 18, "audio");
_Static_assert(offsetof(rec_event_t, code) == 8 && offsetof(rec_event_t, arg) == 10, "event");
_Static_assert(offsetof(rec_level_t, flags) == 9 && offsetof(rec_level_t, zero_elevation_cdeg) == 20,
               "level");
_Static_assert(offsetof(rec_stats_t, ring_size) == 40 && offsetof(rec_stats_t, battery_mv) == 52,
               "stats");
_Static_assert(offsetof(rec_end_t, records) == 9 && offsetof(rec_end_t, audio_overflows) == 33,
               "end");

// The SESSION profile byte is imu_profile_t.
_Static_assert(REC_PROFILE_LEVEL == IMU_PROFILE_LEVEL && REC_PROFILE_RAW448 == IMU_PROFILE_RAW448 &&
               REC_PROFILE_RAW448_16G == IMU_PROFILE_RAW448_16G &&
               REC_PROFILE_RAW897 == IMU_PROFILE_RAW897 && REC_PROFILE_COUNT == IMU_PROFILE_COUNT,
               "REC_PROFILE_* mirror imu_profile_t");
_Static_assert(REC_IMU_FRAME_LEN == IMU_FRAME_BYTES, "a board frame has the chip frame's size");

static void test_crc32_check_value(void)
{
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, rec_crc32(0, "123456789", 9));
    TEST_ASSERT_EQUAL_HEX32(0, rec_crc32(0, "", 0));
    // Continued in pieces = in one go.
    uint32_t c = rec_crc32(0, "1234", 4);
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, rec_crc32(c, "56789", 5));
}

static void test_crc32_combine(void)
{
    // Every split of a buffer longer than a record header, 0 bytes included.
    static uint8_t b[300];
    for (size_t i = 0; i < sizeof(b); i++) {
        b[i] = (uint8_t)(i * 37u + 11u);
    }
    uint32_t whole = rec_crc32(0, b, sizeof(b));
    for (size_t k = 0; k <= sizeof(b); k++) {
        uint32_t c = rec_crc32_combine(rec_crc32(0, b, k), rec_crc32(0, &b[k], sizeof(b) - k),
                                       rec_crc32_combine_op(sizeof(b) - k));
        TEST_ASSERT_EQUAL_HEX32(whole, c);
    }
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, rec_crc32_combine(rec_crc32(0, "1234", 4),
                                                           rec_crc32(0, "56789", 5),
                                                           rec_crc32_combine_op(5)));
}

static void test_encode_is_byte_exact(void)
{
    const rec_part_t parts[] = {{"A", 1}, {"", 0}, {"B", 1}};
    uint8_t out[32];
    TEST_ASSERT_EQUAL_UINT(14, rec_stream_encode(REC_T_EVENT, 1, 0x01020304u, parts, 3, out,
                                                 sizeof(out)));
    // zlib.crc32(bytes.fromhex("04010200040302014142")) = 0xb9751fb0
    const uint8_t want[14] = {0x04, 0x01, 0x02, 0x00, 0x04, 0x03, 0x02, 0x01, 0x41, 0x42,
                              0xB0, 0x1F, 0x75, 0xB9};
    TEST_ASSERT_EQUAL_HEX8_ARRAY(want, out, 14);
}

static void test_imu_record_is_byte_exact(void)
{
    const rec_imu_t h = {.ts_first = 0x00ABCDEF, .chip_ts = 0x00ABCDF0,
                         .host_us = 123456789012ull, .n = 1, .flags = REC_IMU_DISCONT};
    const int16_t frame[6] = {1, -2, 3, -4, 5, INT16_MIN};
    const rec_part_t parts[] = {{&h, sizeof(h)}, {frame, sizeof(frame)}};
    uint8_t out[64];
    size_t n = rec_stream_encode(REC_T_IMU, REC_V_IMU, 7, parts, 2, out, sizeof(out));
    // struct.pack("<BBHI", 2, 1, 32, 7) + struct.pack("<IIQHH6h", ...) + crc32
    const uint8_t want[] = {
        0x02, 0x01, 0x20, 0x00, 0x07, 0x00, 0x00, 0x00, 0xef, 0xcd, 0xab, 0x00, 0xf0, 0xcd,
        0xab, 0x00, 0x14, 0x1a, 0x99, 0xbe, 0x1c, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
        0x01, 0x00, 0xfe, 0xff, 0x03, 0x00, 0xfc, 0xff, 0x05, 0x00, 0x00, 0x80, 0x0c, 0x3d,
        0xa6, 0x71,
    };
    TEST_ASSERT_EQUAL_UINT(sizeof(want), n);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(want, out, n);
}

static void test_encode_limits(void)
{
    static uint8_t big[REC_MAX_PAYLOAD + 1];
    static uint8_t out[REC_MAX_PAYLOAD + 64];
    rec_part_t p = {big, REC_MAX_PAYLOAD};
    TEST_ASSERT_EQUAL_UINT(rec_record_size(REC_MAX_PAYLOAD),
                           rec_stream_encode(REC_T_AUDIO, 1, 0, &p, 1, out, sizeof(out)));
    p.len = REC_MAX_PAYLOAD + 1;
    TEST_ASSERT_EQUAL_UINT(0, rec_stream_encode(REC_T_AUDIO, 1, 0, &p, 1, out, sizeof(out)));
    // Output one byte short.
    p.len = 10;
    TEST_ASSERT_EQUAL_UINT(0, rec_stream_encode(REC_T_AUDIO, 1, 0, &p, 1, out, 21));
    TEST_ASSERT_EQUAL_UINT(22, rec_stream_encode(REC_T_AUDIO, 1, 0, &p, 1, out, 22));
    // Empty payload: header and CRC only.
    TEST_ASSERT_EQUAL_UINT(12, rec_stream_encode(REC_T_END, 1, 0, NULL, 0, out, sizeof(out)));
}

static void test_event_payload(void)
{
    uint8_t out[REC_EVENT_MAX];
    size_t n = rec_event_payload(out, 0x1122334455667788ull, REC_EV_MARK, 0xBEEF, "hole 1");
    TEST_ASSERT_EQUAL_UINT(sizeof(rec_event_t) + 6, n);
    const uint8_t head[12] = {0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11,
                              0x01, 0x00, 0xEF, 0xBE};
    TEST_ASSERT_EQUAL_HEX8_ARRAY(head, out, 12);
    TEST_ASSERT_EQUAL_MEMORY("hole 1", &out[12], 6);

    TEST_ASSERT_EQUAL_UINT(sizeof(rec_event_t), rec_event_payload(out, 1, REC_EV_TOUCH, 0, NULL));
    TEST_ASSERT_EQUAL_UINT(sizeof(rec_event_t), rec_event_payload(out, 1, REC_EV_TOUCH, 0, ""));

    // Cut to 64 bytes.
    char text[100];
    memset(text, 'x', sizeof(text) - 1);
    text[sizeof(text) - 1] = 0;
    TEST_ASSERT_EQUAL_UINT(REC_EVENT_MAX, rec_event_payload(out, 1, REC_EV_MARK, 0, text));
    // Exactly 64 fits.
    text[64] = 0;
    TEST_ASSERT_EQUAL_UINT(REC_EVENT_MAX, rec_event_payload(out, 1, REC_EV_MARK, 0, text));
    // A two-byte character across the limit is left out whole.
    memset(text, 'x', 63);
    text[63] = (char)0xC3;
    text[64] = (char)0xA4;              // "a umlaut" at bytes 63..64
    text[65] = 0;
    TEST_ASSERT_EQUAL_UINT(sizeof(rec_event_t) + 63, rec_event_payload(out, 1, 1, 0, text));
    // ... and one ending at byte 64 is kept.
    memset(text, 'x', 62);
    text[62] = (char)0xC3;
    text[63] = (char)0xA4;
    text[64] = 'y';
    text[65] = 0;
    n = rec_event_payload(out, 1, 1, 0, text);
    TEST_ASSERT_EQUAL_UINT(REC_EVENT_MAX, n);
    TEST_ASSERT_EQUAL_HEX8(0xA4, out[n - 1]);
    // A four-byte character at the limit.
    memset(text, 'x', 62);
    memcpy(&text[62], "\xF0\x9F\x94\xA9", 4);
    text[66] = 0;
    TEST_ASSERT_EQUAL_UINT(sizeof(rec_event_t) + 62, rec_event_payload(out, 1, 1, 0, text));
}

static void test_check_finds_records(void)
{
    uint8_t buf[64];
    const rec_part_t p = {"payload", 7};
    size_t n = rec_stream_encode(REC_T_LEVEL, 3, 99, &p, 1, buf, sizeof(buf));
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT((int)n, rec_stream_check(buf, n, &h));
    TEST_ASSERT_EQUAL_UINT8(REC_T_LEVEL, h.type);
    TEST_ASSERT_EQUAL_UINT8(3, h.ver);
    TEST_ASSERT_EQUAL_UINT16(7, h.len);
    TEST_ASSERT_EQUAL_UINT32(99, h.seq);
    // Trailing bytes do not matter.
    TEST_ASSERT_EQUAL_INT((int)n, rec_stream_check(buf, sizeof(buf), &h));
    // Every prefix needs more.
    for (size_t i = 0; i < n; i++) {
        TEST_ASSERT_EQUAL_INT(0, rec_stream_check(buf, i, &h));
    }
    // Any flipped bit is caught.
    for (size_t i = 0; i < n * 8; i++) {
        buf[i / 8] ^= (uint8_t)(1u << (i % 8));
        int r = rec_stream_check(buf, n, &h);
        // A flip in len makes the record longer (needs more) or shorter.
        TEST_ASSERT_TRUE(r <= 0);
        buf[i / 8] ^= (uint8_t)(1u << (i % 8));
    }
    TEST_ASSERT_EQUAL_INT((int)n, rec_stream_check(buf, n, &h));
}

// rec_wifi.c's HELLO_OK: the same 12 bytes in the app's StreamVectorsTest
// and db_client.py.
static void test_hello_ok_is_byte_exact(void)
{
    static const uint8_t expect[] = {0x07, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                     0xC4, 0xDD, 0x90, 0xC9};
    uint8_t buf[REC_HDR_LEN + REC_CRC_LEN];
    size_t n = rec_stream_encode(REC_T_HELLO_OK, REC_V_HELLO_OK, 0, NULL, 0, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(sizeof(expect), n);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expect, buf, sizeof(expect));
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT((int)n, rec_stream_check(buf, n, &h));
    TEST_ASSERT_EQUAL_UINT16(0, h.len);
}

static void test_check_rejects_implausible_headers(void)
{
    uint8_t buf[16] = {REC_T_IMU, 0, 0, 0, 0, 0, 0, 0};
    rec_hdr_t h;
    // Version 0 is never sent.
    TEST_ASSERT_EQUAL_INT(-1, rec_stream_check(buf, 8, &h));
    // len above REC_MAX_PAYLOAD: known as soon as the header is there.
    buf[1] = 1;
    buf[2] = (uint8_t)((REC_MAX_PAYLOAD + 1) & 0xFF);
    buf[3] = (uint8_t)((REC_MAX_PAYLOAD + 1) >> 8);
    TEST_ASSERT_EQUAL_INT(-1, rec_stream_check(buf, 8, &h));
    TEST_ASSERT_EQUAL_INT(0, rec_stream_check(buf, 7, &h));
}

static void test_constants(void)
{
    TEST_ASSERT_EQUAL_INT(47100, REC_STREAM_PORT);
    TEST_ASSERT_EQUAL_INT(REC_HELLO_TOKEN_LEN + 2, REC_HELLO_LEN);
    TEST_ASSERT_EQUAL_UINT(0x03, REC_MIC_ALL);
    // A full FIFO read in one IMU record.
    TEST_ASSERT_TRUE(sizeof(rec_imu_t) + REC_IMU_MAX_FRAMES * REC_IMU_FRAME_LEN <= REC_MAX_PAYLOAD);
    TEST_ASSERT_EQUAL_UINT(REC_FW_LEN, sizeof(((rec_session_t *)0)->fw));
}

static void test_fw_text(void)
{
    char fw[REC_FW_LEN];
    memset(fw, 0x55, sizeof(fw));
    rec_fw_text(fw, "0.1.0", "0e5105e-dirty");
    TEST_ASSERT_EQUAL_STRING("0.1.0-g0e5105e-dirty", fw);
    for (size_t i = strlen(fw); i < sizeof(fw); i++) {
        TEST_ASSERT_EQUAL_HEX8(0, fw[i]);       // NUL-padded
    }
    rec_fw_text(fw, "0.1.0", "");
    TEST_ASSERT_EQUAL_STRING("0.1.0", fw);
    rec_fw_text(fw, "0.1.0", NULL);
    TEST_ASSERT_EQUAL_STRING("0.1.0", fw);
    // Cut to 31 bytes, always terminated.
    rec_fw_text(fw, "0.1.0-a-very-long-version", "0123456789abcdef");
    TEST_ASSERT_EQUAL_STRING("0.1.0-a-very-long-version-g0123", fw);
    rec_fw_text(fw, "0123456789012345678901234567890123", "abc");
    TEST_ASSERT_EQUAL_STRING("0123456789012345678901234567890", fw);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_crc32_check_value);
    RUN_TEST(test_crc32_combine);
    RUN_TEST(test_encode_is_byte_exact);
    RUN_TEST(test_imu_record_is_byte_exact);
    RUN_TEST(test_encode_limits);
    RUN_TEST(test_event_payload);
    RUN_TEST(test_check_finds_records);
    RUN_TEST(test_hello_ok_is_byte_exact);
    RUN_TEST(test_check_rejects_implausible_headers);
    RUN_TEST(test_constants);
    RUN_TEST(test_fw_text);
    return UNITY_END();
}
