cmake_minimum_required(VERSION 3.22.1)
project(pocketgb LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(CORE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core")
set(GBA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../gba")

# Núcleo Game Boy Advance (N8): biblioteca de objetos con sus propias rutas de cabeceras y los flags de
# gba/Makefile, enlazada en la misma libpocketgb.so. No lleva sha256.c: lo aporta una sola vez la lista del
# núcleo GB de abajo (como en gba/Makefile). Sus objetos entran todos en la biblioteca (no un .a del que el
# enlazador elija miembros), así que un símbolo repetido entre los dos núcleos es un error de enlace.
add_library(
    pocketgba
    OBJECT
    "${GBA_ROOT}/src/arm7.c"
    "${GBA_ROOT}/src/arm_mulcarry.c"
    "${GBA_ROOT}/src/bus.c"
    "${GBA_ROOT}/src/gba.c"
    "${GBA_ROOT}/src/gba_apu.c"
    "${GBA_ROOT}/src/gba_cart.c"
    "${GBA_ROOT}/src/gba_ppu.c"
    "${GBA_ROOT}/src/gba_state.c"
    "${GBA_ROOT}/src/hle.c"
    "${GBA_ROOT}/src/io.c"
)
target_include_directories(pocketgba PRIVATE "${GBA_ROOT}/include" "${GBA_ROOT}/src")
# N8-H1: también en Debug con -O2 (sin perder -g): `installDebug` es la ruta de instalación de Joel y el núcleo GBA a
# -O0 va 3–5× más lento, así que las pruebas de 60 fps y las medidas de ms/frame no serían las reales.
target_compile_options(pocketgba PRIVATE -Wall -Wextra -Werror -pedantic -fno-common $<$<CONFIG:Debug>:-O2>)
set_target_properties(pocketgba PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
    pocketgb
    SHARED
    audio_output.c
    audio_ring.c
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
    $<TARGET_OBJECTS:pocketgba>
)

target_include_directories(
    pocketgb
    PRIVATE
    "${CORE_ROOT}/include"
    "${CORE_ROOT}/src"
    "${GBA_ROOT}/include"
)

# N8-H1: el núcleo GB y la sesión nativa (bucle de emulación y blit a la superficie) también con -O2 en Debug.
target_compile_options(pocketgb PRIVATE -Wall -Wextra -Werror -pedantic $<$<CONFIG:Debug>:-O2>)
target_link_libraries(pocketgb PRIVATE aaudio android m)
