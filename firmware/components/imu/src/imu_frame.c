#include <math.h>
#include "imu_frame.h"

#define ACCEL_SCALE (1.0f / IMU_ACCEL_LSB_PER_G)
#define GYRO_SCALE LEVEL_DEG2RAD(1.0f / IMU_GYRO_LSB_PER_DPS)

bool imu_axis_map_valid(const imu_axis_map_t *map)
{
    const imu_axis_t axes[3] = {map->x, map->y, map->z};
    int m[3][3] = {{0}};
    unsigned used = 0;
    for (int i = 0; i < 3; i++) {
        if (axes[i].src > 2 || (axes[i].sign != 1 && axes[i].sign != -1)) {
            return false;
        }
        used |= 1u << axes[i].src;
        m[i][axes[i].src] = axes[i].sign;
    }
    if (used != 0x7u) {
        return false;
    }
    int det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
              m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
              m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    return det == 1;
}

static int16_t le16(const uint8_t *p)
{
    return (int16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static float pick(const int16_t raw[3], imu_axis_t a)
{
    return (float)(a.sign * raw[a.src]);
}

static vec3_t map_vec(const imu_axis_map_t *map, const uint8_t *p, float scale)
{
    const int16_t raw[3] = {le16(&p[0]), le16(&p[2]), le16(&p[4])};
    return (vec3_t){
        pick(raw, map->x) * scale,
        pick(raw, map->y) * scale,
        pick(raw, map->z) * scale,
    };
}

void imu_decode_frame(const imu_axis_map_t *map, const uint8_t frame[IMU_FRAME_BYTES],
                      imu_sample_t *out)
{
    out->accel_g = map_vec(map, &frame[0], ACCEL_SCALE);
    out->gyro_rad_s = map_vec(map, &frame[6], GYRO_SCALE);
}

size_t imu_fifo_frames(uint8_t smpl_cnt, uint8_t fifo_status)
{
    size_t words = ((size_t)(fifo_status & 0x03u) << 8) | smpl_cnt;
    return (2u * words) / IMU_FRAME_BYTES;
}

// a - b on the 24-bit timestamp circle.
static int32_t ts_diff(uint32_t a, uint32_t b)
{
    uint32_t d = (a - b) & IMU_TS_MASK;
    return (d & 0x800000u) ? (int32_t)d - 0x1000000 : (int32_t)d;
}

// Samples lost since next_ts, or -1 if the numbers do not fit together.
static int32_t lost_raw(const imu_gap_t *g, uint32_t ts, size_t frames)
{
    int32_t lost = ts_diff(ts, g->next_ts) + 1 - (int32_t)frames;
    return (lost < 0 || lost > (int32_t)IMU_GAP_MAX) ? -1 : lost;
}

static size_t min_size(size_t a, size_t b)
{
    return a < b ? a : b;
}

imu_gap_plan_t imu_gap_plan(const imu_gap_t *g, uint32_t ts, size_t frames, bool trusted,
                            size_t max)
{
    imu_gap_plan_t p = {
        .n_read = min_size(min_size(frames, max), IMU_FIFO_MAX_FRAMES),
    };
    if (!g->valid || frames == 0) {
        return p;
    }
    int32_t lost = lost_raw(g, ts, frames);
    if (lost > 0) {
        p.lost = (uint32_t)lost;
        p.insert_at = min_size(g->contiguous, frames);
    }
    if (!trusted || p.lost == 0 || p.n_read <= p.insert_at) {
        return p;
    }
    if (p.insert_at > 0 && p.insert_at + p.lost >= max) {
        p.n_read = p.insert_at;     // emit the gap with the next call
    } else {
        p.n_fill = min_size(p.lost, max - p.insert_at - 1);
        p.n_read = min_size(p.n_read, max - p.n_fill);
    }
    return p;
}

void imu_gap_commit(imu_gap_t *g, uint32_t ts, size_t frames, size_t read)
{
    size_t head = min_size(g->contiguous, frames);
    if (g->valid && read <= head && lost_raw(g, ts, frames) >= 0) {
        // Still inside the old run: any gap stays ahead of us.
        g->next_ts = (g->next_ts + (uint32_t)read) & IMU_TS_MASK;
        g->contiguous = head - read;
        return;
    }
    // Past the gap (or resynchronising): the newest FIFO sample carries ts.
    g->valid = true;
    g->next_ts = (ts - (uint32_t)frames + 1u + (uint32_t)read) & IMU_TS_MASK;
    g->contiguous = frames - read;
}

static vec3_t lerp3(vec3_t a, vec3_t b, float t)
{
    return vec3_add(a, vec3_scale(vec3_sub(b, a), t));
}

void imu_fill_gap(const imu_sample_t *a, const imu_sample_t *b, size_t n, imu_sample_t *out)
{
    for (size_t i = 0; i < n; i++) {
        float t = (float)(i + 1) / (float)(n + 1);
        out[i].accel_g = lerp3(a->accel_g, b->accel_g, t);
        out[i].gyro_rad_s = lerp3(a->gyro_rad_s, b->gyro_rad_s, t);
    }
}

void imu_rate_init(imu_rate_t *r, float nominal_period_s)
{
    *r = (imu_rate_t){.nominal_s = nominal_period_s, .period_s = nominal_period_s};
}

void imu_rate_update(imu_rate_t *r, uint32_t ts, int64_t now_us)
{
    if (!r->started) {
        r->started = true;
        r->last_ts = ts;
        r->t0_us = now_us;
        r->last_us = now_us;
        r->ticks = 0;
        return;
    }
    if (now_us <= r->last_us) {
        return;
    }
    r->ticks += (ts - r->last_ts) & IMU_TS_MASK;
    r->last_ts = ts;
    r->last_us = now_us;

    int64_t span_us = now_us - r->t0_us;
    if (span_us < IMU_RATE_MIN_BASELINE_US || r->ticks == 0) {
        return;
    }
    float p = (float)span_us * 1e-6f / (float)r->ticks;
    if (fabsf(p - r->nominal_s) <= IMU_RATE_MAX_DEVIATION * r->nominal_s) {
        r->period_s = p;
        r->measured = true;
    }
}
