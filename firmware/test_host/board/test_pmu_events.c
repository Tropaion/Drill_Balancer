#include "unity.h"
#include "pmu_events.h"

void setUp(void) {}
void tearDown(void) {}

static uint32_t events(uint8_t s0, uint8_t s1, uint8_t s2)
{
    const uint8_t st[3] = {s0, s1, s2};
    return pmu_events_from_irq(st);
}

static void test_each_irq_maps_to_its_event(void)
{
    TEST_ASSERT_EQUAL_HEX32(0, events(0, 0, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_BATTERY_LOW, events(0x80, 0, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_BATTERY_LOW, events(0x40, 0, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_VBUS_IN, events(0, 0x80, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_VBUS_OUT, events(0, 0x40, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_PWRKEY_SHORT, events(0, 0x08, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_PWRKEY_LONG, events(0, 0x04, 0));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_CHARGE_DONE, events(0, 0, 0x10));
    TEST_ASSERT_EQUAL_HEX32(PMU_EVT_CHARGE_START, events(0, 0, 0x08));
}

static void test_unused_irqs_are_ignored(void)
{
    // Gauge/temperature IRQs, battery insert/remove, key edges, watchdog,
    // over-current, over-temperature, safety timer, battery OVP.
    TEST_ASSERT_EQUAL_HEX32(0, events(0x3F, 0x33, 0xE7));
}

static void test_all_events_together(void)
{
    uint32_t all = PMU_EVT_VBUS_IN | PMU_EVT_VBUS_OUT | PMU_EVT_CHARGE_START |
                   PMU_EVT_CHARGE_DONE | PMU_EVT_PWRKEY_SHORT | PMU_EVT_PWRKEY_LONG |
                   PMU_EVT_BATTERY_LOW;
    TEST_ASSERT_EQUAL_HEX32(all, events(0xFF, 0xFF, 0xFF));
}

static void test_enable_masks_match_decoded_bits(void)
{
    // Everything decoded must also be enabled, or the event never fires.
    TEST_ASSERT_EQUAL_HEX32(0, events((uint8_t)~PMU_IRQ0_ENABLED, (uint8_t)~PMU_IRQ1_ENABLED,
                                      (uint8_t)~PMU_IRQ2_ENABLED));
}

static void test_status_charging_on_usb(void)
{
    pmu_status_t s;
    // VBUS good + battery present; battery current "charge", CC phase.
    pmu_decode_status(0x28, 0x22, 57, 3900, &s);
    TEST_ASSERT_TRUE(s.vbus_present);
    TEST_ASSERT_TRUE(s.battery_present);
    TEST_ASSERT_TRUE(s.charging);
    TEST_ASSERT_FALSE(s.charge_done);
    TEST_ASSERT_EQUAL_UINT8(57, s.percent);
    TEST_ASSERT_EQUAL_UINT16(3900, s.voltage_mv);
}

static void test_status_charge_done(void)
{
    pmu_status_t s;
    // Standby current direction, charging status 100 = done.
    pmu_decode_status(0x28, 0x14, 100, 4180, &s);
    TEST_ASSERT_TRUE(s.charge_done);
    TEST_ASSERT_FALSE(s.charging);
}

static void test_status_on_battery(void)
{
    pmu_status_t s;
    // No VBUS, discharging, "not charging".
    pmu_decode_status(0x08, 0x55, 80, 3950, &s);
    TEST_ASSERT_FALSE(s.vbus_present);
    TEST_ASSERT_TRUE(s.battery_present);
    TEST_ASSERT_FALSE(s.charging);
    TEST_ASSERT_FALSE(s.charge_done);
}

static void test_status_without_battery(void)
{
    pmu_status_t s;
    // Fuel gauge and ADC values are meaningless without a cell.
    pmu_decode_status(0x20, 0x15, -1, 1234, &s);
    TEST_ASSERT_FALSE(s.battery_present);
    TEST_ASSERT_TRUE(s.vbus_present);
    TEST_ASSERT_EQUAL_UINT8(0, s.percent);
    TEST_ASSERT_EQUAL_UINT16(0, s.voltage_mv);
}

static void test_status_clamps_gauge(void)
{
    pmu_status_t s;
    pmu_decode_status(0x08, 0x40, 0xFF, 4100, &s);
    TEST_ASSERT_EQUAL_UINT8(100, s.percent);
}

static void test_status_implausible_voltage_is_no_battery(void)
{
    pmu_status_t s;
    // Present bit set, but the ADC reads below PMU_MIN_BATTERY_MV.
    pmu_decode_status(0x08, 0x40, 35, PMU_MIN_BATTERY_MV - 1, &s);
    TEST_ASSERT_FALSE(s.battery_present);
    TEST_ASSERT_EQUAL_UINT8(0, s.percent);
    TEST_ASSERT_EQUAL_UINT16(0, s.voltage_mv);
    pmu_decode_status(0x08, 0x40, 35, PMU_MIN_BATTERY_MV, &s);
    TEST_ASSERT_TRUE(s.battery_present);
    TEST_ASSERT_EQUAL_UINT8(35, s.percent);
}

static void test_status_negative_gauge_is_zero(void)
{
    pmu_status_t s;
    pmu_decode_status(0x08, 0x40, -1, 3700, &s);
    TEST_ASSERT_EQUAL_UINT8(0, s.percent);
}

static void test_vbat_adc_decode(void)
{
    // REG 34 holds bits 12:8 (upper bits ignored), REG 35 bits 7:0.
    const uint8_t a[2] = {0x0F, 0x3C};   // 0x0F3C = 3900
    TEST_ASSERT_EQUAL_UINT16(3900, pmu_decode_vbat_mv(a));
    const uint8_t b[2] = {0xE0 | 0x10, 0x68};   // 0x1068 = 4200
    TEST_ASSERT_EQUAL_UINT16(4200, pmu_decode_vbat_mv(b));
    const uint8_t c[2] = {0xFF, 0xFF};
    TEST_ASSERT_EQUAL_UINT16(0x1FFF, pmu_decode_vbat_mv(c));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_each_irq_maps_to_its_event);
    RUN_TEST(test_unused_irqs_are_ignored);
    RUN_TEST(test_all_events_together);
    RUN_TEST(test_enable_masks_match_decoded_bits);
    RUN_TEST(test_status_charging_on_usb);
    RUN_TEST(test_status_charge_done);
    RUN_TEST(test_status_on_battery);
    RUN_TEST(test_status_without_battery);
    RUN_TEST(test_status_clamps_gauge);
    RUN_TEST(test_status_implausible_voltage_is_no_battery);
    RUN_TEST(test_status_negative_gauge_is_zero);
    RUN_TEST(test_vbat_adc_decode);
    return UNITY_END();
}
