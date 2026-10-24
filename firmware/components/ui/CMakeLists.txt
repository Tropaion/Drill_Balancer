# LVGL user interface. ui_logic.c is pure C and also built on the host
# (test_host/ui_preview: unit test and a PNG preview of every screen).
idf_component_register(SRCS "src/ui.c" "src/ui_level.c" "src/ui_settings.c" "src/ui_cal.c"
                            "src/ui_logic.c" "src/fonts/ui_font_num112.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_common
                       PRIV_REQUIRES lvgl esp32_s3_touch_amoled_1_75 app_core settings level
                                     esp_app_format log)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
