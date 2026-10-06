# Auditoría G3 (PPU GBA) · Opus de respaldo
Commit auditado: `f29fdb7`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G3-A1 | media | ppu.c `render_affine_bg`, `render_bitmap_bg` | Falta el mosaico vertical en fondos afines y bitmap; la spec lo da por hecho y ninguna escena lo prueba. | `line` se descarta; solo se usa `mh`. | Repetir la fila de la primera línea del bloque y añadir una escena. |
| G3-A2 | baja | ppu.c `render_objs` (`tile & ~1u`) | En 256 colores se borra el bit 0 de la tesela también en 1D; GBATEK solo lo ignora en 2D. | Lectura. | Borrarlo solo en 2D. |
| G3-A3 | baja | ppu.c, avance de `ref_x/ref_y` | Sin recarga (p. ej. un estado de G6 no validado) puede haber desbordamiento con signo. | 2000 frames sin VBlank: `signed integer overflow`. En el flujo real no ocurre. | Aritmética sin signo y `sext28` al restaurar. |
| G3-A4 | baja | gba-compare.py | El frame 1 no es comparable entre emuladores. | Ejecución. | Documentarlo. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| jsmolka ppu idénticos | sí | 0 diferencias. |
| 10 de 12 escenas idénticas | sí | 8: 559, 10: 27043, como la spec. |
| Referencias del oráculo | sí | Las 10 `-mgba` coinciden con una ejecución nueva; ninguna trivial. |
| Modo ref | sí | Compara RGB por píxel; rechaza tamaños incorrectos. |
| test/asan G3 | sí | 66/66 y 66/66. |
| Estado hostil (ASan+UBSan) | sí | 20 000 iteraciones aleatorias sin lecturas fuera de rango. |
| Regla dura 2 | sí | Estructura propia; sin código de mGBA; `shot.c` solo usa la API pública; libmgba solo en `gba/build/mgba-shot`. |
| Reglas 1 y 4 | sí | Sin binarios; sin globals ni malloc. |
