# G6 · Evidencia (save states y determinismo)

Rama `g6-gba-states` (sobre G5), 2026-10-05, Linux (Ubuntu clang 18.1.3).

## Qué se implementó
- `gba/src/state.c`: formato "PGBA" v1 (cabecera con SHA-256 del ROM, CRC-32 y serialización por visitante de CPU, bus, E/S, PPU, cartucho/RTC y APU). La carga valida sobre una copia (`malloc`) y solo entonces la aplica: un estado corrupto nunca deja la instancia a medias.
- Runner: modos `state` (guardar a los N frames, seguir M, recargar, repetir y comparar framebuffer y audio) y `det` (dos instancias, mismos frames idénticos).
- Fuzzers `fuzz_state_load` (estados arbitrarios) y `fuzz_io` (escrituras arbitrarias a E/S vía `gba_fuzz_poke`).

## Salida
```
$ make -C gba test HITO=G6
PASS  G6 ppu_scene_11.gba: PASS build/hb/ppu_scene_11.gba (estado de 681754 bytes, 60+120 frames)
PASS  G6 ppu_scene_7.gba: PASS build/hb/ppu_scene_7.gba (estado de 681754 bytes, 60+120 frames)
PASS  G6 audio_ds.gba: PASS build/hb/audio_ds.gba (estado de 681754 bytes, 45+120 frames)
PASS  G6 audio_psg.gba: PASS build/hb/audio_psg.gba (estado de 681754 bytes, 45+120 frames)
PASS  G6 eeprom.gba: PASS build/hb/eeprom.gba (estado de 681754 bytes, 2+120 frames)
PASS  G6 gba-tests/save/flash128.gba: PASS tests/roms/gba-tests/save/flash128.gba (estado de 681754 bytes, 30+120 frames)
PASS  G6 gba-tests/bios/bios.gba: PASS tests/roms/gba-tests/bios/bios.gba (estado de 681754 bytes, 1+120 frames)
PASS  G6 ppu_scene_11.gba: PASS build/hb/ppu_scene_11.gba (1000 frames idénticos en dos instancias)
PASS  G6 audio_ds.gba: PASS build/hb/audio_ds.gba (300 frames idénticos en dos instancias)
PASS  G6 gba-tests/save/flash128.gba: PASS tests/roms/gba-tests/save/flash128.gba (200 frames idénticos en dos instancias)
91/91 sin fallos requeridos (16.4 s)
$ make -C gba asan HITO=G6
91/91 sin fallos requeridos (41.1 s)
$ make -C gba check-header check-globals check-symbols
Símbolos de los dos núcleos sin colisiones: OK
```

## Fuzzing (600 s cada uno, ASan + UBSan)
```
$ make -C gba fuzz-one FUZZER=fuzz_io FUZZ_SECONDS=600
Done 62602 runs in 601 second(s)
salida: 0
$ make -C gba fuzz-one FUZZER=fuzz_state_load FUZZ_SECONDS=600
Done 7882 runs in 601 second(s)
salida: 0
```
Sin crashes ni avisos.
