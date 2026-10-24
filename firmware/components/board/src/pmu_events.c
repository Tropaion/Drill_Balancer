#include <stddef.h>
#include "pmu_events.h"

static const struct {
    uint8_t reg;    // index into status[]: REG 48 + reg
    uint8_t bit;
    uint32_t event;
} IRQ_EVENTS[] = {
    {0, PMU_IRQ0_SOC_WARN, PMU_EVT_BATTERY_LOW},
    {0, PMU_IRQ0_SOC_SHUTDOWN, PMU_EVT_BATTERY_LOW},
    {1, PMU_IRQ1_VBUS_INSERT, PMU_EVT_VBUS_IN},
    {1, PMU_IRQ1_VBUS_REMOVE, PMU_EVT_VBUS_OUT},
    {1, PMU_IRQ1_PWRKEY_SHORT, PMU_EVT_PWRKEY_SHORT},
    {1, PMU_IRQ1_PWRKEY_LONG, PMU_EVT_PWRKEY_LONG},
    {2, PMU_IRQ2_CHARGE_START, PMU_EVT_CHARGE_START},
    {2, PMU_IRQ2_CHARGE_DONE, PMU_EVT_CHARGE_DONE},
};

uint32_t pmu_events_from_irq(const uint8_t status[3])
{
    uint32_t ev = 0;
    for (size_t i = 0; i < sizeof(IRQ_EVENTS) / sizeof(IRQ_EVENTS[0]); i++) {
        if (status[IRQ_EVENTS[i].reg] & IRQ_EVENTS[i].bit) {
            ev |= IRQ_EVENTS[i].event;
        }
    }
    return ev;
}

uint16_t pmu_decode_vbat_mv(const uint8_t adc[2])
{
    return (uint16_t)(((adc[0] & 0x1Fu) << 8) | adc[1]);
}

// REG 00 / REG 01 fields (AXP2101 datasheet 6.13.2.1-2).
#define STATUS1_VBUS_GOOD     (1u << 5)
#define STATUS2_DIR_SHIFT     5
#define STATUS2_DIR_MASK      0x3u
#define STATUS2_DIR_CHARGE    0x1u
#define STATUS2_CHG_MASK      0x7u
#define STATUS2_CHG_DONE      0x4u

void pmu_decode_status(uint8_t status1, uint8_t status2, int percent, uint16_t vbat_mv,
                       pmu_status_t *out)
{
    bool present = (status1 & PMU_STATUS1_BAT_PRESENT) != 0 && vbat_mv >= PMU_MIN_BATTERY_MV;
    if (percent < 0) {
        percent = 0;
    } else if (percent > 100) {
        percent = 100;
    }
    *out = (pmu_status_t){
        .battery_present = present,
        .percent = present ? (uint8_t)percent : 0,
        .voltage_mv = present ? vbat_mv : 0,
        // Battery current direction covers every charger phase (trickle,
        // pre, CC, CV) in one field.
        .charging = ((status2 >> STATUS2_DIR_SHIFT) & STATUS2_DIR_MASK) == STATUS2_DIR_CHARGE,
        .charge_done = (status2 & STATUS2_CHG_MASK) == STATUS2_CHG_DONE,
        .vbus_present = (status1 & STATUS1_VBUS_GOOD) != 0,
    };
}
