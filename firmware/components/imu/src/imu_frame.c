#include <math.h>
#include "imu_frame.h"

// CTRL2/CTRL3 (Table 22): full scale <<4 | ODR. aFS 001 = +-4 g, 011 =
// +-16 g; gFS 101 = +-512 dps. ODR 0100 = 448.4 Hz in 6DOF mode, the
// closest to 470 Hz; 0011 = 896.8 Hz. In 6DOF the accelerometer runs at the
// gyro-derived rate, and the FIFO needs both at the same ODR (Table 22 note
// 13, 8.2).
#define FS_A4G   (0x1 << 4)
#define FS_A16G  (0x3 << 4)
#define FS_G512  (0x5 << 4)
#define ODR_448  0x4
#define ODR_897  0x3

// CTRL5 LPF for LEVEL: gLPF mode 10 (5.39% of ODR = 24.2 Hz), aLPF mode 00
// (2.66% = 11.9 Hz) (Tables 11, 13). Hand motion is well below both; the
// accel only feeds the slow tilt correction, so it gets the stronger filter
// against drill vibration. The vibration detector (level/src/vibration.c)
// sees only what the aLPF passes: retune its defaults whenever the aLPF
// changes.
// TODO(hw): tune both on a running drill.
#define CTRL5_LEVEL ((0x2 << 5) | (1 << 4) | (0x0 << 1) | 1)
// Both LPFs off: what remains is the chip's own anti-aliasing at the ODR.
// TODO(hw): look at the spectrum of a running drill for aliasing at 448 Hz.
#define CTRL5_RAW 0x00

// The scales from the LSB counts, folded at compile time: decoding only
// multiplies.
#define SCALES(a_lsb, g_lsb) a_lsb, g_lsb, 1.0f / (a_lsb), LEVEL_DEG2RAD(1.0f / (g_lsb))

// RAW897 doubles the FIFO traffic; at 400 kHz I2C the read-mode loss is
// expected around 25 %. TODO(hw): measure the loss and decide whether it
// stays.
static const imu_profile_info_t PROFILES[IMU_PROFILE_COUNT] = {
    [IMU_PROFILE_LEVEL] = {"LEVEL", FS_A4G | ODR_448, FS_G512 | ODR_448, CTRL5_LEVEL, 448400,
                           SCALES(8192, 64)},
    [IMU_PROFILE_RAW448] = {"RAW448", FS_A4G | ODR_448, FS_G512 | ODR_448, CTRL5_RAW, 448400,
                            SCALES(8192, 64)},
    // TODO(hw): check whether hammer drilling clips at +-4 g at all.
    [IMU_PROFILE_RAW448_16G] = {"RAW448_16G", FS_A16G | ODR_448, FS_G512 | ODR_448, CTRL5_RAW,
                                448400, SCALES(2048, 64)},
    [IMU_PROFILE_RAW897] = {"RAW897", FS_A4G | ODR_897, FS_G512 | ODR_897, CTRL5_RAW, 896800,
                            SCALES(8192, 64)},
};

const imu_profile_info_t *imu_profile_info(imu_profile_t p)
{
    return (unsigned)p < IMU_PROFILE_COUNT ? &PROFILES[p] : NULL;
}

// Chip axes -> board frame (X right, Y up along the screen, Z towards the
// user), measured on the board: at rest the chip reads -1 g on Z with the
// screen up, +1 g on X standing on the bottom edge (top edge up) and +1 g on
// Y lying on the left edge (right edge up). The QMI8658 sits turned by 90
// degrees and upside down. imu_init() rejects mirror maps.
static const imu_axis_map_t AXIS_MAP = {
    .x = {1, 1},
    .y = {0, 1},
    .z = {2, -1},
};

const imu_axis_map_t *imu_axis_map(void)
{
    return &AXIS_MAP;
}

vec3_t imu_board_to_chip(const imu_axis_map_t *map, vec3_t board)
{
    const imu_axis_t axes[3] = {map->x, map->y, map->z};
    const float b[3] = {board.x, board.y, board.z};
    float c[3] = {0.0f, 0.0f, 0.0f};
    for (int i = 0; i < 3; i++) {
        c[axes[i].src] = (float)axes[i].sign * b[i];
    }
    return (vec3_t){c[0], c[1], c[2]};
}

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

// One chip vector (x, y, z, le16) of a frame.
static void chip_raw3(const uint8_t *p, int16_t raw[3])
{
    raw[0] = le16(&p[0]);
    raw[1] = le16(&p[2]);
    raw[2] = le16(&p[4]);
}

static float pick(const int16_t raw[3], imu_axis_t a)
{
    return (float)(a.sign * raw[a.src]);
}

static vec3_t map_vec(const imu_axis_map_t *map, const uint8_t *p, float scale)
{
    int16_t raw[3];
    chip_raw3(p, raw);
    return (vec3_t){
        pick(raw, map->x) * scale,
        pick(raw, map->y) * scale,
        pick(raw, map->z) * scale,
    };
}

void imu_decode_frame(const imu_axis_map_t *map, const imu_profile_info_t *p,
                      const uint8_t frame[IMU_FRAME_BYTES], imu_sample_t *out)
{
    out->accel_g = map_vec(map, &frame[0], p->accel_scale);
    out->gyro_rad_s = map_vec(map, &frame[6], p->gyro_scale);
}

static int16_t pick_i16(const int16_t raw[3], imu_axis_t a)
{
    int32_t v = a.sign * raw[a.src];
    return v > INT16_MAX ? INT16_MAX : (int16_t)v;
}

void imu_map_frame_i16(const imu_axis_map_t *map, const uint8_t frame[IMU_FRAME_BYTES],
                       int16_t out[6])
{
    for (int k = 0; k < 2; k++) {
        int16_t raw[3];
        chip_raw3(&frame[6 * k], raw);
        out[3 * k + 0] = pick_i16(raw, map->x);
        out[3 * k + 1] = pick_i16(raw, map->y);
        out[3 * k + 2] = pick_i16(raw, map->z);
    }
}

size_t imu_fifo_frames(uint8_t smpl_cnt, uint8_t fifo_status)
{
    size_t words = ((size_t)(fifo_status & 0x03u) << 8) | smpl_cnt;
    return (2u * words) / IMU_FRAME_BYTES;
}

// Samples lost since next_ts, or -1 if the numbers do not fit together.
static int32_t lost_raw(const imu_gap_t *g, uint32_t ts, size_t frames)
{
    int32_t lost = imu_ts_diff(ts, g->next_ts) + 1 - (int32_t)frames;
    return (lost < 0 || lost > (int32_t)IMU_GAP_MAX) ? -1 : lost;
}

static size_t min_size(size_t a, size_t b)
{
    return a < b ? a : b;
}

imu_gap_plan_t imu_gap_plan(const imu_gap_t *g, uint32_t ts, size_t frames, bool trusted,
                            size_t max)
{
    // The same test as imu_gap_commit()'s resynchronisation.
    int32_t lost = g->valid ? lost_raw(g, ts, frames) : -1;
    imu_gap_plan_t p = {
        .n_read = min_size(min_size(frames, max), IMU_FIFO_MAX_FRAMES),
        .resync = lost < 0,
        .ts_first = lost < 0 ? (ts - (uint32_t)frames + 1u) & IMU_TS_MASK : g->next_ts,
    };
    if (frames == 0 || lost <= 0) {
        return p;
    }
    p.lost = (uint32_t)lost;
    p.insert_at = min_size(g->contiguous, frames);
    if (!trusted || p.n_read <= p.insert_at) {
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
    int32_t lost = g->valid ? lost_raw(g, ts, frames) : -1;
    if (lost >= 0 && read <= head) {
        // Still inside the old run: any gap stays ahead of us. Without one
        // the whole FIFO is a single run, also what arrived since.
        g->next_ts = (g->next_ts + (uint32_t)read) & IMU_TS_MASK;
        g->contiguous = (lost == 0 ? frames : head) - read;
        return;
    }
    // Past the gap (or resynchronising): the newest FIFO sample carries ts.
    g->valid = true;
    g->next_ts = (ts - (uint32_t)frames + 1u + (uint32_t)read) & IMU_TS_MASK;
    g->contiguous = frames - read;
}

size_t imu_raw_runs(const imu_gap_plan_t *p, imu_raw_run_t run[2])
{
    run[0] = run[1] = (imu_raw_run_t){0};
    size_t n = p->n_read;
    if (n == 0) {
        return 0;
    }
    if (p->resync) {
        run[0] = (imu_raw_run_t){
            .count = (uint16_t)n,
            .ts_first = p->ts_first,
            .discontinuous = true,
        };
        return 1;
    }
    size_t before = p->lost > 0 ? min_size(p->insert_at, n) : n;
    size_t k = 0;
    if (before > 0) {
        run[k++] = (imu_raw_run_t){.count = (uint16_t)before, .ts_first = p->ts_first};
    }
    if (before < n) {
        run[k++] = (imu_raw_run_t){
            .first = (uint16_t)before,
            .count = (uint16_t)(n - before),
            .ts_first = (p->ts_first + (uint32_t)before + p->lost) & IMU_TS_MASK,
        };
    }
    return k;
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

void imu_rate_restart(imu_rate_t *r, float nominal_period_s)
{
    bool measured = r->measured && r->nominal_s > 0.0f;
    float period = measured ? r->period_s * (nominal_period_s / r->nominal_s) : nominal_period_s;
    imu_rate_init(r, nominal_period_s);
    r->period_s = period;
    r->measured = measured;
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
