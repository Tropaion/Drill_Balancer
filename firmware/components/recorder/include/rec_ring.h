#pragma once

// Byte ring of whole stream records between the producers (sensor task,
// audio capture, recorder events) and the TCP sender. Pure C, host-tested.
//
// - A record goes in whole or not at all. When it does not fit it is
//   dropped, but its seq is still used, so the client sees the gap; the
//   drops are counted for STATS / END.
// - seq is assigned under the lock, so ring order is seq order whichever
//   producer pushes.
// - One consumer (the sender) takes bytes out with peek/consume; it may send
//   a record in pieces. The bytes of the last peek stay untouched until they
//   are consumed, whatever flush or reset does meanwhile, so any task may
//   call those two (the recorder task does, for the FSM's actions).
// The caller supplies the lock (a FreeRTOS mutex on the device, nothing on
// the host); every function takes it except rec_ring_init().

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "rec_stream.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*rec_lock_fn)(void *ctx);

typedef struct {
    uint8_t *buf;
    size_t cap;
    size_t rd;                // first unsent byte
    size_t used;              // unsent bytes
    size_t rec_left;          // bytes of the record at rd still to send (0: at a boundary)
    size_t inflight;          // bytes from rd the sender peeked and has not consumed
    uint32_t records;         // records (whole or partly sent) in the ring
    uint32_t next_seq;
    size_t high_water;        // most bytes used since rec_ring_begin_take()
    uint32_t dropped_records; // ring full or flushed, since rec_ring_begin_take()
    uint32_t dropped_bytes;
    uint64_t sent_bytes;      // consumed since rec_ring_init()
    rec_lock_fn lock;
    rec_lock_fn unlock;
    void *lock_ctx;
} rec_ring_t;

typedef struct {
    size_t used;
    size_t cap;
    size_t high_water;
    uint32_t records;
    uint32_t next_seq;
    uint32_t dropped_records;
    uint32_t dropped_bytes;
    uint64_t sent_bytes;
} rec_ring_stats_t;

// buf holds cap bytes (4 MB of PSRAM on the device). lock/unlock may be NULL.
void rec_ring_init(rec_ring_t *r, uint8_t *buf, size_t cap, rec_lock_fn lock,
                   rec_lock_fn unlock, void *lock_ctx);

// A new take: seq restarts at 0, the drop counters and the high-water mark
// start over. Records still queued (an earlier END) stay.
void rec_ring_begin_take(rec_ring_t *r);

// Appends one record (header, the payload parts, CRC) with the next seq.
// False if it was dropped: the ring is full, or the payload is above
// REC_MAX_PAYLOAD. *seq (may be NULL) gets the seq used either way.
bool rec_ring_push(rec_ring_t *r, uint8_t type, uint8_t ver, const rec_part_t *parts,
                   size_t nparts, uint32_t *seq);

// Up to max unsent bytes at the read position that are contiguous in memory
// (the rest follows from the buffer start); 0 when empty. They are the
// sender's until consumed: pass what one send() takes (e.g. the TCP send
// buffer), not the whole ring, or a flush cannot drop anything.
size_t rec_ring_peek(rec_ring_t *r, const uint8_t **data, size_t max);

// n bytes of the last peek were sent (more is cut to what is left of it).
void rec_ring_consume(rec_ring_t *r, size_t n);

// Drops every record nothing of which was sent or peeked; a partly sent or
// peeked record stays whole, so the stream keeps its framing (a stalled
// client before END). The dropped records count as dropped_records /
// dropped_bytes (their seqs are gone).
void rec_ring_flush(rec_ring_t *r);

// Drops everything, also a partly sent record: the client is gone and a new
// connection starts at a record boundary. Close the old socket first: what
// the sender still sends from its last peek may be overwritten, and its
// consume() afterwards does nothing.
void rec_ring_reset(rec_ring_t *r);

void rec_ring_stats(rec_ring_t *r, rec_ring_stats_t *out);

// Fill in percent (0..100), rounded up so a non-empty ring is never 0.
uint8_t rec_ring_pct(size_t used, size_t cap);

#ifdef __cplusplus
}
#endif
