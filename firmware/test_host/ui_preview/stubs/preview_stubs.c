// Host stand-ins for app_state, settings_store, esp_app_desc, the BSP
// display lock and the recorder, so the real UI sources run unchanged in the
// preview.

#include <string.h>
#include "app_state.h"
#include "bsp/esp-bsp.h"
#include "esp_app_desc.h"
#include "esp_err.h"
#include "preview_stubs.h"
#include "recorder.h"
#include "settings_store.h"

app_snapshot_t g_snap;
settings_t g_settings;
int g_settings_store_count;
app_cmd_t g_cmds[PREVIEW_MAX_CMDS];
int g_cmd_count;
uint32_t g_now_ms;
int g_lock_depth;
rec_cmd_t g_rec_cmds[PREVIEW_MAX_REC_CMDS];
int g_rec_cmd_count;
int g_rec_touches;

void preview_stubs_reset(void)
{
    memset(&g_snap, 0, sizeof(g_snap));
    g_snap.q = quat_identity();
    g_snap.cal.last_result = -1;
    g_settings = settings_defaults();
    g_settings_store_count = 0;
    g_cmd_count = 0;
    g_rec_cmd_count = 0;
    g_rec_touches = 0;
}

int preview_count_cmd(app_cmd_id_t id, int32_t arg, bool match_arg)
{
    int n = 0;
    for (int i = 0; i < g_cmd_count; i++) {
        if (g_cmds[i].id == id && (!match_arg || g_cmds[i].arg == arg)) {
            n++;
        }
    }
    return n;
}

void app_state_get(app_snapshot_t *out)
{
    *out = g_snap;
}

bool app_post_cmd(app_cmd_id_t id, int32_t arg)
{
    if (g_cmd_count >= PREVIEW_MAX_CMDS) {
        return false;
    }
    g_cmds[g_cmd_count++] = (app_cmd_t){.id = id, .arg = arg};
    return true;
}

void app_note_activity(void) {}

bool recorder_send(const rec_cmd_t *cmd)
{
    if (g_rec_cmd_count >= PREVIEW_MAX_REC_CMDS) {
        return false;
    }
    g_rec_cmds[g_rec_cmd_count++] = *cmd;
    return true;
}

// As recorder.c: only a recording take logs it.
bool recorder_note_touch(void)
{
    bool rec = g_snap.rec.state == APP_REC_STATE_RECORDING;
    g_rec_touches += rec ? 1 : 0;
    return rec;
}

void settings_get(settings_t *out)
{
    *out = g_settings;
}

// As settings_store.c: only a valid change is stored and counted.
esp_err_t settings_update(settings_update_fn fn, void *ctx)
{
    settings_t next = g_settings;
    if (!fn(&next, ctx) || settings_equal(&next, &g_settings)) {
        return ESP_OK;
    }
    if (!settings_validate(&next)) {
        return ESP_ERR_INVALID_ARG;
    }
    g_settings = next;
    g_settings_store_count++;
    return ESP_OK;
}

const esp_app_desc_t *esp_app_get_description(void)
{
    static const esp_app_desc_t desc = {.version = "0.1.0", .project_name = "drillbalancer"};
    return &desc;
}

const char *esp_err_to_name(esp_err_t code)
{
    return code == ESP_OK ? "ESP_OK" : "ESP_ERR";
}

esp_err_t bsp_display_lock(uint32_t timeout_ms)
{
    (void)timeout_ms;
    g_lock_depth++;
    return ESP_OK;
}

void bsp_display_unlock(void)
{
    g_lock_depth--;
}
