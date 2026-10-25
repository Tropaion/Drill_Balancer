#pragma once

// BLE OTA protocol state machine. Pure C, host-tested; the flash writes and
// SHA-256 come in through callbacks (ota_update.c binds them to esp_ota and
// mbedtls on the device).
//
// Wire format (all integers little-endian):
//
// ota_ctrl characteristic: client writes requests, device replies with an
// indication on the same characteristic.
//   START   0x01 | u32 image_size | u8 sha256[32]               (37 bytes)
//   FINISH  0x02                                                 (1 byte)
//   ABORT   0x03                                                 (1 byte)
//
//   reply   0x80 | u8 request_opcode | u8 status | ...
//     to START (status OK): | u16 max_chunk | u16 ack_every     (7 bytes)
//     to FINISH / ABORT:    nothing more                          (3 bytes)
//   ACK     0x81 | u16 next_seq | u32 bytes_received             (7 bytes)
//     sent every ack_every chunks and after the last chunk; the client
//     must not run more than 2 * ack_every chunks ahead of the last ACK.
//   NACK    0x82 | u16 expected_seq | u8 status                  (4 bytes)
//     on a sequence gap; the client resends from expected_seq.
//
// ota_data characteristic: client writes without response.
//   u16 seq | payload (1 .. max_chunk bytes)
//   seq starts at 0 after START and wraps at 65536.
//   max_chunk = ATT MTU - OTA_DATA_OVERHEAD, so one chunk fills one write.
//
// After FINISH the device checks size and SHA-256, marks the new partition
// bootable and, once the reply is sent, reboots (done by the glue).
//
// Behaviour in the corner cases:
// - Errors caused by a ctrl request are reported in its reply. Errors found
//   in the data path (sink write failure, data beyond image_size, timeout)
//   are reported as a NACK carrying expected_seq = next_seq and the status.
// - Any sink error, size or hash mismatch, timeout, ABORT or disconnect ends
//   the transfer: sink->abort is called (only if begin had succeeded) and the
//   state goes back to IDLE. Data arriving afterwards is dropped silently
//   until the next START.
// - START while RECEIVING aborts the current transfer through the sink and
//   starts a fresh one (seq 0), so a client can simply retry (if the new
//   START is then rejected, the state stays IDLE). START or ABORT
//   in DONE gets BAD_STATE (the new partition is already marked bootable).
//   START with image_size 0 gets BAD_REQUEST, above max_image_size TOO_LARGE.
// - ABORT while IDLE is acknowledged with OK (idempotent).
// - A wrong-length request or an unknown opcode gets BAD_REQUEST and does not
//   change the state (an empty write is answered with request_opcode 0).
// - Data while IDLE gets one NACK BAD_STATE (expected_seq 0), then silence.
// - seq older than expected (up to 32768 behind, modulo 65536) is a
//   retransmission and is ignored; nothing is ever written twice. If no
//   chunk was accepted since the last ACK, the client evidently missed that
//   ACK (an indication can be dropped) and went back to an older one: the
//   ACK is sent again, once per resend round (a round starts with a seq at or
//   before that of the previous retransmission).
// - seq ahead of expected is a gap: one NACK SEQ_ERROR, further chunks are
//   ignored until the expected seq arrives.
// - A chunk at the expected seq that is empty, shorter than the 2-byte seq or
//   longer than max_chunk gets NACK BAD_REQUEST; the transfer continues and
//   the client resends from expected_seq. max_chunk is the value sent in the
//   START reply; ota_proto_set_max_chunk() only affects later STARTs.
// - An ACK is sent every ack_every accepted chunks and after the chunk that
//   completes image_size (again only for a retransmission, as above).
// - The SHA-256 runs over accepted chunks in seq order. timeout_ms 0
//   disables the timeout; ack_every 0 is treated as 1.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OTA_OP_START   0x01
#define OTA_OP_FINISH  0x02
#define OTA_OP_ABORT   0x03
#define OTA_MSG_REPLY  0x80
#define OTA_MSG_ACK    0x81
#define OTA_MSG_NACK   0x82

typedef enum {
    OTA_STATUS_OK = 0,
    OTA_STATUS_BAD_REQUEST = 1,     // malformed or unknown opcode
    OTA_STATUS_BAD_STATE = 2,       // e.g. FINISH without START
    OTA_STATUS_TOO_LARGE = 3,       // image larger than the partition
    OTA_STATUS_FLASH_ERROR = 4,     // sink begin/write/finish failed
    OTA_STATUS_SIZE_MISMATCH = 5,
    OTA_STATUS_HASH_MISMATCH = 6,
    OTA_STATUS_SEQ_ERROR = 7,
    OTA_STATUS_TIMEOUT = 8,
} ota_status_t;

typedef enum {
    OTA_STATE_IDLE = 0,
    OTA_STATE_RECEIVING,
    OTA_STATE_DONE,                 // FINISH verified; waiting for reboot
} ota_state_t;

// Flash target. Each returns 0 on success.
typedef struct {
    int (*begin)(void *ctx, uint32_t image_size);
    int (*write)(void *ctx, const uint8_t *data, size_t len);
    int (*finish)(void *ctx);       // validate image and set boot partition
    void (*abort)(void *ctx);
} ota_sink_t;

// Incremental SHA-256. The state lives in hash_ctx.
typedef struct {
    void (*start)(void *hash_ctx);
    void (*update)(void *hash_ctx, const uint8_t *data, size_t len);
    void (*finish)(void *hash_ctx, uint8_t out[32]);
} ota_hash_t;

// ATT write header (3 bytes) plus the u16 seq of an ota_data write.
#define OTA_DATA_OVERHEAD 5u

// Sends one indication on ota_ctrl.
typedef void (*ota_reply_fn)(void *ctx, const uint8_t *msg, size_t len);

typedef struct {
    uint32_t max_image_size;        // partition size
    uint16_t max_chunk;             // ATT MTU - OTA_DATA_OVERHEAD
    uint16_t ack_every;
    uint32_t timeout_ms;            // no data for this long aborts
} ota_proto_config_t;

typedef struct {
    ota_proto_config_t cfg;
    const ota_sink_t *sink;
    const ota_hash_t *hash;
    void *sink_ctx;
    void *hash_ctx;
    ota_reply_fn reply;
    void *reply_ctx;

    ota_state_t state;
    uint32_t image_size;
    uint8_t expected_sha[32];
    uint32_t received;
    uint16_t chunk_limit;           // cfg.max_chunk as announced at START
    uint16_t next_seq;
    uint16_t since_ack;
    bool nack_sent;                 // one NACK per gap until it is filled
    bool dup_seen;                  // a retransmission since the last accepted chunk
    uint16_t dup_seq;               // its seq
    uint32_t last_rx_ms;
} ota_proto_t;

void ota_proto_init(ota_proto_t *p, const ota_proto_config_t *cfg,
                    const ota_sink_t *sink, void *sink_ctx,
                    const ota_hash_t *hash, void *hash_ctx,
                    ota_reply_fn reply, void *reply_ctx);

// Takes effect at the next START (0 is ignored). A running transfer keeps
// the max_chunk it announced in its START reply, so an MTU exchange in the
// middle of a transfer cannot invalidate the client's chunk size.
void ota_proto_set_max_chunk(ota_proto_t *p, uint16_t max_chunk);

void ota_proto_on_ctrl(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms);
void ota_proto_on_data(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms);
// Aborts a transfer in progress (disconnect).
void ota_proto_on_disconnect(ota_proto_t *p);
// Aborts with OTA_STATUS_TIMEOUT (reported as a NACK) after cfg.timeout_ms
// without data.
void ota_proto_tick(ota_proto_t *p, uint32_t now_ms);

ota_state_t ota_proto_state(const ota_proto_t *p);
// 0..100 while receiving.
uint8_t ota_proto_percent(const ota_proto_t *p);

#ifdef __cplusplus
}
#endif
