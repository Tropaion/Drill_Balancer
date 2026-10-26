#include <stdio.h>
#include <string.h>
#include "rec_util.h"

void rec_ap_ssid(char out[REC_SSID_LEN + 1], const uint8_t mac_tail[2], uint16_t rnd)
{
    snprintf(out, REC_SSID_LEN + 1, "DB-%02X%02X-%04X", mac_tail[0], mac_tail[1], (unsigned)rnd);
}

void rec_ap_pass(char out[REC_PASS_LEN + 1], const uint8_t rnd[REC_PASS_RAND_LEN])
{
    static const char B32[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    for (int i = 0; i < REC_PASS_LEN; i++) {
        // Bits 5i .. 5i+4, most significant first.
        int bit = 5 * i;
        unsigned v = ((unsigned)rnd[bit / 8] << 8) | (bit / 8 + 1 < REC_PASS_RAND_LEN ? rnd[bit / 8 + 1] : 0u);
        out[i] = B32[(v >> (11 - bit % 8)) & 0x1Fu];
    }
    out[REC_PASS_LEN] = '\0';
}

bool rec_hello_ok(const uint8_t *hello, size_t len, const uint8_t token[REC_HELLO_TOKEN_LEN])
{
    if (hello == NULL || len != REC_HELLO_LEN) {
        return false;
    }
    uint8_t d = 0;
    for (size_t i = 0; i < REC_HELLO_TOKEN_LEN; i++) {
        d |= (uint8_t)(hello[i] ^ token[i]);
    }
    unsigned version = (unsigned)hello[16] | ((unsigned)hello[17] << 8);
    return d == 0 && version == REC_STREAM_VERSION;
}

uint8_t rec_audio_channels(uint8_t mic_mask)
{
    return (uint8_t)(((mic_mask & REC_MIC_1) ? 1u : 0u) + ((mic_mask & REC_MIC_2) ? 1u : 0u));
}

size_t rec_audio_select(const int16_t *stereo, size_t frames, uint8_t mic_mask, int16_t *out,
                        uint32_t *clipped)
{
    bool both = (mic_mask & REC_MIC_ALL) == REC_MIC_ALL;
    size_t only = (mic_mask & REC_MIC_1) ? 0u : 1u;
    if (rec_audio_channels(mic_mask) == 0) {
        return 0;
    }
    size_t k = 0;
    for (size_t i = 0; i < frames * 2u; i++) {
        if (both || i % 2u == only) {
            int16_t s = stereo[i];
            out[k++] = s;
            *clipped += (s >= 32767 || s <= -32767) ? 1u : 0u;
        }
    }
    return k;
}

uint32_t rec_block_pair(rec_block_pair_t *p, uint32_t drops_now, uint32_t *dropped_before)
{
    uint32_t lost = drops_now - p->drops_seen;
    p->drops_seen = drops_now;
    uint32_t blk = p->next + lost;
    p->next = blk + 1u;
    *dropped_before = lost;
    return blk;
}

bool rec_imu_tap_read(rec_imu_tap_t *t, uint32_t epoch, uint32_t chip_ts24, uint32_t *chip)
{
    bool new_clock = !t->started || epoch != t->epoch;
    if (new_clock) {
        t->epoch_flag = t->started;
        t->started = true;
        t->epoch = epoch;
        t->ts_ext = (rec_ts_ext_t){0};
        t->have_next = false;
    }
    *chip = rec_ts_extend(&t->ts_ext, chip_ts24);
    return new_clock;
}

uint16_t rec_imu_tap_run(rec_imu_tap_t *t, uint32_t chip, uint32_t ts_first24, uint16_t count,
                         bool discontinuous, int64_t host_us, rec_imu_t *hdr)
{
    uint16_t n = count > REC_IMU_MAX_FRAMES ? (uint16_t)REC_IMU_MAX_FRAMES : count;
    uint32_t first = rec_ts_near(chip, ts_first24);
    uint16_t flags = discontinuous ? REC_IMU_DISCONT : 0u;
    if (t->epoch_flag) {
        flags |= REC_IMU_EPOCH | REC_IMU_DISCONT;
        t->epoch_flag = false;
    }
    if (t->have_next && !(flags & REC_IMU_DISCONT)) {
        t->lost += first - t->next_ts;
    }
    t->next_ts = first + n;
    t->have_next = true;
    t->frames += n;
    *hdr = (rec_imu_t){.ts_first = first, .chip_ts = chip, .host_us = (uint64_t)host_us, .n = n,
                       .flags = flags};
    return n;
}
