#pragma once

// Control task: executes app commands (UI, BLE), polls the AXP2101 and runs
// the power policy (dim, deep sleep, power-off).

#include <stdbool.h>
#include "esp_err.h"
#include "settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// Starts the task. s is the settings loaded at boot; display_ok tells
// whether brightness can be set.
esp_err_t control_start(const settings_t *s, bool display_ok);

#ifdef __cplusplus
}
#endif
