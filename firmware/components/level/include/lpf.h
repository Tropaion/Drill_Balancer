#pragma once

// Software stand-in for the QMI8658's on-chip low-pass filters, for the
// level pipeline under the recording profiles.
//
// The LEVEL profile runs the chip LPFs (imu_frame.c CTRL5_LEVEL: aLPF 2.66 %
// of the ODR = 11.9 Hz, gLPF 5.39 % = 24.2 Hz at 448.4 Hz). The RAW
// profiles turn them off, so the training data keeps the motor vibration;
// fed into fusion as they are, the aliased motor tones and hammer blows tilt
// the reading by degrees. Under those profiles the sensor task runs a copy of
// every sample through lpf_imu_t first and feeds that copy to fusion, level,
// the vibration and turn detectors and the calibration collectors, so they
// see what they see under LEVEL (the same corners in Hz, also at 896.8 Hz);
// the recorder still gets the unfiltered frames.
//
// The datasheet gives the corner of each LPF mode but neither its order nor
// its shape. A 2nd-order Butterworth per axis stands in (bilinear transform
// with a pre-warped corner, transposed direct form II): -3 dB at the corner,
// -40 dB a decade above, unity gain at DC so gravity and a held rate pass
// unchanged, about 19 ms (accel) and 9 ms (gyro) of group delay at DC.
// It filters after the sampler: vibration the ODR folds down near DC (a
// harmonic close to a multiple of 448.4 Hz) is in band and stays, where the
// chip's LPF would have removed it first (the aliasing check of the raw
// profiles, imu_frame.c CTRL5_RAW).
// TODO(hw): compare with the chip (bring-up step 31): a drill held still
// under LEVEL and under RAW448 should read the same, and the step response
// of a quick tilt should lag alike.
//
// Pure C, host-tested.

#include <stdbool.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

// The LEVEL profile's corners: CTRL5 mode 00 / 10 (Tables 11, 13) at 448.4 Hz.
#define LPF_LEVEL_ACCEL_HZ (0.0266f * 448.4f)   // 11.9 Hz
#define LPF_LEVEL_GYRO_HZ  (0.0539f * 448.4f)   // 24.2 Hz

typedef struct {
    float b0, b1, b2;           // numerator
    float a1, a2;               // denominator, a0 = 1
} lpf_biquad_t;

// 2nd-order Butterworth low-pass at fc_hz for samples at fs_hz. fc_hz is
// kept below 0.45 * fs_hz.
lpf_biquad_t lpf_butter2(float fc_hz, float fs_hz);

// Gain at f_hz (0..fs/2), for tests and the docs.
float lpf_gain(const lpf_biquad_t *c, float f_hz, float fs_hz);

// One biquad per axis. The first sample after init primes the state
// as if that value had always been there, so a restart is no step;
// lpf_vec3_prime() does that with a given value instead (the filter then
// outputs x for an input that stays at x).
typedef struct {
    lpf_biquad_t c;
    bool primed;
    vec3_t s1, s2;
} lpf_vec3_t;

void lpf_vec3_init(lpf_vec3_t *f, float fc_hz, float fs_hz);
void lpf_vec3_prime(lpf_vec3_t *f, vec3_t x);
vec3_t lpf_vec3_step(lpf_vec3_t *f, vec3_t x);

// The chip's accel and gyro LPFs of the LEVEL profile at sample rate fs_hz
// (the nominal ODR of the profile the chip runs).
typedef struct {
    lpf_vec3_t accel;
    lpf_vec3_t gyro;
} lpf_imu_t;

// Primed by the first sample (a start, or the IMU back after it was lost:
// fusion starts over from that sample too).
void lpf_imu_init(lpf_imu_t *f, float fs_hz);
// A profile switch, which fusion goes through (sensor_task.c): the
// coefficients for the new rate fs_hz, the state primed with the last
// sample fusion got (accel_g, gyro_rad_s), so the filtered stream goes on
// from there instead of jumping to the first raw sample.
void lpf_imu_restart(lpf_imu_t *f, float fs_hz, vec3_t accel_g, vec3_t gyro_rad_s);
void lpf_imu_step(lpf_imu_t *f, vec3_t *accel_g, vec3_t *gyro_rad_s);

// The filter as the sensor task (and the simulator) runs it: every sample
// fusion gets goes through lpf_feed_step(), filtered or not, and the last
// one is kept for a profile switch.
typedef struct {
    lpf_imu_t f;
    vec3_t last_accel_g, last_gyro_rad_s;   // the last sample fusion got
    bool last_valid;
} lpf_feed_t;

// The IMU lost or back (fusion starts over): the coefficients for fs_hz,
// primed by the next sample, nothing kept.
void lpf_feed_reset(lpf_feed_t *fd, float fs_hz);
// A profile switch (fusion goes through it): the coefficients for the new
// rate, going on from the last sample fusion got (lpf_imu_restart()), or
// primed by the next one if there is none.
void lpf_feed_switch(lpf_feed_t *fd, float fs_hz);
// One sample for fusion: through the filter when `filter` (a profile with
// the chip LPFs off), as it is otherwise; kept as the last one either way.
void lpf_feed_step(lpf_feed_t *fd, bool filter, vec3_t *accel_g, vec3_t *gyro_rad_s);

#ifdef __cplusplus
}
#endif
