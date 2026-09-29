# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-29 · **Hito actual:** M1 **cerrado** (auditoría Opus en la nube: APROBAR CON CAMBIOS; 7 hallazgos bajos: 5 corregidos, 2 diferidos a M3) → **siguiente: M2** (☁️ se puede hacer en la nube).

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs). Auditoría: `docs/auditorias/M0-*`.
- M1: núcleo headless en `core/src` (CPU SM83, MMU, timer, serie, joypad, ROM-only + MBC1 con SRAM, SHA-256) + OAM DMA y PPU mínima de tiempos adelantados de M2. Runner `gbtest` (serial/mooneye/--bench/--unit), `run_suite.py`, `suite.txt`. 63/63 casos requeridos en PASS (incluidos 54 Mooneye), ASan limpio, ~50× tiempo real. Auditoría: `docs/auditorias/M1-*`.

## Siguiente paso exacto
1. Tras el merge de M1: `git checkout main && git pull && git checkout -b m2-ppu`
2. `tools/cloud-setup.sh` (instala el runtime de sanitizers y las ROMs de prueba si faltan)
3. Implementar [M2](hitos/M2-ppu-dmg.md): render por scanline en `core/src/ppu.c` (hoy solo tiene los tiempos), bloqueo VRAM/OAM por modo, `tools/png2rgba.py` y el modo `acid` (`--expect`/`--dump`) en `core/tests/runner.c`; casos M2 en `core/tests/suite.txt` (7.ª columna: referencia).
4. Verificar con `make -C core test HITO=M2 && make -C core asan HITO=M2 && make -C core check-globals` (sin regresiones en M1; vigilar `di_timing-GS`/`halt_ime1_timing2-GS`, que dependen de la PPU).
5. Auditoría → `docs/auditorias/M2-*.md` → PR a `main`.

## Pendiente en el núcleo (por hito)
- M3: `gb_state_*` y `gb_rtc_set_time` (declaradas en `pocketgb.h`, sin definir aún), MBC3/MBC5, test de fallo de memoria inyectado.
- M2/M3: contrastar con SameBoy `EI` justo antes de `HALT` con IRQ pendiente (nota de la auditoría M1); `boot_div`/`boot_hwio`/`boot_sclk_align` (known-fail).
- M5: audio (`gb_audio_*` devuelve 0). M8: modo CGB (hasta entonces un ROM `0xC0` → `GB_ERR_CGB_ONLY`).

## Decisiones tomadas (no reabrir sin Joel)
| Fecha | Decisión |
|---|---|
| 2026-09-28 | Núcleo propio en C11. SameBoy solo como oráculo dev-only. |
| 2026-09-28 | Claude (Opus) desarrolla; Codex solo audita en solo lectura; si Codex no está, audita un subagente Opus. |
| 2026-09-28 | iOS nativo (SwiftUI + Metal), Android nativo futuro (Kotlin + NDK). Sin frameworks híbridos. |
| 2026-09-28 | Firma con Apple ID gratuito (reinstalar cada 7 días). |
| 2026-09-28 | Biblioteca en una carpeta privada de iCloud Drive vía document picker + bookmark (sin capability iCloud). ROMs solo de cartuchos propios; nunca en GitHub. |
| 2026-09-29 | OAM DMA: conflicto por bus (externo vs VRAM, OAM bloqueada, E/S y HRAM libres) también en DMG, en contra de la simplificación "solo HRAM" de Pan Docs; lo exigen las pruebas Mooneye verificadas en DMG real. |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [ ] Crear el proyecto Xcode cuando lleguemos a M4 ([04](04-ios-spec.md) §Proyecto Xcode).
- [ ] Activar el Modo Desarrollador en el iPhone ([07](07-instalacion-iphone.md)).
- [ ] Volcar los cartuchos de Rojo y Amarillo ([08](08-roms-legal.md)).
