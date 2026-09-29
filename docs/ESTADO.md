# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-29 · **Hito actual:** M4 (app iOS mínima) **cerrado** (Codex: 4 vueltas, H1–H7 corregidos; Joel probó dmg-acid2 y Pokémon Rojo en su iPhone) → **siguiente: núcleo de M8 (CGB)** en la nube; **parte iOS de M5** (audio) en el Mac.

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs). Auditoría: `docs/auditorias/M0-*`.
- M1: núcleo headless en `core/src` (CPU SM83, MMU, timer, serie, joypad, ROM-only + MBC1 con SRAM, SHA-256) + OAM DMA y PPU mínima de tiempos adelantados de M2. Runner `gbtest` (serial/mooneye/--bench/--unit), `run_suite.py`, `suite.txt`. 63/63 casos requeridos en PASS (incluidos 54 Mooneye), ASan limpio, ~50× tiempo real. Auditoría: `docs/auditorias/M1-*`.
- M2: PPU DMG por scanline (fondo, ventana, objetos), bloqueo VRAM/OAM por modo, modo `acid` en el runner y `tools/png2rgba.py`. dmg-acid2 idéntico píxel a píxel; 70/70 requeridos; ~45× tiempo real con render. Auditoría: `docs/auditorias/M2-*`.
- M3: MBC1/3/5, RTC del MBC3 (`rtc.c`, .sav de 48/44 bytes), save states (`state.c`, CRC-32, carga en dos pasadas), OOM inyectado, fuzzers con mutación estructurada. 94/94 requeridos (Mooneye MBC1/MBC5, MBC3-Tester, rtc3test ×3), 2×600 s de fuzzing sin crashes. Auditoría: `docs/auditorias/M3-*`.
- M5 (núcleo): APU DMG (`apu.c`) con catch-up (A9), salida PCM a `sample_rate` con pasa-altos, `gb_audio_*`, save state v2, runner `--mode blargg` y `--wav`. dmg_sound 01–08 y 11 PASS (09/10/12 known-fail); 103/103 requeridos. Auditoría: `docs/auditorias/M5-*`.

- M4: `ios/PocketGB.xcodeproj` creado por Claude (carpetas sincronizadas; `.swift` nuevos entran solos), `CoreBridge`, hilo de emulación con pacing por reloj, Metal (shader compilado en runtime), controles multitáctiles, SRAM con `AtomicFile` + 5 backups y flush síncrono en pausa/background/salida. Flush de SRAM también en la red de 60 s sin flanco, ante memoria baja y con reintento tras fallo. dmg-acid2 y Pokémon Rojo en el iPhone de Joel. Núcleo verificado también en macOS (103/103, ASan limpio). Auditoría Codex: `docs/auditorias/M4-*`.

## Siguiente paso exacto
- **En el Mac (🍎):** parte iOS de [M5](hitos/M5-apu-audio.md), rama `m5-ios-audio`: subir el deployment target a iOS 18 (decisión 2026-09-29), `RingBuffer.swift` con `Synchronization.Atomic`, `AudioOutput.swift`, pacing guiado por el audio, 10 min sin underruns, A9 de oído y peor caso del APU medido en el iPhone. Para probar en el simulador: `-rom <ruta>` (solo DEBUG, ver `ios/README.md`).
- **En la nube (☁️):** núcleo de [M8](hitos/M8-cgb.md) (CGB):
  1. Rama `m8-cgb` desde `main` (ya incluye M4). No tocar `ios/`: en Linux no compila; si M8 cambia la API C, anotarlo aquí para adaptar `CoreBridge.swift` en el Mac.
  2. `tools/cloud-setup.sh`.
  3. Doble velocidad (KEY1/STOP), VRAM y WRAM con bancos, paletas CGB, atributos de tile, HDMA, estado post-boot CGB y modo compatibilidad con paletas por checksum; `--model cgb` en el runner; casos M8 en `suite.txt`.
  4. Verificar con `make -C core test HITO=M8 && make -C core asan HITO=M8 && make -C core check-globals`.
  5. Auditoría → PR.

## Pendiente en el núcleo (por hito)
- Herramientas (nota de Codex, M4): `make check-globals` da un falso "OK" si `nm` falla; hacer que falle si `nm` no produce salida.
- M6 (frontend): tras `gb_state_load` guardar la SRAM, con backup (el estado sustituye la RAM del cartucho). Si `gb_sram_load` devuelve `GB_ERR_SRAM_SIZE`, no sobrescribir el `.sav`.
- Con el oráculo (M5): contrastar con SameBoy `EI` justo antes de `HALT` con IRQ pendiente (nota de la auditoría M1); `boot_div`/`boot_hwio`/`boot_sclk_align` (known-fail).
- M2 (known-fail): tiempo fino de STAT/LCD de Mooneye `ppu/*` (fuera de una PPU por scanline).
- M8: el test acid con paleta gris no distingue R de B; cgb-acid2 lo cubrirá (nota de la auditoría M2).
- M5 (known-fail): `dmg_sound` 09/10/12, acceso a la wave RAM con el canal 3 sonando. Sin emular: "zombie mode" de la envolvente.
- M8: modo CGB (hasta entonces un ROM `0xC0` → `GB_ERR_CGB_ONLY`). El frame sequencer usa el bit 13 en doble velocidad.

## Decisiones tomadas (no reabrir sin Joel)
| Fecha | Decisión |
|---|---|
| 2026-09-28 | Núcleo propio en C11. SameBoy solo como oráculo dev-only. |
| 2026-09-28 | Claude (Opus) desarrolla; Codex solo audita en solo lectura; si Codex no está, audita un subagente Opus. |
| 2026-09-28 | iOS nativo (SwiftUI + Metal), Android nativo futuro (Kotlin + NDK). Sin frameworks híbridos. |
| 2026-09-28 | Firma con Apple ID gratuito (reinstalar cada 7 días). |
| 2026-09-28 | Biblioteca en una carpeta privada de iCloud Drive vía document picker + bookmark (sin capability iCloud). ROMs solo de cartuchos propios; nunca en GitHub. |
| 2026-09-29 | APU por catch-up (se pone al día al tocar registros de sonido y al final de cada frame). Formato de save state v2 (sección APU); los v1 se rechazan. |
| 2026-09-29 | MBC3 con ROM > 2 MiB usa banco de 8 bits (MBC30). `gb_sram_load` acepta .sav con RTC de 48, de 44 o sin bloque. |
| 2026-09-29 | OAM DMA: conflicto por bus (externo vs VRAM, OAM bloqueada, E/S y HRAM libres) también en DMG, en contra de la simplificación "solo HRAM" de Pan Docs; lo exigen las pruebas Mooneye verificadas en DMG real. |
| 2026-09-29 | El proyecto Xcode lo genera y mantiene Claude (carpetas sincronizadas). Shaders en fuente MSL compilados en runtime (sin depender del Metal Toolchain). En M4, sincronización con `OSAllocatedUnfairLock` (iOS 17). |
| 2026-09-29 | Deployment target iOS 18 desde M5 (Joel): `Synchronization.Atomic` para el ring buffer de audio y la máscara de botones. |
| 2026-09-29 | Flush síncrono de SRAM (pausa/background/salida) compara con lo último guardado en vez de depender del flanco "el juego guardó" (auditoría M4, H1). |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [x] ~~Crear el proyecto Xcode~~ (lo hizo Claude en M4).
- [x] ~~Conectar el iPhone y generar el perfil de firma~~ (2026-09-29). Recordatorio: reinstalar cada 7 días ([07](07-instalacion-iphone.md)).
- [x] ~~Activar el Modo Desarrollador en el iPhone~~.
- [x] ~~Rojo~~ (en su iCloud Drive privado). [ ] Amarillo ([08](08-roms-legal.md)).
