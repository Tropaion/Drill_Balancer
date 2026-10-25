#include <string.h>
#include "unity.h"
#include "ota_proto.h"
#include "mbedtls/sha256.h"

// ---- fakes ------------------------------------------------------------------

#define FLASH_CAP (128u * 1024u)

typedef struct {
    int begin_calls;
    int write_calls;
    int finish_calls;
    int abort_calls;
    uint32_t begun_size;
    uint8_t flash[FLASH_CAP];
    size_t len;                 // bytes written since the last begin
    int fail_begin;
    int fail_write_call;        // 1-based write call that fails, 0 = never
    int fail_finish;
} fake_sink_t;

static fake_sink_t sink;

static int sink_begin(void *ctx, uint32_t image_size)
{
    fake_sink_t *s = ctx;
    s->begin_calls++;
    if (s->fail_begin) {
        return -1;
    }
    s->begun_size = image_size;
    s->len = 0;
    return 0;
}

static int sink_write(void *ctx, const uint8_t *data, size_t len)
{
    fake_sink_t *s = ctx;
    s->write_calls++;
    if (s->fail_write_call == s->write_calls) {
        return -1;
    }
    // Large images (percent test) are only counted, not stored.
    if (s->len + len <= FLASH_CAP) {
        memcpy(s->flash + s->len, data, len);
    }
    s->len += len;
    return 0;
}

static int sink_finish(void *ctx)
{
    fake_sink_t *s = ctx;
    s->finish_calls++;
    return s->fail_finish ? -1 : 0;
}

static void sink_abort(void *ctx)
{
    fake_sink_t *s = ctx;
    s->abort_calls++;
}

static const ota_sink_t SINK = {sink_begin, sink_write, sink_finish, sink_abort};

// mbedtls, as in ota_update.c on the device.
static mbedtls_sha256_context hash_state;

static void hash_start(void *ctx)
{
    mbedtls_sha256_init(ctx);
    TEST_ASSERT_EQUAL_INT(0, mbedtls_sha256_starts(ctx, 0));
}

static void hash_update(void *ctx, const uint8_t *data, size_t len)
{
    TEST_ASSERT_EQUAL_INT(0, mbedtls_sha256_update(ctx, data, len));
}

static void hash_finish(void *ctx, uint8_t out[32])
{
    TEST_ASSERT_EQUAL_INT(0, mbedtls_sha256_finish(ctx, out));
    mbedtls_sha256_free(ctx);
}

static const ota_hash_t HASH = {hash_start, hash_update, hash_finish};

// For the multi-megabyte percent test: always yields zeros.
static void null_start(void *ctx) { (void)ctx; }
static void null_update(void *ctx, const uint8_t *d, size_t n) { (void)ctx; (void)d; (void)n; }
static void null_finish(void *ctx, uint8_t out[32]) { (void)ctx; memset(out, 0, 32); }
static const ota_hash_t NULL_HASH = {null_start, null_update, null_finish};

#define MAX_MSGS 8192

typedef struct {
    uint8_t len;
    uint8_t b[16];
} msg_t;

static msg_t msgs[MAX_MSGS];
static int n_msgs;

static void reply_rec(void *ctx, const uint8_t *msg, size_t len)
{
    (void)ctx;
    TEST_ASSERT_TRUE_MESSAGE(len <= sizeof(msgs[0].b), "reply too long");
    TEST_ASSERT_TRUE_MESSAGE(n_msgs < MAX_MSGS, "reply recorder full");
    msgs[n_msgs].len = (uint8_t)len;
    memcpy(msgs[n_msgs].b, msg, len);
    n_msgs++;
}

static int count_type(uint8_t type)
{
    int n = 0;
    for (int i = 0; i < n_msgs; i++) {
        if (msgs[i].len > 0 && msgs[i].b[0] == type) {
            n++;
        }
    }
    return n;
}

static uint16_t rd16(const uint8_t *b)
{
    return (uint16_t)(b[0] | b[1] << 8);
}

static uint32_t rd32(const uint8_t *b)
{
    return (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24;
}

static void expect_reply(int idx, uint8_t op, ota_status_t status)
{
    TEST_ASSERT_TRUE_MESSAGE(idx < n_msgs, "reply missing");
    const msg_t *m = &msgs[idx];
    TEST_ASSERT_GREATER_OR_EQUAL(3, m->len);
    TEST_ASSERT_EQUAL_HEX8(OTA_MSG_REPLY, m->b[0]);
    TEST_ASSERT_EQUAL_HEX8(op, m->b[1]);
    TEST_ASSERT_EQUAL(status, m->b[2]);
    if (op == OTA_OP_START && status == OTA_STATUS_OK) {
        TEST_ASSERT_EQUAL(7, m->len);
    } else {
        TEST_ASSERT_EQUAL(3, m->len);
    }
}

static void expect_ack(int idx, uint16_t next_seq, uint32_t bytes)
{
    TEST_ASSERT_TRUE_MESSAGE(idx < n_msgs, "ACK missing");
    const msg_t *m = &msgs[idx];
    TEST_ASSERT_EQUAL(7, m->len);
    TEST_ASSERT_EQUAL_HEX8(OTA_MSG_ACK, m->b[0]);
    TEST_ASSERT_EQUAL_UINT16(next_seq, rd16(&m->b[1]));
    TEST_ASSERT_EQUAL_UINT32(bytes, rd32(&m->b[3]));
}

static void expect_nack(int idx, uint16_t expected_seq, ota_status_t status)
{
    TEST_ASSERT_TRUE_MESSAGE(idx < n_msgs, "NACK missing");
    const msg_t *m = &msgs[idx];
    TEST_ASSERT_EQUAL(4, m->len);
    TEST_ASSERT_EQUAL_HEX8(OTA_MSG_NACK, m->b[0]);
    TEST_ASSERT_EQUAL_UINT16(expected_seq, rd16(&m->b[1]));
    TEST_ASSERT_EQUAL(status, m->b[3]);
}

// ---- fixture -----------------------------------------------------------------

#define IMG_MAX (100u * 1024u)
#define MAX_IMAGE 65536u
#define TIMEOUT_MS 5000u

static ota_proto_t p;
static ota_proto_config_t cfg;
static uint8_t image[IMG_MAX];
static uint8_t image_sha[32];
static uint32_t now;

static void make_image(uint32_t n, uint32_t seed)
{
    uint32_t x = seed;
    for (uint32_t i = 0; i < n; i++) {
        x = x * 1664525u + 1013904223u;
        image[i] = (uint8_t)(x >> 24);
    }
    TEST_ASSERT_EQUAL_INT(0, mbedtls_sha256(image, n, image_sha, 0));
}

static void setup_with(uint32_t max_image, uint16_t max_chunk, uint16_t ack_every,
                       const ota_hash_t *hash)
{
    cfg = (ota_proto_config_t){
        .max_image_size = max_image,
        .max_chunk = max_chunk,
        .ack_every = ack_every,
        .timeout_ms = TIMEOUT_MS,
    };
    ota_proto_init(&p, &cfg, &SINK, &sink, hash, &hash_state, reply_rec, NULL);
}

void setUp(void)
{
    memset(&sink, 0, sizeof(sink));
    n_msgs = 0;
    now = 1000;
    setup_with(MAX_IMAGE, 244, 8, &HASH);
}

void tearDown(void) {}

static void ctrl(const uint8_t *b, size_t len)
{
    ota_proto_on_ctrl(&p, b, len, now);
}

static void start(uint32_t size, const uint8_t sha[32])
{
    uint8_t b[37];
    b[0] = OTA_OP_START;
    b[1] = (uint8_t)size;
    b[2] = (uint8_t)(size >> 8);
    b[3] = (uint8_t)(size >> 16);
    b[4] = (uint8_t)(size >> 24);
    memcpy(&b[5], sha, 32);
    ctrl(b, sizeof(b));
}

static void op(uint8_t opcode)
{
    ctrl(&opcode, 1);
}

static void chunk(uint16_t seq, const uint8_t *payload, size_t len)
{
    static uint8_t b[2 + 1024];
    TEST_ASSERT_TRUE(len <= 1024);
    b[0] = (uint8_t)seq;
    b[1] = (uint8_t)(seq >> 8);
    memcpy(&b[2], payload, len);
    ota_proto_on_data(&p, b, 2 + len, now);
}

// Sends image[off .. off+len) in chunks of `size`, starting at `seq`.
// Returns the next seq.
static uint16_t send_range(uint32_t off, uint32_t len, uint32_t size, uint16_t seq)
{
    while (len > 0) {
        uint32_t n = len < size ? len : size;
        chunk(seq++, &image[off], n);
        off += n;
        len -= n;
    }
    return seq;
}

static void assert_flash_is_image(uint32_t n)
{
    TEST_ASSERT_EQUAL_UINT32(n, sink.len);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(image, sink.flash, n);
}

// ---- SHA-256 sanity ----------------------------------------------------------

static void test_sha256_abc(void)
{
    // FIPS 180-4 "abc": the host build of mbedtls is configured right.
    static const uint8_t abc[32] = {
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
    };
    uint8_t out[32];
    TEST_ASSERT_EQUAL_INT(0, mbedtls_sha256((const uint8_t *)"abc", 3, out, 0));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(abc, out, 32);
}

// ---- happy path --------------------------------------------------------------

static void run_happy(uint32_t size, uint16_t max_chunk, uint32_t chunk_size)
{
    memset(&sink, 0, sizeof(sink));
    n_msgs = 0;
    setup_with(MAX_IMAGE, max_chunk, 8, &HASH);
    make_image(size, 42u + chunk_size);

    start(size, image_sha);
    expect_reply(0, OTA_OP_START, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL_UINT16(max_chunk, rd16(&msgs[0].b[3]));
    TEST_ASSERT_EQUAL_UINT16(8, rd16(&msgs[0].b[5]));
    TEST_ASSERT_EQUAL(1, sink.begin_calls);
    TEST_ASSERT_EQUAL_UINT32(size, sink.begun_size);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));

    uint16_t seq = send_range(0, size, chunk_size, 0);
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    expect_ack(n_msgs - 1, seq, size);

    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(OTA_STATE_DONE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(1, sink.finish_calls);
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
    TEST_ASSERT_EQUAL(100, ota_proto_percent(&p));
    assert_flash_is_image(size);
}

static void test_happy_path_various_chunk_sizes(void)
{
    static const uint32_t sizes[] = {1, 17, 100, 182, 244};
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        run_happy(10240u + 7u, 244, sizes[i]);
    }
    // max_chunk exactly as configured, image an exact multiple of it.
    run_happy(512u * 20u, 512, 512);
}

static void test_ack_cadence_and_contents(void)
{
    setup_with(MAX_IMAGE, 100, 4, &HASH);
    make_image(1050, 1);
    start(1050, image_sha);
    n_msgs = 0;

    send_range(0, 1050, 100, 0);    // 11 chunks
    TEST_ASSERT_EQUAL(3, n_msgs);
    expect_ack(0, 4, 400);
    expect_ack(1, 8, 800);
    expect_ack(2, 11, 1050);        // last chunk, off the cadence
}

static void test_last_chunk_on_cadence_acks_once(void)
{
    setup_with(MAX_IMAGE, 100, 4, &HASH);
    make_image(800, 2);
    start(800, image_sha);
    n_msgs = 0;

    send_range(0, 800, 100, 0);
    TEST_ASSERT_EQUAL(2, n_msgs);
    expect_ack(0, 4, 400);
    expect_ack(1, 8, 800);
}

static void test_ack_every_zero_acks_every_chunk(void)
{
    setup_with(MAX_IMAGE, 100, 0, &HASH);
    make_image(300, 3);
    start(300, image_sha);
    n_msgs = 0;
    send_range(0, 300, 100, 0);
    TEST_ASSERT_EQUAL(3, count_type(OTA_MSG_ACK));
    expect_ack(0, 1, 100);
}

// ---- sequencing --------------------------------------------------------------

static void test_seq_gap_nacks_once_and_resend_recovers(void)
{
    const uint32_t size = 10000, cs = 100;
    make_image(size, 7);
    start(size, image_sha);
    n_msgs = 0;

    chunk(0, &image[0], cs);
    chunk(1, &image[100], cs);
    // chunk 2 lost
    chunk(3, &image[300], cs);
    chunk(4, &image[400], cs);
    chunk(5, &image[500], cs);
    TEST_ASSERT_EQUAL(1, n_msgs);
    expect_nack(0, 2, OTA_STATUS_SEQ_ERROR);
    TEST_ASSERT_EQUAL(2, sink.write_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));

    // Client resends from 2; the rest follows.
    send_range(200, size - 200, cs, 2);
    TEST_ASSERT_EQUAL(1, count_type(OTA_MSG_NACK));
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(100, sink.write_calls);
    assert_flash_is_image(size);
}

static void test_second_gap_after_recovery_nacks_again(void)
{
    make_image(1000, 8);
    start(1000, image_sha);
    n_msgs = 0;
    chunk(1, &image[100], 100);
    expect_nack(0, 0, OTA_STATUS_SEQ_ERROR);
    chunk(0, &image[0], 100);
    chunk(1, &image[100], 100);
    chunk(3, &image[300], 100);
    TEST_ASSERT_EQUAL(2, count_type(OTA_MSG_NACK));
    expect_nack(n_msgs - 1, 2, OTA_STATUS_SEQ_ERROR);
}

static void test_duplicate_and_old_seq_ignored(void)
{
    make_image(500, 9);
    start(500, image_sha);
    n_msgs = 0;
    chunk(0, &image[0], 100);
    chunk(1, &image[100], 100);
    chunk(1, &image[100], 100);       // retransmission
    chunk(0, &image[0], 100);         // older
    chunk(0xFFFF, &image[0], 100);    // "before 0" modulo 65536
    chunk(2, &image[200], 100);
    TEST_ASSERT_EQUAL(0, n_msgs);
    TEST_ASSERT_EQUAL(3, sink.write_calls);
    send_range(300, 200, 100, 3);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(500);
}

static void test_lost_ack_is_repeated_once_per_resend_round(void)
{
    make_image(1000, 12);               // 10 chunks of 100, ack_every 8
    start(1000, image_sha);
    n_msgs = 0;
    send_range(0, 800, 100, 0);
    expect_ack(0, 8, 800);              // lost on the way to the client
    // The client resends from its previous ACK: the round gets the ACK once.
    send_range(0, 800, 100, 0);
    TEST_ASSERT_EQUAL(2, n_msgs);
    expect_ack(1, 8, 800);
    // That one was lost too: the next round (starting further back) gets it again.
    send_range(400, 400, 100, 4);
    TEST_ASSERT_EQUAL(3, n_msgs);
    expect_ack(2, 8, 800);
    TEST_ASSERT_EQUAL(8, sink.write_calls);
    // Once a new chunk is accepted, old ones are silent again.
    chunk(8, &image[800], 100);
    chunk(7, &image[700], 100);
    TEST_ASSERT_EQUAL(3, n_msgs);
    // The final ACK is repeated the same way.
    chunk(9, &image[900], 100);
    expect_ack(3, 10, 1000);
    chunk(9, &image[900], 100);
    TEST_ASSERT_EQUAL(5, n_msgs);
    expect_ack(4, 10, 1000);
    TEST_ASSERT_EQUAL(10, sink.write_calls);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(1000);
}

static void test_seq_wraps_past_65535(void)
{
    const uint32_t size = 70000;
    setup_with(MAX_IMAGE * 2u, 1, 32, &HASH);
    make_image(size, 10);
    start(size, image_sha);
    n_msgs = 0;

    uint16_t seq = send_range(0, size, 1, 0);
    TEST_ASSERT_EQUAL_UINT16((uint16_t)(size & 0xFFFFu), seq);
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    expect_ack(n_msgs - 1, seq, size);
    // A late retransmission of a pre-wrap seq must not be written.
    chunk(0xFFFF, &image[0xFFFF], 1);
    TEST_ASSERT_EQUAL((int)size, sink.write_calls);

    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(size);
}

static void test_gap_across_wrap(void)
{
    const uint32_t size = 65540;
    setup_with(MAX_IMAGE * 2u, 1, 1000, &HASH);
    make_image(size, 11);
    start(size, image_sha);
    send_range(0, 65535, 1, 0);       // seq 0 .. 65534
    n_msgs = 0;
    chunk(0, &image[65536], 1);       // 65535 lost, next wrapped seq arrives
    expect_nack(0, 0xFFFF, OTA_STATUS_SEQ_ERROR);
    send_range(65535, 5, 1, 0xFFFF);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(size);
}

// ---- max_chunk changes -------------------------------------------------------

static void test_max_chunk_change_applies_from_next_start(void)
{
    // An MTU exchange in the middle of a transfer must not change the limit
    // the client was told in the START reply.
    setup_with(MAX_IMAGE, 100, 8, &HASH);
    make_image(400, 21);
    start(400, image_sha);
    TEST_ASSERT_EQUAL_UINT16(100, rd16(&msgs[0].b[3]));
    n_msgs = 0;

    ota_proto_set_max_chunk(&p, 20);       // link shrank: still 100 for this transfer
    chunk(0, &image[0], 100);
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    ota_proto_set_max_chunk(&p, 200);      // grew: 150 is still too long now
    chunk(1, &image[100], 150);
    expect_nack(n_msgs - 1, 1, OTA_STATUS_BAD_REQUEST);
    send_range(100, 300, 100, 1);
    TEST_ASSERT_EQUAL_UINT32(400, sink.len);

    // A new START (a retry) announces and enforces the new limit.
    n_msgs = 0;
    start(400, image_sha);
    expect_reply(0, OTA_OP_START, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL_UINT16(200, rd16(&msgs[0].b[3]));
    send_range(0, 400, 200, 0);
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(400);
}

static void test_max_chunk_zero_is_ignored(void)
{
    setup_with(MAX_IMAGE, 100, 8, &HASH);
    ota_proto_set_max_chunk(&p, 0);
    make_image(10, 3);
    start(10, image_sha);
    TEST_ASSERT_EQUAL_UINT16(100, rd16(&msgs[0].b[3]));
}

// ---- malformed data ----------------------------------------------------------

static void test_bad_chunks_nack_bad_request_and_transfer_continues(void)
{
    setup_with(MAX_IMAGE, 100, 8, &HASH);
    make_image(300, 12);
    start(300, image_sha);
    n_msgs = 0;

    chunk(0, &image[0], 101);         // longer than max_chunk
    expect_nack(0, 0, OTA_STATUS_BAD_REQUEST);
    chunk(0, &image[0], 0);           // empty payload
    expect_nack(1, 0, OTA_STATUS_BAD_REQUEST);
    uint8_t one = 0;
    ota_proto_on_data(&p, &one, 1, now); // not even a seq
    expect_nack(2, 0, OTA_STATUS_BAD_REQUEST);
    ota_proto_on_data(&p, NULL, 0, now);
    expect_nack(3, 0, OTA_STATUS_BAD_REQUEST);
    TEST_ASSERT_EQUAL(0, sink.write_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, sink.abort_calls);

    send_range(0, 300, 100, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(300);
}

static void test_bad_chunk_then_in_flight_chunks_no_extra_nack(void)
{
    setup_with(MAX_IMAGE, 100, 8, &HASH);
    make_image(400, 13);
    start(400, image_sha);
    n_msgs = 0;
    chunk(0, &image[0], 101);
    chunk(1, &image[100], 100);       // already in flight
    chunk(2, &image[200], 100);
    TEST_ASSERT_EQUAL(1, n_msgs);
    expect_nack(0, 0, OTA_STATUS_BAD_REQUEST);
}

static void test_data_beyond_image_size_aborts(void)
{
    make_image(150, 14);
    start(150, image_sha);
    n_msgs = 0;
    chunk(0, &image[0], 100);
    chunk(1, &image[100], 100);       // 50 bytes too many
    expect_nack(n_msgs - 1, 1, OTA_STATUS_SIZE_MISMATCH);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(1, sink.write_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

// ---- FINISH checks -----------------------------------------------------------

static void test_hash_mismatch(void)
{
    make_image(1000, 15);
    uint8_t wrong[32];
    memcpy(wrong, image_sha, 32);
    wrong[31] ^= 0x01;
    start(1000, wrong);
    send_range(0, 1000, 200, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_HASH_MISMATCH);
    TEST_ASSERT_EQUAL(0, sink.finish_calls);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

static void test_corrupted_byte_gives_hash_mismatch(void)
{
    make_image(1000, 16);
    start(1000, image_sha);
    image[500] ^= 0x80;
    send_range(0, 1000, 200, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_HASH_MISMATCH);
}

static void test_size_mismatch_on_finish(void)
{
    make_image(1000, 17);
    start(1000, image_sha);
    send_range(0, 900, 100, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_SIZE_MISMATCH);
    TEST_ASSERT_EQUAL(0, sink.finish_calls);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

// ---- START checks ------------------------------------------------------------

static void test_image_too_large(void)
{
    make_image(16, 18);
    start(MAX_IMAGE + 1u, image_sha);
    expect_reply(0, OTA_OP_START, OTA_STATUS_TOO_LARGE);
    TEST_ASSERT_EQUAL(0, sink.begin_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));

    start(MAX_IMAGE, image_sha);      // exactly the partition size is fine
    expect_reply(1, OTA_OP_START, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
}

static void test_zero_size_start_is_bad_request(void)
{
    make_image(16, 19);
    start(0, image_sha);
    expect_reply(0, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
    TEST_ASSERT_EQUAL(0, sink.begin_calls);
}

static void test_malformed_requests(void)
{
    uint8_t b[38] = {OTA_OP_START, 0x10, 0, 0, 0};
    ctrl(b, 36);
    expect_reply(0, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
    ctrl(b, 38);
    expect_reply(1, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
    ctrl(b, 1);
    expect_reply(2, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
    TEST_ASSERT_EQUAL(0, sink.begin_calls);

    uint8_t fin[2] = {OTA_OP_FINISH, 0};
    ctrl(fin, 2);
    expect_reply(3, OTA_OP_FINISH, OTA_STATUS_BAD_REQUEST);
    uint8_t unknown = 0x7F;
    ctrl(&unknown, 1);
    expect_reply(4, 0x7F, OTA_STATUS_BAD_REQUEST);
    ctrl(NULL, 0);
    expect_reply(5, 0x00, OTA_STATUS_BAD_REQUEST);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

static void test_malformed_request_during_transfer_keeps_it(void)
{
    make_image(400, 20);
    start(400, image_sha);
    send_range(0, 200, 100, 0);
    uint8_t abort2[2] = {OTA_OP_ABORT, 0};
    ctrl(abort2, 2);
    expect_reply(n_msgs - 1, OTA_OP_ABORT, OTA_STATUS_BAD_REQUEST);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
    send_range(200, 200, 100, 2);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
}

static void test_start_while_receiving_restarts(void)
{
    make_image(1000, 21);
    start(1000, image_sha);
    send_range(0, 500, 100, 0);

    make_image(800, 22);              // client retries with another image
    start(800, image_sha);
    expect_reply(n_msgs - 1, OTA_OP_START, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(2, sink.begin_calls);
    TEST_ASSERT_EQUAL(0, ota_proto_percent(&p));

    send_range(0, 800, 100, 0);       // seq restarts at 0
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(800);
}

static void test_start_and_abort_in_done_are_bad_state(void)
{
    make_image(100, 23);
    start(100, image_sha);
    send_range(0, 100, 100, 0);
    op(OTA_OP_FINISH);
    TEST_ASSERT_EQUAL(OTA_STATE_DONE, ota_proto_state(&p));

    start(100, image_sha);
    expect_reply(n_msgs - 1, OTA_OP_START, OTA_STATUS_BAD_STATE);
    op(OTA_OP_ABORT);
    expect_reply(n_msgs - 1, OTA_OP_ABORT, OTA_STATUS_BAD_STATE);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_BAD_STATE);
    ota_proto_on_disconnect(&p);
    TEST_ASSERT_EQUAL(OTA_STATE_DONE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
    TEST_ASSERT_EQUAL(1, sink.begin_calls);
}

// ---- wrong state -------------------------------------------------------------

static void test_finish_without_start(void)
{
    op(OTA_OP_FINISH);
    expect_reply(0, OTA_OP_FINISH, OTA_STATUS_BAD_STATE);
    TEST_ASSERT_EQUAL(0, sink.finish_calls);
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
}

static void test_data_without_start(void)
{
    make_image(200, 24);
    chunk(0, &image[0], 100);
    TEST_ASSERT_EQUAL(1, n_msgs);
    expect_nack(0, 0, OTA_STATUS_BAD_STATE);
    chunk(1, &image[100], 100);
    TEST_ASSERT_EQUAL(1, n_msgs);     // one NACK, then silence
    TEST_ASSERT_EQUAL(0, sink.write_calls);
    TEST_ASSERT_EQUAL(0, sink.begin_calls);
}

// ---- abort paths -------------------------------------------------------------

static void test_abort_during_transfer(void)
{
    make_image(1000, 25);
    start(1000, image_sha);
    send_range(0, 300, 100, 0);
    op(OTA_OP_ABORT);
    expect_reply(n_msgs - 1, OTA_OP_ABORT, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, ota_proto_percent(&p));

    int before = n_msgs;
    chunk(3, &image[300], 100);       // in flight when ABORT was sent
    TEST_ASSERT_EQUAL(before, n_msgs);
    TEST_ASSERT_EQUAL(3, sink.write_calls);

    // A new transfer works afterwards.
    start(1000, image_sha);
    send_range(0, 1000, 100, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_OK);
    assert_flash_is_image(1000);
}

static void test_abort_while_idle_is_ok(void)
{
    op(OTA_OP_ABORT);
    expect_reply(0, OTA_OP_ABORT, OTA_STATUS_OK);
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
}

static void test_disconnect_aborts_sink(void)
{
    make_image(1000, 26);
    start(1000, image_sha);
    send_range(0, 300, 100, 0);
    int before = n_msgs;
    ota_proto_on_disconnect(&p);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(before, n_msgs); // nobody to reply to

    ota_proto_on_disconnect(&p);      // idle: nothing to abort
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
}

static void test_timeout_via_tick(void)
{
    make_image(1000, 27);
    start(1000, image_sha);
    ota_proto_tick(&p, now + TIMEOUT_MS - 1);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));

    now += 3000;
    chunk(0, &image[0], 100);         // data restarts the timer
    ota_proto_tick(&p, now + TIMEOUT_MS - 1);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, sink.abort_calls);

    n_msgs = 0;
    ota_proto_tick(&p, now + TIMEOUT_MS);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(1, n_msgs);
    expect_nack(0, 1, OTA_STATUS_TIMEOUT);

    ota_proto_tick(&p, now + 10u * TIMEOUT_MS); // idle: no further effect
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(1, n_msgs);
}

static void test_timeout_across_clock_wrap(void)
{
    now = 0xFFFFFF00u;
    make_image(100, 28);
    start(100, image_sha);
    ota_proto_tick(&p, now + 1000u);  // wraps past 0, only 1 s elapsed
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
    ota_proto_tick(&p, now + TIMEOUT_MS);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

static void test_timeout_zero_disables(void)
{
    cfg.timeout_ms = 0;
    ota_proto_init(&p, &cfg, &SINK, &sink, &HASH, &hash_state, reply_rec, NULL);
    make_image(100, 29);
    start(100, image_sha);
    ota_proto_tick(&p, now + 0x7FFFFFFFu);
    TEST_ASSERT_EQUAL(OTA_STATE_RECEIVING, ota_proto_state(&p));
}

// ---- sink failures -----------------------------------------------------------

static void test_sink_begin_failure(void)
{
    sink.fail_begin = 1;
    make_image(100, 30);
    start(100, image_sha);
    expect_reply(0, OTA_OP_START, OTA_STATUS_FLASH_ERROR);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
    TEST_ASSERT_EQUAL(0, sink.abort_calls);
}

static void test_sink_write_failure(void)
{
    sink.fail_write_call = 3;
    make_image(1000, 31);
    start(1000, image_sha);
    n_msgs = 0;
    send_range(0, 300, 100, 0);
    TEST_ASSERT_EQUAL(1, n_msgs);
    expect_nack(0, 2, OTA_STATUS_FLASH_ERROR);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));

    send_range(300, 300, 100, 3);     // still in flight: dropped silently
    TEST_ASSERT_EQUAL(1, n_msgs);
    TEST_ASSERT_EQUAL(3, sink.write_calls);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_BAD_STATE);
    TEST_ASSERT_EQUAL(0, sink.finish_calls);
}

static void test_sink_finish_failure(void)
{
    sink.fail_finish = 1;
    make_image(300, 32);
    start(300, image_sha);
    send_range(0, 300, 100, 0);
    op(OTA_OP_FINISH);
    expect_reply(n_msgs - 1, OTA_OP_FINISH, OTA_STATUS_FLASH_ERROR);
    TEST_ASSERT_EQUAL(1, sink.finish_calls);
    TEST_ASSERT_EQUAL(1, sink.abort_calls);
    TEST_ASSERT_EQUAL(OTA_STATE_IDLE, ota_proto_state(&p));
}

// ---- percent -----------------------------------------------------------------

static void test_percent(void)
{
    TEST_ASSERT_EQUAL(0, ota_proto_percent(&p));
    make_image(1000, 33);
    start(1000, image_sha);
    TEST_ASSERT_EQUAL(0, ota_proto_percent(&p));
    send_range(0, 500, 100, 0);
    TEST_ASSERT_EQUAL(50, ota_proto_percent(&p));
    send_range(500, 490, 10, 5);
    TEST_ASSERT_EQUAL(99, ota_proto_percent(&p));
    send_range(990, 10, 10, 54);
    TEST_ASSERT_EQUAL(100, ota_proto_percent(&p));
    op(OTA_OP_FINISH);
    TEST_ASSERT_EQUAL(100, ota_proto_percent(&p));
}

static void test_percent_large_image_no_overflow(void)
{
    // received * 100 exceeds 32 bits here.
    const uint32_t size = 100000000u;
    static uint8_t zeros[1000];
    uint8_t sha[32] = {0};
    setup_with(size, 1000, 1000, &NULL_HASH);
    start(size, sha);
    uint16_t seq = 0;
    for (uint32_t sent = 0; sent < size / 2u; sent += sizeof(zeros)) {
        chunk(seq++, zeros, sizeof(zeros));
    }
    TEST_ASSERT_EQUAL(0, count_type(OTA_MSG_NACK));
    TEST_ASSERT_EQUAL(50, ota_proto_percent(&p));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_sha256_abc);
    RUN_TEST(test_happy_path_various_chunk_sizes);
    RUN_TEST(test_ack_cadence_and_contents);
    RUN_TEST(test_last_chunk_on_cadence_acks_once);
    RUN_TEST(test_ack_every_zero_acks_every_chunk);
    RUN_TEST(test_seq_gap_nacks_once_and_resend_recovers);
    RUN_TEST(test_second_gap_after_recovery_nacks_again);
    RUN_TEST(test_duplicate_and_old_seq_ignored);
    RUN_TEST(test_lost_ack_is_repeated_once_per_resend_round);
    RUN_TEST(test_seq_wraps_past_65535);
    RUN_TEST(test_gap_across_wrap);
    RUN_TEST(test_bad_chunks_nack_bad_request_and_transfer_continues);
    RUN_TEST(test_bad_chunk_then_in_flight_chunks_no_extra_nack);
    RUN_TEST(test_data_beyond_image_size_aborts);
    RUN_TEST(test_hash_mismatch);
    RUN_TEST(test_corrupted_byte_gives_hash_mismatch);
    RUN_TEST(test_size_mismatch_on_finish);
    RUN_TEST(test_image_too_large);
    RUN_TEST(test_zero_size_start_is_bad_request);
    RUN_TEST(test_malformed_requests);
    RUN_TEST(test_malformed_request_during_transfer_keeps_it);
    RUN_TEST(test_start_while_receiving_restarts);
    RUN_TEST(test_start_and_abort_in_done_are_bad_state);
    RUN_TEST(test_finish_without_start);
    RUN_TEST(test_data_without_start);
    RUN_TEST(test_abort_during_transfer);
    RUN_TEST(test_abort_while_idle_is_ok);
    RUN_TEST(test_disconnect_aborts_sink);
    RUN_TEST(test_timeout_via_tick);
    RUN_TEST(test_timeout_across_clock_wrap);
    RUN_TEST(test_timeout_zero_disables);
    RUN_TEST(test_sink_begin_failure);
    RUN_TEST(test_sink_write_failure);
    RUN_TEST(test_sink_finish_failure);
    RUN_TEST(test_percent);
    RUN_TEST(test_max_chunk_change_applies_from_next_start);
    RUN_TEST(test_max_chunk_zero_is_ignored);
    RUN_TEST(test_percent_large_image_no_overflow);
    return UNITY_END();
}
