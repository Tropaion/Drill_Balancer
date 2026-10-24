#pragma once

// Device side of OTA: binds ota_proto to esp_ota_* and mbedtls SHA-256, and
// handles the post-update self-test / rollback.

#include <stdbool.h>
#include "esp_err.h"
#include "ota_proto.h"

#ifdef __cplusplus
extern "C" {
#endif

// Creates the protocol instance for the next OTA partition. reply sends an
// indication on ota_ctrl. The chunk size starts at the default ATT MTU (23);
// call ota_update_set_mtu() on every new link and MTU exchange.
esp_err_t ota_update_init(ota_reply_fn reply, void *reply_ctx);
// Chunk size for the next START: mtu - OTA_DATA_OVERHEAD, with mtu raised to
// 23 if it is below (0 for a link that is already gone).
void ota_update_set_mtu(uint16_t mtu);

// Thread-safe wrappers around ota_proto_* (called from the NimBLE host task).
// The transport notes user activity for these writes (ble_service.c).
// Progress reaches the UI and BLE through app_state (ota_active,
// ota_percent).
void ota_update_on_ctrl(const uint8_t *data, size_t len);
void ota_update_on_data(const uint8_t *data, size_t len);
void ota_update_on_disconnect(void);

// True once FINISH succeeded; the caller reboots after the reply went out.
bool ota_update_reboot_pending(void);

// At boot: true if this image is waiting for confirmation (first boot after
// an update).
bool ota_update_pending_verify(void);
// Self-test passed: cancel rollback. Self-test failed: roll back and reboot.
void ota_update_confirm(bool self_test_ok);

#ifdef __cplusplus
}
#endif
