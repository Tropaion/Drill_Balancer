#include <string.h>
#include "rec_ring.h"

static void lock(rec_ring_t *r)
{
    if (r->lock) {
        r->lock(r->lock_ctx);
    }
}

static void unlock(rec_ring_t *r)
{
    if (r->unlock) {
        r->unlock(r->lock_ctx);
    }
}

static size_t min_size(size_t a, size_t b)
{
    return a < b ? a : b;
}

void rec_ring_init(rec_ring_t *r, uint8_t *buf, size_t cap, rec_lock_fn lock_fn,
                   rec_lock_fn unlock_fn, void *lock_ctx)
{
    *r = (rec_ring_t){
        .buf = buf,
        .cap = cap,
        .lock = lock_fn,
        .unlock = unlock_fn,
        .lock_ctx = lock_ctx,
    };
}

void rec_ring_begin_take(rec_ring_t *r)
{
    lock(r);
    r->next_seq = 0;
    r->dropped_records = 0;
    r->dropped_bytes = 0;
    r->high_water = r->used;
    unlock(r);
}

// Copies n bytes to the ring at *wr, wrapping, and moves *wr on.
static void put(rec_ring_t *r, size_t *wr, const void *src, size_t n)
{
    size_t first = min_size(n, r->cap - *wr);
    memcpy(&r->buf[*wr], src, first);
    memcpy(r->buf, (const uint8_t *)src + first, n - first);
    *wr = (*wr + n) % r->cap;
}

bool rec_ring_push(rec_ring_t *r, uint8_t type, uint8_t ver, const rec_part_t *parts,
                   size_t nparts, uint32_t *seq)
{
    size_t len = rec_parts_len(parts, nparts);
    size_t size = rec_record_size(len);
    // The payload's CRC outside the lock; under it only the header's (it
    // holds seq), put in front of it.
    uint32_t payload_crc = 0;
    if (len <= REC_MAX_PAYLOAD) {
        for (size_t i = 0; i < nparts; i++) {
            payload_crc = rec_crc32(payload_crc, parts[i].data, parts[i].len);
        }
    }
    uint32_t op = rec_crc32_combine_op(len);

    lock(r);
    uint32_t s = r->next_seq++;
    if (seq) {
        *seq = s;
    }
    if (len > REC_MAX_PAYLOAD || size > r->cap - r->used) {
        r->dropped_records++;
        r->dropped_bytes += (uint32_t)size;
        unlock(r);
        return false;
    }
    const rec_hdr_t h = {.type = type, .ver = ver, .len = (uint16_t)len, .seq = s};
    size_t wr = (r->rd + r->used) % r->cap;
    put(r, &wr, &h, sizeof(h));
    for (size_t i = 0; i < nparts; i++) {
        if (parts[i].len > 0) {
            put(r, &wr, parts[i].data, parts[i].len);
        }
    }
    uint32_t crc = rec_crc32_combine(rec_crc32(0, &h, sizeof(h)), payload_crc, op);
    uint8_t c[REC_CRC_LEN];
    rec_put_le32(c, crc);
    put(r, &wr, c, sizeof(c));
    r->used += size;
    r->records++;
    if (r->used > r->high_water) {
        r->high_water = r->used;
    }
    unlock(r);
    return true;
}

size_t rec_ring_peek(rec_ring_t *r, const uint8_t **data, size_t max)
{
    lock(r);
    size_t n = min_size(min_size(r->used, r->cap - r->rd), max);
    *data = &r->buf[r->rd];
    r->inflight = n;
    unlock(r);
    return n;
}

// Size of the record whose header starts at offset off from the read
// position.
static size_t size_at(const rec_ring_t *r, size_t off)
{
    // len is header bytes 2..3, possibly across the wrap.
    uint8_t lo = r->buf[(r->rd + off + 2) % r->cap];
    uint8_t hi = r->buf[(r->rd + off + 3) % r->cap];
    return rec_record_size((size_t)lo | ((size_t)hi << 8));
}

void rec_ring_consume(rec_ring_t *r, size_t n)
{
    lock(r);
    n = min_size(n, r->inflight);
    r->inflight -= n;
    while (n > 0) {
        if (r->rec_left == 0) {
            r->rec_left = size_at(r, 0);
        }
        size_t take = min_size(n, r->rec_left);
        r->rd = (r->rd + take) % r->cap;
        r->used -= take;
        r->rec_left -= take;
        r->sent_bytes += take;
        n -= take;
        if (r->rec_left == 0) {
            r->records--;
        }
    }
    unlock(r);
}

void rec_ring_flush(rec_ring_t *r)
{
    lock(r);
    // Keep the record at rd if it was started, and every record the peeked
    // bytes reach into; rd + keep stays on a record boundary.
    size_t keep = r->rec_left;
    uint32_t kept = keep > 0 ? 1 : 0;
    while (keep < r->inflight) {
        keep += size_at(r, keep);
        kept++;
    }
    r->dropped_records += r->records - kept;
    r->dropped_bytes += (uint32_t)(r->used - keep);
    r->used = keep;
    r->records = kept;
    unlock(r);
}

void rec_ring_reset(rec_ring_t *r)
{
    lock(r);
    r->used = 0;
    r->rec_left = 0;
    r->inflight = 0;
    r->records = 0;
    unlock(r);
}

void rec_ring_stats(rec_ring_t *r, rec_ring_stats_t *out)
{
    lock(r);
    *out = (rec_ring_stats_t){
        .used = r->used,
        .cap = r->cap,
        .high_water = r->high_water,
        .records = r->records,
        .next_seq = r->next_seq,
        .dropped_records = r->dropped_records,
        .dropped_bytes = r->dropped_bytes,
        .sent_bytes = r->sent_bytes,
    };
    unlock(r);
}

uint8_t rec_ring_pct(size_t used, size_t cap)
{
    if (used == 0 || cap == 0) {
        return 0;
    }
    if (used >= cap) {
        return 100;
    }
    return (uint8_t)(((uint64_t)used * 100u + cap - 1u) / cap);
}
