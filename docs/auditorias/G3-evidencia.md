# G3 · Evidencia (PPU)

Rama `g3-gba-ppu`, 2026-10-05, Linux (clang 18.1.3). Oráculo: mGBA 0.10.5 con `COLOR_16_BIT` (`make -C gba oracle`).

## Qué se implementó
- `gba/src/ppu.c`: PPU por scanline (modos 0–5, texto, afines, bitmaps, objetos normales y afines, ventanas, mezcla, aclarar/oscurecer, mosaico). Ver `docs/10-gba-spec.md` §PPU.
- `gba/tests/homebrew/`: ROMs propias (MIT) en C con arranque y linker script propios; 12 escenas (`-DSCENE=n`).
- `tools/oracle-gba/shot.c` y `tools/gba-compare.py`: oráculo y comparación (dev-only).
- Runner: `--dump`/`--frames`/`--keys` y modo `ref`. Suite: 15 casos de G3 con referencias en `gba/tests/ref/`.

## Salida
```
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
PASS  G1 unit: PASS unit: 0 fallos
PASS  G1 gba-tests/arm/arm.gba: PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
PASS  G1 gba-tests/thumb/thumb.gba: PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)
PASS  G2 gba-tests/memory/memory.gba: PASS tests/roms/gba-tests/memory/memory.gba (2 frames)
PASS  G2 gba-tests/bios/bios.gba: PASS tests/roms/gba-tests/bios/bios.gba (3 frames)
PASS  G2 gba-tests/nes/nes.gba: PASS tests/roms/gba-tests/nes/nes.gba (2 frames)
PASS  G3 gba-tests/ppu/hello.gba: PASS tests/roms/gba-tests/ppu/hello.gba (idéntico a la referencia)
PASS  G3 gba-tests/ppu/shades.gba: PASS tests/roms/gba-tests/ppu/shades.gba (idéntico a la referencia)
PASS  G3 gba-tests/ppu/stripes.gba: PASS tests/roms/gba-tests/ppu/stripes.gba (idéntico a la referencia)
PASS  G3 ppu_scene_0.gba: PASS build/hb/ppu_scene_0.gba (idéntico a la referencia)
PASS  G3 ppu_scene_1.gba: PASS build/hb/ppu_scene_1.gba (idéntico a la referencia)
PASS  G3 ppu_scene_2.gba: PASS build/hb/ppu_scene_2.gba (idéntico a la referencia)
PASS  G3 ppu_scene_3.gba: PASS build/hb/ppu_scene_3.gba (idéntico a la referencia)
PASS  G3 ppu_scene_4.gba: PASS build/hb/ppu_scene_4.gba (idéntico a la referencia)
PASS  G3 ppu_scene_5.gba: PASS build/hb/ppu_scene_5.gba (idéntico a la referencia)
PASS  G3 ppu_scene_6.gba: PASS build/hb/ppu_scene_6.gba (idéntico a la referencia)
PASS  G3 ppu_scene_7.gba: PASS build/hb/ppu_scene_7.gba (idéntico a la referencia)
PASS  G3 ppu_scene_9.gba: PASS build/hb/ppu_scene_9.gba (idéntico a la referencia)
PASS  G3 ppu_scene_11.gba: PASS build/hb/ppu_scene_11.gba (idéntico a la referencia)
PASS  G3 ppu_scene_8.gba: PASS build/hb/ppu_scene_8.gba (idéntico a la referencia)
PASS  G3 ppu_scene_10.gba: PASS build/hb/ppu_scene_10.gba (idéntico a la referencia)

66/66 sin fallos requeridos (5.4 s)
66/66 sin fallos requeridos (11.9 s)
bench: 3000 frames en 6.87 s = 437 fps (7.3x tiempo real)
bench: 3000 frames en 6.87 s = 437 fps (7.3x tiempo real)
bench: 3000 frames en 7.37 s = 407 fps (6.8x tiempo real)
bench: 3000 frames en 5.91 s = 507 fps (8.5x tiempo real)
OK   ppu_scene_0 frame 120: 0 píxeles distintos
OK   ppu_scene_1 frame 120: 0 píxeles distintos
OK   ppu_scene_2 frame 120: 0 píxeles distintos
OK   ppu_scene_3 frame 120: 0 píxeles distintos
OK   ppu_scene_4 frame 120: 0 píxeles distintos
OK   ppu_scene_5 frame 120: 0 píxeles distintos
OK   ppu_scene_6 frame 120: 0 píxeles distintos
OK   ppu_scene_7 frame 120: 0 píxeles distintos
DIFF ppu_scene_8 frame 120: 559 píxeles distintos
OK   ppu_scene_9 frame 120: 0 píxeles distintos
DIFF ppu_scene_10 frame 120: 27043 píxeles distintos
OK   ppu_scene_11 frame 120: 0 píxeles distintos
```

Las líneas "66/66" son `make test` y `make asan`; las de bench, escenas 0, 7, 8 y 11; las últimas, `tools/gba-compare.py` contra mGBA (escenas 8 y 10: diferencias documentadas en la spec).

Determinismo: dos ejecuciones de la escena 11 (IRQ y DMA por línea) volcando los frames 30 y 300 dan archivos idénticos (`cmp`).
