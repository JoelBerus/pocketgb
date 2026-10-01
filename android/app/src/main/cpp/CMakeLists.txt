cmake_minimum_required(VERSION 3.22.1)
project(pocketgb LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(CORE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core")

add_library(
    pocketgb
    SHARED
    native_session.c
    pocketgb_jni.c
    "${CORE_ROOT}/src/apu.c"
    "${CORE_ROOT}/src/cart.c"
    "${CORE_ROOT}/src/cgb.c"
    "${CORE_ROOT}/src/cpu.c"
    "${CORE_ROOT}/src/dma.c"
    "${CORE_ROOT}/src/gb.c"
    "${CORE_ROOT}/src/joypad.c"
    "${CORE_ROOT}/src/link.c"
    "${CORE_ROOT}/src/mmu.c"
    "${CORE_ROOT}/src/ppu.c"
    "${CORE_ROOT}/src/rtc.c"
    "${CORE_ROOT}/src/serial.c"
    "${CORE_ROOT}/src/sha256.c"
    "${CORE_ROOT}/src/state.c"
    "${CORE_ROOT}/src/timer.c"
)

target_include_directories(
    pocketgb
    PRIVATE
    "${CORE_ROOT}/include"
    "${CORE_ROOT}/src"
)

target_compile_options(pocketgb PRIVATE -Wall -Wextra -Werror -pedantic)
target_link_libraries(pocketgb PRIVATE m)
