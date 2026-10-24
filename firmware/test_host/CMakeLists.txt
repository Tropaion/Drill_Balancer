# Host unit tests for the hardware-independent components.
#
#   cmake -S test_host -B build-test -G Ninja && cmake --build build-test && ctest --test-dir build-test
#
# Unity comes from the ESP-IDF tree (IDF_PATH, default ~/esp/v5.5.1/esp-idf).

cmake_minimum_required(VERSION 3.16)
project(drillbalancer_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(DEFINED ENV{IDF_PATH})
    set(IDF_PATH $ENV{IDF_PATH})
else()
    set(IDF_PATH $ENV{HOME}/esp/v5.5.1/esp-idf)
endif()
set(UNITY_DIR ${IDF_PATH}/components/unity/unity/src)
if(NOT EXISTS ${UNITY_DIR}/unity.c)
    message(FATAL_ERROR "Unity not found in ${UNITY_DIR}; set IDF_PATH")
endif()

set(COMPONENTS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../components)

add_library(unity STATIC ${UNITY_DIR}/unity.c)
target_include_directories(unity PUBLIC ${UNITY_DIR})

# -Wdouble-promotion: the ESP32-S3 FPU is single precision, so accidental
# double math is a bug in this code.
set(STRICT_WARNINGS -Wall -Wextra -Werror -Wdouble-promotion -Wshadow)

add_library(level STATIC
    ${COMPONENTS_DIR}/level/src/level_math.c
    ${COMPONENTS_DIR}/level/src/fusion.c
    ${COMPONENTS_DIR}/level/src/level.c
    ${COMPONENTS_DIR}/level/src/accel_cal.c)
target_include_directories(level PUBLIC ${COMPONENTS_DIR}/level/include)
target_compile_options(level PRIVATE ${STRICT_WARNINGS})
target_link_libraries(level PUBLIC m)

enable_testing()

foreach(name test_level_math test_fusion test_level test_accel_cal)
    add_executable(${name} ${name}.c test_util.c)
    target_compile_options(${name} PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(${name} PRIVATE level unity)
    add_test(NAME ${name} COMMAND ${name})
endforeach()

# Tests of the other components live in one subdirectory each.
file(GLOB TEST_SUBDIRS LIST_DIRECTORIES true ${CMAKE_CURRENT_SOURCE_DIR}/*)
foreach(dir ${TEST_SUBDIRS})
    if(IS_DIRECTORY ${dir} AND EXISTS ${dir}/CMakeLists.txt)
        add_subdirectory(${dir})
    endif()
endforeach()
