#include <string.h>
#include "rec_util.h"
#include "unity.h"

void setUp(void) {}
void tearDown(void) {}

static void test_ssid(void)
{
    char s[REC_SSID_LEN + 1];
    memset(s, 'x', sizeof(s));
    rec_ap_ssid(s, (const uint8_t[]){0x4A, 0x0F}, 0xBEEF);
    TEST_ASSERT_EQUAL_STRING("DB-4A0F-BEEF", s);
    rec_ap_ssid(s, (const uint8_t[]){0x00, 0x01}, 0x0002);
    TEST_ASSERT_EQUAL_STRING("DB-0001-0002", s);
    // rec_state carries 16 bytes of SSID.
    TEST_ASSERT_LESS_OR_EQUAL(16, strlen(s));
}

static void test_pass_is_base32_of_the_random_bits(void)
{
    char p[REC_PASS_LEN + 1];
    uint8_t zero[REC_PASS_RAND_LEN] = {0};
    rec_ap_pass(p, zero);
    TEST_ASSERT_EQUAL_STRING("AAAAAAAAAAAAAAAAAAAA", p);
    uint8_t ones[REC_PASS_RAND_LEN];
    memset(ones, 0xFF, sizeof(ones));
    rec_ap_pass(p, ones);
    TEST_ASSERT_EQUAL_STRING("77777777777777777777", p);
    // RFC 4648 test vector: "foobar" -> MZXW6YTBOI (the first 10 characters
    // cover its 48 bits plus 2 of the next byte).
    uint8_t foobar[REC_PASS_RAND_LEN] = {'f', 'o', 'o', 'b', 'a', 'r'};
    rec_ap_pass(p, foobar);
    TEST_ASSERT_EQUAL_STRING_LEN("MZXW6YTBOI", p, 10);
    TEST_ASSERT_EQUAL(REC_PASS_LEN, strlen(p));
    // WPA2 wants 8..63 characters; the 24-byte rec_state field holds it.
    TEST_ASSERT_TRUE(REC_PASS_LEN >= 8 && REC_PASS_LEN < 24);
    // 100 bits: the last character takes bits 95..99 (the low bit of byte
    // 11 and the top four of byte 12).
    uint8_t last[REC_PASS_RAND_LEN] = {0};
    last[11] = 0x01;
    last[12] = 0xF0;
    rec_ap_pass(p, last);
    TEST_ASSERT_EQUAL_STRING("AAAAAAAAAAAAAAAAAAA7", p);
    last[11] = 0x00;
    last[12] = 0x0F;    // bits 100..103 are not used
    rec_ap_pass(p, last);
    TEST_ASSERT_EQUAL_STRING("AAAAAAAAAAAAAAAAAAAA", p);
}

static void test_hello(void)
{
    uint8_t token[REC_HELLO_TOKEN_LEN];
    for (int i = 0; i < REC_HELLO_TOKEN_LEN; i++) {
        token[i] = (uint8_t)(0xA0 + i);
    }
    uint8_t h[REC_HELLO_LEN + 1];
    memcpy(h, token, sizeof(token));
    h[16] = REC_STREAM_VERSION & 0xFF;
    h[17] = REC_STREAM_VERSION >> 8;
    TEST_ASSERT_TRUE(rec_hello_ok(h, REC_HELLO_LEN, token));
    TEST_ASSERT_FALSE(rec_hello_ok(h, REC_HELLO_LEN - 1, token));
    TEST_ASSERT_FALSE(rec_hello_ok(h, REC_HELLO_LEN + 1, token));
    TEST_ASSERT_FALSE(rec_hello_ok(NULL, REC_HELLO_LEN, token));
    for (int i = 0; i < REC_HELLO_TOKEN_LEN; i++) {
        h[i] ^= 0x01;
        TEST_ASSERT_FALSE(rec_hello_ok(h, REC_HELLO_LEN, token));
        h[i] ^= 0x01;
    }
    h[16]++;
    TEST_ASSERT_FALSE(rec_hello_ok(h, REC_HELLO_LEN, token));
}

static void test_audio_select(void)
{
    const int16_t st[] = {1, -1, 32767, -32768, 100, -32767, 7, 8};
    int16_t out[8];
    uint32_t clipped = 0;
    TEST_ASSERT_EQUAL(8, rec_audio_select(st, 4, REC_MIC_ALL, out, &clipped));
    TEST_ASSERT_EQUAL_INT16_ARRAY(st, out, 8);
    TEST_ASSERT_EQUAL_UINT32(3, clipped);
    clipped = 0;
    TEST_ASSERT_EQUAL(4, rec_audio_select(st, 4, REC_MIC_1, out, &clipped));
    TEST_ASSERT_EQUAL_INT16_ARRAY(((const int16_t[]){1, 32767, 100, 7}), out, 4);
    TEST_ASSERT_EQUAL_UINT32(1, clipped);
    clipped = 0;
    TEST_ASSERT_EQUAL(4, rec_audio_select(st, 4, REC_MIC_2, out, &clipped));
    TEST_ASSERT_EQUAL_INT16_ARRAY(((const int16_t[]){-1, -32768, -32767, 8}), out, 4);
    TEST_ASSERT_EQUAL_UINT32(2, clipped);
    TEST_ASSERT_EQUAL(0, rec_audio_select(st, 4, 0, out, &clipped));
    TEST_ASSERT_EQUAL_UINT8(2, rec_audio_channels(REC_MIC_ALL));
    TEST_ASSERT_EQUAL_UINT8(1, rec_audio_channels(REC_MIC_2));
    TEST_ASSERT_EQUAL_UINT8(0, rec_audio_channels(0));
}

static void test_block_pairing(void)
{
    rec_block_pair_t p = {0};
    uint32_t lost;
    TEST_ASSERT_EQUAL_UINT32(0, rec_block_pair(&p, 0, &lost));
    TEST_ASSERT_EQUAL_UINT32(0, lost);
    TEST_ASSERT_EQUAL_UINT32(1, rec_block_pair(&p, 0, &lost));
    // The queue overflowed twice: blocks 2 and 3 are gone, this read is 4.
    TEST_ASSERT_EQUAL_UINT32(4, rec_block_pair(&p, 2, &lost));
    TEST_ASSERT_EQUAL_UINT32(2, lost);
    TEST_ASSERT_EQUAL_UINT32(5, rec_block_pair(&p, 2, &lost));
    TEST_ASSERT_EQUAL_UINT32(0, lost);
    // Counters that wrap still give the difference.
    p = (rec_block_pair_t){.next = 10, .drops_seen = UINT32_MAX};
    TEST_ASSERT_EQUAL_UINT32(11, rec_block_pair(&p, 0, &lost));
    TEST_ASSERT_EQUAL_UINT32(1, lost);
}

static void test_in_place_select(void)
{
    // rec_audio.c selects in its DMA block.
    int16_t b[] = {1, -1, 2, -2, 3, -3};
    uint32_t clipped = 0;
    TEST_ASSERT_EQUAL(3, rec_audio_select(b, 3, REC_MIC_2, b, &clipped));
    TEST_ASSERT_EQUAL_INT16_ARRAY(((const int16_t[]){-1, -2, -3}), b, 3);
}

static void test_imu_tap(void)
{
    rec_imu_tap_t t = {0};
    rec_imu_t h;
    uint32_t chip;
    // The take's first read: a new clock (EVENT PROFILE), no EPOCH flag.
    TEST_ASSERT_TRUE(rec_imu_tap_read(&t, 3, 1000, &chip));
    TEST_ASSERT_EQUAL_UINT32(1000, chip);
    TEST_ASSERT_EQUAL_UINT16(10, rec_imu_tap_run(&t, chip, 990, 10, false, 5000, &h));
    TEST_ASSERT_EQUAL_UINT32(990, h.ts_first);
    TEST_ASSERT_EQUAL_UINT32(1000, h.chip_ts);
    TEST_ASSERT_EQUAL_UINT64(5000, h.host_us);
    TEST_ASSERT_EQUAL_UINT16(10, h.n);
    TEST_ASSERT_EQUAL_UINT16(0, h.flags);
    // The same clock: two samples lost between the runs (1000 -> 1002).
    TEST_ASSERT_FALSE(rec_imu_tap_read(&t, 3, 1012, &chip));
    rec_imu_tap_run(&t, chip, 1002, 10, false, 6000, &h);
    TEST_ASSERT_EQUAL_UINT32(2, t.lost);
    // A discontinuous run counts no loss.
    rec_imu_tap_run(&t, chip, 1100, 5, true, 6000, &h);
    TEST_ASSERT_EQUAL_UINT16(REC_IMU_DISCONT, h.flags);
    TEST_ASSERT_EQUAL_UINT32(2, t.lost);
    TEST_ASSERT_EQUAL_UINT32(25, t.frames);
    // A chip reset: a new clock, extended anew, its first record EPOCH.
    TEST_ASSERT_TRUE(rec_imu_tap_read(&t, 4, 20, &chip));
    TEST_ASSERT_EQUAL_UINT32(20, chip);
    rec_imu_tap_run(&t, chip, 10, 10, false, 7000, &h);
    TEST_ASSERT_EQUAL_UINT16(REC_IMU_EPOCH | REC_IMU_DISCONT, h.flags);
    rec_imu_tap_run(&t, chip, 20, 1, false, 7000, &h);
    TEST_ASSERT_EQUAL_UINT16(0, h.flags);
    TEST_ASSERT_EQUAL_UINT32(2, t.lost);
    // A run above REC_IMU_MAX_FRAMES is cut there; what follows counts the
    // frames left out as lost.
    TEST_ASSERT_EQUAL_UINT16(REC_IMU_MAX_FRAMES,
                             rec_imu_tap_run(&t, chip, 21, REC_IMU_MAX_FRAMES + 4, false, 7000, &h));
    TEST_ASSERT_EQUAL_UINT16(REC_IMU_MAX_FRAMES, h.n);
    rec_imu_tap_run(&t, chip, 21 + REC_IMU_MAX_FRAMES + 4, 1, false, 8000, &h);
    TEST_ASSERT_EQUAL_UINT32(2 + 4, t.lost);
    TEST_ASSERT_EQUAL_UINT32(25 + 10 + 1 + REC_IMU_MAX_FRAMES + 1, t.frames);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_ssid);
    RUN_TEST(test_pass_is_base32_of_the_random_bits);
    RUN_TEST(test_hello);
    RUN_TEST(test_audio_select);
    RUN_TEST(test_block_pairing);
    RUN_TEST(test_in_place_select);
    RUN_TEST(test_imu_tap);
    return UNITY_END();
}
