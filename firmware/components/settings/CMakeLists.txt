# settings.c is pure C + cJSON and also built on the host (test_host/settings).
# settings_store.c (NVS) is device only.
idf_component_register(SRCS "src/settings.c" "src/settings_store.c"
                       INCLUDE_DIRS "include"
                       REQUIRES level esp_common
                       PRIV_REQUIRES json nvs_flash esp_rom freertos log app_core)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
