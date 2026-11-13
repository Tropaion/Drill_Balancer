# ble_codec.c is pure C and also built on the host (test_host/ble).
idf_component_register(SRCS "src/ble_service.c" "src/ble_codec.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_common
                       PRIV_REQUIRES bt app_core settings ota level recorder esp_timer esp_hw_support
                                     esp_app_format esp_system freertos log nvs_flash)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)

# OTA needs NimBLE's GATT client; the reason is in sdkconfig.d/ble.defaults.
if(NOT CONFIG_BT_NIMBLE_GATT_CLIENT)
    message(FATAL_ERROR "components/ble needs CONFIG_BT_NIMBLE_GATT_CLIENT for OTA "
        "(sdkconfig.d/ble.defaults). An sdkconfig from before keeps the old "
        "value: delete it and build again.")
endif()
