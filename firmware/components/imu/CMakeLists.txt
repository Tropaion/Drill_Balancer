# QMI8658 driver. imu_frame.c is pure C and also built in test_host/imu.
idf_component_register(SRCS "src/imu.c" "src/imu_frame.c"
                       INCLUDE_DIRS "include"
                       REQUIRES esp_driver_i2c level
                       PRIV_REQUIRES esp_timer)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)
