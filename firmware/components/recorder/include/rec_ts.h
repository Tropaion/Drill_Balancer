#pragma once

// Recorder time keeping: the QMI8658's 24-bit TIMESTAMP extended to 32 bits
// for the stream (the 24-bit arithmetic is imu_ts.h's), and rate estimates
// for STATS. Pure C, host-tested.

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// The extended value of ts24 closest to ref (an extended value): ref plus
// the 24-bit difference. Wraps like uint32_t.
uint32_t rec_ts_near(uint32_t ref, uint32_t ts24);

// Extends successive TIMESTAMP reads. The first read keeps its 24-bit value;
// each later one moves by its 24-bit difference to the previous, so the
// counter carries on across the 24-bit wrap (every 10.4 h at 448 Hz) and a
// read that is one sample older than the last (the count/timestamp race)
// stays one sample older instead of jumping a whole wrap. Start it over
// (valid = false) when imu_raw_info_t.epoch changes: TIMESTAMP restarted.
typedef struct {
    bool valid;
    uint32_t last;
} rec_ts_ext_t;

uint32_t rec_ts_extend(rec_ts_ext_t *e, uint32_t ts24);

// Rate of a counter against the host clock, from the first and the latest
// (count, host_us) pair: over a whole take this averages out the host-side
// jitter of each pair. Nothing is reported before REC_RATE_MIN_US.
#define REC_RATE_MIN_US 1000000

typedef struct {
    bool started;
    uint32_t epoch;
    uint64_t count0;
    int64_t us0;
    uint64_t count;
    int64_t us;
} rec_rate_t;

void rec_rate_reset(rec_rate_t *r);
// epoch names the counter's run: imu_raw_info_t.epoch for the IMU (a reset
// restarts TIMESTAMP), a constant for audio. A pair from another epoch
// starts the estimate over from it. Within an epoch, pairs with a host time
// not after the last one or a count below the first one are ignored.
void rec_rate_update(rec_rate_t *r, uint32_t epoch, uint64_t count, int64_t host_us);
// Counts per second x 1000 (mHz), 0 while unknown.
uint32_t rec_rate_mhz(const rec_rate_t *r);
// Nanoseconds per count, 0 while unknown.
uint32_t rec_rate_period_ns(const rec_rate_t *r);

#ifdef __cplusplus
}
#endif
