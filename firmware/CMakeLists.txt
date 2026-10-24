cmake_minimum_required(VERSION 3.16)

set(PROJECT_VER "0.1.0")

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
