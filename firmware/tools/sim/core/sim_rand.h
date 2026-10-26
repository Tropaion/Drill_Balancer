#pragma once

// The simulator's random numbers: xorshift32 (Marsaglia 2003), one generator
// for sim_core.c (the IMU read timing) and sim_audio.c (the microphones),
// each with its own state, seeded by sim_seed(). Not firmware.

#include <stdint.h>

static inline uint32_t sim_xorshift32(uint32_t *state)
{
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

// Uniform in [-1, 1), from the top 24 bits.
static inline float sim_urand(uint32_t *state)
{
    return (float)(sim_xorshift32(state) >> 8) * (2.0f / 16777216.0f) - 1.0f;
}
