#pragma once

// AXP2101 power management (charger, fuel gauge, PWR key, rails), I2C 0x34
// on the BSP's shared bus. C API over XPowersLib.

#include <stdbool.h>
#include <stdint.h>
#include "driver/i2c_master.h"
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool battery_present;
    uint8_t percent;          // fuel gauge 0..100
    uint16_t voltage_mv;
    bool charging;
    bool charge_done;
    bool vbus_present;
} pmu_status_t;

// Charger: 400 mA constant current (the cell allows 500 mA), 25 mA
// termination, 4.2 V. TS pin disabled (2-wire cell). Fuel gauge on. PWR key:
// short press raises an IRQ, long press (hardware) powers off. IRQs enabled:
// VBUS in/out, charge start/done, PWR key short/long, battery low.
esp_err_t pmu_init(i2c_master_bus_handle_t bus);

esp_err_t pmu_get_status(pmu_status_t *out);

#define PMU_EVT_VBUS_IN       (1u << 0)
#define PMU_EVT_VBUS_OUT      (1u << 1)
#define PMU_EVT_CHARGE_START  (1u << 2)
#define PMU_EVT_CHARGE_DONE   (1u << 3)
#define PMU_EVT_PWRKEY_SHORT  (1u << 4)
#define PMU_EVT_PWRKEY_LONG   (1u << 5)
#define PMU_EVT_BATTERY_LOW   (1u << 6)

// Reads and clears the AXP2101 IRQ status; returns PMU_EVT_* bits. Poll it
// (e.g. every 200 ms) - the IRQ line only reaches the TCA9554 expander.
uint32_t pmu_poll_events(void);

// Before ESP32 deep sleep: switch off the rails nothing needs while asleep,
// and disable and clear the IRQs so none can hold the IRQ line low through
// the sleep (pmu_init() enables them again). DCDC1 (VCC3V3: ESP32, IMU) must
// stay on for wake-on-motion. pmu_poll_events() reports nothing afterwards.
esp_err_t pmu_prepare_sleep(void);

// Full power-off through the AXP2101 (IRQs disabled and cleared first). The
// PWR key turns the device on again.
esp_err_t pmu_power_off(void);

#ifdef __cplusplus
}
#endif
