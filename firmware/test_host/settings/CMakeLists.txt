# settings.c (pure C + cJSON). settings_store.c needs NVS and is device only.

set(SETTINGS_CJSON_DIR ${IDF_PATH}/components/json/cJSON)

# Third-party code: its own warnings are not ours to fix.
add_library(settings_cjson STATIC ${SETTINGS_CJSON_DIR}/cJSON.c)
target_include_directories(settings_cjson PUBLIC ${SETTINGS_CJSON_DIR})
target_link_libraries(settings_cjson PUBLIC m)

# Also usable by other host tests that need settings_t and its JSON form.
add_library(settings_host STATIC ${COMPONENTS_DIR}/settings/src/settings.c)
target_include_directories(settings_host PUBLIC ${COMPONENTS_DIR}/settings/include)
target_compile_options(settings_host PRIVATE ${STRICT_WARNINGS})
target_link_libraries(settings_host PUBLIC level settings_cjson)

add_executable(test_settings test_settings.c)
target_compile_options(test_settings PRIVATE -Wall -Wextra -Werror)
target_link_libraries(test_settings PRIVATE settings_host unity)
add_test(NAME test_settings COMMAND test_settings)
