#pragma once

// The QMI8658's 24-bit TIMESTAMP counter (0x30..0x32), one count per 6DOF
// sample. Pure C without ESP-IDF headers: the driver's gap bookkeeping
// (imu_frame.c) and the recorder's time keeping (rec_ts.c) share it.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define IMU_TS_MASK 0xFFFFFFu

// a - b on the 24-bit circle, in -2^23 .. 2^23 - 1.
static inline int32_t imu_ts_diff(uint32_t a, uint32_t b)
{
    uint32_t d = (a - b) & IMU_TS_MASK;
    return (d & 0x800000u) ? (int32_t)d - 0x1000000 : (int32_t)d;
}

#ifdef __cplusplus
}
#endif
