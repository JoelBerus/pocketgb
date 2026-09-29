# M8 · Auditoría (subagente Opus de respaldo, sin historial del desarrollo)

Auditado: commit `f48a4ff` (núcleo CGB), con `docs/auditorias/PROMPT.md`. Codex no está disponible en la nube. Informe copiado tal cual lo devolvió el auditor.

## Veredicto: APROBAR CON CAMBIOS

Audité el commit f48a4ff frente a su padre. La rama `main` local está en a40f515 (M0), así que `git diff main...HEAD` arrastra también M1–M5. Para ver solo M8 usé `git show f48a4ff`. No encontré nada bloqueante: no hay ROMs ni saves en el historial, la licencia es compatible, no hay estado global ni `malloc` en `gb_run_frame`, y los requeridos de M8 pasan idénticos. Hay un fallo de ida y vuelta en los save states que un ROM puede provocar (H1) y varias desviaciones menores respecto a Pan Docs.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | core/src/state.c (sección `CGB `, `u16(&local, &cg.stall, 128 * 16)`); core/src/cgb.c:244 y 274-276 | `stall` puede valer más de 2048, que es el máximo que acepta la carga. Se guarda sin error, pero luego no se puede cargar. Pasa en doble velocidad cuando el último bloque de un HDMA de HBlank cae en el M-ciclo previo a la escritura en FF55 (`gb_tick` corre antes del acceso) y esa escritura lanza un HDMA general de 128 bloques: 16 + 2048 = 2064. | Sondas en /tmp/audit-m8/probe: `probe.c` (API directa) da `stall=2064 save=0 load=12` (GB_ERR_STATE_CORRUPT); `probe3.c` (ROM real: `LD A,1; LDH (4D),A; STOP; bucle { LD A,80; LDH (55),A; LD A,7F; LDH (55),A; JR }`) da `nops=0 stall=2064 save=0 load=12`. `gb_state_save` no valida rangos, `gb_state_load` sí. | Subir el máximo a `128*16 + 16` (o al valor exacto alcanzable) y añadir un test de ida y vuelta con ese caso. Mejor aún: comprobar en el guardado los mismos invariantes que en la carga. |
| H2 | baja | core/src/gb.c:53-54; core/src/cpu.c (`stall` antes que `halted`) | El HDMA de HBlank sigue copiando bloques con la CPU en HALT. Pan Docs (FF55): "Upon halting the CPU … the transfer will also be halted and will be resumed only when the CPU resumes execution". | Lectura del código: `hdma_req` se pone en `ppu_tick` y se atiende en `gb_tick` sin mirar `g->cpu.halted`. | No atender `hdma_req` con `g->cpu.halted` (ni en STOP). Test HALT + HDMA de HBlank. |
| H3 | baja | core/src/cgb.c:242 | Cuando el destino pasa de 0x1FF0 se envuelve a 0x0000 y sigue escribiendo al principio de la VRAM. Pan Docs: "If the transfer's destination address overflows, the transfer stops prematurely". | Lectura del código frente a Pan Docs. | Terminar la transferencia al desbordar y documentarlo. No es un problema de memoria: el índice siempre queda acotado. |
| H4 | baja | core/src/cgb.c:10-19 | El aviso Expat de SameBoy está recortado (la cláusula de garantía omite "INCLUDING BUT NOT LIMITED TO … OTHER DEALINGS IN THE SOFTWARE"). La MIT pide el aviso completo. | Comparado con el LICENSE de SameBoy, que aplica Expat a todo salvo `iOS/` y `HexFiend/`. | Pegar el texto Expat íntegro y literal. |
| H5 | baja | core/src/state.c (APPLY de `CGB `); core/src/cgb.c:154-161 | En compatibilidad las paletas dependen solo de `opts.compat_palette`, pero `gb_state_load` restaura `bg_pal/obj_pal` del archivo: si Joel cambia la paleta y carga un estado, vuelve la antigua. | Lectura del código. | Tras cargar en compatibilidad, llamar a `load_compat_palettes(g)`, o documentarlo en pocketgb.h. |

## Criterios del hito
| Criterio | Verificado por el auditor | Resultado |
|---|---|---|
| `make -C core test HITO=M8`: cgb-acid2 idéntico | sí (copia en /tmp/audit-m8) | `PASS: acid: framebuffer idéntico a la referencia (frame 24)`; comparación RGBA exacta (runner.c:381-395). |
| dmg-acid2 en CGB idéntico a `dmg-acid2-cgb.png` | sí | `PASS … (frame 19)` |
| Casos M1–M3 sin regresiones | sí | `157/176 PASS · requeridos: 157/157 PASS · HITO=M8`, `EXIT=0`. Los 19 FAIL son `known-fail` (5 de M8). `boot_regs-cgb` PASS. |
| `make -C core asan HITO=M8` | sí | 157/157 requeridos, `EXIT=0`, sin `runtime error`/`AddressSanitizer`. Unit: 1148 comprobaciones, 0 fallos. |
| `make -C core check-globals` | sí | `Sin estado global mutable: OK`. Tablas de cgb.c `static const`. `malloc` solo en cart.c:49 y gb.c:28, fuera de `gb_run_frame`. |
| Licencia de las tablas | sí | Las 5 tablas son idénticas a `BootROMs/cgb_boot.asm` de SameBoy (comparadas con script). Expat válida (ver H4). |
| Seguridad de `gb_state_load` (sección CGB) | sí | Todos los índices derivados acotados; sin lecturas fuera de rango. Único problema de invariantes: H1. |
| Render CGB y doble velocidad frente a Pan Docs | sí (revisión) | Coinciden; desviaciones H2 y H3. |
| iPhone: Amarillo en color y Rojo con paleta | no | Lo prueba Joel. |
| Evidencia `M8-evidencia.md` | no | Aún no existía; sus salidas coinciden con el mensaje del commit. |

## Notas
- Sin .gb/.gbc/.sav/.bin en `git ls-files` ni en `git log --all`. Sin código de Gambatte, Delta ni del `iOS/` de SameBoy.
- Limitaciones ya documentadas (no son hallazgos): pausa del cambio de velocidad, DIV inicial en CGB nativo sin verificar, mapa del logo para 0x43/0x58.
- `boot_regs-cgb` y `unused_hwio-C` corren en compatibilidad; los registros post-arranque de CGB nativo solo los cubre `unit_cgb.c` (coinciden con Pan Docs).
- El cambio de `ppu_tick` a un paso de 2 o 4 dots es correcto con su único llamador; la VBlank se dispara aunque el cambio de línea caiga en dot ≠ 0.
- No ejecutó `make fuzz` ni modificó el repo.
