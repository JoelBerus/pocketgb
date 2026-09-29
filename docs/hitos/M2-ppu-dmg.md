# M2 · PPU DMG + OAM DMA + STAT

☁️ Nube o Mac. Spec: [03](../03-core-spec.md) §PPU.

**Archivos:** `core/src/ppu.c` (ya existe la parte de tiempos desde M1: añadir render y bloqueo VRAM/OAM), `core/src/dma.c` (hecho en M1; revisar la interacción con la PPU), `tools/png2rgba.py`, modo `acid` en el runner, opción `--dump` para inspeccionar frames (escribe RGBA; `png2rgba.py --reverse` lo convierte a PNG).

**Tareas**
1. Máquina de modos 2/3/0/1 por dots, `LY`, `LYC`, línea STAT con detección de flanco, interrupción VBlank.
2. Render por scanline: BG, ventana (contador interno), sprites (10/línea, prioridad DMG, 8×16, flips, prioridad BG).
3. Bloqueo de VRAM/OAM por modo, apagado/encendido del LCD.
4. OAM DMA de 160 M-ciclos.

**Criterios de aceptación**
- [x] `make -C core test HITO=M2` → dmg-acid2 idéntico píxel a píxel y los casos `oam_dma` en PASS. Los casos M1 siguen en PASS (sin regresiones).
- [x] `make -C core asan HITO=M2` limpio.
- [x] Captura `build/dmg-acid2.png` adjunta en el informe de auditoría.

Cerrado el 2026-09-29. Evidencia: [M2-evidencia](../auditorias/M2-evidencia.md) · Captura: [M2-dmg-acid2.png](../auditorias/M2-dmg-acid2.png) · Auditoría: [M2-opus](../auditorias/M2-opus.md) · Respuesta: [M2-respuesta](../auditorias/M2-respuesta.md).
