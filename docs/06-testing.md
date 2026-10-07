# 06 · Pruebas

## ROMs de prueba (libres, nunca en el repo)
Se usa el paquete compilado **c-sp/game-boy-test-roms v7.0**:
- URL: `https://github.com/c-sp/game-boy-test-roms/releases/download/v7.0/game-boy-test-roms-v7.0.zip`
- SHA-256: `b9a9d7a1075aa35a3d07c07c34974048672d8520dca9e07a50178f5860c3832c` (3 756 786 bytes, verificado el 2026-09-28)
- Se descarga con `tools/fetch-test-roms.sh`, que verifica el hash, y se extrae en `core/tests/roms/` (ignorada por git).

Incluye Blargg, Mooneye, dmg-acid2, cgb-acid2, rtc3test, MBC3-Tester y SameSuite, cada uno con su README de condición de salida.

## Runner headless (`core/tests/runner.c`)
Un único binario `build/gbtest`:
```
gbtest <rom> --mode {serial|mooneye|acid} [--model dmg|cgb] [--max-frames N] [--expect PATH.rgba] [--dump PATH.rgba]
gbtest <rom> --mode frames --max-frames N --expect REF.rgba [--input GUION]   # captura tras N frames
gbtest <rom> --bench N     # N frames sin límite de velocidad; imprime el múltiplo de tiempo real
gbtest <rom> --mode blargg [--wav out.wav]   # salida por RAM (A000) de dmg_sound…; --wav guarda el audio e informa del pico
gbtest --fuzz-seeds DIR    # semillas para make fuzz
gbtest --unit              # unit tests (core/tests/unit_*.c)
```
Salida: 0 = PASS, 1 = FAIL, 2 = error de uso o de carga. Desde M2 existen todos los modos; desde M8, `--model dmg|cgb|auto` (por defecto `dmg`; `cgb` con un ROM DMG = compatibilidad). Ejemplo: `tools/png2rgba.py ref.png ref.rgba && build/gbtest dmg-acid2.gb --mode acid --expect ref.rgba --dump out.rgba && tools/png2rgba.py --reverse out.rgba out.png`.
| Suite | Condición de salida | Éxito |
|---|---|---|
| Blargg (`cpu_instrs`, `instr_timing`, `mem_timing`) | La salida serie contiene `Passed` o `Failed`, o se alcanza `--max-frames` | Contiene `Passed` |
| Blargg por RAM (`dmg_sound`, modo `blargg`) | Firma `DE B0 61` en `A001–A003` y `A000` ≠ `0x80` | `A000` = 0 (el texto empieza en `A004`) |
| Mooneye | La CPU ejecuta `LD B,B` (0x40) | `B,C,D,E,H,L = 3,5,8,13,21,34`. Fallo: todos `0x42` |
| dmg-acid2 / cgb-acid2 | La CPU ejecuta `LD B,B` (0x40) | Framebuffer idéntico a la referencia |

Paletas para comparar con las referencias de acid2 (según el howto de c-sp):
- **DMG:** `#FFFFFF #AAAAAA #555555 #000000` (es la paleta por defecto del núcleo).
- **CGB en compatibilidad:** BG `#FFFFFF #7BFF31 #0063C6 #000000` y OBJ `#FFFFFF #FF8484 #943939 #000000`.

`tools/png2rgba.py` convierte los PNG de referencia a RGBA crudo de 160×144. Usa solo la stdlib de Python (`zlib`, `struct`), sin Pillow, para que funcione igual en macOS y en Linux (nube).

`core/tests/suite.txt` lista cada caso `hito|ruta|modo|modelo|max_frames|tipo` (la ruta admite comodines; `unit` = unit tests). `make test HITO=Mn` ejecuta los casos de los hitos ≤ Mn (así se detectan regresiones) e imprime una tabla PASS/FAIL. Sale con código ≠ 0 si falla algún caso de tipo **requerido**. `known-fail` e `info` se reportan pero no bloquean. Los casos `acid` y `frames` llevan una 7.ª columna con el PNG de referencia (relativo a `core/tests/roms/`); `run_suite.py` lo convierte a RGBA con `tools/png2rgba.py` en `build/refs/`. Una 8.ª columna opcional es el guion de botones de `--input` (`frame:botones,...`, p. ej. `30:A,40:-` para elegir el subtest de rtc3test).

## Casos requeridos por hito
| Hito | Casos |
|---|---|
| M1 | `mooneye-test-suite/acceptance/boot_regs-dmgABC.gb`, `blargg/cpu_instrs/cpu_instrs.gb`, `blargg/instr_timing/instr_timing.gb`, `blargg/mem_timing/mem_timing.gb`, `mooneye-test-suite/acceptance/timer/*`, `.../acceptance/{ei_sequence,ei_timing,rapid_di_ei,halt_ime0_ei,reti_timing,reti_intr_timing}.gb` |
| M2 | `dmg-acid2/dmg-acid2.gb` (DMG), `.../acceptance/oam_dma/{basic,reg_read}.gb`, `.../acceptance/oam_dma_start.gb` |
| M3 | `mooneye-test-suite/emulator-only/mbc1/*` (excepto `multicart_*`), `.../mbc5/*`, MBC3-Tester, rtc3test (subtests básicos) |
| M5 | `blargg/dmg_sound/rom_singles/01..06` (el resto, `known-fail` permitido) |
| M8 | `mooneye-test-suite/misc/boot_regs-cgb.gb`, `cgb-acid2/cgb-acid2.gbc`, `dmg-acid2/dmg-acid2.gb` en CGB (compatibilidad) |

## Unit tests
`core/tests/unit_*.c` es un mini framework propio de ~50 líneas (`CHECK(expr)`), sin dependencias. Cubre:
- validación de cabecera (tamaños absurdos, códigos `0x52–0x54` rechazados, archivo truncado, MBC no soportado, título de 16/15/11 bytes)
- SHA-256 contra los vectores de FIPS 180-4 (`""`, `"abc"`, 1 MB de `'a'`)
- `gb_load_rom` con fallo de memoria inyectado → `GB_ERR_OUT_OF_MEMORY` sin fugas y con la instancia usable. `gbtest` se compila con `-DGB_TEST_HOOKS`, que añade a la instancia el campo `dbg.fail_alloc_at` (la reserva n-ésima falla); la biblioteca normal no lo lleva
- mapeo de bancos con ROMs sintéticos generados en el test (cada banco lleno con su número)
- flancos del timer
- round-trip de save states (guardar → cargar → mismo framebuffer tras N frames)
- rechazo de estados corruptos
- cable link virtual (`unit_link.c`, M9): deriva del lockstep tras 10⁶ bloques, causalidad en el primer flanco, cable suelto y reentrada, intercambio de bytes entre dos ROMs sintéticos (DMG, CGB con reloj rápido, doble velocidad, compatibilidad), framebuffer sin cortes y realineado tras recarga o save state

## Núcleo GBA (`gba/`, G0–G9)
- **Suites** (`make -C gba test`, `make -C gba asan`; runner `gba/tests/runner.c`, casos en `gba/tests/suite.txt` con la misma convención `hito|ruta|modo|…|tipo`): unit tests propios (`gbatest --unit`); **SingleStepTests/ARM7TDMI** (45 × 50 000 casos, 100 %); **jsmolka/gba-tests** (`arm`, `thumb`, `memory`, `bios`, `nes`, `save/*`, `ppu/*`); 14 escenas homebrew propias de PPU, ROMs de EEPROM, RTC y audio (`gba/tests/homebrew/`, compiladas con `clang --target=armv4t` y `ld.lld`); modos `state` y `det` de determinismo. Se descargan con `tools/fetch-gba-test-roms.sh` (nunca versionadas).
- **Oráculo:** mGBA 0.10.5 compilado aparte (`make -C gba oracle`, `tools/gba-compare.py`, `tools/oracle-gba/`), solo para desarrollo y nunca enlazado en la app.
- **Fuzzers** (`make -C gba fuzz`): `fuzz_load_rom`, `fuzz_cart`, `fuzz_state_load` y `fuzz_io`, 600 s cada uno en la nube (resultado en la evidencia de cada hito).
- **Chequeos:** `check-header` (el header compila aislado), `check-globals` y `check-symbols` (sin colisiones con el núcleo GB; pensados para `nm` de Linux).
- **Limitación en el Mac de Joel:** `make -C gba test`/`asan` completos necesitan `ld.lld` (homebrew de la suite) y los ROMs de SingleStepTests, que no están en este Mac; ahí se ejecutan `gbatest --unit`, la variante ASan compilada con el `EXTRA` del Makefile y los ROMs de jsmolka con el runner. `check-symbols`/`check-globals` dan falsos positivos con el `nm` de macOS (prefijo `_`, símbolos locales `s` de datos constantes de Mach-O); la suite completa corre en la nube (Linux) y en el CI.
- `make -C core test` usa `HITO=M1` por defecto: la regresión completa del núcleo GB es `make -C core test HITO=M8` (157/157 requeridos).

## Tests de la app (G7–G8)
Suite unitaria de Swift (`GBATests`, ajustes por juego, L/R, saves GBA con RTC, estados por configuración) y de UI con capturas (`tools/ios-screenshots.sh`; en G8: 117 tests y 94 capturas). Las pruebas con temporizadores cortos (`SaveMirrorTests`, `StateSRAMTests`) esperan 2 s y pueden fallar si el Mac está muy cargado: repetirlas con la máquina libre.

## Sanitizers y fuzzing
- `make asan`: todo lo anterior compilado con `-fsanitize=address,undefined -fno-omit-frame-pointer`.
- `make fuzz`: cuatro fuzzers en `core/fuzz/`:
  - `fuzz_load_rom.c`: los bytes son el ROM. Se carga, se ejecutan 30 frames con botones pseudoaleatorios derivados de los bytes, se hace el round-trip de SRAM y de save state (un estado recién guardado **tiene** que cargar: si no, `abort()`) y se destruye. Si el archivo es corto para lo que declara, se repite rellenándolo con ceros (máx. 1 MiB) para que la CPU ejecute código arbitrario.
  - `fuzz_state_load.c`: carga un ROM sintético (MBC3+RTC+RAM, MBC1+RAM, MBC5+RAM o ROM-only según el primer byte) y después `gb_state_load` con los bytes: (1) tal cual; (2) con cabecera y CRC corregidos; (3) **mutación estructurada**: cada 4 bytes son (sección, desplazamiento, desplazamiento, valor) y se **asigna** ese byte dentro de la sección elegida de un estado válido de la instancia (en secciones de más de 256 bytes, solo en sus primeros o últimos 32), con el CRC recalculado, para llegar a combinaciones de campos incoherentes; (4) los bytes como `.sav` (RAM + bloque RTC) en `gb_sram_load` y después `gb_rtc_set_time`. Tras cada carga aceptada se ejecutan frames.
  - `fuzz_link.c` (M9): dos instancias con código arbitrario (tras un prólogo que escribe SB y SC) conectadas por el cable virtual, con modelo por lado, save state, recarga del ROM y desconexión a mitad según el primer byte. Además de ASan/UBSan comprueba el lockstep (`|t_a - t_b| ≤ 44` tras cada frame) y que no se cuelga. Semillas: los programas de intercambio de `unit_link.c` (DMG, CGB rápido, doble velocidad, compatibilidad, con save state y con recarga/desconexión); se ejecuta con `-len_control=0 -max_len=16384`.
  - `fuzz_progress.c` (N6-C): el lector de progreso Pokémon. Entrada `[juego][indicadores][partida]`: un preajuste de cabecera (Rojo, Azul, Amarillo, Oro, Plata, Cristal, Amarillo europeo D/F/I/S) o una cabecera libre, con opciones para recalcular el checksum, los bytes de validación, el nombre y los rangos (BCD, horas, dinero, minutos, segundos) y así llegar a cada rechazo y a las ramas de éxito, y longitudes de partida y de cabecera elegidas. La cabecera y la partida se copian a bloques de tamaño exacto (ASan detecta cualquier lectura fuera de rango) y se comprueban invariantes (UTF-8 válido, minutos/segundos < 60, horas ≤ 999 en la 2.ª gen, dinero ≤ 999999, Pokédex ≤ 151/251, salida a cero si falla, resultado idéntico en dos llamadas). Semillas: una partida sintética válida por juego. `make fuzz-progress` lo ejecuta solo (`-len_control=0 -max_len=33000`); `make fuzz-progress-smoke` es el equivalente sin libFuzzer (mutación aleatoria con ASan/UBSan, sin cobertura guiada).
  - `make fuzz` genera antes unas semillas (`gbtest --fuzz-seeds fuzz/corpus`, ignoradas por git) y compila con `-fno-sanitize-recover=all` para que cualquier UB sea un crash.
- **Importante:** el clang de Apple **no incluye libFuzzer**. En macOS, `brew install llvm` y `make fuzz CC=$(brew --prefix llvm)/bin/clang`. En Linux (Claude en la nube) sirve el clang del sistema. El Makefile detecta la falta de libFuzzer y lo explica.
- Criterio de M3: `FUZZ_SECONDS=600` por fuzzer sin crash, leak ni UB.

## Oráculo SameBoy (opcional, dev-only)
- `make -C core oracle` clona SameBoy en un tag fijo en `tools/oracle/SameBoy/` (ignorado por git), lo compila como `libsameboy` (`make lib`, requiere `rgbds`) y enlaza `tools/oracle/diff.c`.
- `diff.c` ejecuta PocketGB y SameBoy en paralelo con la misma ROM y la misma secuencia de botones, y compara framebuffers frame a frame. Informa del primer frame distinto y guarda ambos PNG.
- Licencia: SameBoy es Expat/MIT **salvo sus directorios `iOS/` y `HexFiend/`**. El oráculo solo usa `Core/`, y la app **nunca** lo enlaza.
- Uso principal: depurar diferencias en Pokémon (A9 audio de Pikachu, glitches gráficos) con **volcados propios que nunca salen de la máquina local**.

## Pruebas de Android
Comandos en [android/README.md](../android/README.md). Última cifra registrada (A7, `docs/auditorias/A7-android-evidencia.md`): JVM 372/372, instrumentados 333/333, kill-test 50/50, lint sin errores.
- **JVM** (`./gradlew :app:testDebugUnitTest`, `app/src/test`): lógica pura sin dispositivo (mapeo de mando, geometría de controles, `SaveResolution`, `SramFlushPolicy`, `SaveStore`, `AtomicFile`, columnas por fuente, esquemas de contraste, preferencias).
- **Instrumentadas** (`./gradlew :app:connectedDebugAndroidTest`, `app/src/androidTest`): emulador o teléfono; núcleo nativo real, `SaveCoordinator`, SAF, interfaz Compose, ciclo de vida, TalkBack (nodos virtuales), tamaños táctiles, rotación sin pausa y `CatalogCoverageTest` (el catálogo cubre `tools/android-screens.txt`).
- **`ProcessKillTest`**: mata un proceso hijo en mitad de escrituras de partida y verifica la recuperación.
- **Kill-test** (`tools/android-save-kill-test.sh [N]`): N iteraciones de `save-stress` + cierre forzado + `save-verify` en el emulador (50/50 en A6 y A7). No combina rotación y cierre.
- **Catálogo** (`tools/android-screenshots.sh`): capturas Debug en claro/oscuro, color dinámico, fuente grande, contraste, ventana ancha y recorte; revisadas a ojo, no se versionan ([diseno-android/VERIFICACION.md](diseno-android/VERIFICACION.md)).
- **Emulador:** `Small_Phone_API_35`, sin ventana, animaciones a 0 y `hide_error_dialogs` (ver el README). Las instrumentadas y el catálogo no pueden ejecutarse a la vez en el mismo emulador.
- **Limitaciones:** el emulador no prueba mando físico real, TalkBack con gestos, audio real ni rendimiento a 60 fps; el recorte y la tablet se simulan; el backup en la nube de Android no se ejercita. Esa parte es manual: [PRUEBAS-JOEL.md](PRUEBAS-JOEL.md). Con poca RAM conviene `--no-daemon --max-workers=1` y no encadenar el emulador y Gradle en paralelo.

## Prueba de aceptación manual en iPhone (checklist por release; la lista ampliada de Android e iPhone está en [PRUEBAS-JOEL.md](PRUEBAS-JOEL.md))
- [ ] Rojo y Amarillo arrancan y muestran la intro con música.
- [ ] 10 min de juego sin cortes de audio (el contador de underruns en el menú de depuración es 0).
- [ ] Guardar en el juego → forzar cierre desde el multitarea → reabrir: la partida está.
- [ ] Reinstalar desde Xcode → la partida sigue.
- [ ] Horizontal: botones translúcidos, A+B simultáneos y deslizar sobre el D-pad funcionan.
- [ ] Vertical: layout correcto.
- [ ] Modo avión activado: todo funciona (confirma que la app no necesita red).

## Núcleo GBA (`gba/`)
- **Pruebas libres:** `tools/fetch-gba-test-roms.sh` descarga [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (MIT) y [SingleStepTests/ARM7TDMI](https://github.com/SingleStepTests/ARM7TDMI) (MIT, ~1 GB) en `gba/tests/roms/` (ignorado por git), cada repo fijado a un commit (git verifica cada objeto por su hash). No corre en el arranque de sesión por su tamaño.
- **Runner `gbatest`** (`gba/tests/runner.c`): `--sst ARCHIVO` (una instrucción por caso: registros de todos los bancos, CPSR/SPSR, pipeline y escrituras al bus en orden), `ROM --mode jsmolka` (termina en un bucle `b .` con el resultado en r12; 0 = todo bien), `ROM --bench N`, `--unit`.
- **Suite:** `gba/tests/suite.txt`, formato `hito|ruta|modo|max_frames|tipo`. `make -C gba test HITO=Gn` ejecuta los casos de los hitos ≤ Gn; `make -C gba asan` repite con ASan + UBSan (SingleStepTests limitado a 5 000 casos por archivo).
- **Reglas:** `make -C gba check-header check-globals check-symbols`.
- **PPU:** modo `ref` (frame idéntico a un PNG de `gba/tests/ref/`). ROMs homebrew propias en `gba/tests/homebrew/` (`make -C gba homebrew`, necesita `clang`, `ld.lld` y `llvm-objcopy`). Oráculo opcional: `make -C gba oracle` compila mGBA 0.10.5 en `tools/oracle/mgba` (ignorado por git) y `tools/gba-compare.py ROM FRAMES --out DIR` compara con PNG de diferencias.
- **CI:** `.github/workflows/gba.yml` (Linux, runners de GitHub) con caché de las pruebas.
