#include <stddef.h>
#include <string.h>
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "app_state.h"
#include "settings_store.h"

static const char *TAG = "settings";

#define NVS_NAMESPACE "db"
#define NVS_KEY "settings"

// The size field catches a settings_t layout change that forgot to bump
// SETTINGS_SCHEMA_VERSION; the CRC catches a torn or corrupted write.
typedef struct {
    uint16_t version;
    uint16_t size;
    settings_t settings;
    uint32_t crc;
} settings_blob_t;

static SemaphoreHandle_t s_mutex;
static StaticSemaphore_t s_mutex_buf;
static settings_t s_current;

static uint32_t blob_crc(const settings_blob_t *b)
{
    return esp_rom_crc32_le(0, (const uint8_t *)b, offsetof(settings_blob_t, crc));
}

static esp_err_t init_nvs(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        // Unusable as it is; losing settings and bonds beats not booting.
        ESP_LOGW(TAG, "NVS unusable (%s), erasing", esp_err_to_name(err));
        err = nvs_flash_erase();
        if (err == ESP_OK) {
            err = nvs_flash_init();
        }
    }
    return err;
}

// Fills *out from NVS, or with defaults if there is nothing usable.
static void load(settings_t *out)
{
    *out = settings_defaults();

    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READONLY, &h);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "no stored settings, using defaults");
        return;
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "nvs_open: %s, using defaults", esp_err_to_name(err));
        return;
    }
    settings_blob_t blob;
    size_t len = sizeof(blob);
    err = nvs_get_blob(h, NVS_KEY, &blob, &len);
    nvs_close(h);

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "no stored settings, using defaults");
        return;
    }
    if (err != ESP_OK || len != sizeof(blob)) {
        // ESP_ERR_NVS_INVALID_LENGTH: the stored blob is larger than ours.
        ESP_LOGW(TAG, "stored settings unreadable (%s, %u bytes), using defaults",
                 esp_err_to_name(err), (unsigned)len);
        return;
    }
    if (blob.version != SETTINGS_SCHEMA_VERSION || blob.size != sizeof(settings_t)) {
        ESP_LOGW(TAG, "stored settings v%u/%u bytes, expected v%d/%u, using defaults",
                 (unsigned)blob.version, (unsigned)blob.size, SETTINGS_SCHEMA_VERSION,
                 (unsigned)sizeof(settings_t));
        return;
    }
    if (blob.crc != blob_crc(&blob)) {
        ESP_LOGW(TAG, "stored settings CRC mismatch, using defaults");
        return;
    }
    if (!settings_validate(&blob.settings)) {
        ESP_LOGW(TAG, "stored settings out of range, using defaults");
        return;
    }
    *out = blob.settings;
    ESP_LOGI(TAG, "settings loaded (mount cal %s, accel cal %s)",
             out->mount_cal_valid ? "yes" : "no", out->accel_cal_valid ? "yes" : "no");
}

static esp_err_t store(const settings_t *s)
{
    settings_blob_t blob;
    // Zeroed first so padding bytes are deterministic in flash.
    memset(&blob, 0, sizeof(blob));
    blob.version = SETTINGS_SCHEMA_VERSION;
    blob.size = sizeof(settings_t);
    memcpy(&blob.settings, s, sizeof(*s));
    blob.crc = blob_crc(&blob);

    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open: %s", esp_err_to_name(err));
        return err;
    }
    err = nvs_set_blob(h, NVS_KEY, &blob, sizeof(blob));
    if (err == ESP_OK) {
        err = nvs_commit(h);
    }
    nvs_close(h);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "storing settings: %s", esp_err_to_name(err));
    }
    return err;
}

esp_err_t settings_store_init(void)
{
    if (s_mutex != NULL) {
        return ESP_OK;
    }
    s_current = settings_defaults();
    s_mutex = xSemaphoreCreateMutexStatic(&s_mutex_buf);

    esp_err_t err = init_nvs();
    if (err != ESP_OK) {
        // Keep running on defaults; settings_update() will report the failure.
        ESP_LOGE(TAG, "NVS init failed: %s, using defaults", esp_err_to_name(err));
        return err;
    }
    settings_t loaded;
    load(&loaded);
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    s_current = loaded;
    xSemaphoreGive(s_mutex);
    return ESP_OK;
}

void settings_get(settings_t *out)
{
    if (s_mutex == NULL) {
        *out = settings_defaults();
        return;
    }
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    *out = s_current;
    xSemaphoreGive(s_mutex);
}

esp_err_t settings_update(settings_update_fn fn, void *ctx)
{
    if (fn == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    // Held from the read to the end of the write, so concurrent writers
    // can't interleave RAM and flash state or lose each other's changes.
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    settings_t next = s_current;
    // A write that repeats the stored values (a config write, a UI edit
    // changed and changed back, a reset at defaults) costs no flash write
    // and no reload.
    bool changed = fn(&next, ctx) && !settings_equal(&next, &s_current);
    esp_err_t err = ESP_OK;
    if (changed) {
        err = settings_validate(&next) ? store(&next) : ESP_ERR_INVALID_ARG;
        if (err == ESP_OK) {
            s_current = next;
        }
    }
    xSemaphoreGive(s_mutex);

    if (changed && err == ESP_OK && !app_post_cmd(APP_CMD_SETTINGS_CHANGED, 0)) {
        ESP_LOGW(TAG, "command queue full, settings change not announced");
    }
    return err;
}

static bool reset_to_defaults(settings_t *s, void *ctx)
{
    (void)ctx;
    *s = settings_defaults();
    return true;
}

esp_err_t settings_reset(void)
{
    return settings_update(reset_to_defaults, NULL);
}
