# PocketGB — Plan de soporte para Game Boy Advance (hitos G0–G9)

> Estado: **en marcha** (2026-10-05). Joel pidió aplicar el plan; se toman las recomendaciones de §1. G0 y G1 implementados en `g0-gba-instrucciones`.
> Ejecutor: Claude en la nube para el núcleo (☁️) y para la app con el CI de macOS; Joel en el iPhone (🍎).
> Mismas reglas que el resto del repo: [AGENTS.md](../../AGENTS.md), un hito a la vez, auditoría antes de cerrar.

## 0. Resumen en diez líneas
- La GBA **no es una Game Boy con más registros**: es una máquina distinta (ARM7TDMI de 32 bits a 16,78 MHz, pantalla 240×160 de 15 bits, PPU con 6 modos y capas afines, 4 DMA, 4 timers, 6 canales de audio, saves en SRAM/Flash/EEPROM). Compartir código con `core/` sería forzado; se añade un **segundo núcleo** `gba/` en C11 con las mismas reglas duras, y la app pasa a hablar con una **abstracción de consola**.
- El GB sigue intacto: `core/` no se toca más que para extraer `sha256` a un sitio común. Las 157/157 pruebas de GB siguen siendo la red de seguridad.
- **Sin BIOS de Nintendo en el repo** (regla dura 1). Las llamadas SWI se emulan por alto nivel (HLE) en C propio, documentado con GBATEK. Opcionalmente, Joel puede poner su propio volcado de BIOS junto a los ROMs y la app lo usa si está.
- **Licencias:** SkyEmu (MIT) es la única referencia de la que se puede copiar código con cita. mGBA (MPL 2.0), NanoBoyAdvance (GPLv3), Hades (GPLv2) y FuzzARM (GPLv3): **leer sí, copiar no**. Ninguno de sus bytes ni "adaptaciones" entran en `gba/`.
- Pruebas libres (MIT): [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) (arm, thumb, memory, bios, flash64/128, sram, eeprom, nes), [SingleStepTests/ARM7TDMI](https://github.com/SingleStepTests/ARM7TDMI) (JSON de instrucciones aisladas), más la suite de mGBA **solo como ROM para ejecutar** (los resultados en pantalla sí se pueden usar como oráculo; su código no se copia). AGS aging cartridge es comercial: no.
- Estimación de tamaño del núcleo: ~9 000 líneas de C (el núcleo GB tiene 3 665). Es el mayor bloque de trabajo del proyecto hasta ahora.
- Rendimiento objetivo en el iPhone: intérprete con decodificación por tabla, ≥ 2× tiempo real con vídeo y audio en un A15 sin JIT (iOS no permite JIT en apps instaladas con Apple ID gratuito). En Linux el runner mide `--bench`.
- Partidas: formato `.sav` crudo compatible con mGBA/VBA (SRAM 32 KiB, Flash 64/128 KiB, EEPROM 512 B/8 KiB) y RTC de 16 bytes aparte. Misma ruta atómica + 5 backups de la app (regla dura 6). Las partidas GB y GBA no se mezclan: huella SHA-256 del ROM entero, como hoy.
- App: `.gba` en la biblioteca (hasta 32 MiB), pantalla 3:2, botones L/R, disposición de controles propia, misma pausa, save states, mando, avance rápido y ajustes. Lo que ya existe se parametriza por consola; no se duplica.
- Orden: G0 instrucciones → G1 CPU → G2 memoria+DMA+timers+IRQ → G3 PPU → G4 cartucho/saves → G5 APU → G6 save states+fuzz → G7 app (biblioteca, sesión) → G8 app (controles, pantalla) → G9 cierre. Cada hito con su auditoría.

## 1. Decisiones (tomadas el 2026-10-05: las recomendaciones; la 5 sigue abierta)
| # | Pregunta | Recomendación |
|---|---|---|
| 1 | ¿Segundo núcleo `gba/` separado o un `core/` común con `#ifdef`? | **Separado.** Ninguna pieza de hardware coincide; un núcleo común solo añadiría ramas y riesgo de regresión al GB. Se comparte `sha256` **sin moverlo**: `gba/Makefile` compila `core/src/sha256.c` y la app ya lo enlaza con el núcleo GB (moverlo obligaría a tocar el proyecto Xcode y el núcleo GB). |
| 2 | ¿BIOS? | **HLE propio** por defecto (Nintendo no se puede incluir). Añadir soporte para un `gba_bios.bin` que Joel vuelque de su propia GBA, en la carpeta de la biblioteca, para los juegos que la HLE no cubra. Normmatt publicó una BIOS libre, pero su repositorio no expone licencia (`github.com/Normmatt/gba_bios`, LICENSE 404): **no se usa** hasta que se verifique. |
| 3 | ¿Referencia de la que copiar? | Solo **SkyEmu** (MIT, confirmado en su `LICENSE`). Para todo lo demás, GBATEK y las ROMs de prueba. El oráculo de comparación dev-only pasa a ser mGBA (como SameBoy en GB: se compila, no se enlaza en la app). |
| 4 | ¿Cómo se llama el proyecto cuando reproduce GBA? | Sin cambios de nombre: PocketGB sigue. (Si Joel quiere, se renombra al final en G9.) |
| 5 | ¿Qué juego de Joel sirve de prueba real 🍎? | Hace falta un cartucho GBA propio volcado (p. ej. Pokémon Rubí/Zafiro/Esmeralda: Flash 128 KiB + RTC, el peor caso de save; Rojo Fuego/Verde Hoja: Flash 128 KiB sin RTC). Hasta que exista, todo es homebrew. |

## 2. Arquitectura resultante
```
ios/ (Swift)
  EmulatorSession ──► protocol ConsoleCore { loadRom, runFrame, framebuffer(w,h), audioRead,
                      │                     buttons, sram, state, info }
                      ├── GBCore  (CoreBridge actual → pocketgb.h)
                      └── GBACore (nuevo        → pocketgba.h)
core/   (C11)  Game Boy / Color — sin cambios funcionales
gba/    (C11)  Game Boy Advance — nuevo: include/pocketgba.h, src/, tests/, fuzz/, Makefile
               (usa core/src/sha256.c; símbolos con prefijo gba_/arm_, make check-symbols)
```
Reglas del núcleo `gba/` (idénticas a las de `core/`, regla dura 4): sin I/O, sin `malloc` en `gba_run_frame`, sin estáticos mutables, determinista, bounds-check en todo acceso al cartucho, `-std=c11 -Wall -Wextra -Werror -pedantic`, `make -C gba test|asan|fuzz|check-globals`.

### 2.1 API C (`gba/include/pocketgba.h`), espejo de `pocketgb.h`
| Función | Notas |
|---|---|
| `gba_create` / `gba_destroy` | Una instancia = una GBA. |
| `gba_load_rom(g, data, len, opts)` | ≤ 32 MiB. Valida cabecera (logo opcional, checksum de cabecera `0xBD`), detecta el tipo de save por las cadenas `EEPROM_V`, `SRAM_V`, `FLASH_V`, `FLASH512_V`, `FLASH1M_V` (como mGBA/VBA) con override en `opts.save_type`. |
| `gba_load_bios(g, data, 16384)` | Opcional. Sin ella, HLE. |
| `gba_set_buttons(g, mask)` | `GBA_BTN_A/B/SELECT/START/RIGHT/LEFT/UP/DOWN/R/L`. |
| `gba_run_frame(g)` | Hasta el siguiente VBlank: 280 896 ciclos. |
| `gba_framebuffer(g)` | `uint32_t[240*160]` RGBA8888 (mismo convenio que GB). |
| `gba_audio_read(g, out, max)` | Estéreo `int16` a `opts.sample_rate`; mezcla interna a 32 768 Hz → remuestreo. |
| `gba_save_data/size/dirty/clear_dirty` | Bytes crudos del medio de guardado (SRAM/Flash/EEPROM) + `gba_rtc_save/load` (16 B). |
| `gba_state_size/save/load` | Formato propio versionado con CRC-32 y huella del ROM; `GBA_ERR_STATE_*`. |
| `gba_rom_info_get` | Título (12), código (4), fabricante, versión, tipo de save detectado, huella SHA-256, flags (RTC, sensor…). |

## 3. Hitos

### G0 · Instrucciones y andamiaje (☁️)
Rama `g0-gba-instrucciones`. Este documento aprobado; `docs/10-gba-spec.md` (equivalente a [03-core-spec](../03-core-spec.md): mapa de memoria, waitstates, registros E/S, tiempos del frame, qué se emula y qué no); `gba/` con Makefile, header, `check-globals`, `check-header`, runner `gbatest` vacío; `tools/fetch-test-roms.sh` descarga con hash jsmolka/gba-tests (release) y los JSON de SingleStepTests/ARM7TDMI; `.githooks/pre-commit` detecta también ROMs GBA por contenido (logo Nintendo en `0x004`, 156 bytes, y `0x96` en `0xB2`) y `*.gba`; `.gitignore`. CI: job Linux para `make -C gba test asan`.
**Criterios:**
- [x] `gba/` con contrato, Makefile (`test`, `asan`, `check-header`, `check-globals`, `check-symbols`), runner y suite; `tools/fetch-gba-test-roms.sh` (descarga fijada a commit; `codeload` está bloqueado en la nube, así que se usa git en vez de tarballs con hash).
- [x] El hook bloquea un ROM GBA renombrado a `.txt` y un `gba_bios.bin` de 16 KiB.
- [x] `.github/workflows/gba.yml` (Linux) y `docs/10-gba-spec.md`.
- [x] Auditoría Opus: APROBAR CON CAMBIOS → G0-1..4 corregidos ([G0-opus](../auditorias/G0-opus.md), [respuesta](../auditorias/G0-G1-respuesta.md)).

### G1 · CPU ARM7TDMI (☁️)
`arm.c`/`thumb.c` (decodificación por tabla de 4096 entradas para ARM y 1024 para Thumb, generada en tiempo de compilación con `.inc`), banca de registros por modo, CPSR/SPSR, excepciones (IRQ, SWI, undefined), pipeline de 3 etapas modelado como prefetch de 2 instrucciones (necesario para que `PC` lea +8/+4), tiempos N/S/I por acceso. Sin PPU: memoria plana de prueba.
**Criterios:**
- [x] SingleStepTests/ARM7TDMI al 100 %: 45 archivos × 50 000 casos (2 250 000) (incluye el acarreo de las multiplicaciones).
- [x] `arm.gba` y `thumb.gba` de jsmolka en PASS (resultado en r12 al llegar al bucle final). `memory.gba` también pasa.
- [x] ASan + UBSan limpios.
- [x] `--bench`: 13,5× tiempo real solo CPU con 1 ciclo por acceso (el 50× que pedía el borrador era irreal para un intérprete ARM; el objetivo que importa es el del iPhone con vídeo y audio, medido en G3 y G8).
- [x] Auditoría Opus: APROBAR CON CAMBIOS → G1-1..3 corregidos ([G1-opus](../auditorias/G1-opus.md), [respuesta](../auditorias/G0-G1-respuesta.md)).

### G2 · Bus, DMA, timers, interrupciones, HLE de BIOS (☁️)
Mapa de memoria completo (BIOS, EWRAM 256 KiB con waitstate 2, IWRAM, E/S, paleta, VRAM con espejo, OAM, ROM con 3 regiones de waitstate y prefetch, SRAM), `WAITCNT`, lecturas abiertas (open bus) y acceso desalineado, `IE/IF/IME`, `HALTCNT`, 4 DMA (inmediato, VBlank, HBlank, FIFO, especial del vídeo), 4 timers en cascada, `KEYINPUT`/`KEYCNT`. HLE de SWI: `SoftReset`, `RegisterRamReset`, `Halt`, `IntrWait`, `VBlankIntrWait`, `Div`, `Sqrt`, `ArcTan(2)`, `CpuSet`, `CpuFastSet`, `BgAffineSet`, `ObjAffineSet`, `LZ77`, `Huffman`, `RLUnComp`, `Diff*`, `MidiKey2Freq`, `SoundBias`; lo no cubierto se registra en una lista de "known-unimplemented" como hoy. Lectura de la BIOS protegida (devuelve la última instrucción leída desde la BIOS).
**Criterios:**
- [x] `memory.gba`, `bios.gba` y `nes.gba` de jsmolka en PASS.
- [x] Tests unitarios: timers en cascada y prescaler, DMA inmediata con IRQ y de HBlank con repetición, waitstates (por defecto y `0x4317`), `IntrWait` desde un programa ARM, Div/Sqrt/ArcTan2, LZ77 (WRAM y VRAM), RL, CpuSet/CpuFastSet, Huffman malformado sin cuelgue.
- [x] ASan + UBSan limpios (encontró un desplazamiento de negativo en ArcTan2: corregido a 64 bits).
- [ ] La suite de tiempos de mGBA no se ejecuta: no hay binario publicado y compilarla necesita devkitARM. Se mide con juegos reales en G8. El prefetch del cartucho es una aproximación (`docs/10-gba-spec.md` §Tiempos).
- [x] Auditoría: Opus RECHAZAR (H1 recursión de DMA, H2 VBlankIntrWait) → H1–H8 corregidos → segunda vuelta APROBAR CON CAMBIOS → N1–N3 corregidos ([G2-opus](../auditorias/G2-opus.md), [v2](../auditorias/G2-opus-v2.md), [respuesta](../auditorias/G2-respuesta.md)).

### G3 · PPU (☁️)
Scanline a scanline (como el GB): modos 0–2 (fondos de tiles, 2 afines en modo 1, 4 en modo 2), modos 3–5 (bitmap), objetos regulares y afines, ventanas 0/1/OBJ, mosaico, mezcla alfa y brillo, prioridades, `VCOUNT`/`DISPSTAT` con IRQ de HBlank/VBlank/VCount, DMA de HBlank disparado desde la PPU, pantalla apagada (forced blank). Salida RGBA8888 desde BGR555.
**Criterios:**
- [x] `hello.gba`, `shades.gba` y `stripes.gba` de jsmolka idénticos a mGBA.
- [x] Oráculo mGBA (`make -C gba oracle`) y 14 escenas homebrew propias (las demos de tonc no tienen binarios publicados ni se pueden compilar sin devkitARM; se sustituyen por ROMs propias compiladas con clang). 12 de 14 idénticas píxel a píxel; las 2 restantes difieren por detalles de mGBA documentados en `docs/10-gba-spec.md` §PPU.
- [x] Referencias en `gba/tests/ref/` comprobadas por la suite sin mGBA (68/68), ASan + UBSan limpios, determinismo (dos ejecuciones idénticas).
- [x] `--bench` con vídeo: 6,3–7,9× tiempo real según la escena.
- [x] Auditoría Opus: APROBAR CON CAMBIOS → A1–A4 corregidos ([G3-opus](../auditorias/G3-opus.md), [respuesta](../auditorias/G3-respuesta.md), [evidencia](../auditorias/G3-evidencia.md)).

### G4 · Cartucho y saves (☁️)
Detección del tipo de save, SRAM 32 KiB, Flash 64 KiB (SST/Panasonic) y 128 KiB con bancos (Sanyo/Macronix) incluyendo máquina de estados de comandos y IDs de fabricante, EEPROM 512 B / 8 KiB vía DMA con detección automática de tamaño, RTC S-3511A por GPIO (`0x80C4–0x80C8`) para Pokémon Rubí/Zafiro/Esmeralda, flag `dirty`. Todo con bounds-check; tamaños contra la cabecera y el archivo real (regla dura 3).
**Criterios:**
- [x] `sram.gba`, `flash64.gba`, `flash128.gba` y `none.gba` de jsmolka en PASS (jsmolka no tiene prueba de EEPROM).
- [x] EEPROM de 512 B y 8 KiB y RTC: ROMs homebrew propias que se autoverifican (`eeprom.c`, `rtc.c`).
- [x] Tests unitarios: detección de las 6 cadenas, `.sav` de tamaño incorrecto rechazado sin tocar la partida, EEPROM con tamaño por `.sav` o por ajuste, modo ID de la Flash, `dirty`, `.rtc` ida y vuelta.
- [x] ASan + UBSan limpios; fuzzer `fuzz_load_rom` 600 s sin crashes.
- [x] Auditoría Opus: APROBAR CON CAMBIOS → A1, M1–M3 y B1–B4 corregidos ([G4-opus](../auditorias/G4-opus.md), [respuesta](../auditorias/G4-respuesta.md), [evidencia](../auditorias/G4-evidencia.md)).

### G5 · APU (☁️)
Los 4 canales heredados de GB (reutilizando el **diseño** de `apu.c`, no el código tal cual: distinta base de reloj y registros `SOUNDCNT_H` con volúmenes 25/50/100 %), 2 canales DirectSound con FIFO de 32 bytes alimentados por DMA 1/2 y timers 0/1, `SOUNDBIAS`, remuestreo a `sample_rate` (misma técnica de catch-up que M5). El estado de la APU se serializa en G6.
**Criterios:**
- [x] Tests unitarios: FIFO con recarga por DMA al bajar de 16 bytes, reinicio de FIFO, apagado, bancos de onda, SOUNDBIAS, ~804 frames de audio por frame de vídeo a 48 kHz.
- [x] ROMs homebrew propias de audio: pulso PSG (439,8 Hz), seno por DirectSound A (1024 Hz) y B (512 Hz, timer 1, solo derecha), canal de onda con dos bancos al 75 % (128 Hz, solo izquierda) y ruido (solo derecha) con la frecuencia correcta en la salida; proporción de niveles PSG/DirectSound igual a mGBA (oráculo con `--audio`). No hay homebrew de audio libre con WAV de referencia publicado.
- [x] ASan + UBSan limpios (77/77); 6,4–12,3× tiempo real con vídeo y audio.
- [x] Auditoría Opus: APROBAR CON CAMBIOS → H1–H6 corregidos ([G5-opus](../auditorias/G5-opus.md), [respuesta](../auditorias/G5-respuesta.md), [evidencia](../auditorias/G5-evidencia.md)).
- [ ] Escucha en el iPhone (G8).

### G6 · Save states, fuzzing, determinismo (☁️)
Formato de estado completo (CPU, bus, PPU, APU, DMA, timers, cartucho, RTC) con CRC-32 y huella del ROM.
**Criterios:**
- [x] Estado completo con validación de rangos y coherencia sobre una copia (la instancia no cambia ante un error); ida y vuelta exacta; errores de magia, versión, ROM, longitud, CRC y campo fuera de rango con CRC correcto (tests unitarios).
- [x] Determinismo: modo `state` en 7 ROMs (vídeo con IRQ/DMA, audio PSG y DirectSound, EEPROM, Flash, BIOS) y modo `det` (dos instancias, hasta 1 000 frames) en 3: frames y audio idénticos.
- [x] Fuzzers: `fuzz_load_rom` (600 s en G4), `fuzz_state_load` (datos arbitrarios y estados válidos mutados con CRC recalculado) y `fuzz_io` (escrituras arbitrarias a E/S, VRAM, OAM, paleta, EEPROM y SRAM), 600 s cada uno (resultado en la evidencia).
- [x] `check-globals` y `check-symbols`; ASan + UBSan limpios en toda la suite.
- [x] Auditoría de núcleo completa (Opus de respaldo en la nube; Codex en el Mac) antes de tocar la app ([G6-evidencia](../auditorias/G6-evidencia.md), [G6-opus](../auditorias/G6-opus.md): APROBAR CON CAMBIOS → [corregido](../auditorias/G6-respuesta.md)).

### G7 · App: biblioteca y sesión multiconsola (☁️ + CI macOS)
`ConsoleCore` como protocolo; `GBCore` envuelve el puente actual sin cambiar su comportamiento (los 93 tests de la app deben seguir en verde); `GBACore` sobre `pocketgba.h` (se añade `gba/src` al target de Xcode como referencia a carpeta y `pocketgba.h` al modulemap); `RomEntry` acepta `.gba` (límite 32 MiB, cabecera GBA válida, mensaje de error propio); portadas, favoritos, filtros por consola en Biblioteca; `SaveStore` guarda el `.sav` GBA (medio + 16 bytes de RTC al final cuando el cartucho tiene reloj, como hace mGBA; decisión de implementación del G7 en lugar de un `.rtc` aparte, que obligaba a un segundo archivo y a un segundo espejo) con la misma ruta atómica y backups y el mismo espejo junto al ROM (`SaveResolution` no cambia; se parametriza el tamaño esperado); save states GBA en la misma carpeta con prefijo de consola; BIOS opcional detectada en la raíz de la carpeta de la biblioteca (`gba_bios.bin`, 16 KiB, se valida por SHA-256 conocido y se informa en Ajustes). **Criterio:** CI verde, capturas de la biblioteca con un homebrew `.gba`, test de que un save GBA de tamaño incorrecto no se sobrescribe. **Cumplido** ([G7-evidencia](../auditorias/G7-evidencia.md); auditoría Codex y [respuesta](../auditorias/G7-respuesta.md)).

### G8 · App: pantalla, controles y audio GBA (☁️ + CI macOS, 🍎 prueba)
Textura Metal de 240×160 (relación 3:2; el shader ya es independiente del tamaño), disposición de controles GBA por orientación con L/R (editor de D4 extendido, valores por defecto propios), mapeo de mando físico con L/R, HUD y pausa iguales, avance rápido ×2/×4, ajustes por juego (BIOS, tipo de save forzado, RTC). Audio: ring buffer igual, `sample_rate` 48 kHz. **Criterio:** capturas del catálogo en GBA (horizontal y vertical, AX5, Reduce Transparency), 60 fps con `--bench` del HUD DEBUG en el iPhone de Joel, Joel juega un cartucho propio ≥ 30 min, guarda, cierra forzado y recupera la partida.
**Estado:** hecho y auditado (Opus: APROBAR CON CAMBIOS → [corregido](../auditorias/G8-respuesta.md); H11, la documentación, se cerró en G9): disposición de controles propia de GBA por orientación, ajustes por juego validados, capturas horizontal/vertical/AX5/Reduce Transparency. **Sigue 🍎 (Joel, en el iPhone):** 60 fps con el HUD DEBUG, cartucho propio ≥ 30 min con cierre forzado, escucha del audio y L/R con el juego; además su aprobación del descarte de G7-1 y su decisión sobre G7-3.

### G9 · Cierre (☁️ + 🍎)
**Estado (2026-10-06): ☁️ hecho, falta lo 🍎.** Regresión y documentación ejecutadas ([G9-evidencia](../auditorias/G9-evidencia.md)): GB 157/157 (+ ASan), GBA en el Mac con la limitación de `ld.lld`/SingleStepTests documentada, tests de la app y Release. Docs actualizados (10-gba-spec, 02, 04, 06, 08, 09, diseno/SPEC, hitos, ESTADO; H11 cerrado).
**Pendiente de Joel (🍎, no marcado como cumplido):** [ ] 60 fps con el HUD DEBUG en el iPhone; [ ] cartucho propio (Kirby) ≥ 30 min, guardar, cierre forzado y recuperar; [ ] escucha del audio (PSG + DirectSound); [ ] L/R con el juego; [ ] **aprobar el descarte de G7-1** y **decidir sobre G7-3** ([G7-respuesta](../auditorias/G7-respuesta.md)); [ ] aprobar la PR a `main`. Pendiente de Claude/Codex: [ ] auditoría final Codex de todo el trabajo GBA.

Alcance original: Regresión total (GB 157/157 + GBA completa + tests de la app + UI), documentación (`docs/10-gba-spec.md` final, [02-arquitectura](../02-arquitectura.md), [04-ios-spec](../04-ios-spec.md), [06-testing](../06-testing.md), [08-roms-legal](../08-roms-legal.md) con la BIOS, [09-referencias](../09-referencias.md)), auditoría final Codex, PR a `main` con aprobación de Joel, nota en [ESTADO.md](../ESTADO.md).

## 4. Riesgos y cómo se acotan
| Riesgo | Mitigación |
|---|---|
| Tamaño: es ~2,5× el núcleo GB. | Un hito por subsistema, cada uno con pruebas libres que lo cierran. Codex puede implementar bloques mecánicos (tabla de decodificación, HLE de descompresión) por encargo; audita Opus. |
| Rendimiento en el iPhone sin JIT. | Decodificación por tabla y sin ramas por byte en el bus; medir desde G1 (`--bench`). Si no llega a ×1 en el A15 con −O2, el plan B es un caché de decodificación por bloque (sigue siendo intérprete). |
| Juegos que exigen la BIOS real (p. ej. `Huffman`, timings de `IntrWait`). | HLE amplia + BIOS propia de Joel opcional; lista pública de known-fail. |
| Pérdida de partida por detección errónea del tipo de save. | Override por juego en Ajustes; nunca sobrescribir un `.sav` de tamaño distinto; backups; test dedicado. |
| Contaminación de licencia al "leer" mGBA/NBA. | Solo SkyEmu como fuente copiable, citada por archivo; la auditoría compara contra las reglas de [AGENTS.md](../../AGENTS.md) §2. |
| Regresión en GB. | `core/` no cambia salvo mover `sha256`; su suite corre en cada PR. |

## 5. Lo que queda fuera (por ahora)
Cable link GBA (multiboot/serial entre instancias), e-Reader, sensores (solar, giroscopio, rumble), Wireless Adapter, GB Player, emulación de GB/GBC *dentro* de la GBA (ya tenemos el núcleo nativo), JIT.

## 6. Referencias
- GBATEK (Martin Korth), la referencia principal; CowBite spec; tonc (Jasper Vijn) para las demos.
- [SkyEmu](https://github.com/skylersaleh/SkyEmu) — MIT, única fuente copiable con cita.
- [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests) — MIT. [SingleStepTests/ARM7TDMI](https://github.com/SingleStepTests/ARM7TDMI) — MIT.
- mGBA (MPL 2.0), NanoBoyAdvance (GPLv3), FuzzARM (GPLv3): solo lectura y ROMs/oráculo.
