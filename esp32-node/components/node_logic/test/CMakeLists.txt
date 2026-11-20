# Host build of the node's logic: the component is plain C, so its tests run on the PC without ESP-IDF.
#   cmake -S esp32-node/components/node_logic/test -B build/node-test && cmake --build build/node-test
#   ./build/node-test/node_test
cmake_minimum_required(VERSION 3.16)
project(node_test C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-Wall -Wextra -Werror)
# AddressSanitizer and UBSan catch out-of-bounds access and overflow that a plain run would miss
add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
add_link_options(-fsanitize=address,undefined)

add_executable(node_test
    test_main.c
    ../src/current.c
    ../src/machine_state.c
    ../src/node_event.c
    ../src/message.c
    ../src/backoff.c)
target_include_directories(node_test PRIVATE ../include)
target_link_libraries(node_test m)
