// The sound at the two microphones: what the ES7210 digitises, before its
// PGA. Not firmware: the simulator's physics, in C so that 16 kHz stereo
// stays cheap next to the real-time device loop. dbsim/physics.py describes
// the motor per 16 ms block (sim_motor_t); this turns it into samples.
//
// One source signal reaches both microphones: the motor's harmonics k * f0
// with amplitude 1/k, band-limited cutting noise (1-6 kHz) with the load,
// decaying impact bursts (hammer blows, the breakthrough) and a quiet room.
// MIC2 hears it SIM_MIC2_DELAY frames later; each microphone has its own
// sensitivity (sim_audio_set_mic_gain) and self-noise. Deterministic: one
// seeded generator (sim_seed()).

#include <math.h>
#include <stdbool.h>
#include <string.h>

#include "sim_audio.h"
#include "sim_rand.h"

#define FS            16000.0f
#define HARMONICS     8
#define TOP_HZ        7000.0f       // harmonics above are left out (no aliasing)
#define MAX_BURSTS    16
#define BURST_LIFE    8.0f          // taus
#define BURST_TONE_HZ 1800.0f
#define DELAY_LEN     16
#define SELF_NOISE    0.0004f       // full scale, per microphone
#define TWO_PI        6.28318530718f
// Band-pass of the cutting noise (RBJ cookbook, 0 dB peak gain): most of its
// energy within 1-6 kHz, where the dataset's E_cut feature looks.
#define BP_HZ         2500.0f
#define BP_Q          0.5f

typedef struct {
    float amp;                      // 0: free slot
    float tau_frames;
    float age;                      // frames since the onset (negative: not yet)
    float phase;
} burst_t;

static struct {
    bool ready;
    uint32_t rng;
    float phase[HARMONICS];
    float b0, a1, a2;               // band-pass, b1 = 0 and b2 = -b0
    float x1, x2, y1, y2;
    burst_t burst[MAX_BURSTS];
    float delay[DELAY_LEN];
    unsigned delay_pos;
    float mic_gain[2];              // linear
} s;

static void init(uint32_t seed)
{
    memset(&s, 0, sizeof(s));
    s.ready = true;
    s.rng = seed ? seed : 0x9E3779B9u;
    s.mic_gain[0] = 1.0f;
    s.mic_gain[1] = powf(10.0f, SIM_MIC2_DEFAULT_DB / 20.0f);
    float w0 = TWO_PI * BP_HZ / FS;
    float alpha = sinf(w0) / (2.0f * BP_Q);
    float a0 = 1.0f + alpha;
    s.b0 = alpha / a0;
    s.a1 = -2.0f * cosf(w0) / a0;
    s.a2 = (1.0f - alpha) / a0;
}

void sim_audio_reset(uint32_t seed)
{
    init(seed);
}

void sim_audio_set_mic_gain(int mic, float db)
{
    if (!s.ready) {
        init(0);
    }
    if (mic == 1 || mic == 2) {
        s.mic_gain[mic - 1] = powf(10.0f, db / 20.0f);
    }
}

float sim_audio_mic_gain_db(int mic)
{
    if (!s.ready) {
        init(0);
    }
    return (mic == 1 || mic == 2) ? 20.0f * log10f(s.mic_gain[mic - 1]) : 0.0f;
}

// Uniform in [-1, 1).
static float urand(void)
{
    return sim_urand(&s.rng);
}

// Roughly normal: the sum of three uniforms (variance 1).
static float nrand(void)
{
    return urand() + urand() + urand();
}

static float band_pass(float x)
{
    float y = s.b0 * (x - s.x2) - s.a1 * s.y1 - s.a2 * s.y2;
    s.x2 = s.x1;
    s.x1 = x;
    s.y2 = s.y1;
    s.y1 = y;
    return y;
}

static void add_burst(float onset_frames, float amp, float tau_s)
{
    if (amp <= 0.0f || tau_s <= 0.0f) {
        return;
    }
    for (int i = 0; i < MAX_BURSTS; i++) {
        if (s.burst[i].amp == 0.0f) {
            s.burst[i] = (burst_t){.amp = amp, .tau_frames = tau_s * FS, .age = -onset_frames,
                                   .phase = (urand() + 1.0f) * 3.14159265f};
            return;
        }
    }
}

static float bursts_next(void)
{
    float v = 0.0f;
    for (int i = 0; i < MAX_BURSTS; i++) {
        burst_t *b = &s.burst[i];
        if (b->amp == 0.0f) {
            continue;
        }
        if (b->age >= 0.0f) {
            // Full amplitude at the onset: a crack, then a ringing decay.
            float env = b->amp * expf(-b->age / b->tau_frames);
            v += env * (0.6f * urand() + 0.4f * sinf(b->phase));
            b->phase += TWO_PI * BURST_TONE_HZ / FS;
            if (b->phase > TWO_PI) {
                b->phase -= TWO_PI;
            }
            if (b->age > BURST_LIFE * b->tau_frames) {
                b->amp = 0.0f;
            }
        }
        b->age += 1.0f;
    }
    return v;
}

void sim_audio_synth(const sim_motor_t *m, int n, float *out)
{
    if (!s.ready) {
        init(0);
    }
    int ni = m->n_impacts < 0 ? 0 : m->n_impacts > SIM_MAX_IMPACTS ? SIM_MAX_IMPACTS : m->n_impacts;
    for (int i = 0; i < ni; i++) {
        add_burst(m->impact_s[i] * FS, m->impact_amp[i], m->impact_tau_s[i]);
    }
    for (int i = 0; i < n; i++) {
        float u = n > 1 ? (float)i / (float)(n - 1) : 0.0f;
        float f0 = m->f0_hz[0] + (m->f0_hz[1] - m->f0_hz[0]) * u;
        float hum = m->hum[0] + (m->hum[1] - m->hum[0]) * u;
        float noise = m->noise[0] + (m->noise[1] - m->noise[0]) * u;

        float h = 0.0f;
        for (int k = 0; k < HARMONICS && f0 > 0.0f; k++) {
            float fk = f0 * (float)(k + 1);
            if (fk >= TOP_HZ) {
                break;
            }
            h += sinf(s.phase[k]) / (float)(k + 1);
            s.phase[k] += TWO_PI * fk / FS;
            if (s.phase[k] > TWO_PI) {
                s.phase[k] -= TWO_PI;
            }
        }
        // 0.5: the 1/k series peaks near 2.
        float x = 0.5f * hum * h + noise * band_pass(nrand()) + m->ambient * nrand() +
                  bursts_next();

        s.delay[s.delay_pos] = x;
        float x2 = s.delay[(s.delay_pos + DELAY_LEN - SIM_MIC2_DELAY) % DELAY_LEN];
        s.delay_pos = (s.delay_pos + 1u) % DELAY_LEN;
        out[2 * i] = x * s.mic_gain[0] + SELF_NOISE * nrand();
        out[2 * i + 1] = x2 * s.mic_gain[1] + SELF_NOISE * nrand();
    }
}
