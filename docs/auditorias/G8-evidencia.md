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

## Pendiente (criterios que solo puede cumplir Joel en el iPhone)
- [ ] 60 fps con el HUD DEBUG (`emu` < 16 ms, `U 0`) en el iPhone.
- [ ] Jugar Kirby ≥ 30 min, guardar en el juego, cerrar forzado y recuperar la partida.
- [ ] Escuchar el audio (PSG + DirectSound) y probar L/R con el juego (Kirby usa L/R en el menú de habilidades).
- [ ] Auditoría de G8: Claude implementó G7 y G8, así que puede auditarla Codex.

Build de Debug ya instalado en el iPhone de Joel (`com.joelbermudez.pocketgb`). Kirby está en `iCloud Drive/GMRoms/`; la biblioteca de la app apunta a `iCloud Drive/rom/`: hay que elegir esa carpeta o copiar el `.gba` allí.
