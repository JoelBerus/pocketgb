# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-29 · M4, M5, M8 y **D1 (☁️) cerrados**. Siguiente: **D2** (biblioteca por carpeta y saves). **M9 (☁️ núcleo) cerrado**: cable virtual en `link.c`; falta su UI y la prueba 🍎.

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs). Auditoría: `docs/auditorias/M0-*`.
- M1: núcleo headless en `core/src` (CPU SM83, MMU, timer, serie, joypad, ROM-only + MBC1 con SRAM, SHA-256) + OAM DMA y PPU mínima de tiempos adelantados de M2. Runner `gbtest` (serial/mooneye/--bench/--unit), `run_suite.py`, `suite.txt`. 63/63 casos requeridos en PASS (incluidos 54 Mooneye), ASan limpio, ~50× tiempo real. Auditoría: `docs/auditorias/M1-*`.
- M2: PPU DMG por scanline (fondo, ventana, objetos), bloqueo VRAM/OAM por modo, modo `acid` en el runner y `tools/png2rgba.py`. dmg-acid2 idéntico píxel a píxel; 70/70 requeridos; ~45× tiempo real con render. Auditoría: `docs/auditorias/M2-*`.
- M3: MBC1/3/5, RTC del MBC3 (`rtc.c`, .sav de 48/44 bytes), save states (`state.c`, CRC-32, carga en dos pasadas), OOM inyectado, fuzzers con mutación estructurada. 94/94 requeridos (Mooneye MBC1/MBC5, MBC3-Tester, rtc3test ×3), 2×600 s de fuzzing sin crashes. Auditoría: `docs/auditorias/M3-*`.
- M5 iOS (cerrado; Joel lo oyó bien en su iPhone): `AudioOutput` (AVAudioEngine 48 kHz, `.ambient`), `AudioRingBuffer` SPSC lock-free, pacing guiado por el audio con fallback a reloj, HUD DEBUG, sesión y motor en una cola propia (nada de `setActive` en el hilo principal). Implementado con Codex, auditado por Opus (APROBAR CON CAMBIOS) + un crash de aislamiento Swift 6 encontrado en la prueba real y corregido. Evidencia: `docs/auditorias/M5-ios-*`.
- M5 (núcleo): APU DMG (`apu.c`) con catch-up (A9), salida PCM a `sample_rate` con pasa-altos, `gb_audio_*`, save state v2, runner `--mode blargg` y `--wav`. dmg_sound 01–08 y 11 PASS (09/10/12 known-fail); 103/103 requeridos. Auditoría: `docs/auditorias/M5-*`.

- M8 (núcleo): CGB en `cgb.c` + PPU/CPU/timer/APU: VRAM y WRAM con bancos, paletas de color, atributos de fondo, prioridad CGB, HDMA general y de HBlank, doble velocidad (STOP + KEY1), arranque CGB nativo y en compatibilidad, paletas de compatibilidad por checksum del título (tablas de SameBoy, MIT) con selección manual, APU CGB (apagar borra longitudes), save state v3. `--model cgb|auto`. cgb-acid2, dmg-acid2 en CGB y boot_regs-cgb idénticos/PASS; 157/157 requeridos (incluye `interrupt_time` en doble velocidad). Evidencia y auditoría: `docs/auditorias/M8-*`. Tests también en el Mac de Joel. **iPhone (2026-09-29):** Amarillo en color, jugado un rato sin problemas; Rojo sigue en DMG (esperado: la paleta de compatibilidad necesita `GB_MODEL_CGB`, que pedirá la app en D5/D6).

- D1 (☁️): design system (12 colores claro/oscuro, espaciado, radios, motion), shell de tres tabs nativas (Biblioteca, Favoritos, Ajustes) con minimización, Ajustes en `Form` (Apariencia, Acerca de con licencias y privacidad sin red), router DEBUG (`-screen`, `-demo*`) excluido de Release (el CI compila Release) y 12 capturas de catálogo. Hasta D2, "Abrir un archivo…" (menú `…` de Biblioteca) abre un ROM suelto. Auditoría Opus: APROBAR CON CAMBIOS → H1–H6 corregidos. Evidencia: `docs/auditorias/D1-*`. Probado por Joel en su iPhone (2026-09-29): tabs con Liquid Glass, launch oscuro, barra de estado, abrir un archivo y apariencia, todo bien.

- M4: `ios/PocketGB.xcodeproj` creado por Claude (carpetas sincronizadas; `.swift` nuevos entran solos), `CoreBridge`, hilo de emulación con pacing por reloj, Metal (shader compilado en runtime), controles multitáctiles, SRAM con `AtomicFile` + 5 backups y flush síncrono en pausa/background/salida. Flush de SRAM también en la red de 60 s sin flanco, ante memoria baja y con reintento tras fallo. dmg-acid2 y Pokémon Rojo en el iPhone de Joel. Núcleo verificado también en macOS (103/103, ASan limpio). Auditoría Codex: `docs/auditorias/M4-*`.

## Siguiente paso exacto
- **En la nube (☁️), dos líneas independientes:**
  1. **Diseño de la app, [D2](hitos/D-README.md) (siguiente; D1 cerrado):** Leer antes [diseno/SPEC.md](diseno/SPEC.md), [diseno/VERIFICACION.md](diseno/VERIFICACION.md) y [diseno/API-iOS26.md](diseno/API-iOS26.md) (firmas reales del SDK: no inventar APIs). Cada lote: push → CI (~8 min) → revisar `ci-shots/<rama>` (SUMMARY + cada PNG) → corregir. Un push por lote (minutos macOS limitados). No tocar `Emulator/`, `Audio/` ni `Saves/` salvo lo que pida el hito. Trampa conocida de Swift 6: un closure que se ejecuta en otro hilo (audio, callbacks de sistema) no puede crearse dentro de un método `@MainActor` (ver M5-ios-respuesta H0).
  2. ~~M9 (núcleo)~~ cerrado 2026-09-29: auditorías Opus y Codex corregidas, fuzz-link de 600 s en la nube sin crashes (3159 ejecuciones, `docs/auditorias/M9-evidencia.md`). Pendiente 🍎: UI del cable (pantalla dividida o alternar) e intercambio Rojo ↔ Amarillo en el iPhone. La app debe usar `gb_link_framebuffer` y llamar a `gb_link_detach` antes de `gb_destroy`.
  3. ~~Corrección de M8 (buses de OAM DMA en CGB)~~ hecha en `main` (Codex implementó, Opus: APROBAR).
  4. ~~M8~~ cerrado. Siguiente hito de núcleo: [M9](hitos/M9-link-virtual.md) (cable virtual), cuando Joel lo pida.
- **Pendiente para la app (D5/D6):** ajuste por juego «Color en juegos de Game Boy» → `model = GB_MODEL_CGB` + selector de paleta (`compat_palette`, 0 auto / 1..12). Sin eso Rojo se ve en blanco y negro. Medir el rendimiento en CGB en el iPhone.
- **API C cambiada en M8 (para la app):** `gb_options.compat_palette` (0 auto, 1..12 = combinaciones de botones del arranque), `gb_set_compat_palette(g, id)` en caliente, `GB_COMPAT_PALETTES`, `gb_rom_info.cgb_compat`; `gb_rom_info.cgb_mode` ahora es verdadero en CGB (nativo o compatibilidad). `GB_ERR_CGB_ONLY` solo con `GB_MODEL_DMG`. Save states v3: los v2 se rechazan (`GB_ERR_STATE_VERSION`), y un estado de otro modelo da `GB_ERR_STATE_ROM_MISMATCH`. `gb_cycle_count` cuenta tiempo real también en doble velocidad.

## Pendiente en el núcleo (por hito)
- Herramientas (nota de Codex, M4): `make check-globals` da un falso "OK" si `nm` falla; hacer que falle si `nm` no produce salida.
- M6 (frontend): tras `gb_state_load` guardar la SRAM, con backup (el estado sustituye la RAM del cartucho). Si `gb_sram_load` devuelve `GB_ERR_SRAM_SIZE`, no sobrescribir el `.sav`.
- Con el oráculo (M5): contrastar con SameBoy `EI` justo antes de `HALT` con IRQ pendiente (nota de la auditoría M1); `boot_div`/`boot_hwio`/`boot_sclk_align` (known-fail).
- M2 (known-fail): tiempo fino de STAT/LCD de Mooneye `ppu/*` (fuera de una PPU por scanline).
- M8 (known-fail/sin emular): `boot_hwio-C` (valores exactos de E/S tras el arranque), `vblank_stat_intr-C` (tiempo fino de STAT), `cgb_sound` 09/12 (wave RAM con el canal 3 sonando), cgb-acid-hell (2 píxeles: efectos a mitad de línea), pausa de ~2050 M-ciclos del cambio de velocidad, mapa del logo en compatibilidad para los checksums 0x43/0x58, DIV post-arranque en CGB nativo sin verificar, salida PCM12/PCM34.
- M5 (known-fail): `dmg_sound` 09/10/12, acceso a la wave RAM con el canal 3 sonando. Sin emular: "zombie mode" de la envolvente.

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
| 2026-09-29 | Deployment target **iOS 26** (Joel; sustituye la decisión de iOS 18): Liquid Glass nativo y `Synchronization.Atomic`. |
| 2026-09-29 | Diseño de la app = propuesta Liquid Glass de Joel ([diseno/propuesta.html](diseno/propuesta.html)) adaptada a GB/GBC en [diseno/SPEC.md](diseno/SPEC.md). Hitos D1–D8 sustituyen a M6/M7. |
| 2026-09-29 | CI de macOS en GitHub Actions con capturas en `ci-shots/<rama>` para que la nube verifique la UI (Joel acepta el coste en minutos macOS). |
| 2026-09-29 | Codex puede implementar por encargo de Claude (Joel, para ahorrar tokens); no audita lo que implementa (AGENTS.md). |
| 2026-09-29 | Flush síncrono de SRAM (pausa/background/salida) compara con lo último guardado en vez de depender del flanco "el juego guardó" (auditoría M4, H1). |
| 2026-09-29 | M8: paletas de compatibilidad = tablas de la reimplementación libre del arranque CGB de SameBoy (MIT, citadas en `cgb.c`), no el boot ROM de Nintendo. `GB_MODEL_AUTO` elige CGB para ROMs con flag `0x80`/`0xC0`; un ROM DMG solo va en compatibilidad si se pide `GB_MODEL_CGB`. |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [x] ~~Crear el proyecto Xcode~~ (lo hizo Claude en M4).
- [x] ~~Conectar el iPhone y generar el perfil de firma~~ (2026-09-29). Recordatorio: reinstalar cada 7 días ([07](07-instalacion-iphone.md)).
- [x] ~~Activar el Modo Desarrollador en el iPhone~~.
- [x] ~~Rojo y Amarillo~~ volcados, en su iCloud Drive privado (`rom/`). Amarillo: POKEMON YELLOW, MBC5+RAM+BATTERY, flag CGB 0x80, checksums OK, huella `8cbaa499…`.
