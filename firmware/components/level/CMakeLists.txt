# Pure C, no ESP-IDF dependencies: also built and tested on the host (test_host/).
idf_component_register(SRCS "src/level_math.c" "src/fusion.c" "src/level.c" "src/accel_cal.c"
                            "src/vibration.c"
                       INCLUDE_DIRS "include")
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
