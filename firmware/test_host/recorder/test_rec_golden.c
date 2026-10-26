// The golden stream: one deterministic take with every record type, a
// dropped record, a FIFO loss, a resynchronised IMU run, an unknown record
// type, junk bytes and a record with a bad CRC. It is checked byte for byte
// against vectors/golden_stream.bin; a missing file fails, and
// REC_GOLDEN_UPDATE=1 (re)writes it. tools/dataset
// (tests/test_dbrec.py) decodes the committed file and checks every field;
// the app's stream decoder test reads the same file in place, through
// FirmwareSources (../firmware/test_host/recorder/vectors/golden_stream.bin).
//
// Contents, in stream order (T = 1203345000 us, TS0 = 0x00FFFFFA; IMU host
// times are T + 100000 + (chip_ts - TS0) * 2230, audio block k ends at
// T + 90000 + (k + 1) * 16000):
//   seq  0 SESSION take 42, RAW448, JSON tail GOLDEN_JSON
//   seq  1 LEVEL   T+50000, wall, IMU_OK|STILL, elevation 40
//   seq  2 IMU     8 frames from TS0, DISCONT (first read of the take)
//   seq  3 AUDIO   block 0
//   seq  4 IMU     3 frames from TS0+8      } one FIFO read, 2 samples
//   seq  5 IMU     4 frames from TS0+13     } lost in read mode
//   seq  6 EVENT   MARK arg 7 "hole 1 – oak"
//   seq  7 AUDIO   block 1
//   seq  8 (dropped on the device: IMU, 5 frames from TS0+17)
//   seq  9 AUDIO   block 3, OVF_BEFORE (block 2 lost)
//   seq 10 IMU     5 frames from TS0+22
//   seq 11 EVENT   TOUCH
//   seq 12 type 0x7E (unknown), payload "future"
//          5 junk bytes
//   seq 13 LEVEL   T+150000, angle, IMU_OK|ZERO_SET, zero 1234/10/-20
//   seq 14 EVENT   VIB_START with its CRC inverted
//   seq 15 IMU     4 frames from TS0+70, DISCONT (resynchronised)
//   seq 16 STATS
//   seq 17 END     USER

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "unity.h"
#include "rec_stream.h"

#define T 1203345000ull
#define TS0 0x00FFFFFAu

static const char GOLDEN_JSON[] =
    "{\"settings\":{\"tol\":0.5,\"near\":2.0,\"target\":0.0,\"mode\":\"wall\"},"
    "\"cal\":{\"accel\":{\"valid\":true,\"bias_g\":[0.004,-0.011,0.02],"
    "\"scale\":[1.002,0.998,1.004]},"
    "\"mount\":{\"valid\":true,\"drill_axis\":[0.012,-0.02,-0.9997]},"
    "\"gyro_bias_rad_s\":[0.0011,-0.0004,0.0002]}}";

static uint8_t s_buf[16384];
static size_t s_len;
static uint32_t s_seq;

void setUp(void) {}
void tearDown(void) {}

static size_t emit_seq(uint8_t type, uint8_t ver, uint32_t seq, const rec_part_t *parts,
                       size_t nparts)
{
    size_t n = rec_stream_encode(type, ver, seq, parts, nparts, &s_buf[s_len],
                                 sizeof(s_buf) - s_len);
    TEST_ASSERT_TRUE(n > 0);
    s_len += n;
    return n;
}

static void emit(uint8_t type, uint8_t ver, const rec_part_t *parts, size_t nparts)
{
    emit_seq(type, ver, s_seq++, parts, nparts);
}

static uint64_t imu_host(uint32_t chip_ts)
{
    return T + 100000u + (uint64_t)(chip_ts - TS0) * 2230u;
}

// Sample k of the take (k = ts - TS0): a pattern every decoder can recompute.
static void imu_frame(uint32_t k, int16_t f[6])
{
    f[0] = (int16_t)(100 + k);
    f[1] = (int16_t)(8192 - k);
    f[2] = (int16_t)(-50 + 2 * (int)k);
    f[3] = (int16_t)k;
    f[4] = (int16_t)(-(int)k);
    f[5] = (int16_t)(3 * k);
}

static void imu(uint32_t first, uint16_t n, uint32_t chip_ts, uint16_t flags, bool send)
{
    int16_t frames[16][6];
    for (uint16_t i = 0; i < n; i++) {
        imu_frame(first + i - TS0, frames[i]);
    }
    const rec_imu_t h = {.ts_first = first, .chip_ts = chip_ts, .host_us = imu_host(chip_ts),
                         .n = n, .flags = flags};
    const rec_part_t p[] = {{&h, sizeof(h)}, {frames, (size_t)n * 12u}};
    if (send) {
        emit(REC_T_IMU, REC_V_IMU, p, 2);
    } else {
        s_seq++;                        // dropped on the device: the seq is used
    }
}

static void audio(uint32_t block, uint16_t flags)
{
    int16_t s[256][2];
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t idx = block * 256u + i;
        s[i][0] = (int16_t)((int32_t)((idx * 37u) % 2001u) - 1000);
        s[i][1] = (int16_t)((int32_t)((idx * 53u) % 4001u) - 2000);
    }
    const rec_audio_t h = {.sample_index = block * 256u,
                           .host_us = T + 90000u + (block + 1u) * 16000u, .n = 256,
                           .flags = flags};
    const rec_part_t p[] = {{&h, sizeof(h)}, {s, sizeof(s)}};
    emit(REC_T_AUDIO, REC_V_AUDIO, p, 2);
}

static void event(uint64_t host_us, uint16_t code, uint16_t arg, const char *text)
{
    uint8_t e[REC_EVENT_MAX];
    const rec_part_t p = {e, rec_event_payload(e, host_us, code, arg, text)};
    emit(REC_T_EVENT, REC_V_EVENT, &p, 1);
}

static void level(const rec_level_t *l)
{
    const rec_part_t p = {l, sizeof(*l)};
    emit(REC_T_LEVEL, REC_V_LEVEL, &p, 1);
}

static void build(void)
{
    s_len = 0;
    s_seq = 0;

    rec_session_t s = {
        .take_id = 42,
        .start_us = T,
        .board_id = {0xB3, 0xC9, 0xE1, 0xA2},
        .profile = REC_PROFILE_RAW448,
        .ctrl2 = 0x14,
        .ctrl3 = 0x54,
        .ctrl5 = 0x00,
        .odr_mhz = 448400,
        .accel_lsb_per_g = 8192,
        .gyro_lsb_per_dps = 64,
        .axis = {{1, 1}, {0, -1}, {2, 1}},
        .audio_rate_hz = 16000,
        .audio_channels = 2,
        .audio_bits = 16,
        .mic_mask = REC_MIC_ALL,
        .gain_db = 6,
        .audio_block_frames = 256,
        .max_s = 300,
    };
    rec_fw_text(s.fw, "0.1.0", "0e5105e");
    const rec_part_t sp[] = {{&s, sizeof(s)}, {GOLDEN_JSON, strlen(GOLDEN_JSON)}};
    emit(REC_T_SESSION, REC_V_SESSION, sp, 2);

    level(&(rec_level_t){.host_us = T + 50000, .mode = 0, .flags = 0x60, .elevation_cdeg = 40,
                         .deviation_cdeg = 40});
    imu(TS0, 8, TS0 + 7, REC_IMU_DISCONT, true);
    audio(0, 0);
    imu(TS0 + 8, 3, TS0 + 16, 0, true);
    imu(TS0 + 13, 4, TS0 + 16, 0, true);
    event(T + 125000, REC_EV_MARK, 7, "hole 1 \xE2\x80\x93 oak");
    audio(1, 0);
    imu(TS0 + 17, 5, TS0 + 21, 0, false);
    audio(3, REC_AUDIO_OVF_BEFORE);
    imu(TS0 + 22, 5, TS0 + 26, 0, true);
    event(T + 151000, REC_EV_TOUCH, 0, NULL);
    const rec_part_t fut = {"future", 6};
    emit(0x7E, 1, &fut, 1);

    static const uint8_t junk[] = {0xDE, 0xAD, 0xBE, 0xEF, 0x00};
    memcpy(&s_buf[s_len], junk, sizeof(junk));
    s_len += sizeof(junk);

    level(&(rec_level_t){.host_us = T + 150000, .mode = 2, .flags = 0x50, .elevation_cdeg = 120,
                         .yaw_cdeg = -30, .bubble_x_cdeg = 5, .bubble_y_cdeg = -5,
                         .deviation_cdeg = 120, .zero_elevation_cdeg = 1234,
                         .zero_bubble_x_cdeg = 10, .zero_bubble_y_cdeg = -20});

    size_t at = s_len;
    event(T + 205000, REC_EV_VIB_START, 0, NULL);
    for (size_t i = s_len - REC_CRC_LEN; i < s_len; i++) {
        s_buf[i] ^= 0xFF;
    }
    TEST_ASSERT_EQUAL_UINT(24, s_len - at);

    imu(TS0 + 70, 4, TS0 + 73, REC_IMU_DISCONT, true);

    const rec_stats_t st = {
        .host_us = T + 210000,
        .imu_frames = 24,
        .imu_lost = 45,
        .imu_period_ns = 2230000,
        .audio_frames = 1024,
        .audio_overflows = 1,
        .audio_rate_mhz = 16000500,
        .audio_clipped = 0,
        .ring_high_water = 1234,
        .ring_size = 4u << 20,
        .dropped_records = 1,
        .dropped_bytes = 92,
        .battery_mv = 3987,
        .battery_pct = 76,
        .power_flags = REC_STATS_BATTERY_VALID,
    };
    const rec_part_t stp = {&st, sizeof(st)};
    emit(REC_T_STATS, REC_V_STATS, &stp, 1);

    const rec_end_t end = {
        .host_us = T + 220000,
        .reason = REC_REASON_USER,
        .records = s_seq + 1,
        .dropped_records = 1,
        .dropped_bytes = 92,
        .imu_frames = 24,
        .imu_lost = 45,
        .audio_frames = 1024,
        .audio_overflows = 1,
    };
    const rec_part_t ep = {&end, sizeof(end)};
    emit(REC_T_END, REC_V_END, &ep, 1);
}

// A decoder's walk: records, and the bytes skipped to resynchronise.
static void test_stream_walks_like_a_decoder(void)
{
    build();
    uint32_t seqs[32];
    size_t n = 0, skipped = 0, o = 0;
    while (o < s_len) {
        rec_hdr_t h;
        int r = rec_stream_check(&s_buf[o], s_len - o, &h);
        if (r > 0) {
            TEST_ASSERT_TRUE(n < 32);
            seqs[n++] = h.seq;
            o += (size_t)r;
        } else {
            o++;
            skipped++;
        }
    }
    static const uint32_t want[] = {0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 15, 16, 17};
    TEST_ASSERT_EQUAL_UINT(sizeof(want) / sizeof(want[0]), n);
    TEST_ASSERT_EQUAL_UINT32_ARRAY(want, seqs, n);
    TEST_ASSERT_EQUAL_UINT(5 + 24, skipped);
}

// The built stream against the file at path, or written there with update.
// NULL when they match (or it was written), else why not.
static const char *check_file(const char *path, bool update)
{
    if (update) {
        FILE *fp = fopen(path, "wb");
        if (!fp) {
            return "cannot write the file";
        }
        size_t put = fwrite(s_buf, 1, s_len, fp);
        return fclose(fp) == 0 && put == s_len ? NULL : "writing the file failed";
    }
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        return "the file is missing: REC_GOLDEN_UPDATE=1 writes it";
    }
    static uint8_t file[sizeof(s_buf) + 1];
    size_t got = fread(file, 1, sizeof(file), fp);
    fclose(fp);
    if (got != s_len || memcmp(file, s_buf, s_len) != 0) {
        return "the file differs: the wire format changed? REC_GOLDEN_UPDATE=1 rewrites it";
    }
    return NULL;
}

static void test_matches_the_committed_file(void)
{
    build();
    const char *update = getenv("REC_GOLDEN_UPDATE");
    bool write = update && *update;
    const char *err = check_file(GOLDEN_PATH, write);
    TEST_ASSERT_NULL_MESSAGE(err, err);
    if (write) {
        TEST_MESSAGE("wrote " GOLDEN_PATH);
    }
}

// A missing file is no pass, and the check does not create it.
static void test_missing_file_fails(void)
{
    build();
    const char *path = GOLDEN_PATH ".missing";
    remove(path);
    TEST_ASSERT_NOT_NULL(check_file(path, false));
    FILE *fp = fopen(path, "rb");
    if (fp) {
        fclose(fp);
        remove(path);
    }
    TEST_ASSERT_NULL(fp);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_stream_walks_like_a_decoder);
    RUN_TEST(test_matches_the_committed_file);
    RUN_TEST(test_missing_file_fails);
    return UNITY_END();
}
