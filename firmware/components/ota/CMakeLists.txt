# ota_proto.c is pure C and also built on the host (test_host/ota).
idf_component_register(SRCS "src/ota_proto.c" "src/ota_update.c"
                       INCLUDE_DIRS "include"
                       PRIV_REQUIRES app_core app_update esp_app_format bootloader_support
                                     esp_partition esp_timer mbedtls log)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
