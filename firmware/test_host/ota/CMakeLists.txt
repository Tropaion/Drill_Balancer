# Host tests for the OTA protocol state machine (components/ota/src/ota_proto.c).

add_library(ota_proto_host STATIC ${COMPONENTS_DIR}/ota/src/ota_proto.c)
target_include_directories(ota_proto_host PUBLIC ${COMPONENTS_DIR}/ota/include)
target_compile_options(ota_proto_host PRIVATE ${STRICT_WARNINGS})

# SHA-256 from the IDF tree's mbedtls, the library ota_update.c uses on the
# device (default config, plain C on the host). Third-party code, built as is.
set(OTA_MBEDTLS_DIR ${IDF_PATH}/components/mbedtls/mbedtls)
add_library(ota_sha256_host STATIC
    ${OTA_MBEDTLS_DIR}/library/sha256.c
    ${OTA_MBEDTLS_DIR}/library/platform_util.c)
target_include_directories(ota_sha256_host PUBLIC ${OTA_MBEDTLS_DIR}/include)
target_include_directories(ota_sha256_host PRIVATE ${OTA_MBEDTLS_DIR}/library)

add_executable(test_ota_proto test_ota_proto.c)
target_compile_options(test_ota_proto PRIVATE ${STRICT_WARNINGS})
target_link_libraries(test_ota_proto PRIVATE ota_proto_host ota_sha256_host unity)
add_test(NAME test_ota_proto COMMAND test_ota_proto)
