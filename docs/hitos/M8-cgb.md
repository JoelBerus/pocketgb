# M8 · Game Boy Color (Amarillo en color) + paleta de compatibilidad (Rojo)

☁️ Núcleo · 🍎 prueba en iPhone. Spec: [03](../03-core-spec.md) §Arranque, §PPU (CGB).

**Tareas:** VRAM y WRAM con bancos, paletas CGB, atributos BG, HDMA, doble velocidad (`KEY1` + `STOP`), estado post-boot CGB (`A=0x11`), tabla de paletas de compatibilidad por checksum del título (Pan Docs) y selección manual de paleta en Ajustes para juegos DMG.

**Criterios de aceptación**
- [ ] `make -C core test HITO=M8` → cgb-acid2 idéntico; dmg-acid2 en modo CGB (compatibilidad) idéntico a `dmg-acid2-cgb.png`; casos M1–M3 sin regresiones.
- [ ] iPhone: Amarillo se ve en color (paletas por zona) y Rojo con su paleta de compatibilidad.
