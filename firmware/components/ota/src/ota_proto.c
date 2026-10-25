#include <string.h>
#include "ota_proto.h"

#define START_LEN 37u
#define SEQ_LEN 2u

static void put16(uint8_t *b, uint16_t v)
{
    b[0] = (uint8_t)v;
    b[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t *b, uint32_t v)
{
    b[0] = (uint8_t)v;
    b[1] = (uint8_t)(v >> 8);
    b[2] = (uint8_t)(v >> 16);
    b[3] = (uint8_t)(v >> 24);
}

static uint32_t get32(const uint8_t *b)
{
    return (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24;
}

static void send_reply(ota_proto_t *p, uint8_t opcode, ota_status_t status)
{
    uint8_t m[3] = {OTA_MSG_REPLY, opcode, (uint8_t)status};
    p->reply(p->reply_ctx, m, sizeof(m));
}

static void send_ack(ota_proto_t *p)
{
    uint8_t m[7] = {OTA_MSG_ACK};
    put16(&m[1], p->next_seq);
    put32(&m[3], p->received);
    p->reply(p->reply_ctx, m, sizeof(m));
}

static void send_nack(ota_proto_t *p, ota_status_t status)
{
    uint8_t m[4] = {OTA_MSG_NACK};
    put16(&m[1], p->next_seq);
    m[3] = (uint8_t)status;
    p->reply(p->reply_ctx, m, sizeof(m));
}

// Ends a transfer in progress. Stray data afterwards stays silent (nack_sent)
// because the client already learned about the end from a reply or NACK.
static void end_transfer(ota_proto_t *p)
{
    if (p->state == OTA_STATE_RECEIVING) {
        p->sink->abort(p->sink_ctx);
    }
    p->state = OTA_STATE_IDLE;
    p->nack_sent = true;
}

void ota_proto_init(ota_proto_t *p, const ota_proto_config_t *cfg,
                    const ota_sink_t *sink, void *sink_ctx,
                    const ota_hash_t *hash, void *hash_ctx,
                    ota_reply_fn reply, void *reply_ctx)
{
    memset(p, 0, sizeof(*p));
    p->cfg = *cfg;
    if (p->cfg.ack_every == 0) {
        p->cfg.ack_every = 1;
    }
    p->sink = sink;
    p->sink_ctx = sink_ctx;
    p->hash = hash;
    p->hash_ctx = hash_ctx;
    p->reply = reply;
    p->reply_ctx = reply_ctx;
    p->state = OTA_STATE_IDLE;
}

void ota_proto_set_max_chunk(ota_proto_t *p, uint16_t max_chunk)
{
    if (max_chunk != 0) {
        p->cfg.max_chunk = max_chunk;
    }
}

static void on_start(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms)
{
    if (len != START_LEN) {
        send_reply(p, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
        return;
    }
    if (p->state == OTA_STATE_DONE) {
        send_reply(p, OTA_OP_START, OTA_STATUS_BAD_STATE);
        return;
    }
    // A retry while receiving drops the old transfer first.
    end_transfer(p);

    uint32_t size = get32(&data[1]);
    if (size == 0) {
        send_reply(p, OTA_OP_START, OTA_STATUS_BAD_REQUEST);
        return;
    }
    if (size > p->cfg.max_image_size) {
        send_reply(p, OTA_OP_START, OTA_STATUS_TOO_LARGE);
        return;
    }
    if (p->sink->begin(p->sink_ctx, size) != 0) {
        send_reply(p, OTA_OP_START, OTA_STATUS_FLASH_ERROR);
        return;
    }
    p->hash->start(p->hash_ctx);
    p->state = OTA_STATE_RECEIVING;
    p->image_size = size;
    memcpy(p->expected_sha, &data[5], sizeof(p->expected_sha));
    p->received = 0;
    p->chunk_limit = p->cfg.max_chunk;
    p->next_seq = 0;
    p->since_ack = 0;
    p->nack_sent = false;
    p->dup_seen = false;
    p->last_rx_ms = now_ms;

    uint8_t m[7] = {OTA_MSG_REPLY, OTA_OP_START, OTA_STATUS_OK};
    put16(&m[3], p->chunk_limit);
    put16(&m[5], p->cfg.ack_every);
    p->reply(p->reply_ctx, m, sizeof(m));
}

static void on_finish(ota_proto_t *p)
{
    if (p->state != OTA_STATE_RECEIVING) {
        send_reply(p, OTA_OP_FINISH, OTA_STATUS_BAD_STATE);
        return;
    }
    ota_status_t st = OTA_STATUS_OK;
    if (p->received != p->image_size) {
        st = OTA_STATUS_SIZE_MISMATCH;
    } else {
        uint8_t sha[32];
        p->hash->finish(p->hash_ctx, sha);
        if (memcmp(sha, p->expected_sha, sizeof(sha)) != 0) {
            st = OTA_STATUS_HASH_MISMATCH;
        } else if (p->sink->finish(p->sink_ctx) != 0) {
            st = OTA_STATUS_FLASH_ERROR;
        }
    }
    if (st == OTA_STATUS_OK) {
        p->state = OTA_STATE_DONE;
        p->nack_sent = true;
    } else {
        end_transfer(p);
    }
    send_reply(p, OTA_OP_FINISH, st);
}

static void on_abort(ota_proto_t *p)
{
    if (p->state == OTA_STATE_DONE) {
        send_reply(p, OTA_OP_ABORT, OTA_STATUS_BAD_STATE);
        return;
    }
    if (p->state == OTA_STATE_RECEIVING) {
        end_transfer(p);
    }
    send_reply(p, OTA_OP_ABORT, OTA_STATUS_OK);
}

void ota_proto_on_ctrl(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms)
{
    if (len == 0) {
        send_reply(p, 0x00, OTA_STATUS_BAD_REQUEST);
        return;
    }
    uint8_t opcode = data[0];
    switch (opcode) {
    case OTA_OP_START:
        on_start(p, data, len, now_ms);
        break;
    case OTA_OP_FINISH:
        if (len != 1) {
            send_reply(p, opcode, OTA_STATUS_BAD_REQUEST);
        } else {
            on_finish(p);
        }
        break;
    case OTA_OP_ABORT:
        if (len != 1) {
            send_reply(p, opcode, OTA_STATUS_BAD_REQUEST);
        } else {
            on_abort(p);
        }
        break;
    default:
        send_reply(p, opcode, OTA_STATUS_BAD_REQUEST);
        break;
    }
}

void ota_proto_on_data(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms)
{
    if (p->state != OTA_STATE_RECEIVING) {
        // One hint for a client that skipped START; quiet after that so a
        // burst of in-flight chunks doesn't flood the indication queue.
        if (!p->nack_sent && p->state == OTA_STATE_IDLE) {
            send_nack(p, OTA_STATUS_BAD_STATE);
            p->nack_sent = true;
        }
        return;
    }
    if (len < SEQ_LEN) {
        send_nack(p, OTA_STATUS_BAD_REQUEST);
        p->nack_sent = true;
        return;
    }
    uint16_t seq = (uint16_t)(data[0] | data[1] << 8);
    uint16_t ahead = (uint16_t)(seq - p->next_seq);
    if (ahead >= 0x8000u) {
        // Retransmission of an accepted chunk. Right after an ACK it means the
        // client lost that ACK and resends from an older one; without the ACK
        // again it would never move on. Once per resend round, so a resent
        // window does not flood the indication queue.
        if (p->since_ack == 0) {
            uint16_t step = (uint16_t)(seq - p->dup_seq);
            if (!p->dup_seen || step == 0 || step >= 0x8000u) {
                send_ack(p);
            }
            p->dup_seen = true;
            p->dup_seq = seq;
        }
        return;
    }
    if (ahead != 0) {
        if (!p->nack_sent) {
            send_nack(p, OTA_STATUS_SEQ_ERROR);
            p->nack_sent = true;
        }
        return;
    }

    const uint8_t *payload = data + SEQ_LEN;
    size_t n = len - SEQ_LEN;
    if (n == 0 || n > p->chunk_limit) {
        // Always answered: this is the chunk the client must fix and resend.
        send_nack(p, OTA_STATUS_BAD_REQUEST);
        p->nack_sent = true;
        return;
    }
    if (n > p->image_size - p->received) {
        send_nack(p, OTA_STATUS_SIZE_MISMATCH);
        end_transfer(p);
        return;
    }
    if (p->sink->write(p->sink_ctx, payload, n) != 0) {
        send_nack(p, OTA_STATUS_FLASH_ERROR);
        end_transfer(p);
        return;
    }
    p->hash->update(p->hash_ctx, payload, n);
    p->received += (uint32_t)n;
    p->next_seq++;
    p->nack_sent = false;
    p->dup_seen = false;
    p->last_rx_ms = now_ms;
    p->since_ack++;
    if (p->since_ack >= p->cfg.ack_every || p->received == p->image_size) {
        send_ack(p);
        p->since_ack = 0;
    }
}

void ota_proto_on_disconnect(ota_proto_t *p)
{
    if (p->state == OTA_STATE_RECEIVING) {
        end_transfer(p);
    }
    // A new connection gets the one BAD_STATE hint again.
    if (p->state == OTA_STATE_IDLE) {
        p->nack_sent = false;
    }
}

void ota_proto_tick(ota_proto_t *p, uint32_t now_ms)
{
    if (p->state != OTA_STATE_RECEIVING || p->cfg.timeout_ms == 0) {
        return;
    }
    if ((uint32_t)(now_ms - p->last_rx_ms) >= p->cfg.timeout_ms) {
        send_nack(p, OTA_STATUS_TIMEOUT);
        end_transfer(p);
    }
}

ota_state_t ota_proto_state(const ota_proto_t *p)
{
    return p->state;
}

uint8_t ota_proto_percent(const ota_proto_t *p)
{
    switch (p->state) {
    case OTA_STATE_RECEIVING:
        // 64-bit: received * 100 overflows 32 bits above ~43 MB.
        return (uint8_t)((uint64_t)p->received * 100u / p->image_size);
    case OTA_STATE_DONE:
        return 100;
    default:
        return 0;
    }
}
