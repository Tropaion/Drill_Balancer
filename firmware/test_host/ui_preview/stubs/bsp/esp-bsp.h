#pragma once
#include <stdint.h>
#include "esp_err.h"
esp_err_t bsp_display_lock(uint32_t timeout_ms);
void bsp_display_unlock(void);
