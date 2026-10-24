# power_fsm.c is pure C and also built on the host (test_host/power/).
idf_component_register(SRCS "src/power_fsm.c" "src/power.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_common
                       PRIV_REQUIRES board imu esp_hw_support esp_driver_gpio esp_driver_i2c freertos log
                                     waveshare__esp32_s3_touch_amoled_1_75)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
