# AXP2101 power management over XPowersLib. pmu_events.c is pure C and also
# built in test_host/board.
idf_component_register(SRCS "src/pmu.cpp" "src/pmu_events.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_driver_i2c
                       PRIV_REQUIRES xpowerslib)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
