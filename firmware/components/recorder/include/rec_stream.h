#pragma once

// Training-data stream of the recorder. This header is the contract with the
// app's stream decoder and with tools/dataset/dbdata/dbrec.py (its
// self_check parses the defines and struct layouts below); keep them in sync.
//
// Transport: one TCP connection per session to REC_STREAM_PORT, on the
// device's soft-AP (192.168.4.1) or on the address the BLE rec_state packet
// gives (DB_REC_FLAG_NO_AP: the simulator on the LAN). The client first
// sends HELLO (REC_HELLO_LEN bytes): the 16-byte session token from
// rec_state, then u16 REC_STREAM_VERSION. A wrong token or version closes
// the connection. After that only the device sends.
//
// Records, all integers little-endian, no padding:
//   rec_hdr_t (8 B) | payload (len B) | CRC-32 (4 B)
// The CRC is the IEEE CRC-32 (zlib crc32, java.util.zip.CRC32) over header
// and payload. A take is a SESSION record (seq 0), then IMU / AUDIO / LEVEL
// / EVENT / STATS records, each source in time order, then END. seq counts
// every record of the take, also those the device dropped because its ring
// was full: a seq jump of k means k - 1 records are missing. A decoder skips
// records of unknown type or version (len says how far) and resynchronises
// byte by byte after a CRC error or an implausible header (unknown version
// 0, len > REC_MAX_PAYLOAD). Between takes the connection carries nothing.
//
// Times: host_us is the device's esp_timer (us since boot); IMU and audio
// sample clocks are tied to it by (count, host_us) pairs, which the app fits.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define REC_STREAM_PORT     47100
#define REC_STREAM_VERSION  1        // HELLO version; changes with the framing
#define REC_HELLO_TOKEN_LEN 16
#define REC_HELLO_LEN       18       // token + u16 version
#define REC_HDR_LEN         8
#define REC_CRC_LEN         4
#define REC_MAX_PAYLOAD     4096

// Record types and their payload versions.
#define REC_T_SESSION 0x01
#define REC_T_IMU     0x02
#define REC_T_AUDIO   0x03
#define REC_T_EVENT   0x04
#define REC_T_STATS   0x05
#define REC_T_LEVEL   0x06
#define REC_T_END     0x0F
#define REC_V_SESSION 1
#define REC_V_IMU     1
#define REC_V_AUDIO   1
#define REC_V_EVENT   1
#define REC_V_STATS   1
#define REC_V_LEVEL   1
#define REC_V_END     1

typedef struct __attribute__((packed)) {
    uint8_t type;             // REC_T_*
    uint8_t ver;              // REC_V_* of that type
    uint16_t len;             // payload bytes
    uint32_t seq;
} rec_hdr_t;                  // 8 bytes

// IMU profiles (imu_profile_t, SESSION.profile).
#define REC_PROFILE_LEVEL      0
#define REC_PROFILE_RAW448     1
#define REC_PROFILE_RAW448_16G 2
#define REC_PROFILE_RAW897     3
#define REC_PROFILE_COUNT      4

// SESSION: what the take was recorded with. Followed by a UTF-8 JSON tail
// (the rest of the payload, no NUL) with the settings and calibration in
// effect at the start:
//   {"settings": <settings_to_json()>,
//    "cal": {"accel": {"valid": b, "bias_g": [x,y,z], "scale": [x,y,z]},
//            "mount": {"valid": b, "drill_axis": [x,y,z]},
//            "gyro_bias_rad_s": [x,y,z] | null}}
typedef struct __attribute__((packed)) {
    uint8_t src;              // 0 = chip X, 1 = chip Y, 2 = chip Z
    int8_t sign;              // +1 or -1
} rec_axis_t;

typedef struct __attribute__((packed)) {
    uint32_t take_id;         // from TAKE_START
    uint64_t start_us;        // host_us of the take start
    uint8_t board_id[4];      // salted hash of the chip id (meta.device.board_id,
                              // 8 hex digits); the MAC never leaves the device
    char fw[32];              // rec_fw_text(): firmware version and git revision
    uint8_t profile;          // REC_PROFILE_*
    uint8_t ctrl2;            // QMI8658 CTRL2/3/5 of the profile
    uint8_t ctrl3;
    uint8_t ctrl5;
    uint32_t odr_mhz;         // nominal IMU ODR, mHz
    uint16_t accel_lsb_per_g;
    uint16_t gyro_lsb_per_dps;
    rec_axis_t axis[3];       // board x, y, z = sign * chip[src]
    uint32_t audio_rate_hz;   // nominal; 0 without audio
    uint8_t audio_channels;   // 0 without audio
    uint8_t audio_bits;       // 16
    uint8_t mic_mask;         // REC_MIC_* captured, channel order = bit order
    uint8_t gain_db;          // ES7210 PGA gain
    uint16_t audio_block_frames;   // frames per AUDIO record (one DMA block)
    uint16_t max_s;           // take limit in effect
} rec_session_t;              // 78 bytes, then the JSON tail

// IMU: one timestamp run of a FIFO read (imu_raw_info_t): n consecutive
// samples as int16 counts in the board frame, AX AY AZ GX GY GZ (n x 12 B).
// Within a run sample i has chip timestamp ts_first + i. Between records a
// ts_first jump is the number of samples the FIFO lost in read mode, unless
// the record has REC_IMU_DISCONT: then the driver resynchronised and the
// relation to the previous record is only its best guess (seq jumps there
// in the dataset). One FIFO read gives up to two records with the same
// (chip_ts, host_us).
// REC_IMU_EPOCH (with DISCONT) marks the first record after the IMU was
// reset (imu_raw_info_t.epoch changed: a re-init after IMU_LOST, a profile
// switch): TIMESTAMP counts anew, so ts_first / chip_ts may lie below or far
// above the earlier records' and say nothing about the time between. A
// decoder places the new count by host_us alone (its own fit of (chip_ts,
// host_us)), and the glue starts rec_ts_extend() over there.
// Every IMU record of a take is in SESSION.profile: the glue sends none
// before that profile is in effect and sends EVENT PROFILE (arg = it) just
// before the first. A decoder keeps only IMU records that follow a PROFILE
// event with SESSION.profile as the latest one, or all of them in a take
// without PROFILE events.
#define REC_IMU_DISCONT    (1u << 0)
#define REC_IMU_EPOCH      (1u << 1)
#define REC_IMU_MAX_FRAMES 128
#define REC_IMU_FRAME_LEN  12

typedef struct __attribute__((packed)) {
    uint32_t ts_first;        // chip TIMESTAMP of the first sample, extended to 32 bit
    uint32_t chip_ts;         // TIMESTAMP at the FIFO level read (extended) ...
    uint64_t host_us;         // ... and the esp_timer at that read
    uint16_t n;
    uint16_t flags;           // REC_IMU_*
} rec_imu_t;                  // 20 bytes, then n x 12 B

// AUDIO: one I2S DMA block, n frames of audio_channels int16 samples,
// interleaved (n x channels x 2 B). sample_index counts frames from the
// take's audio start, frames lost to a DMA queue overflow included: a jump
// is that many missing frames (REC_AUDIO_OVF_BEFORE is set then).
#define REC_AUDIO_OVF_BEFORE (1u << 0)

typedef struct __attribute__((packed)) {
    uint64_t sample_index;    // first frame of the block
    uint64_t host_us;         // esp_timer when the block completed (its end)
    uint16_t n;
    uint16_t flags;           // REC_AUDIO_*
} rec_audio_t;                // 20 bytes, then the samples

// Microphones (SESSION.mic_mask): the ES7210 inputs wired on this board.
// TODO(hw): check that MIC1 really is the left I2S channel (a tap next to
// each microphone).
#define REC_MIC_1   (1u << 0)
#define REC_MIC_2   (1u << 1)
#define REC_MIC_ALL (REC_MIC_1 | REC_MIC_2)

// EVENT: something that happened during the take. Followed by text: UTF-8,
// no NUL, at most REC_EVENT_TEXT_MAX bytes (the rest of the payload).
#define REC_EV_MARK      1      // arg = mark code, text from MARK
#define REC_EV_TOUCH     2      // a touch on the screen (ignored by the UI)
#define REC_EV_BLE_LOST  3
#define REC_EV_BLE_BACK  4
#define REC_EV_IMU_LOST  5
#define REC_EV_IMU_BACK  6
#define REC_EV_VIB_START 7      // the vibration detector saw a running motor
#define REC_EV_VIB_STOP  8
#define REC_EV_PROFILE   9      // arg = REC_PROFILE_* now in effect in the IMU data
#define REC_EVENT_TEXT_MAX 64

typedef struct __attribute__((packed)) {
    uint64_t host_us;
    uint16_t code;            // REC_EV_*
    uint16_t arg;
} rec_event_t;                // 12 bytes, then the text

// LEVEL: the 10 Hz device level, the BLE angle packet's fields plus the
// zero in effect (level_t zero_*_deg while a zero is set, else 0): with
// DB_ANGLE_FLAG_ZERO_SET, elevation and bubble are relative to it.
typedef struct __attribute__((packed)) {
    uint64_t host_us;
    uint8_t mode;             // level_mode_t
    uint8_t flags;            // DB_ANGLE_FLAG_*
    int16_t elevation_cdeg;
    int16_t yaw_cdeg;
    int16_t bubble_x_cdeg;
    int16_t bubble_y_cdeg;
    int16_t deviation_cdeg;
    int16_t zero_elevation_cdeg;
    int16_t zero_bubble_x_cdeg;
    int16_t zero_bubble_y_cdeg;
} rec_level_t;                // 26 bytes

// STATS: once a second during a take; counters since the take start.
#define REC_STATS_BATTERY_VALID (1u << 0)
#define REC_STATS_VBUS          (1u << 1)
#define REC_STATS_CHARGING      (1u << 2)

typedef struct __attribute__((packed)) {
    uint64_t host_us;
    uint32_t imu_frames;      // IMU samples sent
    uint32_t imu_lost;        // samples the FIFO lost (timestamp jumps)
    uint32_t imu_period_ns;   // measured sample period, 0 = not yet
    uint32_t audio_frames;    // frames captured
    uint32_t audio_overflows; // DMA blocks lost
    uint32_t audio_rate_mhz;  // measured frame rate, 0 = not yet
    uint32_t audio_clipped;   // samples at +-32767
    uint32_t ring_high_water; // bytes
    uint32_t ring_size;       // bytes
    uint32_t dropped_records; // ring full, or flushed at the drain limit
    uint32_t dropped_bytes;   // (rec_ring_t.dropped_*: every seq the client misses)
    uint16_t battery_mv;
    uint8_t battery_pct;
    uint8_t power_flags;      // REC_STATS_*
} rec_stats_t;                // 56 bytes

// Why a take or a session ended (END.reason, rec_state reason).
#define REC_REASON_NONE        0
#define REC_REASON_USER        1     // STOP, CLOSE or the long-press on the pill
#define REC_REASON_MAX_S       2
#define REC_REASON_CLIENT_LOST 3     // TCP closed, station left or a send blocked > 10 s
#define REC_REASON_BLE_LOST    4     // BLE gone for the whole grace time
#define REC_REASON_BATTERY     5
#define REC_REASON_IMU         6     // IMU lost for too long
#define REC_REASON_SHUTDOWN    7     // sleep, power-off, reboot
#define REC_REASON_AP_FAIL     8     // session only: the soft-AP did not come up
#define REC_REASON_NO_CLIENT   9     // session only: nobody connected in time
#define REC_REASON_IDLE        10    // session only: no take for too long

// END: the last record of a take.
typedef struct __attribute__((packed)) {
    uint64_t host_us;
    uint8_t reason;           // REC_REASON_* (1..7)
    uint32_t records;         // records of the take including this one (= seq + 1)
    uint32_t dropped_records; // as in STATS, also the drain limit's flush
    uint32_t dropped_bytes;
    uint32_t imu_frames;
    uint32_t imu_lost;
    uint32_t audio_frames;
    uint32_t audio_overflows;
} rec_end_t;                  // 37 bytes

// ---- encoding ---------------------------------------------------------------

// One piece of a payload (e.g. rec_imu_t, then the frames).
typedef struct {
    const void *data;
    size_t len;
} rec_part_t;

// IEEE CRC-32, continued from crc (start with 0): rec_crc32(0, "123456789", 9)
// is 0xCBF43926.
uint32_t rec_crc32(uint32_t crc, const void *data, size_t len);

// The CRC of a followed by b from crc_a = rec_crc32(0, a, ..), crc_b =
// rec_crc32(0, b, len_b) and op = rec_crc32_combine_op(len_b) (zlib's
// crc32_combine_gen / crc32_combine_op): the ring takes a payload's CRC
// before its lock and puts the header's, which holds seq, in front under it.
uint32_t rec_crc32_combine_op(size_t len_b);
uint32_t rec_crc32_combine(uint32_t crc_a, uint32_t crc_b, uint32_t op);

// Header + payload + CRC.
static inline size_t rec_record_size(size_t payload_len)
{
    return REC_HDR_LEN + payload_len + REC_CRC_LEN;
}

// The payload length of parts[0..n).
static inline size_t rec_parts_len(const rec_part_t *parts, size_t n)
{
    size_t len = 0;
    for (size_t i = 0; i < n; i++) {
        len += parts[i].len;
    }
    return len;
}

// The record CRC as it goes on the wire (little-endian).
static inline void rec_put_le32(uint8_t p[4], uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static inline uint32_t rec_get_le32(const uint8_t p[4])
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

// Writes one record from the payload parts. Returns its size, or 0 if the
// payload is above REC_MAX_PAYLOAD or out cannot hold it.
size_t rec_stream_encode(uint8_t type, uint8_t ver, uint32_t seq, const rec_part_t *parts,
                         size_t nparts, uint8_t *out, size_t cap);

// SESSION.fw: version (esp_app_desc_t.version, PROJECT_VER) + "-g" + git
// (REC_FW_GIT, the build's `git describe --always --dirty`), e.g.
// "0.1.0-g0e5105e-dirty", cut to 31 bytes and NUL-padded. git may be NULL
// or empty (a build outside git): the version alone.
#define REC_FW_LEN 32
void rec_fw_text(char out[REC_FW_LEN], const char *version, const char *git);

// EVENT payload into out (at least sizeof(rec_event_t) + REC_EVENT_TEXT_MAX
// bytes). text may be NULL; it is cut to REC_EVENT_TEXT_MAX bytes without
// splitting a UTF-8 sequence. Returns the payload length.
#define REC_EVENT_MAX (sizeof(rec_event_t) + REC_EVENT_TEXT_MAX)
size_t rec_event_payload(uint8_t *out, uint64_t host_us, uint16_t code, uint16_t arg,
                         const char *text);

// Whether buf starts with a record: its size (header in *hdr) if it is
// complete with a good CRC, 0 if more bytes are needed, -1 if it is not one
// (version 0, len above REC_MAX_PAYLOAD or a bad CRC: resynchronise).
int rec_stream_check(const uint8_t *buf, size_t len, rec_hdr_t *hdr);

#ifdef __cplusplus
}
#endif
