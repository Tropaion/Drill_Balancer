#include <errno.h>
#include <string.h>
#include "rec_glue.h"

#include "esp_check.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lwip/sockets.h"

static const char *TAG = "rec_wifi";

// Soft-AP for one phone: WPA2-PSK with a fresh passphrase per session (PMF
// offered, not required, so older phones still join), RAM storage only.
// TODO(hw): channel 6 is a guess; check the throughput and the BLE angle
// stream next to the AP on a busy 2.4 GHz band (bring-up step 33), and pick
// the channel at OPEN if one is often worse.
#define AP_CHANNEL         6
#define AP_MAX_CONN        1
// A phone that vanishes without a deauth (out of range, Wi-Fi switched off)
// would hold the only station slot for the default 300 s and the socket
// until the next send stalls: TCP keepalive finds it within about 11 s of
// silence, and its probes' ACKs keep a present phone active for the AP's
// inactivity timeout (at least 10 s, esp_wifi_set_inactive_time()).
// TODO(hw): check both with a phone switched to flight mode in READY and
// one in power save left idle for minutes (bring-up step 32).
#define AP_INACTIVE_S      30
#define KEEPALIVE_IDLE_S   5
#define KEEPALIVE_INTVL_S  2
#define KEEPALIVE_COUNT    3

// rec_tx: Wi-Fi's owner (it carries out rec_wifi_start() and
// rec_wifi_stop(), so nobody else waits for them) and the stream's sender;
// blocks in send(), below the sensor task and the BT controller.
// TODO(hw): check uxTaskGetStackHighWaterMark() after a long take and a
// session close (bring-up step 26).
#define TX_TASK_PRIO       4
#define TX_TASK_CORE       0
#define TX_TASK_STACK      4096
// At most one send()'s worth peeked at a time (rec_ring.h): four MSS, well
// below CONFIG_LWIP_TCP_SND_BUF_DEFAULT (sdkconfig.d/recorder.defaults), so
// a drain-limit flush still finds whole records to drop.
#define TX_CHUNK           (4u * (unsigned)CONFIG_LWIP_TCP_MSS)
// The sender wakes once a send()'s worth is queued, else on its poll: with
// Nagle on (no TCP_NODELAY) that makes MSS-sized segments, not one per
// record (~150 records/s during a take). rec_wifi_push() sends END at once.
#define TX_KICK_BYTES      TX_CHUNK
#define TX_POLL_MS         20u
#define SEND_TIMEOUT_MS    500u
#define SEND_STALL_MS      10000u      // a send blocked this long: CLIENT_LOST
#define HELLO_TIMEOUT_MS   5000u
#define LINGER_POLL_MS     50u
_Static_assert(SEND_TIMEOUT_MS <= 500u, "REC_WIFI_STOP_MAX_MS counts a blocked send as 500 ms");

static rec_ring_t *s_ring;
static TaskHandle_t s_tx;
static esp_netif_t *s_netif;
static esp_event_handler_instance_t s_ev;
static bool s_stack_up;             // esp_netif_init + the default event loop
static bool s_wifi_inited;

// Under s_cli_mux: the client (rec_tx accepts and drops it, the rec task
// serves it), so rec_wifi_serve() of a client that went cannot serve the
// next one (accepted with s_serving false and a new number); the rec task's
// requests, which rec_tx takes in order; the credentials of the AP that is
// up (rec_tx writes them, so it reads them without the lock); the push
// position.
static portMUX_TYPE s_cli_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool s_client;      // HELLO passed, socket open
static volatile bool s_serving;     // rec_wifi_serve() for this client
static uint32_t s_client_id;        // numbers the accepted clients
static volatile bool s_start_req;
static volatile bool s_stop_req;    // also ends a HELLO wait or a send loop
static uint32_t s_drain_ms;
static rec_wifi_info_t s_info;
static uint64_t s_push_until;       // rec_wifi_push(): NODELAY up to this sent_bytes, 0 none

static volatile bool s_run;         // started .. torn down (rec_tx writes it)
static volatile bool s_ap_up;       // WIFI_EVENT_AP_START .. AP_STOP
static volatile bool s_sta_left;

// rec_tx only
static int s_listen = -1;
static int s_sock = -1;
static bool s_nodelay;              // TCP_NODELAY on s_sock
static bool s_welcomed;             // HELLO_OK went out on s_sock

static void close_fd(int *fd)
{
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

// The socket goes first: rec_ring_reset() (CLIENT_LOST) must not overwrite
// bytes a send() still reads.
static void drop_client(const char *why, bool report)
{
    taskENTER_CRITICAL(&s_cli_mux);
    bool was = s_client;
    s_serving = false;
    s_client = false;
    taskEXIT_CRITICAL(&s_cli_mux);
    close_fd(&s_sock);
    if (was && report) {
        ESP_LOGW(TAG, "stream client lost: %s", why);
        rec_glue_input(REC_IN_CLIENT_LOST);
    }
}

static bool open_server(void)
{
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (fd < 0) {
        ESP_LOGE(TAG, "socket: errno %d", errno);
        return false;
    }
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    struct sockaddr_in a = {
        .sin_family = AF_INET,
        .sin_port = htons(REC_STREAM_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) != 0 || listen(fd, 1) != 0) {
        ESP_LOGE(TAG, "listen on %u: errno %d", (unsigned)REC_STREAM_PORT, errno);
        close(fd);
        return false;
    }
    s_listen = fd;
    ESP_LOGI(TAG, "stream server on port %u", (unsigned)REC_STREAM_PORT);
    return true;
}

static bool recv_all(int fd, uint8_t *buf, size_t len, uint32_t timeout_ms)
{
    const struct timeval tv = {.tv_sec = 0, .tv_usec = 100 * 1000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int64_t until = esp_timer_get_time() + (int64_t)timeout_ms * 1000;
    size_t got = 0;
    while (got < len && !s_stop_req && esp_timer_get_time() < until) {
        int r = recv(fd, buf + got, len - got, 0);
        if (r > 0) {
            got += (size_t)r;
        } else if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) {
            return false;
        }
    }
    return got == len;
}

// One client at a time: HELLO (token + version) within HELLO_TIMEOUT_MS,
// else it is closed.
static void accept_client(void)
{
    fd_set r;
    FD_ZERO(&r);
    FD_SET(s_listen, &r);
    struct timeval tv = {.tv_sec = 0, .tv_usec = TX_POLL_MS * 1000};
    if (select(s_listen + 1, &r, NULL, NULL, &tv) <= 0) {
        return;
    }
    int fd = accept(s_listen, NULL, NULL);
    if (fd < 0) {
        return;
    }
    uint8_t hello[REC_HELLO_LEN];
    if (!recv_all(fd, hello, sizeof(hello), HELLO_TIMEOUT_MS) ||
        !rec_hello_ok(hello, sizeof(hello), s_info.token)) {
        ESP_LOGW(TAG, "stream client refused");
        close(fd);
        return;
    }
    // Nagle stays on: the sender's small writes go out as MSS-sized
    // segments; END is pushed (rec_wifi_push()).
    const struct timeval st = {.tv_sec = 0, .tv_usec = SEND_TIMEOUT_MS * 1000};
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &st, sizeof(st));
    int on = 1;
    const int ka[] = {KEEPALIVE_IDLE_S, KEEPALIVE_INTVL_S, KEEPALIVE_COUNT};
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &ka[0], sizeof(ka[0]));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &ka[1], sizeof(ka[1]));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &ka[2], sizeof(ka[2]));
    s_sock = fd;
    s_nodelay = false;
    s_welcomed = false;
    s_sta_left = false;
    taskENTER_CRITICAL(&s_cli_mux);
    uint32_t id = ++s_client_id;
    s_serving = false;
    s_client = true;
    s_push_until = 0;
    taskEXIT_CRITICAL(&s_cli_mux);
    ESP_LOGI(TAG, "stream client connected");
    rec_glue_hello(id);
}

// The client closed the connection or its station left: lost.
static bool peer_gone(void)
{
    if (s_sta_left) {
        drop_client("station left", true);
        return true;
    }
    uint8_t b;
    int r = recv(s_sock, &b, 1, MSG_DONTWAIT);   // after HELLO only the device sends
    if (r == 0 || (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
        drop_client("connection closed", true);
        return true;
    }
    return false;
}

// TCP_NODELAY while the stream is short of rec_wifi_push()'s position:
// lwIP's send() then puts the last short segment (END) on the air at once
// instead of holding it until the client ACKs the one before (Nagle).
static void apply_push(void)
{
    taskENTER_CRITICAL(&s_cli_mux);
    bool pending = s_push_until != 0;
    taskEXIT_CRITICAL(&s_cli_mux);
    if (!pending && !s_nodelay) {
        return;     // the usual case: no ring stats needed
    }
    rec_ring_stats_t st;
    rec_ring_stats(s_ring, &st);
    taskENTER_CRITICAL(&s_cli_mux);
    if (s_push_until != 0 && (st.sent_bytes >= s_push_until || st.used == 0)) {
        s_push_until = 0;
    }
    bool on = s_push_until != 0;
    taskEXIT_CRITICAL(&s_cli_mux);
    if (on != s_nodelay) {
        int v = on ? 1 : 0;
        setsockopt(s_sock, IPPROTO_TCP, TCP_NODELAY, &v, sizeof(v));
        s_nodelay = on;
    }
}

// One send() of up to n bytes: the bytes written (progress is then now), 0
// while it would block, or -1 once the client is lost: no progress since
// *progress for SEND_STALL_MS, or a closed connection.
static int send_some(const uint8_t *d, size_t n, int64_t *progress)
{
    int w = send(s_sock, d, n, 0);
    int64_t now = esp_timer_get_time();
    if (w > 0) {
        *progress = now;
        return w;
    }
    if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        if (now - *progress < (int64_t)SEND_STALL_MS * 1000) {
            return 0;
        }
        drop_client("send blocked for 10 s", true);
        return -1;
    }
    drop_client("send failed", true);
    return -1;
}

// Sends what the ring holds until it is empty, or until `until` (esp_timer
// us; a stop's drain limit) passes; without a limit a stop request ends it
// too. A stalled or closed connection (send_some) or the station leaving
// lose the client. True once the ring is empty.
#define NO_LIMIT INT64_MAX
static bool send_ring(int64_t until)
{
    bool sent = false;
    bool empty = false;
    int64_t progress = esp_timer_get_time();
    for (;;) {
        if (s_sta_left) {
            drop_client("station left", true);
            return false;
        }
        if ((until == NO_LIMIT && s_stop_req) || esp_timer_get_time() >= until) {
            break;
        }
        const uint8_t *d;
        size_t n = rec_ring_peek(s_ring, &d, TX_CHUNK);
        if (n == 0) {
            empty = true;
            break;
        }
        apply_push();
        int w = send_some(d, n, &progress);
        if (w < 0) {
            return false;
        }
        if (w > 0) {
            rec_ring_consume(s_ring, (size_t)w);
            sent = true;
        }
    }
    if (sent && empty) {
        rec_glue_sent();
    }
    return empty;
}

// HELLO_OK, the served client's first record (rec_stream.h): the recorder
// holds this connection now, which rec_state alone cannot tell the client.
// Sent before any of the ring's bytes. False if the client was lost.
static bool welcome(void)
{
    if (s_welcomed) {
        return true;
    }
    uint8_t rec[REC_HDR_LEN + REC_CRC_LEN];
    size_t n = rec_stream_encode(REC_T_HELLO_OK, REC_V_HELLO_OK, 0, NULL, 0, rec, sizeof(rec));
    size_t off = 0;
    int64_t progress = esp_timer_get_time();
    while (off < n) {
        if (s_sta_left) {
            drop_client("station left", true);
            return false;
        }
        int w = send_some(&rec[off], n - off, &progress);
        if (w < 0) {
            return false;
        }
        off += (size_t)w;
    }
    s_welcomed = true;
    return true;
}

static void serve(void)
{
    if (peer_gone() || !s_serving) {
        return;     // or only watches the connection until rec_wifi_serve()
    }
    if (welcome()) {
        send_ring(NO_LIMIT);
    }
}

// The stream ends: FIN after what lwIP still holds (up to TCP_SND_BUF, END
// among it). esp_wifi_stop() right after close() would drop that with the
// link, so the client gets REC_WIFI_LINGER_MS to read up to the FIN and
// close its side (db_client.py does on EOF; the app must too): its FIN, or
// a reset, says it has everything there is.
static void finish_stream(void)
{
    if (shutdown(s_sock, SHUT_WR) != 0) {
        return;
    }
    const struct timeval tv = {.tv_sec = 0, .tv_usec = LINGER_POLL_MS * 1000};
    setsockopt(s_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int64_t until = esp_timer_get_time() + (int64_t)REC_WIFI_LINGER_MS * 1000;
    while (!s_sta_left && esp_timer_get_time() < until) {
        uint8_t b[16];
        int r = recv(s_sock, b, sizeof(b), 0);
        if (r == 0 || (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
            return;
        }
    }
    ESP_LOGW(TAG, "stream client did not close within %u ms", (unsigned)REC_WIFI_LINGER_MS);
}

static void on_wifi(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;
    (void)base;
    (void)data;
    switch (id) {
    case WIFI_EVENT_AP_START:
        s_ap_up = true;
        rec_glue_input(REC_IN_AP_UP);
        xTaskNotifyGive(s_tx);
        break;
    case WIFI_EVENT_AP_STOP:
        s_ap_up = false;
        break;
    case WIFI_EVENT_AP_STACONNECTED:
        ESP_LOGI(TAG, "station joined");
        break;
    case WIFI_EVENT_AP_STADISCONNECTED:
        ESP_LOGI(TAG, "station left");
        s_sta_left = true;
        xTaskNotifyGive(s_tx);
        break;
    default:
        break;
    }
}

static void teardown(void)
{
    s_run = false;
    if (s_wifi_inited) {
        esp_wifi_stop();            // ESP_ERR_WIFI_NOT_STARTED after a failed start
        if (s_ev) {
            esp_event_handler_instance_unregister(WIFI_EVENT, ESP_EVENT_ANY_ID, s_ev);
            s_ev = NULL;
        }
        esp_wifi_deinit();
        s_wifi_inited = false;
    }
    if (s_netif) {
        esp_netif_destroy_default_wifi(s_netif);
        s_netif = NULL;
    }
    s_ap_up = false;
    taskENTER_CRITICAL(&s_cli_mux);
    memset(&s_info, 0, sizeof(s_info));
    taskEXIT_CRITICAL(&s_cli_mux);
}

// rec_wifi_start() in rec_tx: new credentials, the AP. The credentials are
// in s_info before esp_wifi_start(), so they are there at AP_UP.
static esp_err_t wifi_up(void)
{
    rec_wifi_info_t info = {.port = REC_STREAM_PORT, .channel = AP_CHANNEL};
    if (!s_stack_up) {
        // lwIP and the event loop stay up once started (esp_netif has no deinit).
        ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "esp_netif_init");
        esp_err_t err = esp_event_loop_create_default();
        if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
            return err;
        }
        s_stack_up = true;
    }
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_BT);
    rec_ap_ssid(info.ssid, &mac[4], (uint16_t)esp_random());
    uint8_t rnd[REC_PASS_RAND_LEN];
    esp_fill_random(rnd, sizeof(rnd));
    rec_ap_pass(info.pass, rnd);
    memset(rnd, 0, sizeof(rnd));
    esp_fill_random(info.token, sizeof(info.token));

    // esp_netif_create_default_wifi_ap() by hand: it asserts where these
    // return errors, and short internal RAM must give AP_FAIL, not a reboot.
    esp_err_t ret = ESP_ERR_NO_MEM;     // ESP_GOTO_ON_ERROR sets it
    const esp_netif_config_t ncfg = ESP_NETIF_DEFAULT_WIFI_AP();
    s_netif = esp_netif_new(&ncfg);
    if (!s_netif) {
        ESP_LOGE(TAG, "esp_netif_new");
        goto fail;
    }
    ESP_GOTO_ON_ERROR(esp_netif_attach_wifi_ap(s_netif), fail, TAG, "netif attach");
    ESP_GOTO_ON_ERROR(esp_wifi_set_default_wifi_ap_handlers(), fail, TAG, "netif handlers");
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_GOTO_ON_ERROR(esp_wifi_init(&cfg), fail, TAG, "esp_wifi_init");
    s_wifi_inited = true;
    ESP_GOTO_ON_ERROR(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi,
                                                          NULL, &s_ev),
                      fail, TAG, "event handler");
    ESP_GOTO_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), fail, TAG, "storage");
    ESP_GOTO_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_AP), fail, TAG, "mode");
    wifi_config_t wc = {
        .ap = {
            .ssid_len = (uint8_t)strlen(info.ssid),
            .channel = AP_CHANNEL,
            .authmode = WIFI_AUTH_WPA2_PSK,
            .max_connection = AP_MAX_CONN,
            .pmf_cfg = {.required = false},
        },
    };
    memcpy(wc.ap.ssid, info.ssid, strlen(info.ssid));
    memcpy(wc.ap.password, info.pass, strlen(info.pass));
    ESP_GOTO_ON_ERROR(esp_wifi_set_config(WIFI_IF_AP, &wc), fail, TAG, "config");
    memset(&wc, 0, sizeof(wc));

    esp_netif_ip_info_t ip;
    ESP_GOTO_ON_ERROR(esp_netif_get_ip_info(s_netif, &ip), fail, TAG, "ip");
    info.ip[0] = esp_ip4_addr1(&ip.ip);
    info.ip[1] = esp_ip4_addr2(&ip.ip);
    info.ip[2] = esp_ip4_addr3(&ip.ip);
    info.ip[3] = esp_ip4_addr4(&ip.ip);

    taskENTER_CRITICAL(&s_cli_mux);
    s_info = info;
    taskEXIT_CRITICAL(&s_cli_mux);
    s_run = true;
    ESP_GOTO_ON_ERROR(esp_wifi_start(), fail, TAG, "start");
    if (esp_wifi_set_inactive_time(WIFI_IF_AP, AP_INACTIVE_S) != ESP_OK) {
        ESP_LOGW(TAG, "station inactivity timeout stays at the default");
    }
    ESP_LOGI(TAG, "soft-AP %s on channel %u, %u.%u.%u.%u:%u", info.ssid, (unsigned)AP_CHANNEL,
             info.ip[0], info.ip[1], info.ip[2], info.ip[3], (unsigned)info.port);
    memset(&info, 0, sizeof(info));
    return ESP_OK;

fail:
    teardown();
    memset(&info, 0, sizeof(info));
    return ret;
}

// rec_wifi_stop() in rec_tx: what is queued (END) gets its chance first,
// then the stream ends and Wi-Fi goes.
static void wifi_down(uint32_t drain_ms)
{
    int64_t t0 = esp_timer_get_time();
    if (s_sock >= 0 && s_serving && welcome()) {
        send_ring(t0 + (int64_t)drain_ms * 1000);
        if (s_sock >= 0) {
            finish_stream();
        }
    }
    drop_client(NULL, false);
    close_fd(&s_listen);
    teardown();
    ESP_LOGI(TAG, "soft-AP down in %u ms", (unsigned)((esp_timer_get_time() - t0) / 1000));
}

static void tx_task(void *arg)
{
    (void)arg;
    for (;;) {
        // Without an AP nothing to poll: the requests and AP_START notify.
        bool idle = !s_run || !s_ap_up;
        ulTaskNotifyTake(pdTRUE, idle ? portMAX_DELAY : pdMS_TO_TICKS(TX_POLL_MS));
        taskENTER_CRITICAL(&s_cli_mux);
        bool start = s_start_req;
        bool stop = s_stop_req;
        uint32_t drain_ms = s_drain_ms;
        s_start_req = s_stop_req = false;
        taskEXIT_CRITICAL(&s_cli_mux);
        if (start) {
            esp_err_t err = wifi_up();
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "soft-AP: %s", esp_err_to_name(err));
                rec_glue_wifi(REC_IN_AP_FAIL);
            }
        }
        if (stop) {
            wifi_down(drain_ms);
            rec_glue_wifi(REC_IN_AP_DOWN);
            continue;
        }
        if (!s_run || !s_ap_up) {
            continue;
        }
        if (s_listen < 0 && !open_server()) {
            vTaskDelay(pdMS_TO_TICKS(500));
            continue;
        }
        if (s_sock < 0) {
            s_sta_left = false;     // nobody to lose
            accept_client();
            continue;
        }
        serve();
    }
}

void rec_wifi_start(void)
{
    taskENTER_CRITICAL(&s_cli_mux);
    s_start_req = true;
    taskEXIT_CRITICAL(&s_cli_mux);
    xTaskNotifyGive(s_tx);
}

void rec_wifi_stop(uint32_t drain_ms)
{
    taskENTER_CRITICAL(&s_cli_mux);
    s_drain_ms = drain_ms;
    s_stop_req = true;
    taskEXIT_CRITICAL(&s_cli_mux);
    xTaskNotifyGive(s_tx);
}

void rec_wifi_info(rec_wifi_info_t *out)
{
    taskENTER_CRITICAL(&s_cli_mux);
    *out = s_info;
    taskEXIT_CRITICAL(&s_cli_mux);
}

bool rec_wifi_client(void)
{
    return s_client;
}

bool rec_wifi_is_client(uint32_t client)
{
    taskENTER_CRITICAL(&s_cli_mux);
    bool is = s_client && s_client_id == client;
    taskEXIT_CRITICAL(&s_cli_mux);
    return is;
}

void rec_wifi_serve(uint32_t client)
{
    taskENTER_CRITICAL(&s_cli_mux);
    bool is = s_client && s_client_id == client;
    if (is) {
        s_serving = true;
    }
    taskEXIT_CRITICAL(&s_cli_mux);
    if (is) {
        xTaskNotifyGive(s_tx);
    }
}

void rec_wifi_kick(void)
{
    if (!s_tx || !s_client) {
        return;
    }
    rec_ring_stats_t st;
    rec_ring_stats(s_ring, &st);
    if (st.used >= TX_KICK_BYTES) {
        xTaskNotifyGive(s_tx);
    }
}

void rec_wifi_push(void)
{
    if (!s_tx) {
        return;
    }
    rec_ring_stats_t st;
    rec_ring_stats(s_ring, &st);
    taskENTER_CRITICAL(&s_cli_mux);
    s_push_until = st.sent_bytes + st.used;
    taskEXIT_CRITICAL(&s_cli_mux);
    xTaskNotifyGive(s_tx);
}

esp_err_t rec_wifi_init(rec_ring_t *ring)
{
    s_ring = ring;
    if (xTaskCreatePinnedToCore(tx_task, "rec_tx", TX_TASK_STACK, NULL, TX_TASK_PRIO, &s_tx,
                                TX_TASK_CORE) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}
