// AXP2101 through XPowersLib, which talks to the chip via register callbacks
// bound to our own i2c_master device on the BSP bus. Register references are
// to the AXP2101 datasheet V1.4 (managed_components/xpowerslib/datasheet).
//
// Rails on the Waveshare ESP32-S3-Touch-AMOLED-1.75 (schematic in
// waveshareteam/ESP32-S3-Touch-AMOLED-1.75, page 1, rail table and AXP2101
// pins):
//   DCDC1   VCC3V3: ESP32-S3, LCD, QMI8658, SD, codec ...
//   ALDO1   A3V3: ES7210 microphone ADC analog/digital supply
//   ALDO2-4 test points only
//   BLDO1/2, CPUSLDO, DLDO1/2   not connected
//   DCDC2-4 no inductor fitted; DCDC5 not connected
//   RTCLDO  VCC-RTC (also the AXP_IRQ pull-up)

#include "pmu.h"
#include "pmu_events.h"
#include "esp_check.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

// Third-party header-only code: its warnings are not ours to fix.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdouble-promotion"
#include "XPowersAXP2101.tpp"
#pragma GCC diagnostic pop

static const char *TAG = "pmu";

#define PMU_I2C_ADDR        0x34
#define PMU_I2C_HZ          400000
#define PMU_I2C_TIMEOUT_MS  50

// 1000 mAh LiPo, 500 mA maximum charge current: 0.4 C.
#define PMU_PRECHARGE       XPOWERS_AXP2101_PRECHARGE_50MA   // REG 61 = 0x02
#define PMU_CHARGE_CURRENT  XPOWERS_AXP2101_CHG_CUR_400MA
#define PMU_TERM_CURRENT    XPOWERS_AXP2101_CHG_ITERM_25MA
#define PMU_CHARGE_VOLTAGE  XPOWERS_AXP2101_CHG_VOL_4V2

// SOC IRQ levels (REG 1A): warning 5..20%, shutdown 0..15%. Both raise
// PMU_EVT_BATTERY_LOW; the app decides when to power off.
#define PMU_LOW_BAT_WARN_PCT      10
#define PMU_LOW_BAT_SHUTDOWN_PCT  5
// Hard cut-off (REG 24, 2.6..3.3 V): the AXP2101 powers off below this VSYS.
// Reset default is an eFuse value; 2.9 V keeps the cell out of deep discharge
// with some margin for load dips.
#define PMU_VOFF_MV               2900

#define PMU_IRQ_MASK (PMU_IRQ0_ENABLED | (PMU_IRQ1_ENABLED << 8) | (PMU_IRQ2_ENABLED << 16))

static XPowersAXP2101 s_pmu;
static i2c_master_dev_handle_t s_dev;
static SemaphoreHandle_t s_lock;
static StaticSemaphore_t s_lock_buf;
static bool s_ready;
// XPowersLib drops most I/O errors; the callbacks count them instead.
static unsigned s_io_errors;

static int reg_read(uint8_t addr, uint8_t reg, uint8_t *data, uint8_t len)
{
    (void)addr;
    if (i2c_master_transmit_receive(s_dev, &reg, 1, data, len, PMU_I2C_TIMEOUT_MS) != ESP_OK) {
        s_io_errors++;
        return -1;
    }
    return 0;
}

static int reg_write(uint8_t addr, uint8_t reg, uint8_t *data, uint8_t len)
{
    (void)addr;
    i2c_master_transmit_multi_buffer_info_t parts[2] = {
        {.write_buffer = &reg, .buffer_size = 1},
        {.write_buffer = data, .buffer_size = len},
    };
    if (i2c_master_multi_buffer_transmit(s_dev, parts, 2, PMU_I2C_TIMEOUT_MS) != ESP_OK) {
        s_io_errors++;
        return -1;
    }
    return 0;
}

static void lock(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
}

static void unlock(void)
{
    xSemaphoreGive(s_lock);
}

// Burst reads walk through consecutive registers.
// TODO(hw): the datasheet does not spell out register auto-increment over
// TWSI; check that the first boot log's battery line matches the charger
// state, and that a PWR key press still raises PMU_EVT_PWRKEY_SHORT.
static_assert(XPOWERS_AXP2101_STATUS2 == XPOWERS_AXP2101_STATUS1 + 1, "status burst");
static_assert(XPOWERS_AXP2101_ADC_DATA_RELUST1 == XPOWERS_AXP2101_ADC_DATA_RELUST0 + 1,
              "battery voltage burst");
static_assert(XPOWERS_AXP2101_INTSTS3 == XPOWERS_AXP2101_INTSTS1 + 2, "IRQ status burst");

static bool read_regs(uint8_t reg, uint8_t *buf, uint8_t len)
{
    return s_pmu.readRegister(reg, buf, len) == 0;
}

// IRQ status bits are write-1-to-clear (REG 48-4A). Writing back exactly what
// was read keeps IRQs that arrive in between.
static bool read_and_clear_irqs(uint8_t st[XPOWERS_AXP2101_INTSTS_CNT])
{
    if (!read_regs(XPOWERS_AXP2101_INTSTS1, st, XPOWERS_AXP2101_INTSTS_CNT)) {
        return false;
    }
    for (int i = 0; i < XPOWERS_AXP2101_INTSTS_CNT; i++) {
        if (st[i] != 0) {
            s_pmu.writeRegister((uint8_t)(XPOWERS_AXP2101_INTSTS1 + i), st[i]);
        }
    }
    return true;
}

// All IRQs off and the pending ones cleared. Disabled, no status bit is set
// any more (6.12.1), so nothing can latch the IRQ line low while nobody
// polls it. st receives what was pending.
static bool quiesce_irqs(uint8_t st[XPOWERS_AXP2101_INTSTS_CNT])
{
    s_pmu.disableIRQ(XPOWERS_AXP2101_ALL_IRQ);
    return read_and_clear_irqs(st);
}

// The I/O error count of a sequence (s_io_errors, read under the lock) as a
// result.
static esp_err_t io_result(unsigned errors, const char *what)
{
    if (errors != 0) {
        ESP_LOGE(TAG, "%s: %u I2C errors", what, errors);
        return ESP_FAIL;
    }
    return ESP_OK;
}

static esp_err_t configure(void)
{
    s_io_errors = 0;
    if (!s_pmu.begin(PMU_I2C_ADDR, reg_read, reg_write)) {
        ESP_LOGE(TAG, "no AXP2101 at 0x%02x", PMU_I2C_ADDR);
        return ESP_ERR_NOT_FOUND;
    }
    ESP_LOGI(TAG, "power-on source 0x%02x, last power-off source 0x%02x",
             s_pmu.readRegister(XPOWERS_AXP2101_PWRON_STATUS),
             s_pmu.readRegister(XPOWERS_AXP2101_PWROFF_STATUS));

    // Charger (REG 61-64, REG 18[1]). REG 61[3:0] is the precharge limit in
    // 25 mA steps; XPowersLib's setPrechargeCurr() keeps bits 3:2, so from the
    // reset value 0x05 it would give 0x06 (150 mA). Bits 7:4 are read-only 0.
    int prechg = s_pmu.readRegister(XPOWERS_AXP2101_IPRECHG_SET);
    if (prechg >= 0) {
        s_pmu.writeRegister(XPOWERS_AXP2101_IPRECHG_SET,
                            (uint8_t)((prechg & 0xF0) | PMU_PRECHARGE));
        prechg = s_pmu.readRegister(XPOWERS_AXP2101_IPRECHG_SET);
        if (prechg >= 0 && (prechg & 0x0F) != PMU_PRECHARGE) {
            ESP_LOGE(TAG, "precharge REG 61 = 0x%02x, expected 0x%02x", prechg,
                     (unsigned)PMU_PRECHARGE);
        }
    }
    s_pmu.setChargerConstantCurr(PMU_CHARGE_CURRENT);
    s_pmu.setChargerTerminationCurr(PMU_TERM_CURRENT);
    s_pmu.enableChargerTerminationLimit();
    s_pmu.setChargeTargetVoltage(PMU_CHARGE_VOLTAGE);
    s_pmu.enableCellbatteryCharge();
    // 2-wire cell without NTC: TS becomes an external input that does not
    // block the charger (REG 50[4]), and its ADC channel goes off (REG 30[1]).
    s_pmu.disableTSPinMeasure();

    // Battery detection (REG 68[0]), ADC channels (REG 30), fuel gauge
    // (REG 18[3]).
    s_pmu.enableBattDetection();
    s_pmu.enableBattVoltageMeasure();
    s_pmu.enableVbusVoltageMeasure();
    s_pmu.enableSystemVoltageMeasure();
    s_pmu.setRegisterBit(XPOWERS_AXP2101_CHARGE_GAUGE_WDT_CTRL, 3);

    s_pmu.setLowBatWarnThreshold(PMU_LOW_BAT_WARN_PCT);
    s_pmu.setLowBatShutdownThreshold(PMU_LOW_BAT_SHUTDOWN_PCT);
    s_pmu.setSysPowerDownVoltage(PMU_VOFF_MV);

    // PWR key (REG 22, REG 27): long-press IRQ after 1.5 s (IRQLEVEL), power
    // off (not restart) after 4 s (OFFLEVEL, the shortest setting).
    s_pmu.setIrqLevelTime(XPOWERS_AXP2101_IRQ_TIME_1S5);
    s_pmu.setPowerKeyPressOffTime(XPOWERS_POWEROFF_4S);
    s_pmu.enableLongPressShutdown();
    s_pmu.setLongPressPowerOFF();

    // pmu_prepare_sleep() never turns A3V3 off, but make sure it is up in
    // case an earlier firmware did.
    s_pmu.setALDO1Voltage(3300);
    s_pmu.enableALDO1();

    // IRQs: all off, stale ones from before the reset cleared, ours on.
    uint8_t stale[XPOWERS_AXP2101_INTSTS_CNT];
    if (quiesce_irqs(stale) && (stale[0] | stale[1] | stale[2])) {
        ESP_LOGI(TAG, "cleared stale IRQs %02x %02x %02x", stale[0], stale[1], stale[2]);
    }
    s_pmu.enableIRQ(PMU_IRQ_MASK);

    return io_result(s_io_errors, "setup");
}

esp_err_t pmu_init(i2c_master_bus_handle_t bus)
{
    ESP_RETURN_ON_FALSE(bus, ESP_ERR_INVALID_ARG, TAG, "no I2C bus");
    if (!s_lock) {
        s_lock = xSemaphoreCreateMutexStatic(&s_lock_buf);
    }
    if (!s_dev) {
        i2c_device_config_t cfg = {};
        cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
        cfg.device_address = PMU_I2C_ADDR;
        cfg.scl_speed_hz = PMU_I2C_HZ;
        ESP_RETURN_ON_ERROR(i2c_master_bus_add_device(bus, &cfg, &s_dev), TAG, "add device");
    }

    lock();
    s_ready = false;
    esp_err_t err = configure();
    s_ready = err == ESP_OK;
    unlock();

    if (err == ESP_OK) {
        pmu_status_t st;
        if (pmu_get_status(&st) == ESP_OK) {
            ESP_LOGI(TAG, "battery %s %u%% %u mV, VBUS %s, %s", st.battery_present ? "ok" : "absent",
                     st.percent, st.voltage_mv, st.vbus_present ? "on" : "off",
                     st.charge_done ? "charged" : st.charging ? "charging" : "not charging");
        }
    }
    return err;
}

esp_err_t pmu_get_status(pmu_status_t *out)
{
    ESP_RETURN_ON_FALSE(out, ESP_ERR_INVALID_ARG, TAG, "null argument");
    if (!s_ready) {
        return ESP_ERR_INVALID_STATE;
    }
    uint8_t st[2];                  // REG 00, 01
    uint8_t vbat[2] = {0, 0};       // REG 34, 35
    int percent = -1;               // REG A4
    lock();
    s_io_errors = 0;
    // The gauge and the ADC mean nothing without a cell.
    if (read_regs(XPOWERS_AXP2101_STATUS1, st, sizeof(st)) && (st[0] & PMU_STATUS1_BAT_PRESENT)) {
        read_regs(XPOWERS_AXP2101_ADC_DATA_RELUST0, vbat, sizeof(vbat));
        percent = s_pmu.readRegister(XPOWERS_AXP2101_BAT_PERCENT_DATA);
    }
    bool ok = s_io_errors == 0;
    unlock();
    if (!ok) {
        return ESP_FAIL;
    }
    pmu_decode_status(st[0], st[1], percent, pmu_decode_vbat_mv(vbat), out);
    return ESP_OK;
}

uint32_t pmu_poll_events(void)
{
    if (!s_ready) {
        return 0;
    }
    uint8_t st[XPOWERS_AXP2101_INTSTS_CNT];
    lock();
    bool ok = read_and_clear_irqs(st);
    unlock();
    return ok ? pmu_events_from_irq(st) : 0;
}

esp_err_t pmu_prepare_sleep(void)
{
    if (!s_ready) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    s_io_errors = 0;
    // Only rails with nothing on them (see the table at the top). DCDC1 feeds
    // the ESP32 and the IMU and stays on.
    s_pmu.disableALDO2();
    s_pmu.disableALDO3();
    s_pmu.disableALDO4();
    s_pmu.disableBLDO1();
    s_pmu.disableBLDO2();
    s_pmu.disableCPUSLDO();
    s_pmu.disableDLDO1();
    s_pmu.disableDLDO2();
    // TODO(hw): ALDO1 (A3V3, ES7210) is not needed for IMU wake but stays on:
    // with it off, the unpowered ES7210 on the shared I2C bus may clamp
    // SDA/SCL through its ESD diodes, and power.c still sets up the IMU after
    // this call. Measure the sleep current with ALDO1 on and off, and if it is
    // worth it, switch ALDO1 off after imu_enable_wake_on_motion().

    // Nothing polls the IRQ status while the ESP32 sleeps: an enabled event
    // (PWR key, USB in/out, SOC warning) would latch AXP_IRQ low, and with it
    // the TCA9554 INT, through their 10k pull-ups until the next wake.
    // configure() enables them again and clears stale bits on every boot.
    // TODO(hw): in deep sleep, press PWR and plug/unplug USB; AXP_IRQ (TCA9554
    // P5) and TCA9554 INT must stay high and the sleep current must not step.
    uint8_t st[XPOWERS_AXP2101_INTSTS_CNT];
    quiesce_irqs(st);
    unsigned errors = s_io_errors;
    unlock();
    return io_result(errors, "switching rails off");
}

esp_err_t pmu_power_off(void)
{
    if (!s_dev) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    s_io_errors = 0;
    // A pending IRQ holds the IRQ pin low, and IRQ-low can be an eFuse power-on
    // source (6.5.4.2), so disable and clear them first. Disabled, none can
    // latch again if the shutdown does not take effect (on USB) and power.c
    // falls back to deep sleep.
    uint8_t st[XPOWERS_AXP2101_INTSTS_CNT];
    quiesce_irqs(st);
    s_pmu.shutdown();    // REG 10[0]: soft power-off, everything but RTCLDO
    unsigned errors = s_io_errors;
    unlock();
    return io_result(errors, "power-off");
}
