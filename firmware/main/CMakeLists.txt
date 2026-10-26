idf_component_register(SRCS "app_main.c" "sensor_task.c" "control.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES app_core level settings board imu power ui ble ota recorder
                                     waveshare__esp32_s3_touch_amoled_1_75
                                     esp_app_format esp_system esp_driver_i2c freertos log)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
