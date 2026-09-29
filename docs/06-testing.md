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
gbtest <rom> --bench N     # N frames sin límite de velocidad; imprime el múltiplo de tiempo real
gbtest --unit              # unit tests (core/tests/unit_*.c)
```
Salida: 0 = PASS, 1 = FAIL, 2 = error de uso o de carga. En M1 existen `serial`, `mooneye`, `--bench` y `--unit`; `acid`, `--expect` y `--dump` llegan en M2, y `--model cgb` en M8.
| Suite | Condición de salida | Éxito |
|---|---|---|
| Blargg (`cpu_instrs`, `instr_timing`, `mem_timing`, `dmg_sound`) | La salida serie contiene `Passed` o `Failed`, o se alcanza `--max-frames` | Contiene `Passed` |
| Mooneye | La CPU ejecuta `LD B,B` (0x40) | `B,C,D,E,H,L = 3,5,8,13,21,34`. Fallo: todos `0x42` |
| dmg-acid2 / cgb-acid2 | La CPU ejecuta `LD B,B` (0x40) | Framebuffer idéntico a la referencia |

Paletas para comparar con las referencias de acid2 (según el howto de c-sp):
- **DMG:** `#FFFFFF #AAAAAA #555555 #000000` (es la paleta por defecto del núcleo).
- **CGB en compatibilidad:** BG `#FFFFFF #7BFF31 #0063C6 #000000` y OBJ `#FFFFFF #FF8484 #943939 #000000`.

`tools/png2rgba.py` convierte los PNG de referencia a RGBA crudo de 160×144. Usa solo la stdlib de Python (`zlib`, `struct`), sin Pillow, para que funcione igual en macOS y en Linux (nube).

`core/tests/suite.txt` lista cada caso `hito|ruta|modo|modelo|max_frames|tipo` (la ruta admite comodines; `unit` = unit tests). `make test HITO=Mn` ejecuta los casos de los hitos ≤ Mn (así se detectan regresiones) e imprime una tabla PASS/FAIL. Sale con código ≠ 0 si falla algún caso de tipo **requerido**. `known-fail` e `info` se reportan pero no bloquean. En M2 se añade una 7.ª columna opcional con la referencia de acid2.

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
- `gb_load_rom` con fallo de memoria inyectado (`-DGB_TEST_FAIL_ALLOC=n`) → `GB_ERR_OUT_OF_MEMORY` sin fugas y con la instancia usable
- mapeo de bancos con ROMs sintéticos generados en el test (cada banco lleno con su número)
- flancos del timer
- round-trip de save states (guardar → cargar → mismo framebuffer tras N frames)
- rechazo de estados corruptos

## Sanitizers y fuzzing
- `make asan`: todo lo anterior compilado con `-fsanitize=address,undefined -fno-omit-frame-pointer`.
- `make fuzz`: dos fuzzers en `core/fuzz/`:
  - `fuzz_load_rom.c`: los bytes son el ROM. Se carga, se ejecutan 30 frames con botones pseudoaleatorios derivados de los bytes y se destruye.
  - `fuzz_state_load.c`: carga un ROM sintético fijo y después `gb_state_load` con los bytes.
- **Importante:** el clang de Apple **no incluye libFuzzer**. En macOS, `brew install llvm` y `make fuzz CC=$(brew --prefix llvm)/bin/clang`. En Linux (Claude en la nube) sirve el clang del sistema. El Makefile detecta la falta de libFuzzer y lo explica.
- Criterio de M3: `FUZZ_SECONDS=600` por fuzzer sin crash, leak ni UB.

## Oráculo SameBoy (opcional, dev-only)
- `make -C core oracle` clona SameBoy en un tag fijo en `tools/oracle/SameBoy/` (ignorado por git), lo compila como `libsameboy` (`make lib`, requiere `rgbds`) y enlaza `tools/oracle/diff.c`.
- `diff.c` ejecuta PocketGB y SameBoy en paralelo con la misma ROM y la misma secuencia de botones, y compara framebuffers frame a frame. Informa del primer frame distinto y guarda ambos PNG.
- Licencia: SameBoy es Expat/MIT **salvo sus directorios `iOS/` y `HexFiend/`**. El oráculo solo usa `Core/`, y la app **nunca** lo enlaza.
- Uso principal: depurar diferencias en Pokémon (A9 audio de Pikachu, glitches gráficos) con **volcados propios que nunca salen de la máquina local**.

## Prueba de aceptación manual en iPhone (checklist por release)
- [ ] Rojo y Amarillo arrancan y muestran la intro con música.
- [ ] 10 min de juego sin cortes de audio (el contador de underruns en el menú de depuración es 0).
- [ ] Guardar en el juego → forzar cierre desde el multitarea → reabrir: la partida está.
- [ ] Reinstalar desde Xcode → la partida sigue.
- [ ] Horizontal: botones translúcidos, A+B simultáneos y deslizar sobre el D-pad funcionan.
- [ ] Vertical: layout correcto.
- [ ] Modo avión activado: todo funciona (confirma que la app no necesita red).
