# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-29 · M4 **cerrado**; M5 iOS (audio) **implementado** y probado en el simulador (10 min, 0 underruns), a falta de los criterios del iPhone de Joel. Nube: **D1** (diseño) y núcleo de **M8**, en paralelo.

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs). Auditoría: `docs/auditorias/M0-*`.
- M1: núcleo headless en `core/src` (CPU SM83, MMU, timer, serie, joypad, ROM-only + MBC1 con SRAM, SHA-256) + OAM DMA y PPU mínima de tiempos adelantados de M2. Runner `gbtest` (serial/mooneye/--bench/--unit), `run_suite.py`, `suite.txt`. 63/63 casos requeridos en PASS (incluidos 54 Mooneye), ASan limpio, ~50× tiempo real. Auditoría: `docs/auditorias/M1-*`.
- M2: PPU DMG por scanline (fondo, ventana, objetos), bloqueo VRAM/OAM por modo, modo `acid` en el runner y `tools/png2rgba.py`. dmg-acid2 idéntico píxel a píxel; 70/70 requeridos; ~45× tiempo real con render. Auditoría: `docs/auditorias/M2-*`.
- M3: MBC1/3/5, RTC del MBC3 (`rtc.c`, .sav de 48/44 bytes), save states (`state.c`, CRC-32, carga en dos pasadas), OOM inyectado, fuzzers con mutación estructurada. 94/94 requeridos (Mooneye MBC1/MBC5, MBC3-Tester, rtc3test ×3), 2×600 s de fuzzing sin crashes. Auditoría: `docs/auditorias/M3-*`.
- M5 iOS (sin cerrar: faltan criterios del iPhone): `AudioOutput` (AVAudioEngine 48 kHz, `.ambient`), `AudioRingBuffer` SPSC lock-free, pacing guiado por el audio con fallback a reloj, HUD DEBUG. Implementado con Codex, auditado por Opus (APROBAR CON CAMBIOS) + un crash de aislamiento Swift 6 encontrado en la prueba real y corregido. Evidencia: `docs/auditorias/M5-ios-*`.
- M5 (núcleo): APU DMG (`apu.c`) con catch-up (A9), salida PCM a `sample_rate` con pasa-altos, `gb_audio_*`, save state v2, runner `--mode blargg` y `--wav`. dmg_sound 01–08 y 11 PASS (09/10/12 known-fail); 103/103 requeridos. Auditoría: `docs/auditorias/M5-*`.

- M4: `ios/PocketGB.xcodeproj` creado por Claude (carpetas sincronizadas; `.swift` nuevos entran solos), `CoreBridge`, hilo de emulación con pacing por reloj, Metal (shader compilado en runtime), controles multitáctiles, SRAM con `AtomicFile` + 5 backups y flush síncrono en pausa/background/salida. Flush de SRAM también en la red de 60 s sin flanco, ante memoria baja y con reintento tras fallo. dmg-acid2 y Pokémon Rojo en el iPhone de Joel. Núcleo verificado también en macOS (103/103, ASan limpio). Auditoría Codex: `docs/auditorias/M4-*`.

## Siguiente paso exacto
- **Joel (iPhone, cuando pueda):** criterios 3 y 4 de [M5](hitos/M5-apu-audio.md): 10 min con el HUD (3 dedos sobre la imagen) → debe decir `audio ·0  U 0`; oír el grito de Pikachu en Amarillo. Luego Claude en el Mac lo anota en `docs/auditorias/M5-ios-evidencia.md` y cierra M5.
- **En la nube (☁️), dos líneas independientes:**
  1. **Diseño de la app, [D1](hitos/D-README.md):** rama `d1-fundamentos` desde `main`. Leer antes [diseno/SPEC.md](diseno/SPEC.md), [diseno/VERIFICACION.md](diseno/VERIFICACION.md) y [diseno/API-iOS26.md](diseno/API-iOS26.md) (firmas reales del SDK: no inventar APIs). Cada lote: push → CI (~8 min) → revisar `ci-shots/<rama>` (SUMMARY + cada PNG) → corregir. Un push por lote (minutos macOS limitados). No tocar `Emulator/`, `Audio/` ni `Saves/` salvo lo que pida el hito. Trampa conocida de Swift 6: un closure que se ejecuta en otro hilo (audio, callbacks de sistema) no puede crearse dentro de un método `@MainActor` (ver M5-ios-respuesta H0).
  2. **Núcleo de [M8](hitos/M8-cgb.md) (CGB):** rama `m8-cgb` desde `main`; `tools/cloud-setup.sh`; doble velocidad (KEY1/STOP), VRAM/WRAM con bancos, paletas CGB, atributos de tile, HDMA, post-boot CGB y modo compatibilidad con paletas por checksum; `--model cgb`; casos M8 en `suite.txt`; verificar con `make -C core test HITO=M8 && make -C core asan HITO=M8 && make -C core check-globals`; auditoría → PR. No tocar `ios/`; si cambia la API C, anotarlo aquí. Pokémon Amarillo (flag CGB 0x80) es la prueba real en el iPhone.

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
| 2026-09-29 | Deployment target **iOS 26** (Joel; sustituye la decisión de iOS 18): Liquid Glass nativo y `Synchronization.Atomic`. |
| 2026-09-29 | Diseño de la app = propuesta Liquid Glass de Joel ([diseno/propuesta.html](diseno/propuesta.html)) adaptada a GB/GBC en [diseno/SPEC.md](diseno/SPEC.md). Hitos D1–D8 sustituyen a M6/M7. |
| 2026-09-29 | CI de macOS en GitHub Actions con capturas en `ci-shots/<rama>` para que la nube verifique la UI (Joel acepta el coste en minutos macOS). |
| 2026-09-29 | Codex puede implementar por encargo de Claude (Joel, para ahorrar tokens); no audita lo que implementa (AGENTS.md). |
| 2026-09-29 | Flush síncrono de SRAM (pausa/background/salida) compara con lo último guardado en vez de depender del flanco "el juego guardó" (auditoría M4, H1). |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [x] ~~Crear el proyecto Xcode~~ (lo hizo Claude en M4).
- [x] ~~Conectar el iPhone y generar el perfil de firma~~ (2026-09-29). Recordatorio: reinstalar cada 7 días ([07](07-instalacion-iphone.md)).
- [x] ~~Activar el Modo Desarrollador en el iPhone~~.
- [x] ~~Rojo y Amarillo~~ volcados, en su iCloud Drive privado (`rom/`). Amarillo: POKEMON YELLOW, MBC5+RAM+BATTERY, flag CGB 0x80, checksums OK, huella `8cbaa499…`.
