# ble_codec.c is pure C and also built on the host (test_host/ble).
idf_component_register(SRCS "src/ble_service.c" "src/ble_codec.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_common
                       PRIV_REQUIRES bt app_core settings ota level esp_timer esp_hw_support
                                     esp_app_format esp_system freertos log nvs_flash)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
