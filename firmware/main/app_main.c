// Boot sequence. Runtime work happens in these tasks:
//   sensor  (prio 10, core 1)  IMU FIFO -> fusion -> level, calibration data,
//                              the recorder's IMU tap
//   rec_mic (prio 9, core 1)   recorder audio capture, only during a take
//   LVGL    (prio 6, adapter)  UI, reads app_state snapshots
//   control (prio 5)           app commands, PMU polling, power policy
//   rec     (prio 5)           recorder session/take FSM (recorder.h)
//   rec_tx  (prio 4, core 0)   recorder stream server and sender
// plus the NimBLE host task, the Wi-Fi and lwIP tasks while a recording
// session is open, and esp_timer callbacks (BLE notifications, the OTA
// timeout, the recorder tick). app_main itself returns once everything is
// started.

#include <stdbool.h>
#include "app_state.h"
#include "ble_service.h"
#include "bsp/esp-bsp.h"
#include "control.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "imu.h"
#include "ota_update.h"
#include "pmu.h"
#include "power.h"
#include "recorder.h"
#include "sensor_task.h"
#include "settings_store.h"
#include "ui.h"

static const char *TAG = "main";

// Woken by the deep-sleep timer: only bring up what power_off() needs. The
// IMU is set up too, so power_off()'s deep-sleep fallback (PMU did not cut
// the power, e.g. on USB) can still wake on motion.
static void off_timer_boot(void)
{
    ESP_LOGI(TAG, "off timer expired");
    esp_err_t err = bsp_i2c_init();
    if (err == ESP_OK) {
        i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
        if (pmu_init(bus) != ESP_OK) {
            ESP_LOGE(TAG, "PMU init failed");
        }
        if (imu_init(bus, IMU_PROFILE_LEVEL) != ESP_OK) {
            ESP_LOGE(TAG, "IMU init failed");
        }
    } else {
        ESP_LOGE(TAG, "I2C init failed: %s", esp_err_to_name(err));
    }
    power_off();
}

// The IMU and PMU checks are retried this long, so one I2C error at boot
// does not roll back a good image: a failed imu_init() is retried by the
// sensor task after IMU_RETRY_MS (2 s, sensor_task.c), which this covers
// twice. The image stays pending until confirmed, so waiting costs nothing.
#define OTA_SELF_TEST_WAIT_MS   5000
#define OTA_SELF_TEST_RETRY_MS  500

// First boot after an OTA update: confirm the image only if the essentials
// work, otherwise roll back to the previous one.
static void confirm_ota(bool display_ok)
{
    if (!ota_update_pending_verify()) {
        return;
    }
    bool imu_ok = false;
    bool pmu_ok = false;
    uint32_t start = app_now_ms();
    for (;;) {
        imu_ok = imu_ok || imu_self_test();
        pmu_status_t st;
        pmu_ok = pmu_ok || pmu_get_status(&st) == ESP_OK;
        // No retry helps a display that did not start.
        if (!display_ok || (imu_ok && pmu_ok) ||
            (uint32_t)(app_now_ms() - start) >= OTA_SELF_TEST_WAIT_MS) {
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(OTA_SELF_TEST_RETRY_MS));
    }
    bool ok = display_ok && imu_ok && pmu_ok;
    ESP_LOGI(TAG, "OTA self-test: display %d, IMU %d, PMU %d -> %s (%lu ms)", display_ok, imu_ok,
             pmu_ok, ok ? "pass" : "FAIL", (unsigned long)(app_now_ms() - start));
    ota_update_confirm(ok);   // rolls back and reboots on failure
}

// recorder_hooks_t.request_profile
static void request_profile(uint8_t profile)
{
    sensor_request_imu_profile((imu_profile_t)profile);
}

void app_main(void)
{
    ESP_LOGI(TAG, "DrillBalancer v%s", esp_app_get_description()->version);

    // Before anything touches GPIO21 (it is still routed to the RTC after a
    // deep-sleep wake).
    power_boot_reason_t reason = power_boot_reason();
    if (reason == POWER_BOOT_OFF_TIMER) {
        off_timer_boot();
        return;   // not reached
    }

    app_state_init();
    // NVS for the settings and the NimBLE bond store; serves defaults even
    // if it fails.
    esp_err_t err = settings_store_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "settings store: %s (using defaults)", esp_err_to_name(err));
    }
    settings_t settings;
    settings_get(&settings);

    // The charger is configured first so a cold start on USB never charges
    // the 1000 mAh cell with the AXP2101 defaults for long.
    i2c_master_bus_handle_t bus = NULL;
    err = bsp_i2c_init();
    if (err == ESP_OK) {
        bus = bsp_i2c_get_handle();
        err = pmu_init(bus);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PMU init: %s", esp_err_to_name(err));
        }
    } else {
        ESP_LOGE(TAG, "I2C init: %s", esp_err_to_name(err));
    }

    bool display_ok = bsp_display_start() != NULL;
    if (display_ok) {
        err = power_set_brightness(settings.brightness_pct);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "brightness: %s", esp_err_to_name(err));
        }
    } else {
        ESP_LOGE(TAG, "display init failed, running headless");
    }

    bool imu_ok = false;
    if (bus) {
        err = imu_init(bus, IMU_PROFILE_LEVEL);
        imu_ok = err == ESP_OK;
        if (!imu_ok) {
            ESP_LOGE(TAG, "IMU init: %s (retrying in the background)", esp_err_to_name(err));
        }
    }

    // Before the sensor task, which reports the IMU to it from its first pass.
    static const recorder_hooks_t rec_hooks = {
        .request_profile = request_profile,
        .gyro_bias = sensor_gyro_bias,
    };
    err = recorder_init(imu_ok, &rec_hooks);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "recorder: %s", esp_err_to_name(err));
    }

    err = sensor_start(&settings, imu_ok);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "sensor task: %s", esp_err_to_name(err));
    }

    if (display_ok) {
        err = ui_init();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "UI init: %s", esp_err_to_name(err));
        }
    }

    // Also creates the OTA service (ota_update_init).
    err = ble_service_init(settings.ble_enabled);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "BLE init: %s", esp_err_to_name(err));
    }

    err = control_start(&settings, display_ok);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "control task: %s", esp_err_to_name(err));
    }

    confirm_ota(display_ok);
    ESP_LOGI(TAG, "boot done (%s)", reason == POWER_BOOT_MOTION ? "motion wake" : "cold start");
}
