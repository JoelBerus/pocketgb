# G7 · Evidencia (app multiconsola: biblioteca y sesión)

Rama `g7-gba-app` (PR #17, apilada sobre #16), 2026-10-05. Criterio de G7 (`docs/hitos/G-README.md` §G7): CI verde, capturas de la biblioteca con un homebrew `.gba`, test de que un save GBA de tamaño incorrecto no se sobrescribe, y los 93 tests previos de la app en verde.

## Qué se implementó
- `ConsoleCore` (protocolo) con `CoreBridge` (GB, comportamiento sin cambios) y `GBACoreBridge` (sobre `pocketgba.h`); `Console`/`ScreenSize` (160×144 y 240×160).
- `gba/src` dentro del target de Xcode (fuentes con nombre repetido renombradas con prefijo `gba_`; `gba/include/module.modulemap` = `PocketGBACore`). `make check-symbols` garantiza que no colisionan con `core/`.
- Biblioteca: `.gba` hasta 32 MiB, cabecera GBA válida, errores propios (`gbaBadHeader`, `gbaRomTooLarge`), insignia y filtro GBA.
- Partida GBA = medio (SRAM/Flash/EEPROM) + 16 bytes de RTC al final en **un solo `.sav`**: misma ruta atómica, backups y espejo junto al ROM (regla dura 6). No hay `.rtc` aparte (decisión de implementación: simplifica `SaveResolution`, que no cambia).
- BIOS opcional `gba_bios.bin` en la raíz de la biblioteca, validada por SHA-256 (`BIOSFile`); sin ella, HLE. Informada en Ajustes › Emulación.
- Pendiente para G8 (declarado): L/R (`setButtons` aún toma `UInt8`), controles y pantalla 3:2 propios, audio escuchado.

## Verificación (Mac de Joel, simulador iPhone 17 Pro, iOS 26.x)
```
$ tools/ios-screenshots.sh   # sobre c3b9c12 + test AX5 corregido
✔ Suite GBATests passed
✔ Test run with 100 tests in 12 suites passed       (93 previos + 7 de GBATests)
** BUILD SUCCEEDED **  (Debug)    xcodebuild Release: exit 0
85 capturas (84 previas + game-gba en vertical y horizontal; ver nota sobre la rotación)
$ make -C core test
65/68 PASS · requeridos: 65/65 PASS    (GB sin regresiones; el 157/157 de D8 incluye CGB y se corre en CI Linux)
```
Tests de GBA (`ios/PocketGBTests/GBATests.swift`): cabecera/título/checksum, escáner (lista `.gba` y respeta el límite), el núcleo corre el ROM y guarda la SRAM, estados ida y vuelta y rechazo de estados GB, **`wrongSizeGBASaveIsNeverOverwritten`**, la sesión guarda por la ruta normal, BIOS solo si es el volcado oficial.

Capturas `game-gba-portrait-dark.png` / `game-gba-landscape-dark.png`: `arm.gba` de jsmolka/gba-tests (MIT) muestra "All tests passed" a 3:2. La captura horizontal sale girada 90° porque así la entrega el simulador a XCUITest; ocurre igual con las capturas horizontales de GB (`game-acid-landscape-dark.png`) (los controles de G7 siguen siendo los de GB: se rehacen en G8).

## Incidencia del CI (resuelta)
`ShellAccessibilityTests.testGridReflowsToOneColumnAtAX5` fallaba en el CI y en la corrida completa, pero pasaba aislado. Causa real (no era el scroll): el simulador conserva la orientación del test anterior (el catálogo termina en horizontal), y en horizontal (874 pt) caben varias columnas incluso con AX5. Corrección `fef885d`: fijar `XCUIDevice.orientation = .portrait`, desplazar con arrastres cortos hasta que exista la card y comprobar que su ancho > 70 % del de la pantalla (una columna). Aislado y la clase completa: pasan. Run del CI (runner propio) sobre `fef885d`: **success** (13 min 41 s; tests unitarios, UI, Release y núcleo; capturas publicadas en `ci-shots/g7-gba-app`).

## Límites conocidos
- No se probó en el iPhone (es G8) ni con un cartucho real; solo con homebrew libre.
- `make -C gba test` no se ejecutó en el Mac (faltan `ld.lld` y el cross-compiler para los homebrew propios); la regresión del núcleo GBA (91/91 + ASan, G6) corre en el CI Linux `gba.yml` (verde en la PR).
- Al auditar: `core/` no cambia; `gba/` solo añade `include/module.modulemap`.
