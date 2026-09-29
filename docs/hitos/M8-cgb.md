# M8 · Game Boy Color (Amarillo en color) + paleta de compatibilidad (Rojo)

☁️ Núcleo · 🍎 prueba en iPhone. Spec: [03](../03-core-spec.md) §Arranque, §PPU (CGB).

**Tareas:** VRAM y WRAM con bancos, paletas CGB, atributos BG, HDMA, doble velocidad (`KEY1` + `STOP`), estado post-boot CGB (`A=0x11`), tabla de paletas de compatibilidad por checksum del título (Pan Docs) y selección manual de paleta en Ajustes para juegos DMG.

**Criterios de aceptación**
- [x] `make -C core test HITO=M8` → cgb-acid2 idéntico; dmg-acid2 en modo CGB (compatibilidad) idéntico a `dmg-acid2-cgb.png`; casos M1–M3 sin regresiones. ✅ 2026-09-29 ([evidencia](../auditorias/M8-evidencia.md), auditoría [Opus](../auditorias/M8-opus.md)).
- [x] iPhone: Amarillo se ve en color (paletas por zona). ✅ 2026-09-29, Joel lo jugó un rato sin problemas.
- [ ] Rojo con su paleta de compatibilidad: el núcleo la soporta (`GB_MODEL_CGB` + `compat_palette`, cubierto por `unit_cgb.c` y dmg-acid2 en CGB); falta que la app la active → **diferido a D5/D6** (ajustes por juego).
