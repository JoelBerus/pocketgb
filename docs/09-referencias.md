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

## Game Boy Advance
| Referencia | Licencia | Uso permitido |
|---|---|---|
| **GBATEK** (Martin Korth) | docs | Especificación principal; se cita por sección. |
| **ARM7TDMI Technical Reference Manual** (ARM DDI 0029) | docs | Instrucciones, modos, excepciones. |
| **SkyEmu**: https://github.com/skylersaleh/SkyEmu | MIT | Única fuente de la que se puede copiar código, citando el origen en el archivo. |
| **zaydlang/multiplication-algorithm** | zlib | Acarreo de las multiplicaciones (`gba/src/arm_mulcarry.c`, portado y marcado como modificado). |
| **jsmolka/gba-tests**, **SingleStepTests/ARM7TDMI** | MIT | Pruebas; descarga fijada a commit. |
| **mGBA** 0.10.5 (MPL-2.0) | — | Solo lectura y **oráculo de desarrollo** de la PPU (`make -C gba oracle`, nunca enlazado en la app). No copiar. |
| **NanoBoyAdvance** (GPLv3), **Hades** (GPLv2), **FuzzARM** (GPLv3) | — | Solo lectura. No copiar. |
