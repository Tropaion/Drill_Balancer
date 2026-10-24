#pragma once
// Host stand-in for ESP-IDF's esp_err.h (codes as in ESP-IDF), shared by the
// host tests (board, imu, ui_preview). esp_err_to_name() is only defined
// where a test calls it (ui_preview/stubs/preview_stubs.c).
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_FAIL -1
#define ESP_ERR_NO_MEM 0x101
#define ESP_ERR_INVALID_ARG 0x102
#define ESP_ERR_INVALID_STATE 0x103
#define ESP_ERR_TIMEOUT 0x107
const char *esp_err_to_name(esp_err_t code);
