#pragma once

// AXP2101 register decoding for pmu.cpp. Pure C, host-tested.

#include <stdint.h>
#include "pmu.h"

#ifdef __cplusplus
extern "C" {
#endif

// IRQ enable/status bits (AXP2101 datasheet 6.13.2.40-45, REG 40-42/48-4A).
#define PMU_IRQ0_SOC_WARN      (1u << 7)   // SOC below the warning level (REG 1A[7:4])
#define PMU_IRQ0_SOC_SHUTDOWN  (1u << 6)   // SOC below the shutdown level (REG 1A[3:0])
#define PMU_IRQ1_VBUS_INSERT   (1u << 7)
#define PMU_IRQ1_VBUS_REMOVE   (1u << 6)
#define PMU_IRQ1_PWRKEY_SHORT  (1u << 3)
#define PMU_IRQ1_PWRKEY_LONG   (1u << 2)
#define PMU_IRQ2_CHARGE_DONE   (1u << 4)
#define PMU_IRQ2_CHARGE_START  (1u << 3)

#define PMU_IRQ0_ENABLED (PMU_IRQ0_SOC_WARN | PMU_IRQ0_SOC_SHUTDOWN)
#define PMU_IRQ1_ENABLED (PMU_IRQ1_VBUS_INSERT | PMU_IRQ1_VBUS_REMOVE | \
                          PMU_IRQ1_PWRKEY_SHORT | PMU_IRQ1_PWRKEY_LONG)
#define PMU_IRQ2_ENABLED (PMU_IRQ2_CHARGE_DONE | PMU_IRQ2_CHARGE_START)

// REG 00 bit 3: pmu.cpp reads the gauge and the ADC only with a battery.
#define PMU_STATUS1_BAT_PRESENT (1u << 3)

// A battery voltage below this is "no battery" rather than an empty cell.
#define PMU_MIN_BATTERY_MV 2500u

// REG 48, 49, 4A -> PMU_EVT_*. Bits not enabled above are ignored.
uint32_t pmu_events_from_irq(const uint8_t status[3]);

// REG 34, 35 (battery voltage ADC, 13 bits, 1 mV/LSB) -> mV.
uint16_t pmu_decode_vbat_mv(const uint8_t adc[2]);

// REG 00 (status1), REG 01 (status2), fuel gauge percent (REG A4, -1 if no
// battery) and battery voltage -> pmu_status_t. percent comes out as 0..100,
// and percent and voltage are 0 unless a battery is present (status1 says so
// and vbat_mv >= PMU_MIN_BATTERY_MV).
void pmu_decode_status(uint8_t status1, uint8_t status2, int percent, uint16_t vbat_mv,
                       pmu_status_t *out);

#ifdef __cplusplus
}
#endif
