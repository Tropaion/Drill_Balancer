#include <string.h>
#include "ota_update.h"
#include "app_state.h"
#include "esp_app_desc.h"
#include "esp_app_format.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "mbedtls/sha256.h"

static const char *TAG = "ota";

// BLE default ATT MTU, until the MTU exchange.
#define DEFAULT_MTU 23u
// 16 chunks per ACK keeps up to 32 chunks in flight (about 7.7 KB at MTU 247).
#define ACK_EVERY 16u
// Generous: a phone app can stall for seconds when backgrounded.
#define TIMEOUT_MS 10000u
#define TICK_PERIOD_US (1000u * 1000u)

// The app descriptor sits at the start of the first (DROM) segment, the same
// offset esp_ota_get_partition_description() reads.
#define DESC_OFFSET (sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t))
#define HEAD_LEN (DESC_OFFSET + sizeof(esp_app_desc_t))

typedef struct {
    const esp_partition_t *part;
    esp_ota_handle_t handle;
    bool open;              // esp_ota_end/abort free the handle either way
    uint8_t head[HEAD_LEN];
    size_t head_len;
    bool desc_ok;
} ota_sink_ctx_t;

typedef struct {
    mbedtls_sha256_context ctx;
    bool live;
} ota_hash_ctx_t;

static ota_proto_t s_proto;
static ota_sink_ctx_t s_sink;
static ota_hash_ctx_t s_hash;
static SemaphoreHandle_t s_lock;
static StaticSemaphore_t s_lock_buf;
static esp_timer_handle_t s_timer;
static bool s_timer_running;
static bool s_pub_active;
static uint8_t s_pub_percent;

// ---- sink: esp_ota -------------------------------------------------------------

// The device is the transfer's from sink_begin() until it ends (finished,
// failed or aborted: ota_proto calls abort for every failure after a
// successful begin).
static void sink_abort(void *ctx)
{
    ota_sink_ctx_t *s = ctx;
    if (s->open) {
        esp_ota_abort(s->handle);
        s->open = false;
    }
    app_excl_release(APP_EXCL_OTA);
}

static int sink_begin(void *ctx, uint32_t image_size)
{
    ota_sink_ctx_t *s = ctx;
    sink_abort(s);
    // OTA, calibration and a recording session exclude each other.
    app_excl_t owner = app_excl_acquire(APP_EXCL_OTA);
    if (owner != APP_EXCL_OTA) {
        ESP_LOGE(TAG, "esp_ota_begin: refused, %s", app_excl_what(owner));
        return -1;
    }
    s->head_len = 0;
    s->desc_ok = false;
    s->part = esp_ota_get_next_update_partition(NULL);
    if (s->part == NULL) {
        ESP_LOGE(TAG, "no OTA partition");
        app_excl_release(APP_EXCL_OTA);
        return -1;
    }
    // Erase as we go: no multi-second stall before the first chunk.
    esp_err_t err = esp_ota_begin(s->part, OTA_WITH_SEQUENTIAL_WRITES, &s->handle);
    if (err != ESP_OK) {
        // ESP_ERR_OTA_ROLLBACK_INVALID_STATE: the running image is not
        // confirmed yet (ota_update_confirm).
        ESP_LOGE(TAG, "esp_ota_begin: %s", esp_err_to_name(err));
        app_excl_release(APP_EXCL_OTA);
        return -1;
    }
    s->open = true;
    ESP_LOGI(TAG, "receiving %lu bytes into %s", (unsigned long)image_size, s->part->label);
    return 0;
}

// Refuses images built for another project before they can be booted (the
// bootloader would happily run any valid ESP32-S3 app).
static bool desc_acceptable(const uint8_t *head)
{
    esp_app_desc_t desc;
    memcpy(&desc, head + DESC_OFFSET, sizeof(desc));
    if (desc.magic_word != ESP_APP_DESC_MAGIC_WORD) {
        ESP_LOGE(TAG, "image has no app descriptor");
        return false;
    }
    const esp_app_desc_t *running = esp_app_get_description();
    if (strncmp(desc.project_name, running->project_name, sizeof(desc.project_name)) != 0) {
        ESP_LOGE(TAG, "image is for project '%.*s', not '%s'",
                 (int)sizeof(desc.project_name), desc.project_name, running->project_name);
        return false;
    }
    ESP_LOGI(TAG, "new image version '%.*s'", (int)sizeof(desc.version), desc.version);
    return true;
}

static int sink_write(void *ctx, const uint8_t *data, size_t len)
{
    ota_sink_ctx_t *s = ctx;
    if (!s->open) {
        return -1;
    }
    if (!s->desc_ok) {
        size_t n = HEAD_LEN - s->head_len;
        if (n > len) {
            n = len;
        }
        memcpy(s->head + s->head_len, data, n);
        s->head_len += n;
        if (s->head_len == HEAD_LEN) {
            if (!desc_acceptable(s->head)) {
                return -1;
            }
            s->desc_ok = true;
        }
    }
    esp_err_t err = esp_ota_write(s->handle, data, len);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_write: %s", esp_err_to_name(err));
        return -1;
    }
    return 0;
}

static int sink_finish(void *ctx)
{
    ota_sink_ctx_t *s = ctx;
    if (!s->open) {
        return -1;
    }
    if (!s->desc_ok) {
        ESP_LOGE(TAG, "image too short for an app descriptor");
        return -1;
    }
    s->open = false;
    esp_err_t err = esp_ota_end(s->handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_end: %s", esp_err_to_name(err));
        return -1;
    }
    err = esp_ota_set_boot_partition(s->part);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_set_boot_partition: %s", esp_err_to_name(err));
        return -1;
    }
    ESP_LOGI(TAG, "update written to %s, reboot pending", s->part->label);
    app_excl_release(APP_EXCL_OTA);     // done: a failure above is aborted
    return 0;
}

static const ota_sink_t SINK = {sink_begin, sink_write, sink_finish, sink_abort};

// ---- hash: mbedtls -------------------------------------------------------------

static void hash_start(void *ctx)
{
    ota_hash_ctx_t *h = ctx;
    if (h->live) {
        mbedtls_sha256_free(&h->ctx);
    }
    mbedtls_sha256_init(&h->ctx);
    (void)mbedtls_sha256_starts(&h->ctx, 0);
    h->live = true;
}

static void hash_update(void *ctx, const uint8_t *data, size_t len)
{
    ota_hash_ctx_t *h = ctx;
    (void)mbedtls_sha256_update(&h->ctx, data, len);
}

static void hash_finish(void *ctx, uint8_t out[32])
{
    ota_hash_ctx_t *h = ctx;
    if (mbedtls_sha256_finish(&h->ctx, out) != 0) {
        memset(out, 0, 32);     // forces a hash mismatch
    }
    mbedtls_sha256_free(&h->ctx);
    h->live = false;
}

static const ota_hash_t HASH = {hash_start, hash_update, hash_finish};

// ---- glue ----------------------------------------------------------------------

static void lock(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
}

static void unlock(void)
{
    xSemaphoreGive(s_lock);
}

static uint16_t chunk_for_mtu(uint16_t mtu)
{
    return (uint16_t)((mtu < DEFAULT_MTU ? DEFAULT_MTU : mtu) - OTA_DATA_OVERHEAD);
}

// Called with s_lock held after every protocol call.
static void sync_outputs(void)
{
    bool active = ota_proto_state(&s_proto) == OTA_STATE_RECEIVING;
    uint8_t percent = ota_proto_percent(&s_proto);
    if (active != s_pub_active || percent != s_pub_percent) {
        s_pub_active = active;
        s_pub_percent = percent;
        app_state_set_ota(active, percent);
    }
    // The tick only matters during a transfer; don't wake the CPU otherwise.
    if (active && !s_timer_running) {
        s_timer_running = esp_timer_start_periodic(s_timer, TICK_PERIOD_US) == ESP_OK;
    } else if (!active && s_timer_running) {
        esp_timer_stop(s_timer);
        s_timer_running = false;
    }
}

static void tick_cb(void *arg)
{
    (void)arg;
    lock();
    bool was_active = ota_proto_state(&s_proto) == OTA_STATE_RECEIVING;
    ota_proto_tick(&s_proto, app_now_ms());
    if (was_active && ota_proto_state(&s_proto) != OTA_STATE_RECEIVING) {
        ESP_LOGW(TAG, "transfer timed out");
    }
    sync_outputs();
    unlock();
}

esp_err_t ota_update_init(ota_reply_fn reply, void *reply_ctx)
{
    if (reply == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_lock != NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    const esp_partition_t *part = esp_ota_get_next_update_partition(NULL);
    if (part == NULL) {
        ESP_LOGE(TAG, "no OTA partition in the partition table");
        return ESP_ERR_NOT_FOUND;
    }
    const esp_timer_create_args_t targs = {
        .callback = tick_cb,
        .name = "ota_tick",
    };
    esp_err_t err = esp_timer_create(&targs, &s_timer);
    if (err != ESP_OK) {
        return err;
    }
    s_lock = xSemaphoreCreateMutexStatic(&s_lock_buf);

    ota_proto_config_t cfg = {
        .max_image_size = part->size,
        .max_chunk = chunk_for_mtu(DEFAULT_MTU),
        .ack_every = ACK_EVERY,
        .timeout_ms = TIMEOUT_MS,
    };
    ota_proto_init(&s_proto, &cfg, &SINK, &s_sink, &HASH, &s_hash, reply, reply_ctx);
    ESP_LOGI(TAG, "ready, target %s (%lu bytes)", part->label, (unsigned long)part->size);
    return ESP_OK;
}

void ota_update_set_mtu(uint16_t mtu)
{
    if (s_lock == NULL) {
        return;
    }
    lock();
    ota_proto_set_max_chunk(&s_proto, chunk_for_mtu(mtu));   // from the next START on
    unlock();
}

typedef void (*proto_write_fn)(ota_proto_t *p, const uint8_t *data, size_t len, uint32_t now_ms);

static void feed(proto_write_fn fn, const uint8_t *data, size_t len)
{
    if (s_lock == NULL) {
        return;
    }
    lock();
    fn(&s_proto, data, len, app_now_ms());
    sync_outputs();
    unlock();
}

void ota_update_on_ctrl(const uint8_t *data, size_t len)
{
    feed(ota_proto_on_ctrl, data, len);
}

void ota_update_on_data(const uint8_t *data, size_t len)
{
    feed(ota_proto_on_data, data, len);
}

void ota_update_on_disconnect(void)
{
    if (s_lock == NULL) {
        return;
    }
    lock();
    if (ota_proto_state(&s_proto) == OTA_STATE_RECEIVING) {
        ESP_LOGW(TAG, "disconnected during transfer, aborted");
    }
    ota_proto_on_disconnect(&s_proto);
    sync_outputs();
    unlock();
}

bool ota_update_reboot_pending(void)
{
    if (s_lock == NULL) {
        return false;
    }
    lock();
    bool done = ota_proto_state(&s_proto) == OTA_STATE_DONE;
    unlock();
    return done;
}

// ---- rollback ------------------------------------------------------------------

bool ota_update_pending_verify(void)
{
    const esp_partition_t *running = esp_ota_get_running_partition();
    esp_ota_img_states_t st;
    if (running == NULL || esp_ota_get_state_partition(running, &st) != ESP_OK) {
        return false;   // factory app or no otadata entry: nothing to confirm
    }
    return st == ESP_OTA_IMG_PENDING_VERIFY;
}

void ota_update_confirm(bool self_test_ok)
{
    if (self_test_ok) {
        esp_err_t err = esp_ota_mark_app_valid_cancel_rollback();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "mark valid: %s", esp_err_to_name(err));
        } else {
            ESP_LOGI(TAG, "image confirmed");
        }
        return;
    }
    ESP_LOGE(TAG, "self-test failed, rolling back");
    // Only returns if there is no other valid image to go back to.
    esp_err_t err = esp_ota_mark_app_invalid_rollback_and_reboot();
    ESP_LOGE(TAG, "rollback failed: %s", esp_err_to_name(err));
}
