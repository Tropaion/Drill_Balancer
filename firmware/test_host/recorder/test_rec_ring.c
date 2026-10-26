#include <stdint.h>
#include <string.h>
#include "unity.h"
#include "rec_ring.h"

static int s_depth;       // lock nesting: must stay 0/1
static int s_locks;

static void test_lock(void *ctx)
{
    TEST_ASSERT_EQUAL_PTR(&s_depth, ctx);
    TEST_ASSERT_EQUAL_INT(0, s_depth);
    s_depth++;
    s_locks++;
}

static void test_unlock(void *ctx)
{
    TEST_ASSERT_EQUAL_PTR(&s_depth, ctx);
    TEST_ASSERT_EQUAL_INT(1, s_depth);
    s_depth--;
}

static uint8_t s_mem[4096];
static rec_ring_t r;

void setUp(void)
{
    s_depth = 0;
    s_locks = 0;
    memset(s_mem, 0xEE, sizeof(s_mem));
}

void tearDown(void)
{
    TEST_ASSERT_EQUAL_INT(0, s_depth);
}

static void init(size_t cap)
{
    rec_ring_init(&r, s_mem, cap, test_lock, test_unlock, &s_depth);
}

// A payload of n bytes, filled with a pattern that depends on tag.
static rec_part_t payload(uint8_t *buf, size_t n, uint8_t tag)
{
    for (size_t i = 0; i < n; i++) {
        buf[i] = (uint8_t)(tag * 31u + i);
    }
    return (rec_part_t){buf, n};
}

// Everything unsent, in order (through peek/consume, up to max bytes).
static size_t drain(uint8_t *out, size_t max)
{
    size_t got = 0;
    for (;;) {
        const uint8_t *p;
        size_t n = rec_ring_peek(&r, &p, max - got);
        if (n == 0) {
            return got;
        }
        memcpy(&out[got], p, n);
        rec_ring_consume(&r, n);
        got += n;
    }
}

static void test_push_then_drain_gives_the_encoded_record(void)
{
    init(256);
    uint8_t a[10], b[5], want[64], got[64];
    rec_part_t parts[2] = {payload(a, sizeof(a), 1), payload(b, sizeof(b), 2)};
    uint32_t seq = 99;
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_AUDIO, 1, parts, 2, &seq));
    TEST_ASSERT_EQUAL_UINT32(0, seq);
    size_t n = rec_stream_encode(REC_T_AUDIO, 1, 0, parts, 2, want, sizeof(want));
    TEST_ASSERT_EQUAL_UINT(n, drain(got, sizeof(got)));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(want, got, n);
    TEST_ASSERT_TRUE(s_locks > 0);

    rec_ring_stats_t st;
    rec_ring_stats(&r, &st);
    TEST_ASSERT_EQUAL_UINT(0, st.used);
    TEST_ASSERT_EQUAL_UINT32(0, st.records);
    TEST_ASSERT_EQUAL_UINT64(n, st.sent_bytes);
    TEST_ASSERT_EQUAL_UINT(n, st.high_water);
    TEST_ASSERT_EQUAL_UINT32(1, st.next_seq);
}

static void test_seq_counts_up_and_drops_use_it(void)
{
    init(64);
    uint8_t a[40];
    rec_part_t p = payload(a, 30, 3);        // 42-byte records
    uint32_t seq;
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(0, seq);
    // No room for a second one: dropped, but seq 1 is gone.
    TEST_ASSERT_FALSE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(1, seq);
    // A smaller record still fits (whole records, no oldest-first eviction).
    rec_part_t small = payload(a, 2, 4);     // 14 bytes
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_EVENT, 1, &small, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(2, seq);

    rec_ring_stats_t st;
    rec_ring_stats(&r, &st);
    TEST_ASSERT_EQUAL_UINT32(1, st.dropped_records);
    TEST_ASSERT_EQUAL_UINT32(42, st.dropped_bytes);
    TEST_ASSERT_EQUAL_UINT32(2, st.records);
    TEST_ASSERT_EQUAL_UINT(56, st.used);
    TEST_ASSERT_EQUAL_UINT(56, st.high_water);

    uint8_t got[64];
    size_t n = drain(got, sizeof(got));
    TEST_ASSERT_EQUAL_UINT(56, n);
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT(42, rec_stream_check(got, n, &h));
    TEST_ASSERT_EQUAL_UINT32(0, h.seq);
    TEST_ASSERT_EQUAL_INT(14, rec_stream_check(&got[42], n - 42, &h));
    TEST_ASSERT_EQUAL_UINT32(2, h.seq);
}

static void test_oversized_payload_is_dropped(void)
{
    static uint8_t big[REC_MAX_PAYLOAD + 1];
    static uint8_t mem[2 * REC_MAX_PAYLOAD];
    rec_ring_init(&r, mem, sizeof(mem), NULL, NULL, NULL);
    rec_part_t p = {big, sizeof(big)};
    uint32_t seq;
    TEST_ASSERT_FALSE(rec_ring_push(&r, REC_T_AUDIO, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(0, seq);
    TEST_ASSERT_EQUAL_UINT32(1, r.dropped_records);
    p.len = REC_MAX_PAYLOAD;
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_AUDIO, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(1, seq);
}

static void test_record_across_the_wrap(void)
{
    init(100);
    uint8_t a[64], want[128], got[128];
    rec_part_t p = payload(a, 48, 5);        // 60-byte records
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    TEST_ASSERT_EQUAL_UINT(60, drain(got, sizeof(got)));
    // rd is at 60: the next record runs over the end of the buffer.
    rec_part_t q = payload(a, 48, 6);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &q, 1, NULL));
    const uint8_t *data;
    TEST_ASSERT_EQUAL_UINT(40, rec_ring_peek(&r, &data, SIZE_MAX));
    TEST_ASSERT_EQUAL_PTR(&s_mem[60], data);
    size_t n = rec_stream_encode(REC_T_IMU, 1, 1, &q, 1, want, sizeof(want));
    TEST_ASSERT_EQUAL_UINT(n, drain(got, sizeof(got)));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(want, got, n);
    // Nothing was written outside the ring.
    TEST_ASSERT_EQUAL_HEX8(0xEE, s_mem[100]);
}

static void test_header_across_the_wrap(void)
{
    // The len bytes of a header sit on both sides of the wrap.
    init(50);
    uint8_t a[64], got[128], want[64];
    rec_part_t p = payload(a, 35, 7);        // 47 bytes
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    TEST_ASSERT_EQUAL_UINT(47, drain(got, sizeof(got)));
    rec_part_t q = payload(a, 20, 8);        // 32 bytes from 47: header bytes 47..54
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_AUDIO, 1, &q, 1, NULL));
    // Consume one byte at a time: the boundary bookkeeping reads len across it.
    size_t n = 0;
    for (;;) {
        const uint8_t *d;
        if (rec_ring_peek(&r, &d, 1) == 0) {
            break;
        }
        got[n++] = *d;
        rec_ring_consume(&r, 1);
        TEST_ASSERT_EQUAL_UINT32(n == 32 ? 0 : 1, r.records);
    }
    size_t w = rec_stream_encode(REC_T_AUDIO, 1, 1, &q, 1, want, sizeof(want));
    TEST_ASSERT_EQUAL_UINT(w, n);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(want, got, n);
}

// Peeks up to max bytes and consumes n of them.
static void send_some(size_t max, size_t n)
{
    const uint8_t *d;
    rec_ring_peek(&r, &d, max);
    rec_ring_consume(&r, n);
}

static void test_consume_clamps_to_the_peek(void)
{
    init(128);
    uint8_t a[8];
    rec_part_t p = payload(a, 8, 9);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_EVENT, 1, &p, 1, NULL));
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_EVENT, 1, &p, 1, NULL));
    // Nothing peeked: nothing to consume.
    rec_ring_consume(&r, 5);
    TEST_ASSERT_EQUAL_UINT64(0, r.sent_bytes);
    send_some(25, 1000);
    rec_ring_stats_t st;
    rec_ring_stats(&r, &st);
    TEST_ASSERT_EQUAL_UINT(15, st.used);
    TEST_ASSERT_EQUAL_UINT32(1, st.records);
    TEST_ASSERT_EQUAL_UINT64(25, st.sent_bytes);
    send_some(SIZE_MAX, 1000);
    TEST_ASSERT_EQUAL_UINT(0, r.used);
    TEST_ASSERT_EQUAL_UINT64(40, r.sent_bytes);
    rec_ring_consume(&r, 5);
    TEST_ASSERT_EQUAL_UINT64(40, r.sent_bytes);
}

static void test_flush_keeps_the_partly_sent_record(void)
{
    init(256);
    uint8_t a[32], want[64], got[64];
    rec_part_t p = payload(a, 20, 10);       // 32 bytes each
    for (int i = 0; i < 3; i++) {
        TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    }
    // Part of the first record went out.
    send_some(10, 10);
    rec_ring_flush(&r);
    TEST_ASSERT_EQUAL_UINT(22, r.used);
    TEST_ASSERT_EQUAL_UINT32(1, r.records);
    TEST_ASSERT_EQUAL_UINT32(2, r.dropped_records);
    TEST_ASSERT_EQUAL_UINT32(64, r.dropped_bytes);
    size_t n = rec_stream_encode(REC_T_IMU, 1, 0, &p, 1, want, sizeof(want));
    TEST_ASSERT_EQUAL_UINT(22, drain(got, sizeof(got)));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(&want[10], got, n - 10);
    TEST_ASSERT_EQUAL_UINT32(0, r.records);

    // At a boundary a flush empties the ring; new records follow on.
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    rec_ring_flush(&r);
    TEST_ASSERT_EQUAL_UINT(0, r.used);
    TEST_ASSERT_EQUAL_UINT32(3, r.dropped_records);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    n = drain(got, sizeof(got));
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT(32, rec_stream_check(got, n, &h));
    TEST_ASSERT_EQUAL_UINT32(4, h.seq);
}

// The drain limit hits a sender blocked in send() on a peeked block: the
// flush keeps every record that block reaches into, END goes in behind
// them, and the stream stays whole.
static void test_flush_keeps_what_is_in_flight(void)
{
    init(2048);
    uint8_t a[200];
    rec_part_t p = payload(a, 200, 13);      // 212 bytes each
    for (int i = 0; i < 5; i++) {
        TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_AUDIO, 1, &p, 1, NULL));
    }
    const uint8_t *d;
    TEST_ASSERT_EQUAL_UINT(500, rec_ring_peek(&r, &d, 500));
    uint8_t rx[2048];
    memcpy(rx, d, 100);                     // the first 100 bytes went out
    rec_ring_flush(&r);
    // Records 0..2 hold bytes 0..635: the peek reaches into all three.
    TEST_ASSERT_EQUAL_UINT(636, r.used);
    TEST_ASSERT_EQUAL_UINT32(3, r.records);
    TEST_ASSERT_EQUAL_UINT32(2, r.dropped_records);
    TEST_ASSERT_EQUAL_UINT32(424, r.dropped_bytes);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_END, 1, NULL, 0, NULL));
    memcpy(&rx[100], d + 100, 400);         // the blocked send() completes
    rec_ring_consume(&r, 500);
    size_t n = 500 + drain(&rx[500], sizeof(rx) - 500);
    TEST_ASSERT_EQUAL_UINT(636 + 12, n);
    size_t o = 0;
    uint32_t want_seq[] = {0, 1, 2, 5};
    for (int i = 0; i < 4; i++) {
        rec_hdr_t h;
        int k = rec_stream_check(&rx[o], n - o, &h);
        TEST_ASSERT_TRUE(k > 0);
        TEST_ASSERT_EQUAL_UINT32(want_seq[i], h.seq);
        o += (size_t)k;
    }
    TEST_ASSERT_EQUAL_UINT(n, o);
}

// A sender that consumes after a reset (its send went to the closed socket)
// does not eat the new stream.
static void test_consume_after_reset_does_nothing(void)
{
    init(256);
    uint8_t a[32], got[64];
    rec_part_t p = payload(a, 20, 14);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    const uint8_t *d;
    TEST_ASSERT_EQUAL_UINT(32, rec_ring_peek(&r, &d, SIZE_MAX));
    rec_ring_reset(&r);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_SESSION, 1, &p, 1, NULL));
    rec_ring_consume(&r, 32);
    size_t n = drain(got, sizeof(got));
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT(32, rec_stream_check(got, n, &h));
    TEST_ASSERT_EQUAL_UINT8(REC_T_SESSION, h.type);
}

static void test_reset_drops_everything(void)
{
    init(256);
    uint8_t a[32], got[64];
    rec_part_t p = payload(a, 20, 11);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, NULL));
    send_some(5, 5);
    rec_ring_reset(&r);
    TEST_ASSERT_EQUAL_UINT(0, r.used);
    TEST_ASSERT_EQUAL_UINT32(0, r.records);
    // The next record starts a clean stream.
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_END, 1, NULL, 0, NULL));
    size_t n = drain(got, sizeof(got));
    rec_hdr_t h;
    TEST_ASSERT_EQUAL_INT(12, rec_stream_check(got, n, &h));
    TEST_ASSERT_EQUAL_UINT8(REC_T_END, h.type);
}

static void test_begin_take_restarts_seq_and_counters(void)
{
    init(64);
    uint8_t a[64];
    rec_part_t p = payload(a, 40, 12);       // 52 bytes
    uint32_t seq;
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, &seq));
    TEST_ASSERT_FALSE(rec_ring_push(&r, REC_T_IMU, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(1, r.dropped_records);
    // An END still queued from the last take stays.
    rec_ring_begin_take(&r);
    rec_ring_stats_t st;
    rec_ring_stats(&r, &st);
    TEST_ASSERT_EQUAL_UINT32(0, st.next_seq);
    TEST_ASSERT_EQUAL_UINT32(0, st.dropped_records);
    TEST_ASSERT_EQUAL_UINT32(0, st.dropped_bytes);
    TEST_ASSERT_EQUAL_UINT(52, st.used);
    TEST_ASSERT_EQUAL_UINT(52, st.high_water);
    send_some(52, 52);
    rec_ring_begin_take(&r);
    TEST_ASSERT_EQUAL_UINT(0, r.high_water);
    TEST_ASSERT_TRUE(rec_ring_push(&r, REC_T_SESSION, 1, &p, 1, &seq));
    TEST_ASSERT_EQUAL_UINT32(0, seq);
}

static void test_pct(void)
{
    TEST_ASSERT_EQUAL_UINT8(0, rec_ring_pct(0, 100));
    TEST_ASSERT_EQUAL_UINT8(1, rec_ring_pct(1, 4u << 20));
    TEST_ASSERT_EQUAL_UINT8(50, rec_ring_pct(50, 100));
    TEST_ASSERT_EQUAL_UINT8(51, rec_ring_pct(501, 1000));
    TEST_ASSERT_EQUAL_UINT8(100, rec_ring_pct(100, 100));
    TEST_ASSERT_EQUAL_UINT8(100, rec_ring_pct(4u << 20, 4u << 20));
    TEST_ASSERT_EQUAL_UINT8(0, rec_ring_pct(5, 0));
}

// Random pushes and partial sends: the received bytes are exactly the
// accepted records in seq order, and every missing seq was counted.
static void test_random_traffic(void)
{
    init(997);
    static uint8_t rx[400000];
    size_t rx_len = 0;
    uint32_t rng = 1;
    uint8_t a[300];
    uint32_t accepted = 0;
    for (int i = 0; i < 5000; i++) {
        rng = rng * 1664525u + 1013904223u;
        if ((rng >> 28) < 9) {
            rec_part_t parts[2] = {payload(a, (rng >> 8) % 120, (uint8_t)i),
                                   payload(&a[150], (rng >> 16) % 100, (uint8_t)(i + 1))};
            if (rec_ring_push(&r, (uint8_t)(1 + i % 6), 1, parts, 2, NULL)) {
                accepted++;
            }
        } else {
            rx_len += drain(&rx[rx_len], (rng >> 4) % 200);
        }
        TEST_ASSERT_TRUE(rx_len < sizeof(rx) - 1000);
    }
    rx_len += drain(&rx[rx_len], sizeof(rx) - rx_len);

    uint32_t seen = 0, expect_seq = 0, missing = 0;
    size_t o = 0;
    while (o < rx_len) {
        rec_hdr_t h;
        int n = rec_stream_check(&rx[o], rx_len - o, &h);
        TEST_ASSERT_TRUE(n > 0);
        TEST_ASSERT_TRUE(h.seq >= expect_seq);
        missing += h.seq - expect_seq;
        expect_seq = h.seq + 1;
        seen++;
        o += (size_t)n;
    }
    TEST_ASSERT_EQUAL_UINT(rx_len, o);
    TEST_ASSERT_EQUAL_UINT32(accepted, seen);
    // Drops after the last accepted record show in the counter only.
    TEST_ASSERT_EQUAL_UINT32(r.dropped_records, missing + (r.next_seq - expect_seq));
    TEST_ASSERT_TRUE(r.dropped_records > 100);
    TEST_ASSERT_TRUE(r.high_water <= 997);
    TEST_ASSERT_TRUE(r.high_water > 800);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_push_then_drain_gives_the_encoded_record);
    RUN_TEST(test_seq_counts_up_and_drops_use_it);
    RUN_TEST(test_oversized_payload_is_dropped);
    RUN_TEST(test_record_across_the_wrap);
    RUN_TEST(test_header_across_the_wrap);
    RUN_TEST(test_consume_clamps_to_the_peek);
    RUN_TEST(test_flush_keeps_the_partly_sent_record);
    RUN_TEST(test_flush_keeps_what_is_in_flight);
    RUN_TEST(test_consume_after_reset_does_nothing);
    RUN_TEST(test_reset_drops_everything);
    RUN_TEST(test_begin_take_restarts_seq_and_counters);
    RUN_TEST(test_pct);
    RUN_TEST(test_random_traffic);
    return UNITY_END();
}
