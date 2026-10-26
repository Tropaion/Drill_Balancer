#pragma once

// Pure helpers of the recorder glue (recorder.c, rec_wifi.c, rec_audio.c):
// the soft-AP credentials, the HELLO check, the audio format and channel
// selection, the pairing of I2S blocks with their DMA timestamps and the
// IMU tap's records. Host-tested, and the simulator's glue mirror uses them
// too.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "rec_stream.h"
#include "rec_ts.h"

#ifdef __cplusplus
extern "C" {
#endif

// "DB-XXXX-YYYY": XXXX the last two BT MAC bytes (as the BLE name
// DrillBalancer-XXXX), YYYY random, so every session has a new network the
// phone did not join before. out holds REC_SSID_LEN + 1 bytes.
#define REC_SSID_LEN 12
void rec_ap_ssid(char out[REC_SSID_LEN + 1], const uint8_t mac_tail[2], uint16_t rnd);

// The WPA2 passphrase: REC_PASS_LEN base32 characters (RFC 4648 alphabet,
// no padding) from the first 100 bits of rnd, 13 random bytes. out holds
// REC_PASS_LEN + 1 bytes.
#define REC_PASS_LEN       20
#define REC_PASS_RAND_LEN  13
void rec_ap_pass(char out[REC_PASS_LEN + 1], const uint8_t rnd[REC_PASS_RAND_LEN]);

// A HELLO (rec_stream.h): REC_HELLO_LEN bytes, the session token and
// REC_STREAM_VERSION. The token is compared in constant time.
bool rec_hello_ok(const uint8_t *hello, size_t len, const uint8_t token[REC_HELLO_TOKEN_LEN]);

// The capture (rec_audio.c) and what SESSION says about it: 16 kHz, 16-bit
// stereo I2S, one DMA block of REC_AUDIO_BLOCK_FRAMES frames per AUDIO
// record (16 ms).
#define REC_AUDIO_RATE_HZ      16000u
#define REC_AUDIO_BLOCK_FRAMES 256u
#define REC_AUDIO_BITS         16u

// One stereo I2S block (frames x L/R int16, L = MIC1) to the take's
// channels: both for REC_MIC_ALL, else the one mic. Writes frames x
// channels samples to out (out may be stereo itself: the selection
// compacts in place) and returns that count; samples at +-32767 (and
// -32768) are added to *clipped.
size_t rec_audio_select(const int16_t *stereo, size_t frames, uint8_t mic_mask, int16_t *out,
                        uint32_t *clipped);

// Channels a mic_mask records (0, 1 or 2).
uint8_t rec_audio_channels(uint8_t mic_mask);

// The I2S RX path: the DMA interrupt numbers every finished block (on_recv)
// and, when the driver's queue is full, drops the oldest queued one
// (on_recv_q_ovf); the capture task reads the rest in order. From the drop
// count after each read, the block that read returned: the dropped ones
// were the oldest, so they come right before it.
typedef struct {
    uint32_t next;              // block index the next read returns without drops
    uint32_t drops_seen;
} rec_block_pair_t;

// Index of the block just read, given the drop counter now; *dropped_before
// gets how many blocks were lost right before it.
uint32_t rec_block_pair(rec_block_pair_t *p, uint32_t drops_now, uint32_t *dropped_before);

// The IMU tap (recorder_imu_block()): one IMU record per timestamp run of a
// FIFO read (imu_raw_info_t), with the take's frame and loss counts. Zero it
// at the take's start.
typedef struct {
    bool started;               // EVENT PROFILE sent for epoch
    uint32_t epoch;
    bool epoch_flag;            // the next IMU record follows a chip reset
    rec_ts_ext_t ts_ext;
    bool have_next;
    uint32_t next_ts;           // extended TIMESTAMP after the last run
    uint32_t frames, lost;      // END and STATS imu_frames, imu_lost
} rec_imu_tap_t;

// A read of clock epoch (imu_raw_info_t.epoch) at TIMESTAMP chip_ts24:
// *chip gets it extended. True when the read starts a new chip clock (the
// take's first read, or the first after a reset): EVENT PROFILE goes before
// its records.
bool rec_imu_tap_read(rec_imu_tap_t *t, uint32_t epoch, uint32_t chip_ts24, uint32_t *chip);

// One timestamp run of that read (imu_raw_run_t; chip from
// rec_imu_tap_read(), host_us the read's): fills the record's header and
// counts its frames and the samples lost since the run before. Returns the
// frames the record carries: count, at most REC_IMU_MAX_FRAMES (the rest
// of a longer run is left out).
uint16_t rec_imu_tap_run(rec_imu_tap_t *t, uint32_t chip, uint32_t ts_first24, uint16_t count,
                         bool discontinuous, int64_t host_us, rec_imu_t *hdr);

#ifdef __cplusplus
}
#endif
