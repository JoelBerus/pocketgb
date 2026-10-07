# G5 · Evidencia (APU)

Rama `g5-gba-apu` (sobre G4), 2026-10-05, Linux (clang 18.1.3).

## Qué se implementó
- `gba/src/apu.c`: PSG (adaptación propia de `core/src/apu.c` con ciclos del GBA y dos bancos de onda), DirectSound A/B con FIFO de 32 bytes, recarga por DMA1/2 en modo especial y mezcla `SOUNDCNT_H`; catch-up, pasa-altos y anillo de salida. `gba_audio_read/available`.
- E/S de sonido enrutada a la APU; desbordes de los timers 0/1 sacan muestras de las FIFO.
- ROMs propias `audio.c` (PSG y DirectSound), modo `audio:HZ` del runner (`--wav` opcional) y `shot --audio` en el oráculo.

## Salida
```
PASS  G1 unit: PASS unit: 0 fallos
PASS  G5 audio_psg.gba: PASS build/hb/audio_psg.gba: 439.8 Hz (esperado 439.8), pico 2638, 96198 muestras
PASS  G5 audio_ds.gba: PASS build/hb/audio_ds.gba: 1025.0 Hz (esperado 1024.0), pico 10654, 96198 muestras
77/77 sin fallos requeridos (7.1 s)
77/77 sin fallos requeridos (15.5 s)
bench: 3000 frames en 7.42 s = 404 fps (6.8x tiempo real)
bench: 3000 frames en 4.08 s = 736 fps (12.3x tiempo real)
GBA DMA: Starting DMA 1 0x02000000 -> 0x040000A0 (B640:4000)
GBA DMA: Starting DMA 1 0x02000000 -> 0x040000A0 (B640:4000)
GBA DMA: Starting DMA 1 0x02000000 -> 0x040000A0 (B640:4000)
GBA DMA: Starting DMA 1 0x02000000 -> 0x040000A0 (B640:4000)
mGBA:     PSG 6187 pico 439.9 Hz | DS 24875 pico 1023.5 Hz | DS/PSG 4.02
PocketGB: PSG 2638 pico 439.8 Hz | DS 10654 pico 1025.0 Hz | DS/PSG 4.04
```

Las dos líneas "77/77" son `make test` y `make asan`; las de bench, la escena 7 (vídeo cargado) y el seno por DirectSound; las dos últimas, niveles y frecuencias en mGBA y en PocketGB (la escala absoluta difiere a propósito; la proporción DirectSound/PSG coincide).
