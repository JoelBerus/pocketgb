# G2 · Evidencia (bus, E/S, DMA, timers, IRQ y BIOS en alto nivel)

Rama `g2-gba-bus`, 2026-10-05, Linux (clang 18.1.3).

## Qué se implementó
- `gba/src/bus.c`: waitstates por región y tipo de acceso (`WAITCNT`), secuencial/no secuencial, prefetch aproximado, bus abierto, protección de lectura de la BIOS, escrituras de 8 bits en VRAM según el modo.
- `gba/src/io.c`: DISPSTAT/VCOUNT con IRQ de VBlank, HBlank y VCount; IE/IF/IME; HALTCNT; KEYCNT; DMA 0–3 (inmediata, VBlank, HBlank, repetición, IRQ, controles de dirección); timers 0–3 con prescaler, cascada e IRQ; salto de la CPU parada al próximo evento.
- `gba/src/hle.c`: manejador de IRQ propio en la zona de la BIOS y 28 SWI en C (lista en `docs/10-gba-spec.md` §BIOS en alto nivel).
- `gba/src/gba.c`: bucle con IRQ, HALT y avance de vídeo y timers por instrucción.

## Salida (tras las correcciones de la auditoría)
```
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
PASS  G1 unit: PASS unit: 0 fallos
PASS  G1 gba-tests/arm/arm.gba: PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
PASS  G1 gba-tests/thumb/thumb.gba: PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)
PASS  G2 gba-tests/memory/memory.gba: PASS tests/roms/gba-tests/memory/memory.gba (2 frames)
PASS  G2 gba-tests/bios/bios.gba: PASS tests/roms/gba-tests/bios/bios.gba (3 frames)
PASS  G2 gba-tests/nes/nes.gba: PASS tests/roms/gba-tests/nes/nes.gba (2 frames)

51/51 sin fallos requeridos (1.0 s)
51/51 sin fallos requeridos (1.6 s)
```
Las 45 suites de SingleStepTests siguen en 50000/50000 (omitidas; incluidas en el 51/51). La segunda línea "51/51" es `make asan HITO=G2`.
