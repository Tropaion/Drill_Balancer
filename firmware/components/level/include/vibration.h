#pragma once

// Running-motor detector on the raw accelerometer, for the power policy: a
// drill held still while it drills must not count as idle.
//
// Every FIFO sample goes through a 2nd-order high-pass (two cascaded
// first-order stages), which removes gravity, orientation changes and slow
// handling or walking. The decision is taken on fixed windows of sample
// time: a window is SHAKING when at least `duty` of its samples have a
// high-passed vector magnitude above amp_g, CALM otherwise. A duty, not an
// RMS, so a knock or a hammer blow (a few samples far above the threshold)
// does not count. on_windows SHAKING windows in a row give RUNNING,
// quiet_windows CALM ones give QUIET; a mixed run keeps the state.
//
// Pure C, host-tested. Accel only: what it sees of the motor is what the
// QMI8658's on-chip accel low-pass (imu.c CTRL5) lets through.

#include <stdbool.h>
#include <stdint.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    VIB_UNKNOWN = 0,   // after vib_init()/vib_reset(), until the first decision
    VIB_QUIET,
    VIB_RUNNING,
} vib_state_t;

typedef struct {
    float hp_hz;            // high-pass corner (2nd order)
    float amp_g;            // |high-passed accel| that counts as shaking
    float duty;             // fraction of a window's samples above amp_g
    float window_s;         // decision window
    uint8_t on_windows;     // consecutive shaking windows -> RUNNING
    uint8_t quiet_windows;  // consecutive calm windows -> QUIET
} vib_config_t;

typedef struct {
    vib_config_t cfg;
    float tau_s;            // high-pass time constant
    vib_state_t state;
    bool primed;            // m1 holds the first sample
    vec3_t m1, m2;          // high-pass states (g)
    float win_s;            // sample time in the open window
    uint16_t win_n, win_above;
    uint8_t run_shaking, run_calm;
    float last_duty;        // of the last closed window, for the log
} vib_detector_t;

vib_config_t vib_default_config(void);

// Starts in VIB_UNKNOWN.
void vib_init(vib_detector_t *d, const vib_config_t *cfg);

// Back to VIB_UNKNOWN with the same config, e.g. after an IMU restart: the
// next sample primes the filter again, so the gap is no step.
void vib_reset(vib_detector_t *d);

// One raw accelerometer sample (g) and its period. Returns true when it
// closed a decision window (last_duty and the state are then up to date).
bool vib_add_sample(vib_detector_t *d, vec3_t accel_g, float dt_s);

vib_state_t vib_state(const vib_detector_t *d);

#ifdef __cplusplus
}
#endif
