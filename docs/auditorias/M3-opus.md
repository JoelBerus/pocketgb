# M3 · Auditoría (fallback: subagente Opus sin historial, en la nube)

> Codex no está disponible en Claude en la nube. Auditor: subagente Opus nuevo con `docs/auditorias/PROMPT.md`. Commit auditado: `db94dfa`. Informe copiado sin cambios de contenido.

## Veredicto: RECHAZAR

El motivo es H1: un save state manipulado cuyo CRC está bien calculado supera todas las validaciones de `gb_state_load` y, en cuanto se ejecuta un frame, provoca un **heap-buffer-overflow** (escritura de 640 bytes después de `struct gb`). Lo reproduje con ASan. El resto del hito está en buen estado: los tests, ASan, check-globals y el fuzzing corto dan los mismos resultados que la evidencia, y la acotación de bancos es correcta.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | `core/src/state.c:264-266` (efecto en `core/src/ppu.c:131,149,259-260`) | La sección `PPU ` valida cada campo por separado (`dot≤455`, `ly≤153`, `mode≤3`, `mode3_end≤456`, `next_event≤456`), pero no comprueba que sean coherentes entre sí. Un estado con `ly=143, dot=452, mode=3` se acepta. En el siguiente `ppu_tick`, `dot` pasa de 455, `ly` sube a 144, `mode_for()` devuelve 0 y, como `old==3`, se llama a `render_line` con `ly=144`: `out = framebuffer + 144*160` escribe 160 `uint32_t` fuera de `framebuffer`, último miembro de `struct gb`. Un estado es un archivo no confiable y el CRC no protege porque cualquiera puede recalcularlo. | PoC en una copia (`/tmp/audit-m3/poc/poc.c`, modo 0): `gb_state_load` devuelve `GB_OK` y `gb_run_frame` falla con `AddressSanitizer: heap-buffer-overflow ... WRITE of size 4 ... render_line ppu.c:149 ← ppu_tick ppu.c:260 ... 0 bytes after 109200-byte region`. Los 600 s de `fuzz_state_load` no lo encontraron. | Recalcular `mode` y `next_event` al cargar; validar `mode3_end ∈ [252, 319]` y `dot % 4 == 0`; defensa en profundidad `if (p->ly >= GB_SCREEN_H) return;` en `render_line`; test de regresión. |
| H2 | media | `core/src/cart.c:337-339`; origen en `core/src/rtc.c:143` y `core/src/state.c:323` | `unix_time - r->unix` en `int64_t`; `r->unix` puede venir sin acotar del bloque RTC de un `.sav` o de un estado. Con `INT64_MIN` la resta desborda (UB); con `-fno-sanitize-recover` aborta. Ocurre dentro de `gb_sram_load` y en cualquier `gb_rtc_set_time` posterior a cargar un estado. | PoC modos 1 y 2: `src/cart.c:339:53: runtime error: signed integer overflow: 1700000000 - -9223372036854775808 cannot be represented in type 'int64_t'`. | Diferencia en `uint64_t`; acotar `unix` al cargar (p. ej. `0 ≤ unix < 2^40`) y tratar fuera de rango como "sin hora". |
| H3 | media | `core/fuzz/fuzz_state_load.c:52-63`, `core/fuzz/fuzz_load_rom.c:90-93` | `fuzz_state_load` (129 k ejecuciones, cov 442) no llegó a campos PPU incoherentes (H1). Ningún fuzzer pasa bytes hostiles a `gb_sram_load` (H2 inalcanzable). `fuzz_load_rom` va a 6-7 exec/s. | Una corrida de 60 s del auditor dio los mismos cov/ft que la evidencia: el fuzzer se estanca. | Fuzzer estructurado (partir de un estado válido, mutar campos, recalcular CRC); `gb_sram_load` con bytes del fuzzer (tamaño `ram+48`) + `gb_rtc_set_time` + frames. |
| H4 | baja | `core/src/cart.c:270-271`, `core/src/cart.c:222` | Escribir registros RTC no marca `ram_written`: `gb_sram_dirty()` nunca se activa por un cambio de hora (en `0x0F` nunca). El bloque RTC solo llega a disco por las rutas de respaldo del frontend. Coherente con la spec, pero es una laguna. | Lectura de código. | `ram_written = true` en `rtc_write` si `has_battery`, o documentarlo en 04-ios-spec. |
| H5 | baja | `core/src/rtc.c:52` | El comentario "≤ ~2 días" es incorrecto: el peor caso es ~8 h 4 min. | Lectura de código. | Corregir el comentario. |

## Criterios del hito
| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `make -C core test HITO=M3`: casos M3 + bancos 2 MiB + round-trip | sí | `94/105 PASS · requeridos: 94/94 PASS · HITO=M3`; 233 comprobaciones, 0 fallos; `make asan HITO=M3` en verde. |
| `make fuzz FUZZ_SECONDS=600`: 0 crashes, exec/s y cobertura | parcial (60 s) | 0 crashes en 60 s; cifras de la evidencia plausibles. Pero H1 es un crash alcanzable desde `gb_state_load` que los fuzzers no encuentran (H3). |
| Unit test: CRC alterado, huella distinta, longitud > archivo | sí | `STATE_CORRUPT`, `STATE_ROM_MISMATCH`, `STATE_CORRUPT`; más magic, versión, truncado, F y DMA. |
| Unit test: `.sav` Rojo/Amarillo = 32 768 | sí | `unit_cart.c` (`mbc3_mbc5`). |

## Notas
- Reglas duras: sin ROMs/saves/estados en el historial (solo la captura PNG de M2); sin código copiado de GPL/AGPL; `check-globals` OK; sin I/O ni `malloc` fuera de `cart_load`.
- Acotación de bancos (`cart.c:157-192`) correcta; todos los valores de `CART` aceptados por un estado pasan por `cart_update_banks`.
- Resto de `gb_state_load`: la pasada IO_CHECK no escribe; longitudes comprobadas antes de leer; DMA validado. No se revisaron exhaustivamente todas las combinaciones de CPU/timer/serie (ninguna indexa arrays).
- CRC-32: tabla literal constante (no generada en compilación como decía el hito; la spec ya dice "tabla constante"); verificada contra `0xEDB88320` y el vector "123456789".
- Pérdida de partidas: `gb_sram_save/load` validan tamaños; atomicidad y backups son del frontend (M6). Cargar un estado sustituye la SRAM (el frontend debe guardarla después con backup). Un `.sav` con pie RTC de 44 bytes se rechaza con `GB_ERR_SRAM_SIZE`: el frontend no debe sobrescribir el archivo en ese caso.
- Sin verificar: si el MBC5 real compara solo el nibble bajo al habilitar la RAM.
