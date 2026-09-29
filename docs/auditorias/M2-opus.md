# M2 · Auditoría (fallback: subagente Opus sin historial, en la nube)

> Codex no está disponible en Claude en la nube. Auditor: subagente Opus nuevo con `docs/auditorias/PROMPT.md`. Commit auditado: `5c88beb`. Informe copiado sin cambios de contenido.

## Veredicto: APROBAR CON CAMBIOS

Ningún hallazgo es bloqueante ni de severidad alta. Los tres criterios de aceptación los reproduje yo en una copia en `/tmp/audit-m2`, con HEAD en `5c88beb`. Los cambios que pido son menores: robustez del render ante cambios a mitad de línea, un test que no prueba lo que dice su comentario, lógica duplicada y un typo.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `core/src/ppu.c` (`oam_scan` ~L69-82 y `render_line` ~L166-176) | Los objetos se eligen al final del modo 2 con `height` y `oam[i*4]` de ese momento. Al renderizar, en el paso a modo 0, se vuelven a leer `LCDC.2` y la Y de OAM. Si LCDC.2 cambia durante el modo 3, o si el OAM DMA reescribe OAM entre la búsqueda y el render, `row = ly - oy` puede quedar fuera de `[0,height)`. Con flip Y, `height-1-row` da la vuelta como unsigned. En memoria es seguro (`tile_row` aplica `& 0x1FFF`, aritmética unsigned, sin UB), pero se dibujan filas de tile arbitrarias. | Análisis del código. `row` se calcula sin acotar en `render_line`. | Guardar `height` (o `row` por objeto) en `oam_scan` y usarlo en el render, o saltar el objeto si `row >= height`. Mantener la máscara como defensa. |
| H2 | baja | `core/tests/unit_ppu.c:57-72` | El comentario dice "objeto 0 con prioridad BG", pero `oam[3] = 0x00` (bit 7 = 0). Ningún unit test prueba la prioridad BG>OBJ, los flips, 8×16, el límite de 10 por línea ni la ventana; solo lo cubre dmg-acid2 de forma indirecta. | `g->mem.oam[3] = 0x00;` | Corregir el comentario y añadir un caso con `attr=0x80` sobre fondo ≠0 y sobre fondo color 0. |
| H3 | baja | `core/src/cpu.c:18-33` y `core/src/ppu.c` (`ppu_vram_blocked`/`ppu_oam_blocked`) | La regla de bloqueo VRAM/OAM por modo está duplicada: `bus_blocked` la reimplementa en línea y las funciones exportadas solo las usa `unit_ppu.c`. El unit test verifica una copia que la CPU no ejecuta. | `grep ppu_vram_blocked core/src` | Que `bus_blocked` llame a esas funciones (`static inline`), o quitarlas. |
| H4 | baja | `core/tests/suite.txt` (bloque M2 `oam_dma`) | Las tres líneas M2 son código muerto: ya las cubren las de M1 y el dedupe de `run_suite.py` las descarta. | Salida de `make test HITO=M2`: ninguna fila M2 de `oam_dma` | Quitarlas o comentarlas. |
| H5 | baja | `core/tests/runner.c` (bloque `--dump`) | Si `fopen`/`fwrite` fallan, solo se avisa por stderr y el código de salida sigue siendo 0 si hubo PASS. | Código | Devolver 2 si falla el volcado pedido. |
| H6 | baja | `tools/png2rgba.py` (`read_png`) | Un PNG gris/paleta con `depth == 16` entra en la rama sub-byte y falla con un desplazamiento negativo; sin IHDR, `interlace` no está definida. No afecta a las referencias actuales (RGB 8 bits). | Lectura del código | Aceptar solo `depth in (1,2,4,8)` y rechazar el resto con un mensaje claro. |
| H7 | baja | `docs/03-core-spec.md:77` | Falta el punto en "…del cambio de LY El latch de WY…". | diff de docs/03 | Añadir el punto. |

Reglas duras: sin ROMs ni saves en ningún commit (`git ls-tree` de todo `git log --all`; hook activo). Sin código copiado de GPL/AGPL (`ppu.c` y `png2rgba.py` propios, stdlib). No se toca iOS. `check-globals` OK; `render_line` usa arrays en la pila, sin `malloc`. Índices de VRAM acotados (mapa ≤ `0x1FFF`; tiles `0x0800..0x17F0` con signo, `≤0x0FF0` sin signo; objetos con `& 0x1FFF`).

## Criterios del hito
| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `make -C core test HITO=M2`: dmg-acid2 idéntico, `oam_dma` en PASS, sin regresiones de M1 | sí | `70/80 PASS · requeridos: 70/70 PASS · HITO=M2`, exit 0; coincide fila por fila con `M2-evidencia.md`. |
| `make -C core asan HITO=M2` limpio | sí | 70/70 requeridos, exit 0, sin informes. |
| Captura `build/dmg-acid2.png` adjunta | sí | `docs/auditorias/M2-dmg-acid2.png` coincide byte a byte con la referencia decodificada y con el `--dump` del auditor (sha256 `95afb926…ddda`). |
| (extra) `make -C core check-globals` | sí | `Sin estado global mutable: OK` |

## Notas
- Evidencia plausible y del commit auditado. Bench del auditor: 49,1× (dmg-acid2) y 37,9× (cpu_instrs).
- La referencia se decodifica con el mismo `png2rgba.py` que genera la captura; comprobado IHDR (RGB 8 bits) e histograma de 4 grises exactos.
- **Orden R/B no verificado:** con la paleta gris el test acid no distingue R de B. Cubrirlo antes de M8 (cgb-acid2).
- Correcto frente a Pan Docs y docs/03: prioridad DMG estable, píxel opaco "reclama" aunque lo tape el fondo, 8×16 y flips, contador de ventana, LCDC.0=0, bloqueos por modo, STAT por flanco.
- Simplificaciones no contadas como hallazgo (coinciden con los `known-fail` y docs/03): primer frame tras encender el LCD, línea 153 con LY=0, bug de escritura en STAT; la PPU sigue leyendo OAM durante un DMA.
- Pendiente del cierre del hito: `ESTADO.md`, tabla de hitos y casillas de `M2-ppu-dmg.md`.
- Sin verificar: juegos reales (no hay cartuchos en el entorno).
