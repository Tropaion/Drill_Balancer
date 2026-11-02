cmake_minimum_required(VERSION 3.16)

# The one version of firmware and app; the release tag must match it
# (tools/ci/release_check.sh).
file(READ ${CMAKE_CURRENT_LIST_DIR}/../VERSION PROJECT_VER)
string(STRIP "${PROJECT_VER}" PROJECT_VER)
if(NOT PROJECT_VER MATCHES "^[0-9]+\\.[0-9]+\\.[0-9]+$")
    message(FATAL_ERROR "VERSION is not MAJOR.MINOR.PATCH: ${PROJECT_VER}")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../VERSION)

# Build only some of our components (plus main and their dependencies), e.g.
#   DB_COMPONENTS="board;imu" idf.py -B build-board -D SDKCONFIG=build-board/sdkconfig build
if(DEFINED ENV{DB_COMPONENTS})
    set(COMPONENTS main $ENV{DB_COMPONENTS})
endif()

# sdkconfig.defaults plus one fragment per feature in sdkconfig.d/ (applied in
# name order). Defaults only affect options not yet in sdkconfig: delete
# sdkconfig after changing them.
file(GLOB DB_SDKCONFIG_FRAGMENTS ${CMAKE_CURRENT_LIST_DIR}/sdkconfig.d/*.defaults)
list(SORT DB_SDKCONFIG_FRAGMENTS)
set(SDKCONFIG_DEFAULTS ${CMAKE_CURRENT_LIST_DIR}/sdkconfig.defaults ${DB_SDKCONFIG_FRAGMENTS})

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(drillbalancer)
