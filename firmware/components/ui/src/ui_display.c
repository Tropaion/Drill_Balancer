// Display and touch bring-up: bsp_display_start_with_config() from the BSP
// (esp32_s3_touch_amoled_1_75.c), but with the draw buffers in internal RAM.
//
// The BSP keeps two 50-line draw buffers in PSRAM. The ESP-IDF 5.5 SPI
// master cannot DMA from PSRAM, so it copies every flush into a fresh
// internal DMA buffer of the same size (spi_master.c, 466 x 50 x 2 = 46.6 KB):
// with NimBLE, the sensor and the recorder up, no block that big is left and
// every flush fails with ESP_ERR_NO_MEM. Internal RAM on the S3 is
// DMA-capable, so two 10-line buffers there (2 x 9.3 KB for good) are sent as
// they are. bsp_display_get_input_dev() stays NULL (unused).

#include "esp_check.h"
#include "esp_log.h"
#include "bsp/esp-bsp.h"
#include "bsp/touch.h"
#include "ui.h"

static const char *TAG = "ui_display";

// Even: the rounder below makes every flushed area start on an even row and
// end on an odd one, so a strip is always a whole number of row pairs.
#define DRAW_BUF_LINES 10
#define DRAW_BUF_BYTES (BSP_LCD_H_RES * DRAW_BUF_LINES * BSP_LCD_BITS_PER_PIXEL / 8)

// The CO5300 takes windows that start on an even and end on an odd pixel.
static void rounder_cb(lv_area_t *area, void *user_data)
{
    (void)user_data;
    area->x1 &= ~1;
    area->y1 &= ~1;
    area->x2 |= 1;
    area->y2 |= 1;
}

esp_err_t ui_display_start(void)
{
    const esp_lv_adapter_config_t adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG();
    ESP_RETURN_ON_ERROR(esp_lv_adapter_init(&adapter_cfg), TAG, "LVGL adapter");

    // One flush is one SPI transaction of at most a draw buffer. Aborts if the
    // panel cannot be set up (ESP_ERROR_CHECK in the BSP).
    const bsp_display_config_t disp_config = {.max_transfer_sz = DRAW_BUF_BYTES};
    esp_lcd_panel_handle_t panel = NULL;
    esp_lcd_panel_io_handle_t io = NULL;
    ESP_RETURN_ON_ERROR(bsp_display_new(&disp_config, &panel, &io), TAG, "panel");

    esp_lv_adapter_display_config_t lv_disp_cfg = ESP_LV_ADAPTER_DISPLAY_SPI_WITHOUT_PSRAM_DEFAULT_CONFIG(
        panel, io, BSP_LCD_H_RES, BSP_LCD_V_RES, ESP_LV_ADAPTER_ROTATE_0);
    lv_disp_cfg.profile.buffer_height = DRAW_BUF_LINES;
    lv_disp_cfg.profile.require_double_buffer = true;   // render one strip while the other is sent
    lv_display_t *disp = esp_lv_adapter_register_display(&lv_disp_cfg);
    ESP_RETURN_ON_FALSE(disp, ESP_ERR_NO_MEM, TAG, "LVGL display");
    ESP_RETURN_ON_ERROR(esp_lv_adapter_set_area_rounder_cb(disp, rounder_cb, NULL), TAG, "rounder");

    // Without touch the level still shows (and BLE still configures it).
    const bsp_display_cfg_t touch_cfg = {.touch_flags = {.mirror_x = 1, .mirror_y = 1}};
    esp_lcd_touch_handle_t tp = NULL;
    esp_err_t err = bsp_touch_new(&touch_cfg, &tp);
    if (err == ESP_OK) {
        const esp_lv_adapter_touch_config_t lv_touch_cfg = ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(disp, tp);
        if (esp_lv_adapter_register_touch(&lv_touch_cfg) == NULL) {
            ESP_LOGE(TAG, "LVGL touch input failed, running without touch");
        }
    } else {
        ESP_LOGE(TAG, "touch: %s, running without touch", esp_err_to_name(err));
    }

    ESP_RETURN_ON_ERROR(bsp_display_brightness_init(), TAG, "brightness");
    return esp_lv_adapter_start();
}
