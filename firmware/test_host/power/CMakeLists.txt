# Host tests for the power component's pure C state machine.

add_library(power_fsm_host STATIC ${COMPONENTS_DIR}/power/src/power_fsm.c)
target_include_directories(power_fsm_host PUBLIC ${COMPONENTS_DIR}/power/include)
target_compile_options(power_fsm_host PRIVATE ${STRICT_WARNINGS})

add_executable(test_power_fsm test_power_fsm.c)
target_compile_options(test_power_fsm PRIVATE -Wall -Wextra -Werror)
target_link_libraries(test_power_fsm PRIVATE power_fsm_host unity)
add_test(NAME test_power_fsm COMMAND test_power_fsm)
