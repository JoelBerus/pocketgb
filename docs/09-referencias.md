# 09 · Referencias (qué se toma de cada una)

| Referencia | Licencia | Uso permitido |
|---|---|---|
| **Pan Docs**: https://gbdev.io/pandocs/ | CC0 / docs | Especificación canónica. Se cita por sección en el código. |
| **Tabla de opcodes**: https://gbdev.io/gb-opcodes/optables/ | docs | Ciclos por instrucción. |
| **Gekkio, *Game Boy: Complete Technical Reference***: https://gekkio.fi/files/gb-docs/gbctr.pdf | docs | Timing fino de la CPU y de los accesos. |
| **SameBoy**: https://github.com/LIJI32/SameBoy (tag `v1.0.3`) | Expat/MIT **excepto `iOS/` y `HexFiend/`** | `Core/` solo como oráculo de tests (dev-only). Su frontend iOS **solo se mira** como referencia de UX: no se copia. |
| **binjgb**: https://github.com/binji/binjgb | MIT | Lectura de arquitectura. Se puede copiar citando. |
| **mGBA**: https://github.com/mgba-emu/mgba | MPL-2.0 | Solo lectura. |
| **Gambatte** (gambatte-core) | GPLv2 | Solo lectura. **No copiar.** |
| **Delta**: https://github.com/rileytestut/Delta | AGPLv3 | UX de controles superpuestos, solo mirar. **No copiar.** |
| **Suites de test**: https://github.com/c-sp/game-boy-test-roms (v7.0) | Varias libres | Descarga verificada por hash ([06](06-testing.md)). |
| **pret/pokered**, **pret/pokeyellow** | — | Solo los archivos `.sym` para depurar (direcciones de rutinas y SRAM). No se construyen ROMs a partir de ellos. |
