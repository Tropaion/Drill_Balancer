#include <stdio.h>
#include <string.h>
#include "ble_service.h"
#include "ble_codec.h"
#include "ble_protocol.h"
#include "app_state.h"
#include "ota_update.h"
#include "recorder.h"
#include "settings_store.h"

#include "esp_app_desc.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs.h"

#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble";

// Not exported by NimBLE: store/config/src/ble_store_config.c.
void ble_store_config_init(void);

// NimBLE's bond namespace (store/config/src/ble_store_nvs.c), erased
// directly by ble_service_clear_bonds() when the host is not running.
#define NIMBLE_BOND_NVS_NAMESPACE "nimble_bond"

_Static_assert(BLE_CODEC_ATT_INVALID_LEN == BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN,
               "codec uses the standard ATT code");

#define DIS_MANUFACTURER "DrillBalancer"
#define DIS_MODEL        "DrillBalancer (ESP32-S3-Touch-AMOLED-1.75)"

#define NOTIFY_PERIOD_US      (100u * 1000u)   // angle at 10 Hz
#define STATUS_HEARTBEAT_MS   1000u
#define REC_HEARTBEAT_MS      1000u            // rec_state during a take

// Fast advertising right after enable / disconnect so a phone finds us
// quickly, then slow to save power.
#define ADV_FAST_MS           30000
#define ADV_FAST_ITVL_MIN     BLE_GAP_ADV_ITVL_MS(100)
#define ADV_FAST_ITVL_MAX     BLE_GAP_ADV_ITVL_MS(110)
#define ADV_SLOW_ITVL_MIN     BLE_GAP_ADV_ITVL_MS(1000)
#define ADV_SLOW_ITVL_MAX     BLE_GAP_ADV_ITVL_MS(1050)

// Connection intervals in 1.25 ms units: 7.5..15 ms during OTA, 30..60 ms
// otherwise; supervision timeout in 10 ms units.
#define CONN_FAST_ITVL_MIN    6
#define CONN_FAST_ITVL_MAX    12
#define CONN_IDLE_ITVL_MIN    24
#define CONN_IDLE_ITVL_MAX    48
#define CONN_SUPERVISION_TIMEOUT 500

// Reboot this long after the FINISH reply was confirmed, or after the
// fallback delay if the confirmation never arrives.
#define REBOOT_DELAY_US       (500u * 1000u)
#define REBOOT_FALLBACK_US    (3000u * 1000u)
// The reboot goes through the control task (APP_CMD_REBOOT), which saves a
// pending settings edit first; if it has not rebooted this long after the
// command was posted, the timer restarts directly.
#define REBOOT_BACKSTOP_US    (5000u * 1000u)

// Largest ATT attribute value (Core spec); long writes are reassembled by
// NimBLE before the access callback sees them.
#define WRITE_BUF_LEN         512u

// Full-strength keys only on the protected characteristics: the negotiated
// key size is not covered by the LESC checks, so a MITM at pairing time
// could otherwise force a 7-byte key (KNOB-style downgrade).
#define SEC_KEY_SIZE          16u

// Link policy: ble_codec.h (BLE_CODEC_AWAKE_AFTER_WRITE_MS,
// BLE_CODEC_IDLE_UNAUTH_MS), applied by the notify timer.

// OTA replies are at most 7 bytes; a few may queue up behind one indication
// in flight (ATT allows only one outstanding).
#define IND_MSG_MAX           8u
#define IND_QUEUE_LEN         8u

#define DB_UUID128(x) DB_UUID128_INIT((uint8_t)((x) & 0xFFu), (uint8_t)(((x) >> 8) & 0xFFu))

static const ble_uuid128_t UUID_SVC_MAIN = DB_UUID128(DB_SVC_MAIN);
static const ble_uuid128_t UUID_CHR_ANGLE = DB_UUID128(DB_CHR_ANGLE);
static const ble_uuid128_t UUID_CHR_CONFIG = DB_UUID128(DB_CHR_CONFIG);
static const ble_uuid128_t UUID_CHR_COMMAND = DB_UUID128(DB_CHR_COMMAND);
static const ble_uuid128_t UUID_CHR_STATUS = DB_UUID128(DB_CHR_STATUS);
static const ble_uuid128_t UUID_SVC_OTA = DB_UUID128(DB_SVC_OTA);
static const ble_uuid128_t UUID_CHR_OTA_CTRL = DB_UUID128(DB_CHR_OTA_CTRL);
static const ble_uuid128_t UUID_CHR_OTA_DATA = DB_UUID128(DB_CHR_OTA_DATA);
static const ble_uuid128_t UUID_SVC_REC = DB_UUID128(DB_SVC_REC);
static const ble_uuid128_t UUID_CHR_REC_CTRL = DB_UUID128(DB_CHR_REC_CTRL);
static const ble_uuid128_t UUID_CHR_REC_STATE = DB_UUID128(DB_CHR_REC_STATE);

typedef enum {
    CHR_DIS_MANUFACTURER,
    CHR_DIS_MODEL,
    CHR_DIS_FW_REV,
    CHR_BAT_LEVEL,
    CHR_ANGLE,
    CHR_CONFIG,
    CHR_COMMAND,
    CHR_STATUS,
    CHR_OTA_CTRL,
    CHR_OTA_DATA,
    CHR_REC_CTRL,
    CHR_REC_STATE,
} chr_id_t;

// ---- state -----------------------------------------------------------------------
//
// GAP events and GATT access run in the NimBLE host task. The notify timer
// and the OTA reply (also from ota_update's esp_timer) run elsewhere, so the
// connection handle, subscriptions and the indication queue sit behind
// s_mux. Everything else is touched by the host task only.

static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;

static uint16_t s_conn = BLE_HS_CONN_HANDLE_NONE;

// Notifications/indications the client has turned on (s_subs).
#define SUB_ANGLE  (1u << 0)
#define SUB_STATUS (1u << 1)
#define SUB_BAT    (1u << 2)
#define SUB_OTA    (1u << 3)
#define SUB_REC    (1u << 4)
static uint8_t s_subs;

typedef struct {
    uint8_t len;
    uint8_t data[IND_MSG_MAX];
} ind_msg_t;
static ind_msg_t s_ind[IND_QUEUE_LEN];
static uint8_t s_ind_head, s_ind_count;
static bool s_ind_busy;   // head is in flight, waiting for the confirmation

static uint16_t s_h_bat, s_h_angle, s_h_status, s_h_ota_ctrl, s_h_rec_state;

static bool s_inited;
static volatile bool s_enabled;
static volatile bool s_stopping;
static bool s_synced;
static uint8_t s_own_addr_type;
static char s_name[24];
static struct ble_npl_event s_enable_ev;
static struct ble_npl_event s_forget_ev;
static struct ble_npl_event s_kick_ev;

// Link state, written by the host task and read by the notify timer.
static volatile bool s_link_trusted;      // encrypted, authenticated, 16-byte key
static volatile bool s_pairing;           // passkey shown, waiting for the peer
static volatile uint32_t s_link_since_ms; // connect or last security change
static volatile uint32_t s_last_write_ms; // last GATT write on this link

static bool s_ota_ok;
static bool s_ota_inited;
static bool s_reboot_armed;
static volatile bool s_reboot_posted;   // APP_CMD_REBOOT sent, backstop running

static esp_timer_handle_t s_notify_timer;
static esp_timer_handle_t s_reboot_timer;

// Notify timer state (esp_timer task only). s_tick_reset (new connection)
// and s_status_resend / s_rec_resend (new subscription) are set by the host
// task.
static volatile bool s_tick_reset;
static volatile bool s_status_resend;
static volatile bool s_rec_resend;
static bool s_rec_sent_valid;
static db_rec_state_packet_t s_rec_sent;
static uint32_t s_rec_sent_ms;
static uint8_t s_angle_seq;
static bool s_status_sent;
static db_status_packet_t s_status_last;
static uint32_t s_status_last_ms;
static int s_bat_last;
static bool s_fast_params;
static bool s_holds_awake;
static volatile bool s_kick_posted;   // also cleared by kick_ev_cb (host task)

// Host task only.
static uint8_t s_wbuf[WRITE_BUF_LEN];
static char s_cfg_json[SETTINGS_JSON_MAX];
static int s_cfg_len = -1;   // -1: rebuild on the next read

static int gap_event(struct ble_gap_event *event, void *arg);

static uint16_t conn_handle(void)
{
    taskENTER_CRITICAL(&s_mux);
    uint16_t c = s_conn;
    taskEXIT_CRITICAL(&s_mux);
    return c;
}

static void link_snapshot(uint16_t *conn, uint8_t *subs)
{
    taskENTER_CRITICAL(&s_mux);
    *conn = s_conn;
    *subs = s_subs;
    taskEXIT_CRITICAL(&s_mux);
}

static bool idle_unauth(uint8_t subs, uint32_t now)
{
    return ble_codec_link_idle_unauth(s_link_trusted, s_pairing, subs != 0, s_link_since_ms, now);
}

// ---- advertising -------------------------------------------------------------------

static void adv_start(bool fast)
{
    if (!s_synced || !s_enabled || s_stopping || conn_handle() != BLE_HS_CONN_HANDLE_NONE) {
        return;
    }
    if (ble_gap_adv_active()) {
        ble_gap_adv_stop();
    }

    struct ble_hs_adv_fields fields = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .name = (const uint8_t *)s_name,
        .name_len = (uint8_t)strlen(s_name),
        .name_is_complete = 1,
    };
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv fields: %d", rc);
        return;
    }

    // The 128-bit UUID (18 bytes) does not fit next to the name.
    struct ble_hs_adv_fields rsp = {
        .uuids128 = &UUID_SVC_MAIN,
        .num_uuids128 = 1,
        .uuids128_is_complete = 1,
    };
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        ESP_LOGE(TAG, "scan rsp fields: %d", rc);
        return;
    }

    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
        .itvl_min = fast ? ADV_FAST_ITVL_MIN : ADV_SLOW_ITVL_MIN,
        .itvl_max = fast ? ADV_FAST_ITVL_MAX : ADV_SLOW_ITVL_MAX,
    };
    rc = ble_gap_adv_start(s_own_addr_type, NULL, fast ? ADV_FAST_MS : BLE_HS_FOREVER, &params,
                           gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv start: %d", rc);
        return;
    }
    ESP_LOGI(TAG, "advertising as %s (%s)", s_name, fast ? "fast" : "slow");
}

// Runs in the host task, so it cannot race the GAP event handler.
static void enable_ev_cb(struct ble_npl_event *ev)
{
    (void)ev;
    uint16_t conn = conn_handle();
    if (s_enabled) {
        adv_start(true);
    } else {
        if (ble_gap_adv_active()) {
            ble_gap_adv_stop();
        }
        if (conn != BLE_HS_CONN_HANDLE_NONE) {
            ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        }
    }
    app_state_set_ble(s_enabled, conn != BLE_HS_CONN_HANDLE_NONE);
}

// Factory reset: forget every bond. Host task, so it cannot race pairing.
static void forget_ev_cb(struct ble_npl_event *ev)
{
    (void)ev;
    uint16_t conn = conn_handle();
    if (conn != BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
    }
    int rc = ble_store_clear();
    if (rc != 0) {
        ESP_LOGE(TAG, "clearing bonds: %d", rc);
    } else {
        ESP_LOGW(TAG, "all bonds deleted");
    }
}

// Posted by the notify timer for an idle unauthenticated link.
static void kick_ev_cb(struct ble_npl_event *ev)
{
    (void)ev;
    uint16_t conn;
    uint8_t subs;
    link_snapshot(&conn, &subs);
    // Checked again here: the link may have changed since the timer posted.
    if (conn != BLE_HS_CONN_HANDLE_NONE && idle_unauth(subs, app_now_ms())) {
        ESP_LOGW(TAG, "dropping idle unauthenticated link");
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
    } else {
        s_kick_posted = false;   // not idle after all: the timer may post again
    }
}

// ---- OTA indications ---------------------------------------------------------------

static void ind_pump(void)
{
    for (;;) {
        ind_msg_t msg;
        uint16_t conn;
        bool subscribed;

        taskENTER_CRITICAL(&s_mux);
        if (s_ind_busy || s_ind_count == 0) {
            taskEXIT_CRITICAL(&s_mux);
            return;
        }
        msg = s_ind[s_ind_head];
        conn = s_conn;
        subscribed = (s_subs & SUB_OTA) != 0;
        s_ind_busy = true;
        taskEXIT_CRITICAL(&s_mux);

        int rc = BLE_HS_ENOTCONN;
        if (conn != BLE_HS_CONN_HANDLE_NONE && subscribed) {
            struct os_mbuf *om = ble_hs_mbuf_from_flat(msg.data, msg.len);
            // ble_gatts_indicate_custom() frees om on every path.
            rc = om ? ble_gatts_indicate_custom(conn, s_h_ota_ctrl, om) : BLE_HS_ENOMEM;
        }
        if (rc == 0) {
            return;   // completes with BLE_GAP_EVENT_NOTIFY_TX
        }
        ESP_LOGW(TAG, "ota reply 0x%02x dropped (%s, rc %d)", msg.data[0],
                 subscribed ? "send failed" : "not subscribed", rc);

        taskENTER_CRITICAL(&s_mux);
        s_ind_head = (uint8_t)((s_ind_head + 1u) % IND_QUEUE_LEN);
        s_ind_count--;
        s_ind_busy = false;
        taskEXIT_CRITICAL(&s_mux);
    }
}

static void ind_complete(void)
{
    taskENTER_CRITICAL(&s_mux);
    if (s_ind_busy && s_ind_count > 0) {
        s_ind_head = (uint8_t)((s_ind_head + 1u) % IND_QUEUE_LEN);
        s_ind_count--;
    }
    s_ind_busy = false;
    taskEXIT_CRITICAL(&s_mux);
    ind_pump();
}

static bool ind_idle(void)
{
    taskENTER_CRITICAL(&s_mux);
    bool idle = !s_ind_busy && s_ind_count == 0;
    taskEXIT_CRITICAL(&s_mux);
    return idle;
}

static void ind_flush(void)
{
    taskENTER_CRITICAL(&s_mux);
    s_ind_head = 0;
    s_ind_count = 0;
    s_ind_busy = false;
    taskEXIT_CRITICAL(&s_mux);
}

// ota_reply_fn. Called with the OTA mutex held, from the host task or the
// OTA esp_timer: only queues, never calls back into ota_update_*.
static void ota_reply(void *ctx, const uint8_t *msg, size_t len)
{
    (void)ctx;
    if (len == 0 || len > IND_MSG_MAX) {
        ESP_LOGE(TAG, "ota reply of %u bytes", (unsigned)len);
        return;
    }
    bool queued = false;
    taskENTER_CRITICAL(&s_mux);
    if (s_ind_count < IND_QUEUE_LEN) {
        ind_msg_t *m = &s_ind[(s_ind_head + s_ind_count) % IND_QUEUE_LEN];
        m->len = (uint8_t)len;
        memcpy(m->data, msg, len);
        s_ind_count++;
        queued = true;
    }
    taskEXIT_CRITICAL(&s_mux);
    if (!queued) {
        // The client recovers through its stall timeout and resend; a lost
        // ACK is sent again for the resent chunks (ota_proto.h).
        ESP_LOGW(TAG, "ota reply queue full, 0x%02x dropped", msg[0]);
        return;
    }
    ind_pump();
}

// ---- reboot after OTA ----------------------------------------------------------------

// esp_timer task. The control task flushes a settings edit made in the
// last second (the OTA overlay hides at DONE, before this fires) and then
// restarts; doing that here would block the timer task on the display lock.
static void reboot_cb(void *arg)
{
    (void)arg;
    if (!s_reboot_posted && app_post_cmd(APP_CMD_REBOOT, 0)) {
        ESP_LOGI(TAG, "rebooting into the new image");
        s_reboot_posted = true;
        esp_timer_start_once(s_reboot_timer, REBOOT_BACKSTOP_US);
        return;
    }
    ESP_LOGW(TAG, "rebooting into the new image (%s)",
             s_reboot_posted ? "control task did not" : "command queue full");
    esp_restart();
}

static void reboot_after(uint64_t delay_us)
{
    if (s_reboot_posted) {
        return;   // the control task is on it; keep the backstop as it is
    }
    esp_timer_stop(s_reboot_timer);   // ESP_ERR_INVALID_STATE if not running
    esp_timer_start_once(s_reboot_timer, delay_us);
}

static void check_reboot(void)
{
    if (s_reboot_armed || !ota_update_reboot_pending()) {
        return;
    }
    s_reboot_armed = true;
    ESP_LOGI(TAG, "OTA done, reboot once the reply is confirmed");
    reboot_after(ind_idle() ? REBOOT_DELAY_US : REBOOT_FALLBACK_US);
}

// ---- notifications -----------------------------------------------------------------

static int notify(uint16_t conn, uint16_t handle, const void *data, size_t len)
{
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, (uint16_t)len);
    if (om == NULL) {
        return BLE_HS_ENOMEM;
    }
    return ble_gatts_notify_custom(conn, handle, om);   // frees om
}

static void set_conn_params(uint16_t conn, bool fast)
{
    // Fast while OTA runs (throughput); relaxed otherwise, still well below
    // the 100 ms angle period. The central has the final say.
    struct ble_gap_upd_params p = {
        .itvl_min = fast ? CONN_FAST_ITVL_MIN : CONN_IDLE_ITVL_MIN,
        .itvl_max = fast ? CONN_FAST_ITVL_MAX : CONN_IDLE_ITVL_MAX,
        .latency = 0,
        .supervision_timeout = CONN_SUPERVISION_TIMEOUT,
    };
    int rc = ble_gap_update_params(conn, &p);
    if (rc != 0) {
        ESP_LOGW(TAG, "conn param update: %d", rc);
    }
}

// Whether the link should keep the device out of deep sleep, and whether an
// idle unauthenticated link should be dropped (link policy, ble_codec.h).
static void link_policy(uint8_t subs, uint32_t now)
{
    bool holds = ble_codec_link_holds_awake(s_link_trusted, s_last_write_ms, now);
    if (holds != s_holds_awake) {
        s_holds_awake = holds;
        app_state_set_ble_holds_awake(holds);
    }
    if (!s_kick_posted && idle_unauth(subs, now)) {
        s_kick_posted = true;
        ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &s_kick_ev);
    }
}

// rec_state as it is now, for the read and the notification.
static void rec_state_packet(db_rec_state_packet_t *p)
{
    recorder_refresh();
    app_snapshot_t snap;
    app_state_get(&snap);
    ble_codec_rec_state(&snap.rec, p);
}

// rec_state carries the Wi-Fi credentials and the stream token: notified
// to an authenticated link only. On every recorder change (REC_ACT_NOTIFY,
// or a field ble_codec_rec_state_changed() looks at) and once a second
// during a take, computed when it is sent so elapsed_s is this second's.
static void notify_rec(uint16_t conn, uint8_t subs, uint32_t now)
{
    bool pending = recorder_take_notify();
    if (s_rec_resend) {
        s_rec_resend = false;
        s_rec_sent_valid = false;
    }
    if (!s_link_trusted || !(subs & SUB_REC)) {
        s_rec_sent_valid = false;
        return;
    }
    db_rec_state_packet_t p;
    rec_state_packet(&p);
    bool take = p.state == DB_REC_STATE_RECORDING || p.state == DB_REC_STATE_DRAINING;
    if (!pending && s_rec_sent_valid && !ble_codec_rec_state_changed(&s_rec_sent, &p) &&
        !(take && now - s_rec_sent_ms >= REC_HEARTBEAT_MS)) {
        return;
    }
    if (notify(conn, s_h_rec_state, &p, sizeof(p)) == 0) {
        s_rec_sent = p;
        s_rec_sent_valid = true;
        s_rec_sent_ms = now;
    }
}

static void notify_tick(void *arg)
{
    (void)arg;
    if (s_tick_reset) {
        s_tick_reset = false;
        s_fast_params = false;
        s_status_resend = true;
        s_rec_resend = true;
        s_holds_awake = false;
        s_kick_posted = false;
    }
    if (s_status_resend) {
        s_status_resend = false;
        s_status_sent = false;
        s_bat_last = -1;
    }

    uint16_t conn;
    uint8_t subs;
    link_snapshot(&conn, &subs);
    if (conn == BLE_HS_CONN_HANDLE_NONE) {
        return;
    }

    app_snapshot_t snap;
    app_state_get(&snap);
    uint32_t now = app_now_ms();
    link_policy(subs, now);

    // Angles would only compete with the image data during OTA.
    if ((subs & SUB_ANGLE) && !snap.ota_active) {
        db_angle_packet_t a;
        ble_codec_angle(&snap, s_angle_seq, now, &a);
        if (notify(conn, s_h_angle, &a, sizeof(a)) == 0) {
            s_angle_seq++;
        }
    }

    if (subs & SUB_STATUS) {
        db_status_packet_t st;
        ble_codec_status(&snap, &st);
        if (!s_status_sent || ble_codec_status_changed(&s_status_last, &st) ||
            now - s_status_last_ms >= STATUS_HEARTBEAT_MS) {
            if (notify(conn, s_h_status, &st, sizeof(st)) == 0) {
                s_status_last = st;
                s_status_last_ms = now;
                s_status_sent = true;
            }
        }
    }

    // Only valid readings are sent: an invalid one keeps the last value.
    uint8_t pct = ble_codec_battery_pct(&snap.battery);
    if ((subs & SUB_BAT) && snap.battery.valid && (int)pct != s_bat_last) {
        if (notify(conn, s_h_bat, &pct, 1) == 0) {
            s_bat_last = pct;
        }
    }

    notify_rec(conn, subs, now);

    if (snap.ota_active != s_fast_params) {
        s_fast_params = snap.ota_active;
        set_conn_params(conn, s_fast_params);
    }
}

// ---- GATT access -------------------------------------------------------------------

static int append(struct ble_gatt_access_ctxt *ctxt, const void *data, size_t len)
{
    return os_mbuf_append(ctxt->om, data, (uint16_t)len) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

// Flattens a write into s_wbuf. Returns the length, or -1 if it is empty or
// too long.
static int flatten_write(struct ble_gatt_access_ctxt *ctxt)
{
    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0 || len > sizeof(s_wbuf)) {
        return -1;
    }
    uint16_t out = 0;
    if (ble_hs_mbuf_to_flat(ctxt->om, s_wbuf, sizeof(s_wbuf), &out) != 0) {
        return -1;
    }
    return out;
}

static int config_read(struct ble_gatt_access_ctxt *ctxt)
{
    // Captured at offset 0 and reused for the read blobs that follow, so a
    // long read sees one consistent JSON document.
    if (ctxt->offset == 0 || s_cfg_len < 0) {
        settings_t s;
        settings_get(&s);
        s_cfg_len = settings_to_json(&s, s_cfg_json, sizeof(s_cfg_json));
        if (s_cfg_len < 0) {
            ESP_LOGE(TAG, "settings JSON does not fit");
            return BLE_ATT_ERR_UNLIKELY;
        }
    }
    return append(ctxt, s_cfg_json, (size_t)s_cfg_len);
}

typedef struct {
    size_t len;
    settings_json_result_t result;
} json_write_t;

// settings_update() callback: the JSON goes onto the current settings under
// the store mutex, so a calibration stored at the same moment is kept. The
// cJSON parse of at most WRITE_BUF_LEN bytes is short next to the NVS write.
static bool apply_json(settings_t *s, void *ctx)
{
    json_write_t *w = ctx;
    w->result = settings_apply_json(s, (const char *)s_wbuf, w->len);
    return w->result == SETTINGS_JSON_OK;
}

static int config_write(struct ble_gatt_access_ctxt *ctxt)
{
    int len = flatten_write(ctxt);
    if (len < 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    // OK until apply_json says otherwise: if the store is not usable it
    // never runs, and the error below is a store error.
    json_write_t w = {.len = (size_t)len, .result = SETTINGS_JSON_OK};
    esp_err_t err = settings_update(apply_json, &w);
    uint8_t att = ble_codec_settings_att_err(w.result);
    if (att != 0) {
        ESP_LOGW(TAG, "config write rejected: 0x%02x", att);
        return att;
    }
    s_cfg_len = -1;
    if (err == ESP_ERR_INVALID_ARG) {
        return DB_ATT_ERR_JSON_RANGE;
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "settings_update: %s", esp_err_to_name(err));
        return DB_ATT_ERR_STORE;
    }
    return 0;
}

static int rec_ctrl_write(struct ble_gatt_access_ctxt *ctxt)
{
    int len = flatten_write(ctxt);
    if (len < 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    rec_cmd_t cmd;
    uint8_t att = ble_codec_parse_rec(s_wbuf, (size_t)len, &cmd);
    if (att != 0) {
        ESP_LOGW(TAG, "rec_ctrl 0x%02x rejected: 0x%02x", s_wbuf[0], att);
        return att;
    }
    // Answered once the recorder has taken it (OPEN: once the stream ring is
    // there; the AP comes up afterwards, rec_state says when).
    rec_err_t err;
    if (!recorder_post(&cmd, &err)) {
        ESP_LOGW(TAG, "rec_ctrl 0x%02x: recorder busy", s_wbuf[0]);
        return DB_ATT_ERR_BUSY;
    }
    att = ble_codec_rec_att_err(err);
    if (att != 0) {
        ESP_LOGW(TAG, "rec_ctrl 0x%02x refused: 0x%02x", s_wbuf[0], att);
    } else {
        ESP_LOGI(TAG, "rec_ctrl 0x%02x", s_wbuf[0]);
    }
    return att;
}

static int command_write(struct ble_gatt_access_ctxt *ctxt)
{
    int len = flatten_write(ctxt);
    if (len < 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    app_cmd_id_t id;
    int32_t arg;
    uint8_t att = ble_codec_parse_cmd(s_wbuf, (size_t)len, &id, &arg);
    if (att != 0) {
        ESP_LOGW(TAG, "command 0x%02x rejected: 0x%02x", s_wbuf[0], att);
        return att;
    }
    // A calibration start while OTA or a recording session has the device:
    // refused here, where the client sees it.
    app_snapshot_t snap;
    app_state_get(&snap);
    att = ble_codec_cmd_excl_err(id, snap.excl);
    if (att != 0) {
        ESP_LOGW(TAG, "command 0x%02x refused: %s", s_wbuf[0], app_excl_what(snap.excl));
        return att;
    }
    if (!app_post_cmd(id, arg)) {
        return DB_ATT_ERR_BUSY;
    }
    ESP_LOGI(TAG, "command 0x%02x", s_wbuf[0]);
    return 0;
}

// NimBLE answers operations the characteristic flags do not allow itself
// (READ/WRITE_NOT_PERMITTED), so each switch only lists what it can get.
static int gatt_read(chr_id_t id, struct ble_gatt_access_ctxt *ctxt)
{
    switch (id) {
    case CHR_DIS_MANUFACTURER:
        return append(ctxt, DIS_MANUFACTURER, strlen(DIS_MANUFACTURER));
    case CHR_DIS_MODEL:
        return append(ctxt, DIS_MODEL, strlen(DIS_MODEL));
    case CHR_DIS_FW_REV: {
        const char *ver = esp_app_get_description()->version;
        return append(ctxt, ver, strnlen(ver, sizeof(esp_app_get_description()->version)));
    }
    case CHR_BAT_LEVEL: {
        app_snapshot_t snap;
        app_state_get(&snap);
        uint8_t pct = ble_codec_battery_pct(&snap.battery);
        return append(ctxt, &pct, 1);
    }
    case CHR_STATUS: {
        app_snapshot_t snap;
        app_state_get(&snap);
        db_status_packet_t p;
        ble_codec_status(&snap, &p);
        return append(ctxt, &p, sizeof(p));
    }
    case CHR_CONFIG:
        return config_read(ctxt);
    case CHR_REC_STATE: {
        // NimBLE has checked the link (SEC_READ, 16-byte key).
        db_rec_state_packet_t p;
        rec_state_packet(&p);
        return append(ctxt, &p, sizeof(p));
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static int gatt_write(chr_id_t id, struct ble_gatt_access_ctxt *ctxt)
{
    switch (id) {
    case CHR_CONFIG:
        return config_write(ctxt);
    case CHR_COMMAND:
        return command_write(ctxt);
    case CHR_REC_CTRL:
        return rec_ctrl_write(ctxt);
    case CHR_OTA_CTRL:
    case CHR_OTA_DATA: {
        if (!s_ota_ok) {
            return DB_ATT_ERR_UNAVAILABLE;
        }
        // s_wbuf holds the largest attribute value, so every write reaches
        // ota_proto as sent and it answers bad lengths itself.
        int len = flatten_write(ctxt);
        if (len < 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        // TODO(hw): check on a real link that NimBLE's ACL/msys buffers absorb
        // the 2 * ack_every chunks in flight while esp_ota_write() erases a
        // 4 KB sector in this task (tens of ms); lost chunks only cost a
        // NACK and resend, but many would slow the transfer down.
        if (id == CHR_OTA_CTRL) {
            ota_update_on_ctrl(s_wbuf, (size_t)len);
            check_reboot();
        } else {
            ota_update_on_data(s_wbuf, (size_t)len);
        }
        return 0;
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static int gatt_access(uint16_t conn, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt,
                       void *arg)
{
    (void)conn;
    (void)attr_handle;
    chr_id_t id = (chr_id_t)(uintptr_t)arg;
    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR:
        return gatt_read(id, ctxt);
    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        // Every write is user activity, OTA chunks included.
        app_note_activity();
        s_last_write_ms = app_now_ms();
        return gatt_write(id, ctxt);
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

#define CHR_ARG(id) ((void *)(uintptr_t)(id))
#define SEC_WRITE   (BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN)
#define SEC_READ    (BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_READ_AUTHEN)
// The key size check only applies to the operations that need security, so
// the open reads stay open.

static const struct ble_gatt_svc_def s_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x180A),   // Device Information
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = BLE_UUID16_DECLARE(0x2A29), .access_cb = gatt_access,
             .arg = CHR_ARG(CHR_DIS_MANUFACTURER), .flags = BLE_GATT_CHR_F_READ},
            {.uuid = BLE_UUID16_DECLARE(0x2A24), .access_cb = gatt_access,
             .arg = CHR_ARG(CHR_DIS_MODEL), .flags = BLE_GATT_CHR_F_READ},
            {.uuid = BLE_UUID16_DECLARE(0x2A26), .access_cb = gatt_access,
             .arg = CHR_ARG(CHR_DIS_FW_REV), .flags = BLE_GATT_CHR_F_READ},
            {0},
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x180F),   // Battery
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = BLE_UUID16_DECLARE(0x2A19), .access_cb = gatt_access,
             .arg = CHR_ARG(CHR_BAT_LEVEL), .val_handle = &s_h_bat,
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY},
            {0},
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC_MAIN.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &UUID_CHR_ANGLE.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_ANGLE),
             .val_handle = &s_h_angle, .flags = BLE_GATT_CHR_F_NOTIFY},
            {.uuid = &UUID_CHR_CONFIG.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_CONFIG),
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | SEC_WRITE,
             .min_key_size = SEC_KEY_SIZE},
            {.uuid = &UUID_CHR_COMMAND.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_COMMAND),
             .flags = BLE_GATT_CHR_F_WRITE | SEC_WRITE, .min_key_size = SEC_KEY_SIZE},
            {.uuid = &UUID_CHR_STATUS.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_STATUS),
             .val_handle = &s_h_status, .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY},
            {0},
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC_OTA.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &UUID_CHR_OTA_CTRL.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_OTA_CTRL),
             .val_handle = &s_h_ota_ctrl,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_INDICATE | SEC_WRITE,
             .min_key_size = SEC_KEY_SIZE},
            {.uuid = &UUID_CHR_OTA_DATA.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_OTA_DATA),
             .flags = BLE_GATT_CHR_F_WRITE_NO_RSP | SEC_WRITE, .min_key_size = SEC_KEY_SIZE},
            {0},
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC_REC.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &UUID_CHR_REC_CTRL.u, .access_cb = gatt_access, .arg = CHR_ARG(CHR_REC_CTRL),
             .flags = BLE_GATT_CHR_F_WRITE | SEC_WRITE, .min_key_size = SEC_KEY_SIZE},
            // The credentials: authenticated reads only, notified to such a
            // link only (notify_rec()).
            {.uuid = &UUID_CHR_REC_STATE.u, .access_cb = gatt_access,
             .arg = CHR_ARG(CHR_REC_STATE), .val_handle = &s_h_rec_state,
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | SEC_READ,
             .min_key_size = SEC_KEY_SIZE},
            {0},
        },
    },
    {0},
};

// ---- GAP ---------------------------------------------------------------------------

static void clear_passkey(void)
{
    app_state_set_passkey(false, 0);
}

static uint32_t random_passkey(void)
{
    // Rejection sampling: a plain modulo would favour low passkeys slightly.
    const uint32_t limit = UINT32_MAX - (UINT32_MAX % 1000000u);
    uint32_t r;
    do {
        r = esp_random();
    } while (r >= limit);
    return r % 1000000u;
}

// Per-link state back to "new link" (conn) or "no link"
// (BLE_HS_CONN_HANDLE_NONE): connect, disconnect and shutdown.
static void link_reset(uint16_t conn)
{
    taskENTER_CRITICAL(&s_mux);
    s_conn = conn;
    s_subs = 0;
    taskEXIT_CRITICAL(&s_mux);
    ind_flush();
    clear_passkey();
    s_cfg_len = -1;
    s_link_trusted = false;
    s_pairing = false;
    app_state_set_ble_holds_awake(false);
}

static void on_connect(uint16_t conn)
{
    link_reset(conn);
    s_link_since_ms = app_now_ms();
    s_last_write_ms = s_link_since_ms - BLE_CODEC_AWAKE_AFTER_WRITE_MS;   // no write yet
    ESP_LOGI(TAG, "connected, handle %u", conn);

    if (!s_enabled) {
        // Disabled while the connection was being set up.
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    // Connected, but not yet holding the device awake (link_policy()).
    app_state_set_ble(true, true);
    app_note_activity();

    // A peripheral does not start the MTU exchange, and a central that
    // never does stays at 23: OTA must not announce the previous link's
    // chunk size. BLE_GAP_EVENT_MTU raises it again.
    ota_update_set_mtu(ble_att_mtu(conn));   // 0 if the link is gone: counts as 23

#if CONFIG_BT_NIMBLE_LL_CFG_FEAT_LE_2M_PHY
    // Doubles OTA throughput where the central supports it; otherwise the
    // link stays on 1M.
    int rc = ble_gap_set_prefered_le_phy(conn, BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK,
                                         BLE_GAP_LE_PHY_CODED_ANY);
    if (rc != 0) {
        ESP_LOGW(TAG, "2M PHY request: %d", rc);
    }
#endif
    s_tick_reset = true;
    esp_timer_stop(s_notify_timer);
    esp_timer_start_periodic(s_notify_timer, NOTIFY_PERIOD_US);
}

static void on_disconnect(int reason)
{
    ESP_LOGI(TAG, "disconnected, reason 0x%x", reason);
    esp_timer_stop(s_notify_timer);
    link_reset(BLE_HS_CONN_HANDLE_NONE);
    if (s_ota_ok) {
        ota_update_on_disconnect();
    }
    app_state_set_ble(s_enabled, false);
    // A calibration this client started has nobody left to finish it; the
    // control task cancels it only if it was started over BLE.
    if (!app_post_cmd(APP_CMD_CAL_CANCEL, APP_CMD_ARG_FROM_BLE)) {
        ESP_LOGW(TAG, "command queue full, BLE calibration not cancelled");
    }
    recorder_note_ble(false);   // a take goes on for REC_BLE_GRACE_MS

    if (s_reboot_armed) {
        reboot_after(REBOOT_DELAY_US);
        return;
    }
    adv_start(true);
}

static void on_subscribe(const struct ble_gap_event *event)
{
    uint16_t h = event->subscribe.attr_handle;
    uint8_t bit = 0;
    bool on = event->subscribe.cur_notify;
    if (h == s_h_angle) {
        bit = SUB_ANGLE;
    } else if (h == s_h_status) {
        bit = SUB_STATUS;
    } else if (h == s_h_bat) {
        bit = SUB_BAT;
    } else if (h == s_h_ota_ctrl) {
        bit = SUB_OTA;
        on = event->subscribe.cur_indicate;
    } else if (h == s_h_rec_state) {
        bit = SUB_REC;
    }
    taskENTER_CRITICAL(&s_mux);
    s_subs = on ? (uint8_t)(s_subs | bit) : (uint8_t)(s_subs & ~bit);
    taskEXIT_CRITICAL(&s_mux);
    if (h == s_h_status || h == s_h_bat) {
        // Send the current value at once instead of on the next change.
        s_status_resend = true;
    }
    if (h == s_h_rec_state) {
        s_rec_resend = true;
    }
}

static bool sec_ok(const struct ble_gap_sec_state *st)
{
    return st->encrypted && st->authenticated && st->key_size >= SEC_KEY_SIZE;
}

// Only passkey-authenticated, full-strength bonds are kept. NimBLE bonds a
// Just Works pairing too (sm_mitm only sets our AuthReq flag), and such
// bonds would take store slots for nothing: they cannot write anything.
static void on_enc_change(uint16_t conn, int status)
{
    struct ble_gap_conn_desc d;
    if (status != 0 || ble_gap_conn_find(conn, &d) != 0) {
        s_link_since_ms = app_now_ms();   // a failed pairing gets a new minute
        return;
    }
    if (sec_ok(&d.sec_state)) {
        s_link_trusted = true;
        s_last_write_ms = app_now_ms();   // pairing counts as activity
        app_note_activity();
        recorder_note_ble(true);   // the app (an authenticated link) is back
        return;
    }
    s_link_since_ms = app_now_ms();
    if (d.sec_state.bonded) {
        ESP_LOGW(TAG, "unauthenticated bond (key %u bytes) deleted", d.sec_state.key_size);
        ble_store_util_delete_peer(&d.peer_id_addr);
    }
}

// Bond store full: NimBLE's round-robin policy would evict the oldest bond,
// usually the owner's, for any new pairing. Only a passkey-authenticated
// full-strength bond may evict one.
static int store_status(struct ble_store_status_event *event, void *arg)
{
    if (event->event_code == BLE_STORE_EVENT_OVERFLOW &&
        (event->overflow.obj_type == BLE_STORE_OBJ_TYPE_OUR_SEC ||
         event->overflow.obj_type == BLE_STORE_OBJ_TYPE_PEER_SEC)) {
        const struct ble_store_value_sec *v = &event->overflow.value->sec;
        if (!v->authenticated || v->key_size < SEC_KEY_SIZE) {
            ESP_LOGW(TAG, "bond store full: unauthenticated bond refused");
            return BLE_HS_ESTORE_CAP;
        }
    }
    return ble_store_util_status_rr(event, arg);
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            on_connect(event->connect.conn_handle);
        } else {
            ESP_LOGW(TAG, "connect failed: %d", event->connect.status);
            adv_start(true);
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        on_disconnect(event->disconnect.reason);
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        // The fast phase timed out: carry on slowly. Other reasons (connect,
        // explicit stop) need nothing.
        if (event->adv_complete.reason == BLE_HS_ETIMEOUT) {
            adv_start(false);
        }
        return 0;

    case BLE_GAP_EVENT_CONN_UPDATE: {
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(event->conn_update.conn_handle, &d) == 0) {
            ESP_LOGI(TAG, "conn params: itvl %u x 1.25 ms, latency %u, timeout %u x 10 ms",
                     d.conn_itvl, d.conn_latency, d.supervision_timeout);
        }
        return 0;
    }

    case BLE_GAP_EVENT_PASSKEY_ACTION:
        if (event->passkey.params.action == BLE_SM_IOACT_DISP) {
            struct ble_sm_io io = {.action = BLE_SM_IOACT_DISP, .passkey = random_passkey()};
            app_state_set_passkey(true, io.passkey);
            int rc = ble_sm_inject_io(event->passkey.conn_handle, &io);
            if (rc != 0) {
                ESP_LOGE(TAG, "passkey inject: %d", rc);
                clear_passkey();
            } else {
                s_pairing = true;
                ESP_LOGI(TAG, "pairing: showing passkey");
            }
        } else {
            ESP_LOGW(TAG, "unsupported passkey action %d", event->passkey.params.action);
        }
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        clear_passkey();
        s_pairing = false;
        ESP_LOGI(TAG, "encryption %s (%d)", event->enc_change.status == 0 ? "on" : "failed",
                 event->enc_change.status);
        on_enc_change(event->enc_change.conn_handle, event->enc_change.status);
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        // The peer lost its bond (e.g. "forget device" on the phone): drop
        // ours and pair again, but only for a pairing that will be
        // authenticated with the passkey and a full-size key. Otherwise a
        // Just Works request spoofing the owner's address would delete the
        // owner's bond without ever seeing the passkey.
        // TODO(hw): the old bond is still deleted before the new pairing
        // has succeeded (NimBLE offers no way to keep it); check with a
        // phone that a failed passkey entry only costs a re-pair.
        const struct ble_gap_repeat_pairing *rp = &event->repeat_pairing;
        if (!rp->new_authenticated || rp->new_key_size < SEC_KEY_SIZE) {
            ESP_LOGW(TAG, "repeat pairing without passkey refused, bond kept");
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(rp->conn_handle, &d) == 0) {
            ble_store_util_delete_peer(&d.peer_id_addr);
        }
        ESP_LOGI(TAG, "repeat pairing: old bond deleted");
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }

    case BLE_GAP_EVENT_SUBSCRIBE:
        on_subscribe(event);
        return 0;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU %u", event->mtu.value);
        ota_update_set_mtu(event->mtu.value);
        return 0;

    case BLE_GAP_EVENT_NOTIFY_TX:
        if (event->notify_tx.indication && event->notify_tx.attr_handle == s_h_ota_ctrl &&
            (event->notify_tx.status == BLE_HS_EDONE ||
             event->notify_tx.status == BLE_HS_ETIMEOUT)) {
            ind_complete();
            if (s_reboot_armed && ind_idle()) {
                reboot_after(REBOOT_DELAY_US);
            }
        }
        return 0;

    case BLE_GAP_EVENT_DATA_LEN_CHG:
        ESP_LOGI(TAG, "data length tx %u rx %u", event->data_len_chg.max_tx_octets,
                 event->data_len_chg.max_rx_octets);
        return 0;

    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
        ESP_LOGI(TAG, "PHY tx %u rx %u", event->phy_updated.tx_phy, event->phy_updated.rx_phy);
        return 0;

    default:
        return 0;
    }
}

// ---- host --------------------------------------------------------------------------

static void on_reset(int reason)
{
    s_synced = false;
    ESP_LOGW(TAG, "host reset, reason %d", reason);
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) {
        rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "no usable address: %d", rc);
        return;
    }
    // Full 251-byte LL packets so a 247-byte MTU is not split into 27-byte
    // fragments. The controller default, not per link: the S3 controller never
    // answered an LE Set Data Length sent from the connect event.
    rc = ble_gap_write_sugg_def_data_len(251, 2120);
    if (rc != 0) {
        ESP_LOGW(TAG, "default data length: %d", rc);
    }
    s_synced = true;
    adv_start(true);
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();   // returns after nimble_port_stop()
    nimble_port_freertos_deinit();
}

static esp_err_t create_timers(void)
{
    if (s_notify_timer == NULL) {
        const esp_timer_create_args_t a = {.callback = notify_tick, .name = "ble_notify"};
        ESP_RETURN_ON_ERROR(esp_timer_create(&a, &s_notify_timer), TAG, "notify timer");
    }
    if (s_reboot_timer == NULL) {
        const esp_timer_create_args_t a = {.callback = reboot_cb, .name = "ble_reboot"};
        ESP_RETURN_ON_ERROR(esp_timer_create(&a, &s_reboot_timer), TAG, "reboot timer");
    }
    return ESP_OK;
}

esp_err_t ble_service_init(bool enabled)
{
    if (s_inited) {
        return ESP_ERR_INVALID_STATE;
    }
    // Set even if init fails, so ble_service_set_enabled() with the same
    // value stays a no-op.
    s_enabled = enabled;
    // The recorder's LEVEL records carry the angle packet's fields.
    recorder_set_level_encoder(ble_codec_level);
    ESP_RETURN_ON_ERROR(create_timers(), TAG, "timers");

    uint8_t mac[6];
    ESP_RETURN_ON_ERROR(esp_read_mac(mac, ESP_MAC_BT), TAG, "mac");
    snprintf(s_name, sizeof(s_name), "DrillBalancer-%02X%02X", mac[4], mac[5]);

    s_stopping = false;
    s_synced = false;
    s_conn = BLE_HS_CONN_HANDLE_NONE;
    ind_flush();

    // Needs NVS initialised (settings_store_init) for the bond store.
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init: %s", esp_err_to_name(err));
        return err;
    }

    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = store_status;   // full: only a passkey bond evicts the oldest
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_bonding = 1;
    // ID keys too, so a phone's resolvable private address maps to its bond.
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    int rc = ble_gatts_count_cfg(s_svcs);
    if (rc == 0) {
        rc = ble_gatts_add_svcs(s_svcs);
    }
    if (rc == 0) {
        rc = ble_svc_gap_device_name_set(s_name);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "GATT setup: %d", rc);
        nimble_port_deinit();
        return ESP_FAIL;
    }
    ble_store_config_init();

    if (!s_ota_inited) {
        err = ota_update_init(ota_reply, NULL);
        s_ota_inited = true;
        s_ota_ok = err == ESP_OK;
        if (!s_ota_ok) {
            ESP_LOGE(TAG, "OTA unavailable: %s", esp_err_to_name(err));
        }
    }

    ble_npl_event_init(&s_enable_ev, enable_ev_cb, NULL);
    ble_npl_event_init(&s_forget_ev, forget_ev_cb, NULL);
    ble_npl_event_init(&s_kick_ev, kick_ev_cb, NULL);
    s_inited = true;
    app_state_set_ble(enabled, false);
    // TODO(hw): check the host task's stack high-water mark after a config
    // write (cJSON + NVS) and a full OTA (esp_ota_write, SHA-256); the
    // 6144 bytes in sdkconfig.d/ble.defaults are an estimate.
    nimble_port_freertos_init(host_task);
    ESP_LOGI(TAG, "init, %s", enabled ? "enabled" : "disabled");
    return ESP_OK;
}

esp_err_t ble_service_set_enabled(bool enabled)
{
    // Called on every settings reload: an unchanged state must not restart
    // fast advertising.
    if (enabled == s_enabled) {
        return ESP_OK;
    }
    if (!s_inited) {
        return ESP_ERR_INVALID_STATE;
    }
    s_enabled = enabled;
    // Applied in the host task, serialised with the GAP events.
    ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &s_enable_ev);
    return ESP_OK;
}

void ble_service_shutdown(void)
{
    if (!s_inited) {
        return;
    }
    s_enabled = false;
    s_stopping = true;
    esp_timer_stop(s_notify_timer);
    ble_gap_adv_stop();

    // Terminates the connection (OTA is aborted through the disconnect
    // event) and waits for the host task to stop.
    int rc = nimble_port_stop();
    if (rc != 0) {
        ESP_LOGE(TAG, "nimble_port_stop: %d", rc);
    }
    ble_npl_event_deinit(&s_enable_ev);
    ble_npl_event_deinit(&s_forget_ev);
    ble_npl_event_deinit(&s_kick_ev);
    if (rc == 0) {
        nimble_port_deinit();
    }
    link_reset(BLE_HS_CONN_HANDLE_NONE);
    s_synced = false;
    s_inited = false;
    app_state_set_ble(false, false);
    ESP_LOGI(TAG, "stopped");
}

esp_err_t ble_service_clear_bonds(void)
{
    if (s_inited) {
        // In the host task: terminates the link and clears the store.
        ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &s_forget_ev);
        return ESP_OK;
    }
    // Host not running (init failed): erase NimBLE's namespace directly.
    nvs_handle_t h;
    esp_err_t err = nvs_open(NIMBLE_BOND_NVS_NAMESPACE, NVS_READWRITE, &h);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return ESP_OK;   // nothing stored
    }
    if (err != ESP_OK) {
        return err;
    }
    err = nvs_erase_all(h);
    if (err == ESP_OK) {
        err = nvs_commit(h);
    }
    nvs_close(h);
    return err;
}
