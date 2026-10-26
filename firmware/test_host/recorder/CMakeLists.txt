# Host tests for the recorder's pure C parts (components/recorder): stream
# encoding, the record ring, the session/take state machine, the time
# keeping and the glue helpers (rec_util). test_rec_golden also writes /
# checks vectors/golden_stream.bin, which tools/dataset (dbdata.dbrec) and
# the app decode.

add_library(recorder_host STATIC
    ${COMPONENTS_DIR}/recorder/src/rec_stream.c
    ${COMPONENTS_DIR}/recorder/src/rec_ring.c
    ${COMPONENTS_DIR}/recorder/src/rec_fsm.c
    ${COMPONENTS_DIR}/recorder/src/rec_ts.c
    ${COMPONENTS_DIR}/recorder/src/rec_util.c)
# imu/include for imu_ts.h, the 24-bit TIMESTAMP arithmetic.
target_include_directories(recorder_host PUBLIC ${COMPONENTS_DIR}/recorder/include
                                                ${COMPONENTS_DIR}/imu/include)
target_compile_options(recorder_host PRIVATE ${STRICT_WARNINGS})

foreach(name test_rec_stream test_rec_ring test_rec_fsm test_rec_ts test_rec_golden test_rec_util)
    add_executable(${name} ${name}.c)
    target_compile_options(${name} PRIVATE ${STRICT_WARNINGS})
    target_link_libraries(${name} PRIVATE recorder_host unity)
    add_test(NAME ${name} COMMAND ${name})
endforeach()

# REC_PROFILE_* against imu_profile_t (imu.h needs the ESP-IDF stubs).
target_include_directories(test_rec_stream PRIVATE
    ${COMPONENTS_DIR}/imu/include
    ${COMPONENTS_DIR}/level/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../stubs)
target_link_libraries(test_rec_stream PRIVATE imu_frame_host)

target_compile_definitions(test_rec_golden PRIVATE
    GOLDEN_PATH="${CMAKE_CURRENT_SOURCE_DIR}/vectors/golden_stream.bin")
