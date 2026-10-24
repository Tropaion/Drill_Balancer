# Host tests for the pure C part of the BLE service (components/ble/src/ble_codec.c).

add_library(ble_codec_host STATIC ${COMPONENTS_DIR}/ble/src/ble_codec.c)
target_include_directories(ble_codec_host PUBLIC
    ${COMPONENTS_DIR}/ble/include
    ${COMPONENTS_DIR}/ble/src
    ${COMPONENTS_DIR}/app_core/include
    ${COMPONENTS_DIR}/settings/include)
target_compile_options(ble_codec_host PRIVATE ${STRICT_WARNINGS})
target_link_libraries(ble_codec_host PUBLIC level)

add_executable(test_ble_codec test_ble_codec.c)
target_compile_options(test_ble_codec PRIVATE ${STRICT_WARNINGS})
target_link_libraries(test_ble_codec PRIVATE ble_codec_host unity)
add_test(NAME test_ble_codec COMMAND test_ble_codec)
