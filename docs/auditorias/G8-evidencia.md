# G8 · Evidencia (pantalla 3:2, L/R, audio y ajustes por juego de GBA)

Rama `g8-gba-controls` (sobre G7 con las correcciones de la auditoría), 2026-10-05. Mac de Joel, simulador iPhone 17 Pro (iOS 26).

## Qué se implementó
- **Pantalla 3:2**: ya salía de G7 (`ScreenSize` 240×160, textura Metal del tamaño del frame, `aspectRatio` del viewport); en G8 se verifica con un juego real.
- **L y R**: `ControlID.l/.r` (solo existen con `shoulders: true`, es decir en GBA), píldoras de 92×40 pt con área táctil ≥ 44 pt, posiciones por defecto propias por orientación (arriba a los lados; en vertical no pisan el menú ni A/B/cruceta), arrastrables y redimensionables en el editor de D4. La máscara de botones pasa a 16 bits (`ButtonMask`, `ConsoleCore.setButtons(UInt16)`): el núcleo GB descarta los bits 8–9.
- **Mando físico**: hombros izquierdo/derecho → L/R (`GamepadMapping`), igual que el resto del mapeo por posición.
- **HUD, pausa, ×2/×4 y save states**: sin cambios (ya son genéricos por `ConsoleCore`).
- **Ajustes por juego (Ajustes del juego, solo GBA)**: tipo de partida (detectado / sin partida / SRAM 32 KiB / Flash 64 / Flash 128 / EEPROM 512 B / 8 KiB), reloj (detectado / con / sin) y BIOS (global / la tuya / emulada). Pasan al núcleo como `gba_options.save_type/rtc`. Si el tipo forzado no coincide con la partida guardada, el `.sav` no se sobrescribe (misma regla que G7).
- **Audio**: `sample_rate` 48 kHz y el mismo anillo SPSC y pacing que GB (sin cambios; ya cableado en G7).
- Catálogo: `gameplay-gba-landscape-clear`, `gameplay-gba-reduce-transparency`, `gameplay-gba-hidden`, `customize-controls-gba-{portrait,landscape}`, `game-settings-gba` (claro y oscuro), más `game-gba` de G7 (ya con L/R).

## Verificación
```
$ tools/ios-screenshots.sh   # g8-gba-controls (f25f2f7 + ajuste de ajustes GBA)
✔ Test run with 105 tests in 12 suites passed
xcodebuild test: exit 0      xcodebuild Release: exit 0
92 capturas (todas las del catálogo; nuevas de G8 revisadas una a una)
```
Tests nuevos: L/R solo en GBA, bits de L/R y deslizamiento entre ellos, hombros dentro del área y sin solapar controles en vertical, mapeo de hombros del mando, ajustes por juego y tipo forzado (Flash 64 KiB) que cambia el tamaño esperado.

Prueba con un juego real (Kirby: Nightmare in Dream Land, cartucho propio de Joel, GBA, SRAM/Flash), en el simulador con `-debugHUD`:
```
audio ·0   U 0   ring 2806   emu 6.85 ms     (presupuesto por frame: 16.74 ms)
```
Arranca, muestra el intro a 3:2 con la paleta correcta, 0 underruns de audio y 6,85 ms de emulación por frame (≈ 2,4× de margen en el Mac; el iPhone se mide en la prueba manual).

CI verde de `c5d8286` (el que la auditoría pedía enlazar): run https://github.com/JoelBerus/pocketgb/actions/runs/37344495626, `success`, 105 tests, 92 capturas (`origin/ci-shots/g8-gba-controls`, `SUMMARY.md`). La ejecución local citada arriba ("f25f2f7 + ajuste") no corresponde a ningún commit y no sustituye a ese run.

## Pendiente (criterios que solo puede cumplir Joel en el iPhone)
- [ ] 60 fps con el HUD DEBUG (`emu` < 16 ms, `U 0`) en el iPhone.
- [ ] Jugar Kirby ≥ 30 min, guardar en el juego, cerrar forzado y recuperar la partida.
- [ ] Escuchar el audio (PSG + DirectSound) y probar L/R con el juego (Kirby usa L/R en el menú de habilidades).
- [ ] Auditoría de G8: Claude implementó G7 y G8, así que puede auditarla Codex.

Build de Debug ya instalado en el iPhone de Joel (`com.joelbermudez.pocketgb`). Kirby está en `iCloud Drive/GMRoms/`; la biblioteca de la app apunta a `iCloud Drive/rom/`: hay que elegir esa carpeta o copiar el `.gba` allí.

## Correcciones de la auditoría Opus
Respuesta por hallazgo: [G8-respuesta.md](G8-respuesta.md). Mac de Joel, simulador iPhone 17 Pro, todo a través del mutex del simulador.

**Rojos (antes de la corrección)**
```
H1 (hit(at:) con el orden antiguo)
✘ shouldersStayTappableWhenDpadIsMovedOnTop(): hit(at: L) → .control(dpad) != .control(l)
✘ ... hit(at: R) → .control(a) != .control(r)
H2 (validSaveSizes antiguos, EEPROM 512 B forzada + espejo de 8 KiB más reciente)
✘ forcedEEPROM512IgnoresANewerMirrorOf8KiB: validSaveSizes → [512, 8192] != [512]
✘ ... after = {juego.sav 8192 B, backups/<fp>.1.sav 512 B, <fp>.sav 8192 B} != before
✘ ... loadWarning → .localWrongSize != .mirrorIgnored
H8/H9 (núcleo GBA sin la corrección)
FALLO tests/unit.c:516: info.save_type == GBA_SAVE_SRAM && ...
FALLO tests/unit.c:594: gba_state_load(g, st2, sz) == GBA_ERR_STATE_CONFIG
FAIL unit: 4 fallos
```

**Núcleo GBA** (`make -C gba test` y `make -C gba asan` completos no se pueden ejecutar en este Mac: faltan `ld.lld` (homebrew de la suite) y los ROMs SingleStepTests; se ejecutó la parte unitaria, que es donde están los cambios):
```
$ gba/build/gbatest --unit
PASS unit: 0 fallos
$ make -C gba BUILD=build/asan EXTRA="-fsanitize=address,undefined ..." CFLAGS="-O1 -g" build/asan/gbatest && gba/build/asan/gbatest --unit
PASS unit: 0 fallos
```
`make -C gba check-globals` falla igual sin mis cambios (salida de `nm` de macOS sobre `sha256`).

**Suite unitaria completa (`-only-testing:PocketGBTests`)**
```
✔ Test run with 117 tests in 12 suites passed after 6.196 seconds.
** TEST SUCCEEDED **
```
**Catálogo (`tools/ios-screenshots.sh`, segunda ejecución tras limitar el tipo del editor)**
```
✔ Test run with 117 tests in 12 suites passed after 6.325 seconds.
** TEST SUCCEEDED **     ** BUILD SUCCEEDED **  (Release)
xcodebuild Release: exit 0     xcodebuild test: exit 0
94 capturas (92 + las dos AX5 nuevas)
```
Capturas revisadas: `customize-controls-gba-landscape` (cruceta, A, B, Start, Select, L y R en los márgenes, ninguno sobre la imagen 3:2; el aviso del editor sí cae sobre la imagen, como en GB), `gameplay-gba-reduce-transparency` (vertical: L/R arriba, HUD entre ellos sin solape), `game-settings-gba-ax5` (pickers legibles con AX5, «Detectado», BIOS «Global (la tuya si existe)»), `customize-controls-gba-portrait-ax5` (primera ejecución: barra rota, texto letra a letra sobre los controles; tras el límite `xxxLarge`: botones y texto en dos líneas, legibles).
