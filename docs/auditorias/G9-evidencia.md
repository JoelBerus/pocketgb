# G9 · Evidencia (regresión y documentación del cierre GBA)

Rama `g8-gba-controls` (HEAD de partida `92ff6e2`), 2026-10-06, Mac de Joel (simulador iPhone 17 Pro, iOS 26; xcodebuild siempre bajo el mutex del simulador). La carga del Mac era muy alta (hasta 150) por otros agentes.

## Núcleo GB (intacto)
`make -C core test` usa `HITO=M1` por defecto (65/65); la regresión completa es con `HITO=M8`:
```
$ make -C core test HITO=M8 2>&1 | tail -4
M8    FAIL  known-fail  cgb-acid-hell/cgb-acid-hell.gbc (cgb) [cgb-acid-hell.png]   0.1s  FAIL: acid: 2 píxeles distintos (primero en x=80 y=68) (frame 18)

157/176 PASS · requeridos: 157/157 PASS · HITO=M8
OK: todos los casos requeridos en PASS
$ make -C core asan HITO=M8     # salida filtrada con grep -v "PASS ": la línea de recuento quedó fuera
M8    FAIL  known-fail  cgb-acid-hell/cgb-acid-hell.gbc (cgb) [cgb-acid-hell.png]  34.3s  FAIL: acid: 2 píxeles distintos ...

OK: todos los casos requeridos en PASS
```
(Un primer intento de `asan` terminó con `InterruptedError` de Python por la carga del Mac; el segundo completó.) Los `known-fail` son los documentados en ESTADO.md.

## Núcleo GBA en el Mac
`make -C gba test` y `make -C gba asan` **no se pueden ejecutar completos en este Mac**: falta `ld.lld` (homebrew de la suite) y los ROMs de SingleStepTests. Salida real:
```
$ make -C gba test 2>&1 | tail -3
ld.lld -T tests/homebrew/link.ld build/hb/crt0.o build/hb/ppu_scene_0.o build/hb/support.o -o build/hb/ppu_scene_0.elf
make: ld.lld: No such file or directory
make: *** [build/hb/ppu_scene_0.gba] Error 1
```
Alternativa ejecutada (la misma de G8) más los ROMs de jsmolka que sí están en el Mac:
```
$ make -C gba build/gbatest && gba/build/gbatest --unit
make: `build/gbatest' is up to date.
PASS unit: 0 fallos
$ ASan+UBSan manual (EXTRA del Makefile)
clang -std=c11 -Wall -Wextra -Werror -pedantic -fno-common -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all -DGBA_TEST_HOOKS -Iinclude -Isrc -I../core/include src/arm7.c src/arm_mulcarry.c src/bus.c src/gba.c src/gba_apu.c src/gba_cart.c src/gba_ppu.c src/gba_state.c src/hle.c src/io.c ../core/src/sha256.c tests/runner.c tests/unit.c -o build/asan/gbatest -lm
PASS unit: 0 fallos
$ jsmolka arm/thumb/memory/bios/nes con el runner
PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)
PASS tests/roms/gba-tests/memory/memory.gba (2 frames)
PASS tests/roms/gba-tests/bios/bios.gba (3 frames)
PASS tests/roms/gba-tests/nes/nes.gba (2 frames)
```
`check-header`: `pocketgba.h compila aislado: OK`.
`check-symbols` y `check-globals` **fallan en macOS por falsos positivos del `nm` de Mach-O** (el filtro `sha256` no reconoce el prefijo `_`; los símbolos `s` son datos constantes y literales locales, p. ej. `l_.str`, `lJTI`, `_K`, `_hle_sin`):
```
$ make -C gba check-symbols   ->  Símbolos repetidos entre núcleos: _sha256   (el único; es el SHA-256 compartido por diseño)
$ make -C gba check-globals   ->  lista símbolos 's' de Mach-O (solo lectura)
```
Equivalente manual con el filtro adaptado a macOS: repetidos entre núcleos `[]`, símbolos GBA sin prefijo `[]` (ver `gba-checks-mac.txt` en el scratchpad). La suite completa (SingleStepTests, jsmolka, escenas, fuzzers) corre en Linux/CI; sus cifras de G1–G6 están en las evidencias de cada hito. No se ejecutó aquí: SingleStepTests, escenas homebrew, audio y fuzzers.

## App iOS
```
$ ... xcodebuild test ... -only-testing:PocketGBTests   (suite unitaria completa)
✔ Test run with 117 tests in 12 suites passed after 5.996 seconds.
** TEST SUCCEEDED **
$ ... xcodebuild build -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
```
Una primera ejecución completa con el Mac a carga ~120 falló 7 tests de temporizadores de 2 s (`SaveMirrorTests`, `StateSRAMTests`, `GBATests.noSaveWithClock…`: `timedOut`); repetidas esas tres suites con menos carga: 43 tests en verde, y después la suite completa: verde. Es flakiness por carga, no un cambio de código (este lote no toca código).
El catálogo de UI no se repitió: el último ejecutado fue el de G8 (117 tests, 94 capturas; CI verde de `c5d8286`, [G8-evidencia](G8-evidencia.md)).

## Documentación
Actualizados: 10-gba-spec (RTC al final del `.sav`, estado v2, known-fail/límites), 02-arquitectura, 04-ios-spec (textura por consola, GBA, L/R, ajustes por juego, BIOS), 06-testing, 08-roms-legal, 09-referencias, diseno/SPEC (H11), hitos/README, hitos/G-README, ESTADO.

## Pendiente (🍎, no cumplido)
60 fps con el HUD DEBUG, Kirby ≥ 30 min con cierre forzado, audio, L/R; aprobación de Joel del descarte de G7-1 y decisión sobre G7-3; auditoría final Codex; PR a `main`.
