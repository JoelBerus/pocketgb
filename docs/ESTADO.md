# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-29 · **Hito actual:** M3 **cerrado** (auditoría Opus: RECHAZAR por un desbordamiento desde `gb_state_load` → corregido → APROBAR CON CAMBIOS) → **siguiente: núcleo de M5 (APU)** en la nube, o M4 (app iOS) en el Mac.

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs). Auditoría: `docs/auditorias/M0-*`.
- M1: núcleo headless en `core/src` (CPU SM83, MMU, timer, serie, joypad, ROM-only + MBC1 con SRAM, SHA-256) + OAM DMA y PPU mínima de tiempos adelantados de M2. Runner `gbtest` (serial/mooneye/--bench/--unit), `run_suite.py`, `suite.txt`. 63/63 casos requeridos en PASS (incluidos 54 Mooneye), ASan limpio, ~50× tiempo real. Auditoría: `docs/auditorias/M1-*`.
- M2: PPU DMG por scanline (fondo, ventana, objetos), bloqueo VRAM/OAM por modo, modo `acid` en el runner y `tools/png2rgba.py`. dmg-acid2 idéntico píxel a píxel; 70/70 requeridos; ~45× tiempo real con render. Auditoría: `docs/auditorias/M2-*`.
- M3: MBC1/3/5, RTC del MBC3 (`rtc.c`, .sav de 48/44 bytes), save states (`state.c`, CRC-32, carga en dos pasadas), OOM inyectado, fuzzers con mutación estructurada. 94/94 requeridos (Mooneye MBC1/MBC5, MBC3-Tester, rtc3test ×3), 2×600 s de fuzzing sin crashes. Auditoría: `docs/auditorias/M3-*`.

## Siguiente paso exacto
El núcleo ya tiene lo que necesita la app mínima (CPU, PPU, MBC, SRAM y estados), así que M4 queda desbloqueado.
- **En el Mac (🍎):** [M4](hitos/M4-ios-minima.md). Joel crea el proyecto Xcode ([04](04-ios-spec.md) §Proyecto Xcode) y se enlaza `core/`.
- **En la nube (☁️):** núcleo de [M5](hitos/M5-apu-audio.md):
  1. Tras el merge de M3: rama nueva desde `main`.
  2. `tools/cloud-setup.sh`.
  3. `core/src/apu.c` (4 canales, frame sequencer por DIV, mezcla y remuestreo), `gb_audio_*`, `gbtest --wav`, casos M5 en `suite.txt`.
  4. Verificar con `make -C core test HITO=M5 && make -C core asan HITO=M5 && make -C core check-globals`.
  5. Auditoría → PR.

## Pendiente en el núcleo (por hito)
- M6 (frontend): tras `gb_state_load` guardar la SRAM, con backup (el estado sustituye la RAM del cartucho). Si `gb_sram_load` devuelve `GB_ERR_SRAM_SIZE`, no sobrescribir el `.sav`.
- Con el oráculo (M5): contrastar con SameBoy `EI` justo antes de `HALT` con IRQ pendiente (nota de la auditoría M1); `boot_div`/`boot_hwio`/`boot_sclk_align` (known-fail).
- M2 (known-fail): tiempo fino de STAT/LCD de Mooneye `ppu/*` (fuera de una PPU por scanline).
- M8: el test acid con paleta gris no distingue R de B; cgb-acid2 lo cubrirá (nota de la auditoría M2).
- M5: audio (`gb_audio_*` devuelve 0). M8: modo CGB (hasta entonces un ROM `0xC0` → `GB_ERR_CGB_ONLY`).

## Decisiones tomadas (no reabrir sin Joel)
| Fecha | Decisión |
|---|---|
| 2026-09-28 | Núcleo propio en C11. SameBoy solo como oráculo dev-only. |
| 2026-09-28 | Claude (Opus) desarrolla; Codex solo audita en solo lectura; si Codex no está, audita un subagente Opus. |
| 2026-09-28 | iOS nativo (SwiftUI + Metal), Android nativo futuro (Kotlin + NDK). Sin frameworks híbridos. |
| 2026-09-28 | Firma con Apple ID gratuito (reinstalar cada 7 días). |
| 2026-09-28 | Biblioteca en una carpeta privada de iCloud Drive vía document picker + bookmark (sin capability iCloud). ROMs solo de cartuchos propios; nunca en GitHub. |
| 2026-09-29 | MBC3 con ROM > 2 MiB usa banco de 8 bits (MBC30). `gb_sram_load` acepta .sav con RTC de 48, de 44 o sin bloque. |
| 2026-09-29 | OAM DMA: conflicto por bus (externo vs VRAM, OAM bloqueada, E/S y HRAM libres) también en DMG, en contra de la simplificación "solo HRAM" de Pan Docs; lo exigen las pruebas Mooneye verificadas en DMG real. |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [ ] Crear el proyecto Xcode cuando lleguemos a M4 ([04](04-ios-spec.md) §Proyecto Xcode).
- [ ] Activar el Modo Desarrollador en el iPhone ([07](07-instalacion-iphone.md)).
- [ ] Volcar los cartuchos de Rojo y Amarillo ([08](08-roms-legal.md)).
