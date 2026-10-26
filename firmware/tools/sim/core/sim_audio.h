#pragma once

// The microphones' sound (sim_audio.c): the simulator's physics, used by the
// rec_audio.c mirror in sim_core.c.

#include <stdint.h>
#include "sim_core.h"

#define SIM_MIC2_DELAY      3        // frames (~0.19 ms, about 6 cm further away)
#define SIM_MIC2_DEFAULT_DB (-1.5f)  // MIC2 a little less sensitive

// A new device: the generator starts over (seed 0 picks a fixed one) and
// the microphones get their default sensitivities. A reboot keeps both.
void sim_audio_reset(uint32_t seed);
// Sensitivity of microphone 1 or 2, dB (0 = the nominal level).
void sim_audio_set_mic_gain(int mic, float db);
float sim_audio_mic_gain_db(int mic);
// n frames of the block m describes, interleaved MIC1 / MIC2, full scale 1.0
// at 0 dB PGA gain.
void sim_audio_synth(const sim_motor_t *m, int n, float *out);
