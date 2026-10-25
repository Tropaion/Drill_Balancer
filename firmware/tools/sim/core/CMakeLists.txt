# libdbsim.so: the firmware's hardware-independent C code plus the glue
# mirror (sim_core.c), for the Python simulator (dbsim/core.py builds it on
# demand).
#
#   cmake -S core -B core/build -G Ninja && cmake --build core/build   (from tools/sim)
#
# cJSON and mbedtls come from the ESP-IDF tree (IDF_PATH, default
# ~/esp/v5.5.1/esp-idf), as in test_host; test_host/stubs stands in for the
# ESP-IDF headers that pmu.h and imu.h include.

cmake_minimum_required(VERSION 3.16)
project(dbsim C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

if(DEFINED ENV{IDF_PATH})
    set(IDF_PATH $ENV{IDF_PATH})
else()
    set(IDF_PATH $ENV{HOME}/esp/v5.5.1/esp-idf)
endif()
set(CJSON_DIR ${IDF_PATH}/components/json/cJSON)
set(MBEDTLS_DIR ${IDF_PATH}/components/mbedtls/mbedtls)
if(NOT EXISTS ${CJSON_DIR}/cJSON.c OR NOT EXISTS ${MBEDTLS_DIR}/library/sha256.c)
    message(FATAL_ERROR "cJSON / mbedtls not found under ${IDF_PATH}; set IDF_PATH")
endif()

set(C ${CMAKE_CURRENT_SOURCE_DIR}/../../../components)
set(STUBS ${CMAKE_CURRENT_SOURCE_DIR}/../../../test_host/stubs)

# Third-party code, built as is: its warnings are not ours.
add_library(dbsim_thirdparty OBJECT
    ${CJSON_DIR}/cJSON.c
    ${MBEDTLS_DIR}/library/sha256.c
    ${MBEDTLS_DIR}/library/platform_util.c)
target_include_directories(dbsim_thirdparty PRIVATE ${CJSON_DIR} ${MBEDTLS_DIR}/include
                           ${MBEDTLS_DIR}/library)
target_compile_options(dbsim_thirdparty PRIVATE -w)

add_library(dbsim SHARED
    sim_core.c
    ${C}/level/src/level_math.c
    ${C}/level/src/fusion.c
    ${C}/level/src/level.c
    ${C}/level/src/accel_cal.c
    ${C}/settings/src/settings.c
    ${C}/ota/src/ota_proto.c
    ${C}/ble/src/ble_codec.c
    ${C}/power/src/power_fsm.c
    ${C}/ui/src/ui_logic.c
    ${C}/board/src/pmu_events.c
    ${C}/imu/src/imu_frame.c
    $<TARGET_OBJECTS:dbsim_thirdparty>)
target_include_directories(dbsim PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${C}/level/include
    ${C}/settings/include
    ${C}/ota/include
    ${C}/ble/include
    ${C}/ble/src
    ${C}/power/include
    ${C}/app_core/include
    ${C}/ui/src
    ${C}/board/include
    ${C}/board/src
    ${C}/imu/include
    ${C}/imu/src
    ${STUBS}
    ${CJSON_DIR}
    ${MBEDTLS_DIR}/include)
# The firmware sources build with test_host's strict warnings as well.
target_compile_options(dbsim PRIVATE -Wall -Wextra -Werror -Wshadow)
target_link_libraries(dbsim PRIVATE m)
