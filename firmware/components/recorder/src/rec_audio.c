#include <string.h>
#include "rec_glue.h"

#include "bsp/esp-bsp.h"
#include "driver/i2s_std.h"
#include "es7210_adc.h"
#include "esp_codec_dev.h"
#include "esp_codec_dev_defaults.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

static const char *TAG = "rec_mic";

// Our own RX-only I2S channel on the BSP's pins (bsp_audio_init() keeps its
// handles private, and nothing else uses audio): REC_AUDIO_RATE_HZ (16 kHz),
// 16-bit stereo Philips, MCLK = 256 x fs = 4.096 MHz for the ES7210. L =
// MIC1, R = MIC2 (rec_stream.h REC_MIC_*). 8 DMA blocks of
// REC_AUDIO_BLOCK_FRAMES (256) frames: 8 KB internal RAM; the driver queues
// DMA_DESC_NUM - 1 = 7 finished blocks (i2s_common.c), so this task has
// about 112 ms of slack.
#define AUDIO_MCLK_MULT   I2S_MCLK_MULTIPLE_256
#define DMA_DESC_NUM      8u
#define DMA_FRAMES        REC_AUDIO_BLOCK_FRAMES
#define BLOCK_BYTES       (DMA_FRAMES * 2u * sizeof(int16_t))
_Static_assert(REC_AUDIO_BITS == 16, "I2S_DATA_BIT_WIDTH_16BIT, int16_t samples");
#define TS_SLOTS          16u          // > DMA_DESC_NUM: the stamps of the queued blocks
// Above LVGL and the control task, below the sensor task: one block every
// 16 ms, and the DMA queue holds about 112 ms.
// TODO(hw): check uxTaskGetStackHighWaterMark() after a take with both
// microphones (bring-up step 26).
#define MIC_TASK_PRIO     9
#define MIC_TASK_CORE     1
#define MIC_TASK_STACK    3072
#define READ_TIMEOUT_MS   100u

typedef struct {
    bool on;
    uint8_t mic_mask;
    uint8_t gain_db;
    uint32_t gen;
} mic_cmd_t;

static QueueHandle_t s_cmds;

// rec_mic task only
static i2s_chan_handle_t s_rx;
static const audio_codec_data_if_t *s_data_if;
static const audio_codec_ctrl_if_t *s_ctrl_if;
static const audio_codec_if_t *s_codec_if;
static esp_codec_dev_handle_t s_dev;
static int16_t s_block[DMA_FRAMES * 2u];
static rec_block_pair_t s_pair;
static uint32_t s_gen;                  // the capture running ...
static uint8_t s_mic_mask;              // ... and its microphones

// Written by the DMA interrupt, read by the task under s_isr_mux: the
// esp_timer when each block completed, and the blocks the full queue lost.
static portMUX_TYPE s_isr_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile uint32_t s_done;
static volatile uint32_t s_dropped;
static int64_t s_stamp[TS_SLOTS];

static bool IRAM_ATTR on_recv(i2s_chan_handle_t h, i2s_event_data_t *ev, void *ctx)
{
    (void)h;
    (void)ev;
    (void)ctx;
    int64_t now = esp_timer_get_time();
    portENTER_CRITICAL_ISR(&s_isr_mux);
    s_stamp[s_done % TS_SLOTS] = now;
    s_done++;
    portEXIT_CRITICAL_ISR(&s_isr_mux);
    return false;
}

static bool IRAM_ATTR on_recv_q_ovf(i2s_chan_handle_t h, i2s_event_data_t *ev, void *ctx)
{
    (void)h;
    (void)ev;
    (void)ctx;
    portENTER_CRITICAL_ISR(&s_isr_mux);
    s_dropped++;
    portEXIT_CRITICAL_ISR(&s_isr_mux);
    return false;
}

static void mic_off(void)
{
    if (s_dev) {
        esp_codec_dev_close(s_dev);     // ES7210 powered down, the channel disabled
        esp_codec_dev_delete(s_dev);
        s_dev = NULL;
    }
    if (s_codec_if) {
        audio_codec_delete_codec_if(s_codec_if);
        s_codec_if = NULL;
    }
    if (s_ctrl_if) {
        audio_codec_delete_ctrl_if(s_ctrl_if);
        s_ctrl_if = NULL;
    }
    if (s_data_if) {
        audio_codec_delete_data_if(s_data_if);
        s_data_if = NULL;
    }
    if (s_rx) {
        i2s_del_channel(s_rx);          // disabled by the close, or never enabled
        s_rx = NULL;
    }
}

// ES7210 microphone selection and PGA gain from TAKE_START.
// TODO(hw): the defaults the app sends (MIC1|MIC2, 6 dB) are a guess:
// check the channel order with a tap next to each microphone, and the
// level and clipping on a running drill (bring-up step 27).
static bool mic_on(uint8_t mic_mask, uint8_t gain_db)
{
    i2s_chan_config_t cc = I2S_CHANNEL_DEFAULT_CONFIG(CONFIG_BSP_I2S_NUM, I2S_ROLE_MASTER);
    cc.dma_desc_num = DMA_DESC_NUM;
    cc.dma_frame_num = DMA_FRAMES;
    if (i2s_new_channel(&cc, NULL, &s_rx) != ESP_OK) {
        ESP_LOGE(TAG, "no I2S channel");
        return false;
    }
    i2s_std_config_t sc = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(REC_AUDIO_RATE_HZ),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT,
                                                        I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = BSP_I2S_MCLK,
            .bclk = BSP_I2S_SCLK,
            .ws = BSP_I2S_LCLK,
            .dout = I2S_GPIO_UNUSED,
            .din = BSP_I2S_DSIN,
        },
    };
    sc.clk_cfg.mclk_multiple = AUDIO_MCLK_MULT;
    const i2s_event_callbacks_t cbs = {.on_recv = on_recv, .on_recv_q_ovf = on_recv_q_ovf};
    if (i2s_channel_init_std_mode(s_rx, &sc) != ESP_OK ||
        i2s_channel_register_event_callback(s_rx, &cbs, NULL) != ESP_OK) {
        ESP_LOGE(TAG, "I2S setup failed");
        return false;
    }

    audio_codec_i2s_cfg_t ic = {.port = CONFIG_BSP_I2S_NUM, .rx_handle = s_rx};
    s_data_if = audio_codec_new_i2s_data(&ic);
    audio_codec_i2c_cfg_t cc2 = {.port = BSP_I2C_NUM, .addr = ES7210_CODEC_DEFAULT_ADDR,
                                 .bus_handle = bsp_i2c_get_handle()};
    s_ctrl_if = s_data_if ? audio_codec_new_i2c_ctrl(&cc2) : NULL;
    es7210_codec_cfg_t ec = {
        .ctrl_if = s_ctrl_if,
        .mic_selected = (uint8_t)(((mic_mask & REC_MIC_1) ? ES7210_SEL_MIC1 : 0u) |
                                  ((mic_mask & REC_MIC_2) ? ES7210_SEL_MIC2 : 0u)),
    };
    s_codec_if = s_ctrl_if ? es7210_codec_new(&ec) : NULL;
    esp_codec_dev_cfg_t dc = {.dev_type = ESP_CODEC_DEV_TYPE_IN, .codec_if = s_codec_if,
                              .data_if = s_data_if};
    s_dev = s_codec_if ? esp_codec_dev_new(&dc) : NULL;
    if (!s_dev) {
        ESP_LOGE(TAG, "ES7210 not found");
        return false;
    }
    // Both slots whatever the mask: rec_audio_select() picks the channel.
    esp_codec_dev_sample_info_t fs = {
        .bits_per_sample = REC_AUDIO_BITS,
        .channel = 2,
        .sample_rate = REC_AUDIO_RATE_HZ,
        .mclk_multiple = AUDIO_MCLK_MULT,
    };
    portENTER_CRITICAL(&s_isr_mux);
    s_done = s_dropped = 0;
    portEXIT_CRITICAL(&s_isr_mux);
    s_pair = (rec_block_pair_t){0};
    // Opens the codec and enables the channel: the blocks count from here.
    // The codec's set_fmt disables the channel first, which i2s_common logs
    // as an error for a channel never enabled ("the channel has not been
    // enabled yet"): muted for the open, whose result is checked here.
    esp_log_level_t lvl = esp_log_level_get("i2s_common");
    esp_log_level_set("i2s_common", ESP_LOG_NONE);
    int open = esp_codec_dev_open(s_dev, &fs);
    esp_log_level_set("i2s_common", lvl);
    if (open != ESP_CODEC_DEV_OK ||
        esp_codec_dev_set_in_gain(s_dev, (float)gain_db) != ESP_CODEC_DEV_OK) {
        ESP_LOGE(TAG, "ES7210 open failed (%d)", open);
        return false;
    }
    ESP_LOGI(TAG, "on: mics 0x%x, %u dB", (unsigned)mic_mask, (unsigned)gain_db);
    // TODO(hw): the free internal heap during a take with the microphones
    // on (bring-up step 34): the AP's figure minus the I2S DMA.
    ESP_LOGI(TAG, "internal heap %u B free, largest block %u B",
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL));
    return true;
}

static void read_block(void)
{
    size_t got = 0;
    esp_err_t err = i2s_channel_read(s_rx, s_block, BLOCK_BYTES, &got, READ_TIMEOUT_MS);
    if (err != ESP_OK || got != BLOCK_BYTES) {
        return;     // a timeout: nothing came, the next read tries again
    }
    // The block read and its stamp, from the drop count. The stamp is still
    // there unless the task fell more than TS_SLOTS blocks behind (the queue
    // holds DMA_DESC_NUM): then the read time.
    // TODO(hw): it is when the block reached memory; the ES7210's decimation
    // filter delays the sound by a fixed amount before that. Measure it with
    // a tap test against the IMU (bring-up step 29) for the dataset's sync.
    int64_t stamp = esp_timer_get_time();
    uint32_t lost;
    portENTER_CRITICAL(&s_isr_mux);
    uint32_t blk = rec_block_pair(&s_pair, s_dropped, &lost);
    if (s_done - blk <= TS_SLOTS && s_done > blk) {
        stamp = s_stamp[blk % TS_SLOTS];
    }
    portEXIT_CRITICAL(&s_isr_mux);
    // The take's channels, in place, before the recorder's gate.
    uint32_t clipped = 0;
    size_t n = rec_audio_select(s_block, DMA_FRAMES, s_mic_mask, s_block, &clipped);
    rec_glue_audio_block(s_gen, s_block, n, DMA_FRAMES, clipped, blk, lost, stamp);
}

static void mic_task(void *arg)
{
    (void)arg;
    bool on = false;
    for (;;) {
        mic_cmd_t c;
        if (xQueueReceive(s_cmds, &c, on ? 0 : portMAX_DELAY) == pdTRUE) {
            mic_off();
            s_gen = c.gen;
            s_mic_mask = c.mic_mask;
            on = c.on && mic_on(c.mic_mask, c.gain_db);
            if (!on) {
                mic_off();
                if (c.on) {
                    rec_glue_audio_failed(c.gen);
                }
            }
            continue;
        }
        if (on) {
            read_block();
        }
    }
}

void rec_audio_start(uint8_t mic_mask, uint8_t gain_db, uint32_t gen)
{
    mic_cmd_t c = {.on = true, .mic_mask = mic_mask, .gain_db = gain_db, .gen = gen};
    if (!s_cmds || xQueueSend(s_cmds, &c, pdMS_TO_TICKS(50)) != pdTRUE) {
        // The capture never starts: the take goes on without audio, and
        // says so (rec_state, END, STATS).
        ESP_LOGE(TAG, "start lost");
        rec_glue_audio_failed(gen);
    }
}

void rec_audio_stop(void)
{
    mic_cmd_t c = {.on = false};
    if (!s_cmds || xQueueSend(s_cmds, &c, pdMS_TO_TICKS(50)) != pdTRUE) {
        ESP_LOGE(TAG, "stop lost");
    }
}

esp_err_t rec_audio_init(void)
{
    s_cmds = xQueueCreate(4, sizeof(mic_cmd_t));
    if (!s_cmds) {
        return ESP_ERR_NO_MEM;
    }
    if (xTaskCreatePinnedToCore(mic_task, "rec_mic", MIC_TASK_STACK, NULL, MIC_TASK_PRIO, NULL,
                                MIC_TASK_CORE) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}
