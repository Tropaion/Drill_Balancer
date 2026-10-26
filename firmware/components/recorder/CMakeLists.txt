# Training-data recorder. rec_stream, rec_ring, rec_fsm, rec_ts and rec_util
# are pure C and also built in test_host/recorder and in the simulator's
# libdbsim; recorder.c, rec_wifi.c and rec_audio.c are the ESP glue
# (mirrored in tools/sim/core/sim_core.c).
idf_component_register(SRCS "src/rec_stream.c" "src/rec_ring.c" "src/rec_fsm.c" "src/rec_ts.c"
                            "src/rec_util.c" "src/recorder.c" "src/rec_wifi.c" "src/rec_audio.c"
                       INCLUDE_DIRS "include"
                       REQUIRES app_core esp_common
                       PRIV_REQUIRES imu settings mbedtls nvs_flash esp_timer esp_wifi
                                     esp_netif esp_event lwip esp_driver_i2s esp_hw_support
                                     esp_app_format bootloader_support freertos log
                                     espressif__esp_codec_dev
                                     waveshare__esp32_s3_touch_amoled_1_75)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wdouble-promotion -Wshadow)

# REC_FW_GIT for SESSION.fw (rec_fw_text()): PROJECT_VER names no commit.
# Taken when CMake configures; a commit or checkout (.git/logs/HEAD) makes it
# configure again, an edit alone does not update "-dirty".
set(rec_git "")
find_package(Git QUIET)
if(GIT_FOUND)
    execute_process(COMMAND ${GIT_EXECUTABLE} describe --always --dirty --abbrev=7 --match=-
                    WORKING_DIRECTORY ${COMPONENT_DIR}
                    OUTPUT_VARIABLE rec_git OUTPUT_STRIP_TRAILING_WHITESPACE
                    ERROR_QUIET RESULT_VARIABLE rec_git_result)
    execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --absolute-git-dir
                    WORKING_DIRECTORY ${COMPONENT_DIR}
                    OUTPUT_VARIABLE rec_git_dir OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if(NOT rec_git_result EQUAL 0)
        set(rec_git "")
    endif()
    if(rec_git_dir AND EXISTS "${rec_git_dir}/logs/HEAD")
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rec_git_dir}/logs/HEAD")
    endif()
endif()
target_compile_definitions(${COMPONENT_LIB} PRIVATE REC_FW_GIT="${rec_git}")
