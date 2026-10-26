#include "app_state.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

#define CMD_QUEUE_LEN 16

static SemaphoreHandle_t s_lock;
static QueueHandle_t s_cmds;
static app_snapshot_t s_state;
static volatile uint32_t s_last_activity_ms;

void app_state_init(void)
{
    s_lock = xSemaphoreCreateMutex();
    s_cmds = xQueueCreate(CMD_QUEUE_LEN, sizeof(app_cmd_t));
    s_state = (app_snapshot_t){
        .q = quat_identity(),
        .cal = {.kind = APP_CAL_IDLE, .last_result = -1},
    };
    s_last_activity_ms = app_now_ms();
}

#define LOCKED(stmt)                              \
    do {                                          \
        xSemaphoreTake(s_lock, portMAX_DELAY);    \
        stmt;                                     \
        xSemaphoreGive(s_lock);                   \
    } while (0)

void app_state_get(app_snapshot_t *out)
{
    LOCKED(*out = s_state);
}

void app_state_set_motion(bool imu_ok, quat_t q, bool still, bool vibrating,
                          const float *zero_deg, const level_reading_t *reading,
                          uint32_t now_ms)
{
    LOCKED({
        s_state.imu_ok = imu_ok;
        s_state.q = q;
        s_state.still = still;
        s_state.vibrating = vibrating;
        s_state.zero_set = zero_deg != NULL;
        for (int i = 0; i < 3; i++) {
            s_state.zero_deg[i] = zero_deg ? zero_deg[i] : 0.0f;
        }
        s_state.reading = *reading;
        s_state.reading_ms = now_ms;
    });
}

void app_state_set_battery(const app_battery_t *battery)
{
    LOCKED(s_state.battery = *battery);
}

void app_state_set_ble(bool enabled, bool connected)
{
    LOCKED({
        s_state.ble_enabled = enabled;
        s_state.ble_connected = connected;
    });
}

void app_state_set_ble_holds_awake(bool holds_awake)
{
    LOCKED(s_state.ble_holds_awake = holds_awake);
}

void app_state_set_passkey(bool pending, uint32_t passkey)
{
    LOCKED({
        s_state.passkey_pending = pending;
        s_state.passkey = passkey;
    });
}

void app_state_set_ota(bool active, uint8_t percent)
{
    LOCKED({
        s_state.ota_active = active;
        s_state.ota_percent = percent;
    });
}

void app_state_set_power(app_power_t power)
{
    LOCKED(s_state.power = power);
}

void app_state_set_cal(const app_cal_status_t *cal)
{
    LOCKED({
        s_state.cal = *cal;
        if (cal->kind == APP_CAL_IDLE && s_state.excl == APP_EXCL_CAL) {
            s_state.excl = APP_EXCL_NONE;   // the calibration ended
        }
    });
}

void app_state_set_cal_valid(bool mount_cal_valid, bool accel_cal_valid)
{
    LOCKED({
        s_state.mount_cal_valid = mount_cal_valid;
        s_state.accel_cal_valid = accel_cal_valid;
    });
}

void app_state_set_rec(const app_rec_status_t *rec)
{
    LOCKED(s_state.rec = *rec);
}

app_excl_t app_excl_acquire(app_excl_t who)
{
    app_excl_t owner;
    LOCKED({
        if (s_state.excl == APP_EXCL_NONE) {
            s_state.excl = (uint8_t)who;
        }
        owner = (app_excl_t)s_state.excl;
    });
    return owner;
}

void app_excl_release(app_excl_t who)
{
    LOCKED({
        if (s_state.excl == (uint8_t)who) {
            s_state.excl = APP_EXCL_NONE;
        }
    });
}

bool app_post_cmd(app_cmd_id_t id, int32_t arg)
{
    app_cmd_t cmd = {.id = id, .arg = arg};
    return xQueueSend(s_cmds, &cmd, 0) == pdTRUE;
}

bool app_receive_cmd(app_cmd_t *out, uint32_t timeout_ms)
{
    return xQueueReceive(s_cmds, out, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
}

void app_note_activity(void)
{
    s_last_activity_ms = app_now_ms();
}

uint32_t app_last_activity_ms(void)
{
    return s_last_activity_ms;
}

uint32_t app_now_ms(void)
{
    return (uint32_t)(esp_timer_get_time() / 1000);
}
