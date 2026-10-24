#pragma once

// NimBLE peripheral implementing ble_protocol.h. Runs the NimBLE host task;
// a periodic timer sends angle (10 Hz) and status notifications from
// app_state snapshots. Commands go to app_post_cmd(), config writes to
// settings_update(), OTA to ota_update_*. A disconnect posts
// APP_CMD_CAL_CANCEL with APP_CMD_ARG_FROM_BLE.

#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

// Initialises the controller, host, GATT database and bond store, and starts
// advertising if enabled.
esp_err_t ble_service_init(bool enabled);

// Starts or stops advertising (and drops the connection when disabling).
// A call with the current state does nothing, so callers need not track it.
esp_err_t ble_service_set_enabled(bool enabled);

// Stops BLE before deep sleep / power-off.
void ble_service_shutdown(void);

// Deletes every stored bond (factory reset) and drops the current link, so
// a lost or passed-on phone loses its access. Runs in the NimBLE host task
// (returns once queued); if the host is not running, NimBLE's NVS namespace
// is erased directly.
esp_err_t ble_service_clear_bonds(void);

#ifdef __cplusplus
}
#endif
