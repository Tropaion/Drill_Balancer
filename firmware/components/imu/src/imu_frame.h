#pragma once

// Hardware-independent parts of the QMI8658 driver: the profile table, frame
// decoding, axis mapping, FIFO gap bookkeeping, raw timestamp runs and ODR
// estimation. Pure C, so test_host can build it (with stub ESP-IDF headers
// for imu.h).

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "imu.h"
#include "imu_ts.h"

#ifdef __cplusplus
extern "C" {
#endif

// A valid map uses every chip axis exactly once with sign +-1 and is a
// rotation (determinant +1): both frames are right-handed.
bool imu_axis_map_valid(const imu_axis_map_t *map);

// Decodes with the scales of profile p.
void imu_decode_frame(const imu_axis_map_t *map, const imu_profile_info_t *p,
                      const uint8_t frame[IMU_FRAME_BYTES], imu_sample_t *out);

// Complete 6DOF frames in the FIFO from FIFO_SMPL_CNT (0x15) and FIFO_STATUS
// (0x16): the 10-bit count is in 16-bit words (datasheet 8.4).
size_t imu_fifo_frames(uint8_t smpl_cnt, uint8_t fifo_status);

// The FIFO drops every sample that arrives while it is in read mode
// (datasheet 8.7), roughly 12% at 448 Hz over 400 kHz I2C. The 24-bit
// TIMESTAMP counter (0x30..0x32) keeps counting, so the number of lost
// samples follows from it and the driver can fill them in. Samples still in
// the FIFO after a partial read are older than anything lost during that
// read, so the gap sits after them.

typedef struct {
    bool valid;
    uint32_t next_ts;     // timestamp of the next sample the host expects
    size_t contiguous;    // FIFO head samples known to follow next_ts directly
} imu_gap_t;

// One FIFO read: read n_read samples and insert n_fill interpolated ones in
// front of FIFO index insert_at (n_fill > 0 implies insert_at < n_read).
typedef struct {
    uint32_t lost;        // samples lost before FIFO index insert_at
    size_t insert_at;
    size_t n_read;        // FIFO samples to read now
    size_t n_fill;        // lost samples to interpolate now
    // resync: g not valid, or a gap the plan does not report because it is
    // inconsistent or above IMU_GAP_MAX. ts_first: timestamp of FIFO index
    // 0, g->next_ts, or ts - frames + 1 after a resync.
    bool resync;
    uint32_t ts_first;
} imu_gap_plan_t;

// Gaps above this are not a few dropped samples but a stall or a FIFO
// overflow; the driver resynchronises instead of inventing data.
#define IMU_GAP_MAX 32u

// FIFO_CTRL stream mode, 128 samples (1536 bytes, 285 ms): the most one read
// can return.
#define IMU_FIFO_MAX_FRAMES 128u

// ts: TIMESTAMP read together with the FIFO count; frames: samples in the
// FIFO. trusted: TIMESTAMP is known to count 6DOF samples (imu_rate_t
// measured); until then nothing is filled. max: room in the output for read
// plus filled samples. A gap that does not fit behind the old samples ahead
// of it is left for the next call (they are read now); otherwise the fill is
// cut to leave room for at least the sample after the gap.
imu_gap_plan_t imu_gap_plan(const imu_gap_t *g, uint32_t ts, size_t frames, bool trusted,
                            size_t max);

// After reading `read` of the `frames` samples (the gap, if any, was emitted
// when read > plan.insert_at).
void imu_gap_commit(imu_gap_t *g, uint32_t ts, size_t frames, size_t read);

// The timestamp runs of one read (imu_raw_info_t.run) from the plan it
// followed. Frames before p->insert_at carry p->ts_first + i, later ones
// p->ts_first + i + p->lost. After a resynchronisation (p->resync) frame i
// carries p->ts_first + i and run[0] is discontinuous. Returns the number
// of runs used (0 when nothing is read); unused runs have count 0.
size_t imu_raw_runs(const imu_gap_plan_t *p, imu_raw_run_t run[2]);

// n samples linearly interpolated between a and b, excluding both ends.
void imu_fill_gap(const imu_sample_t *a, const imu_sample_t *b, size_t n, imu_sample_t *out);

// The 6DOF ODR is derived from the gyro's natural frequency (datasheet 3.5,
// Table 11 note 10), so the real rate can differ from the nominal 448.4 Hz.
// Measured from the TIMESTAMP counter against the host clock.
#define IMU_RATE_MIN_BASELINE_US 2000000
#define IMU_RATE_MAX_DEVIATION 0.1f

typedef struct {
    float nominal_s;
    float period_s;
    bool measured;        // period_s comes from TIMESTAMP, which is then trusted
    bool started;
    uint32_t last_ts;
    int64_t t0_us;
    int64_t last_us;
    uint32_t ticks;
} imu_rate_t;

void imu_rate_init(imu_rate_t *r, float nominal_period_s);
void imu_rate_update(imu_rate_t *r, uint32_t ts, int64_t now_us);

#ifdef __cplusplus
}
#endif
