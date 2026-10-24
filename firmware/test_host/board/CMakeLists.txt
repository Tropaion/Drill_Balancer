# Host tests for the AXP2101 register decoding (components/board/src/pmu_events.c).
# ../stubs stands in for the ESP-IDF headers that pmu.h includes.

add_library(pmu_events_host STATIC ${COMPONENTS_DIR}/board/src/pmu_events.c)
target_include_directories(pmu_events_host PUBLIC
    ${COMPONENTS_DIR}/board/src
    ${COMPONENTS_DIR}/board/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../stubs)
target_compile_options(pmu_events_host PRIVATE ${STRICT_WARNINGS})

add_executable(test_pmu_events test_pmu_events.c)
target_compile_options(test_pmu_events PRIVATE ${STRICT_WARNINGS})
target_link_libraries(test_pmu_events PRIVATE pmu_events_host unity)
add_test(NAME test_pmu_events COMMAND test_pmu_events)
