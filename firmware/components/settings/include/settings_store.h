#pragma once

// NVS persistence of settings_t (device only). The blob is versioned; an
// unreadable, old or invalid blob falls back to settings_defaults().
// Thread-safe.

#include <stdbool.h>
#include "esp_err.h"
#include "settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// Initialises NVS (erasing it if the partition is full or from a newer
// layout) and loads the settings.
esp_err_t settings_store_init(void);

void settings_get(settings_t *out);

// Atomic read-modify-write: under the store mutex, fn edits a copy of the
// current settings. If it returns true and the copy differs from the current
// settings (settings_equal()), the copy is validated, stored to NVS and
// APP_CMD_SETTINGS_CHANGED posted; ESP_ERR_INVALID_ARG if settings_validate()
// fails. If fn returns false or changes nothing, nothing is stored or posted
// and the result is ESP_OK (a caller that needs a reason passes it back
// through ctx). Every writer goes through here, so two writers (control
// task, BLE, UI) cannot overwrite each other's fields. fn runs with the
// mutex held: keep it short and never block or call settings_* from it.
typedef bool (*settings_update_fn)(settings_t *s, void *ctx);
esp_err_t settings_update(settings_update_fn fn, void *ctx);

// Back to defaults (including calibration), through settings_update().
esp_err_t settings_reset(void);

#ifdef __cplusplus
}
#endif
