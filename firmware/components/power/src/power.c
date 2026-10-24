#include "power.h"
#include "bsp/esp-bsp.h"
#include "driver/i2c_master.h"
#include "driver/rtc_io.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "imu.h"
#include "pmu.h"

static const char *TAG = "power";

// Wake-on-motion threshold: well above sensor noise and the jitter of a
// drill lying on a bench, low enough that picking it up wakes it.
// TODO(hw): tune on the real board (QMI8658 WoM threshold resolution).
#define WAKE_ON_MOTION_MG 150

// Generous bound for the LVGL task to finish a frame before we take over.
#define DISPLAY_LOCK_TIMEOUT_MS 200

// CST9217 touch controller on the BSP bus. It sits on VCC3V3, which stays on
// for the IMU, so it is told to sleep. 0xD1xx is its 16-bit command
// register (the driver enters command mode with 0xD101); 0xD105 is the deep
// sleep command of Hynitron's cst92xx reference driver. It wakes with the
// TP_RST pulse the BSP sends at boot (TP_RST is shared with the LCD reset,
// so it is not held low instead).
// TODO(hw): check that 0xD105 is ACKed and lowers the sleep current
// (measure with and without it), and that touch works after a motion wake.
#define TOUCH_I2C_ADDR       0x5A
#define TOUCH_I2C_HZ         400000
#define TOUCH_I2C_TIMEOUT_MS 50

// INT2 idles low and goes high on motion (imu.c sets it up that way), so
// ext1 wakes on a high level and the RTC pull-down keeps the idle level.
_Static_assert(IMU_WAKE_LEVEL == 1, "start_deep_sleep() assumes an active-high wake line");

// The AXP2101 cuts the rails a moment after the off command.
// TODO(hw): measure how long VCC3V3 takes to drop after pmu_power_off().
#define POWER_OFF_SETTLE_MS 1000

power_boot_reason_t power_boot_reason(void)
{
    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();

    if (cause != ESP_SLEEP_WAKEUP_UNDEFINED) {
        // Deep sleep left the wake pin held and routed to the RTC mux; hand
        // it back to the digital GPIO matrix before imu_init() uses it.
        rtc_gpio_hold_dis(IMU_WAKE_GPIO);
        rtc_gpio_deinit(IMU_WAKE_GPIO);
    }

    switch (cause) {
    case ESP_SLEEP_WAKEUP_EXT1:
        ESP_LOGI(TAG, "Woke up by motion");
        return POWER_BOOT_MOTION;
    case ESP_SLEEP_WAKEUP_TIMER:
        ESP_LOGI(TAG, "Woke up by the power-off timer");
        return POWER_BOOT_OFF_TIMER;
    default:
        return POWER_BOOT_COLD;
    }
}

esp_err_t power_set_brightness(uint8_t percent)
{
    if (percent > 100) {
        percent = 100;
    }
    // The brightness command shares the QSPI bus with LVGL's flushes, so do
    // not send it in the middle of a frame. The lock is recursive: callers
    // already inside LVGL are fine.
    esp_err_t err = bsp_display_lock(DISPLAY_LOCK_TIMEOUT_MS);
    if (err != ESP_OK) {
        return err;
    }
    err = bsp_display_brightness_set(percent);
    bsp_display_unlock();
    return err;
}

static void touch_sleep(void)
{
    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL) {
        return;
    }
    const i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = TOUCH_I2C_ADDR,
        .scl_speed_hz = TOUCH_I2C_HZ,
    };
    i2c_master_dev_handle_t dev;
    esp_err_t err = i2c_master_bus_add_device(bus, &cfg, &dev);
    if (err == ESP_OK) {
        static const uint8_t cmd[2] = {0xD1, 0x05};
        err = i2c_master_transmit(dev, cmd, sizeof(cmd), TOUCH_I2C_TIMEOUT_MS);
        i2c_master_bus_rm_device(dev);
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Touch sleep command failed: %s", esp_err_to_name(err));
    }
}

static void display_off(void)
{
    // Keep the lock for good: LVGL must not draw while we shut down, and the
    // device resets on wake anyway. Without a display (early power-off on
    // POWER_BOOT_OFF_TIMER) both calls just fail.
    if (bsp_display_lock(DISPLAY_LOCK_TIMEOUT_MS) != ESP_OK) {
        ESP_LOGW(TAG, "Display lock not taken, display may still be running");
    }
    bsp_display_brightness_set(0);
    // TODO(hw): the BSP keeps the CO5300 panel handle private, so no
    // DISPOFF/SLPIN (0x28/0x10) is sent and the panel stays powered on
    // VCC3V3 at brightness 0 through deep sleep. Measure the sleep current;
    // if it matters, the display has to be created with bsp_display_new() so
    // that esp_lcd_panel_disp_on_off()/disp_sleep() can be called here.

    // With the display lock held, LVGL no longer polls the touch controller.
    touch_sleep();
}

// Ext1 wake on the IMU interrupt, optional power-off timer, deep sleep.
static void __attribute__((noreturn)) start_deep_sleep(bool motion_wake, uint32_t off_after_s)
{
    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);

    if (motion_wake) {
        const gpio_num_t pin = IMU_WAKE_GPIO;
        esp_err_t err = esp_sleep_enable_ext1_wakeup_io(1ULL << pin, ESP_EXT1_WAKEUP_ANY_HIGH);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Ext1 wake setup failed: %s", esp_err_to_name(err));
        }
        // Pull towards the idle level (low) so the line cannot float into a
        // wake.
        // TODO(hw): INT2 is assumed push-pull; if it is configured open-drain
        // the internal RTC pull (~45 kOhm) is the only pull and must stay.
        rtc_gpio_pullup_dis(pin);
        rtc_gpio_pulldown_en(pin);
    }

    if (off_after_s > 0) {
        esp_sleep_enable_timer_wakeup((uint64_t)off_after_s * 1000000ULL);
    }

    ESP_LOGI(TAG, "Deep sleep (motion wake %s, off timer %lu s)", motion_wake ? "on" : "off",
             (unsigned long)off_after_s);
    esp_deep_sleep_start();
}

// The PMU cuts the power; if it does not (no answer, or USB keeps the system
// up), deep sleep follows. With try_motion_wake, motion can still wake the
// device from it if the IMU answers; off_after_s is its power-off timer.
static void __attribute__((noreturn)) pmu_off_then_sleep(bool try_motion_wake,
                                                         uint32_t off_after_s)
{
    esp_err_t err = pmu_power_off();
    if (err == ESP_OK) {
        vTaskDelay(pdMS_TO_TICKS(POWER_OFF_SETTLE_MS));
        ESP_LOGE(TAG, "Still running after PMU power-off");
    } else {
        ESP_LOGE(TAG, "PMU power-off failed: %s", esp_err_to_name(err));
    }
    bool motion_wake = try_motion_wake && imu_enable_wake_on_motion(WAKE_ON_MOTION_MG) == ESP_OK;
    start_deep_sleep(motion_wake, off_after_s);
}

void power_enter_deep_sleep(uint32_t off_after_s)
{
    display_off();

    esp_err_t err = pmu_prepare_sleep();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "PMU sleep prep failed: %s", esp_err_to_name(err));
    }

    err = imu_enable_wake_on_motion(WAKE_ON_MOTION_MG);
    if (err != ESP_OK) {
        // Without motion wake only the timer could wake us; a real power-off
        // is better since the PWR key turns the device on again.
        ESP_LOGE(TAG, "Wake-on-motion failed (%s), powering off", esp_err_to_name(err));
        // Not retried (it just failed); the timer still tries a power-off.
        pmu_off_then_sleep(false, off_after_s);
    }

    start_deep_sleep(true, off_after_s);
}

void power_off(void)
{
    display_off();
    // No timer: a timer wake would only try to power off again.
    pmu_off_then_sleep(true, 0);
}
