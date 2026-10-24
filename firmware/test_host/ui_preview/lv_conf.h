// LVGL configuration for the host preview. Mirrors the firmware's Kconfig
// choices that affect layout (fonts, DPI, theme); 32-bit colour so the
// dump is plain XRGB8888.
#ifndef LV_CONF_H
#define LV_CONF_H

#define LV_COLOR_DEPTH 32
#define LV_USE_OS LV_OS_NONE
#define LV_USE_STDLIB_MALLOC LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB
#define LV_DPI_DEF 130
#define LV_DEF_REFR_PERIOD 15

#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_16 1
#define LV_FONT_MONTSERRAT_24 1
#define LV_FONT_MONTSERRAT_48 1
#define LV_FONT_DEFAULT &lv_font_montserrat_24

#define LV_USE_THEME_DEFAULT 1
#define LV_THEME_DEFAULT_DARK 0
#define LV_THEME_DEFAULT_GROW 1

#define LV_USE_LOG 1
#define LV_LOG_LEVEL LV_LOG_LEVEL_WARN
#define LV_LOG_PRINTF 1
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1
#define LV_USE_ASSERT_OBJ 1

#endif
