# Host tests for the hardware-independent part of the QMI8658 driver
# (components/imu/src/imu_frame.c). ../stubs stands in for the ESP-IDF
# headers that imu.h includes.

add_library(imu_frame_host STATIC ${COMPONENTS_DIR}/imu/src/imu_frame.c)
target_include_directories(imu_frame_host PUBLIC
    ${COMPONENTS_DIR}/imu/src
    ${COMPONENTS_DIR}/imu/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../stubs)
target_compile_options(imu_frame_host PRIVATE ${STRICT_WARNINGS})
target_link_libraries(imu_frame_host PUBLIC level)

add_executable(test_imu_frame test_imu_frame.c ../test_util.c)
target_include_directories(test_imu_frame PRIVATE ..)
target_compile_options(test_imu_frame PRIVATE ${STRICT_WARNINGS})
target_link_libraries(test_imu_frame PRIVATE imu_frame_host unity)
add_test(NAME test_imu_frame COMMAND test_imu_frame)
