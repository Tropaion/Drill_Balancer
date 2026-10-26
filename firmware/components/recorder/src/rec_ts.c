#include "rec_ts.h"
#include "imu_ts.h"

uint32_t rec_ts_near(uint32_t ref, uint32_t ts24)
{
    return ref + (uint32_t)imu_ts_diff(ts24, ref & IMU_TS_MASK);
}

uint32_t rec_ts_extend(rec_ts_ext_t *e, uint32_t ts24)
{
    if (!e->valid) {
        e->valid = true;
        e->last = ts24 & IMU_TS_MASK;
    } else {
        e->last = rec_ts_near(e->last, ts24);
    }
    return e->last;
}

void rec_rate_reset(rec_rate_t *r)
{
    *r = (rec_rate_t){0};
}

void rec_rate_update(rec_rate_t *r, uint32_t epoch, uint64_t count, int64_t host_us)
{
    if (!r->started || r->epoch != epoch) {
        *r = (rec_rate_t){.started = true, .epoch = epoch, .count0 = count, .us0 = host_us,
                          .count = count, .us = host_us};
        return;
    }
    if (host_us <= r->us || count < r->count0) {
        return;
    }
    r->count = count;
    r->us = host_us;
}

// Span and count delta, or false while the baseline is short.
static bool span(const rec_rate_t *r, uint64_t *dc, uint64_t *dus)
{
    if (!r->started || r->us - r->us0 < REC_RATE_MIN_US || r->count <= r->count0) {
        return false;
    }
    *dc = r->count - r->count0;
    *dus = (uint64_t)(r->us - r->us0);
    return true;
}

uint32_t rec_rate_mhz(const rec_rate_t *r)
{
    uint64_t dc, dus;
    if (!span(r, &dc, &dus)) {
        return 0;
    }
    // Rounded; dc stays far below 2^64 / 10^9 (2 h of audio is 1.2e8).
    uint64_t v = (dc * 1000000000u + dus / 2u) / dus;
    return v > UINT32_MAX ? UINT32_MAX : (uint32_t)v;
}

uint32_t rec_rate_period_ns(const rec_rate_t *r)
{
    uint64_t dc, dus;
    if (!span(r, &dc, &dus)) {
        return 0;
    }
    uint64_t v = (dus * 1000u + dc / 2u) / dc;
    return v > UINT32_MAX ? UINT32_MAX : (uint32_t)v;
}
