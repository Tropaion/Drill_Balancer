#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "app_state.h"
#include "settings.h"

#define PREVIEW_MAX_CMDS 256

extern app_snapshot_t g_snap;      // what app_state_get() returns
extern settings_t g_settings;      // the "stored" settings
extern int g_settings_store_count;   // settings_update() calls that stored a change
extern app_cmd_t g_cmds[PREVIEW_MAX_CMDS];
extern int g_cmd_count;
extern uint32_t g_now_ms;          // fake clock for LVGL
extern int g_lock_depth;

void preview_stubs_reset(void);
int preview_count_cmd(app_cmd_id_t id, int32_t arg, bool match_arg);
