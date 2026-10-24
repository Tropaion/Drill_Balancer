# UI host checks.
#
# test_ui_logic: unit test of the pure helpers (ui_logic.c), a normal ctest.
#
# ui_preview: the real UI on host LVGL, rendered to PNGs in out/ (gitignored).
# Not a ctest and not in the default build, so the other host tests don't
# need LVGL:
#   cmake --build build-test-x --target ui_preview_png
set(UI_DIR ${COMPONENTS_DIR}/ui)

add_executable(test_ui_logic test_ui_logic.c ${UI_DIR}/src/ui_logic.c)
target_include_directories(test_ui_logic PRIVATE ${UI_DIR}/src ${COMPONENTS_DIR}/app_core/include)
target_compile_options(test_ui_logic PRIVATE ${STRICT_WARNINGS})
target_link_libraries(test_ui_logic PRIVATE settings_host unity)
add_test(NAME test_ui_logic COMMAND test_ui_logic)

# --- preview harness ---
set(UI_LVGL_DIR ${COMPONENTS_DIR}/../managed_components/lvgl__lvgl)
set(UI_PREVIEW_OUT ${CMAKE_CURRENT_SOURCE_DIR}/out)

if(EXISTS ${UI_LVGL_DIR}/lvgl.h)
    file(GLOB_RECURSE UI_LVGL_SRCS ${UI_LVGL_DIR}/src/*.c)
    # Third-party code, built as is: its warnings are not ours.
    add_library(ui_lvgl_host STATIC EXCLUDE_FROM_ALL ${UI_LVGL_SRCS})
    target_compile_definitions(ui_lvgl_host PUBLIC LV_CONF_INCLUDE_SIMPLE)
    target_include_directories(ui_lvgl_host SYSTEM PUBLIC ${UI_LVGL_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(ui_lvgl_host PRIVATE -w)
    target_link_libraries(ui_lvgl_host PUBLIC m)

    add_executable(ui_preview EXCLUDE_FROM_ALL
        preview.c
        stubs/preview_stubs.c
        ${UI_DIR}/src/ui.c
        ${UI_DIR}/src/ui_level.c
        ${UI_DIR}/src/ui_settings.c
        ${UI_DIR}/src/ui_cal.c
        ${UI_DIR}/src/ui_logic.c
        ${UI_DIR}/src/fonts/ui_font_num112.c)
    target_include_directories(ui_preview PRIVATE
        stubs ${CMAKE_CURRENT_SOURCE_DIR}/../stubs ${UI_DIR}/include ${UI_DIR}/src ${COMPONENTS_DIR}/app_core/include)
    target_compile_options(ui_preview PRIVATE ${STRICT_WARNINGS})
    target_link_libraries(ui_preview PRIVATE ui_lvgl_host settings_host)

    add_custom_target(ui_preview_png
        COMMAND ${CMAKE_COMMAND} -E make_directory ${UI_PREVIEW_OUT}
        COMMAND $<TARGET_FILE:ui_preview> ${UI_PREVIEW_OUT}
        COMMAND python3 ${CMAKE_CURRENT_SOURCE_DIR}/rgb2png.py ${UI_PREVIEW_OUT}
        DEPENDS ui_preview
        USES_TERMINAL)
endif()
