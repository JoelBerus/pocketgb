# M3 · Auditoría, vuelta 2 (fallback: subagente Opus sin historial, en la nube)

> Auditor: subagente Opus nuevo con `docs/auditorias/PROMPT.md`, encargado de verificar H1–H5 de [M3-opus](M3-opus.md) intentando romper cada corrección. Commit auditado: `fa22c7f`. Informe resumido fielmente.

## Veredicto: APROBAR CON CAMBIOS

Las correcciones de H1 a H5 son reales y suficientes. Se intentó romperlas con estados de CRC recalculado en todas las secciones (CPU, MEM, TIMR, PPU, DMA, SER, JOY, CART/RTC, MISC) bajo ASan+UBSan con `-fno-sanitize-recover=all`, sin encontrar accesos fuera de rango, bucles infinitos ni UB. No hay hallazgos bloqueantes nuevos. El único cambio pedido es la descripción del fuzzer en `docs/06-testing.md` (N1).

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| N1 | baja | `docs/06-testing.md:58` frente a `core/fuzz/fuzz_state_load.c:9-13,85-94` | 06 describe la mutación estructurada como "parejas (offset, XOR)", pero el código usa tuplas de 4 bytes (sección, desplazamiento, desplazamiento, valor) que **asignan** el byte y, en secciones de más de 256 bytes, solo tocan sus primeros y últimos 32. | Lectura de ambos archivos. | Alinear el texto con el código. |
| N2 | baja (nota de calidad) | `core/fuzz/fuzz_state_load.c:25-35` | El fuzzer de estados usa un único ROM MBC3+RTC+RAM: no se fuzzean de forma estructurada las variantes de `CART` para MBC1, MBC5 o ROM-only. | Lectura de código. El auditor lo compensó con un harness propio, sin fallos. | Opcional: alternar el tipo de cartucho según un byte de la entrada. |

### Verificación de H1–H5
| ID | ¿Corregido? | Cómo se verificó |
|---|---|---|
| H1 | Sí | `ppu_resync` tras `IO_APPLY`; validación de `dot` y `mode3_end ∈ [252, 319]` (máximo real de `oam_scan`); defensa `ly ≥ 144` en `render_line`; revisados los demás índices del render. **Prueba exhaustiva** con ASan+UBSan: LY ∈ {0,1,142,143,144,145,152,153} × todos los `dot` múltiplos de 4 × `mode3_end` 252..319 × con y sin 10 sprites = 124 032 estados, todos cargan y ejecutan 3 líneas sin informes (`n=124032 bad=0`). El test de regresión fallaría con el código anterior. |
| H2 | Sí | `rtc_unix_valid` `[0, 2^40)`; estado fuera de rango → `STATE_CORRUPT`; `.sav` → "sin hora"; `gb_rtc_set_time` valida ambos operandos y resta en `uint64_t`; `rtc_tick` no sale del rango; `rtc_add_seconds` acotado. |
| H3 | Sí, con el límite declarado | 120 s por fuzzer: `fuzz_state_load` cov 706 / ft 1546 a 16 exec/s y `fuzz_load_rom` cov 960 / ft 3422, sin crashes. |
| H4 | Sí | `rtc.c:103-104` + test. |
| H5 | Sí | `rtc.c:52-53`. |
| Extra `.sav` de 44 bytes | Correcto | Copia `len - ram` bytes a un bloque de 48 inicializado a cero; sin lecturas fuera de rango. |

## Criterios del hito
| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `make -C core test HITO=M3` + bancos 2 MiB + round-trip | sí | `94/105 PASS · requeridos: 94/94 PASS`; `--unit` 245 comprobaciones sin fallos; `asan` 94/94; `check-globals` OK. |
| `make fuzz FUZZ_SECONDS=600`: 0 crashes, exec/s y cobertura | parcial (120 s por fuzzer) | 0 crashes, `exit=0`; cifras de la evidencia de 600 s plausibles. |
| Unit test: CRC, huella, longitud > archivo | sí | Códigos correctos; además magic, versión, truncado, F, DMA, PPU incoherente y hora inválida. |
| Unit test: `.sav` Rojo/Amarillo = 32 768 | sí | `unit_cart.c`. |

## Notas
- Reglas duras: sin ROMs/saves/estados en el historial; semillas del corpus generadas e ignoradas por git; sin código GPL/AGPL; `check-globals` OK; los `static` nuevos son `const` o están en el fuzzer.
- Harness de ataque propio (fuera del repo): 40 000 estados aleatorios, dentro de los rangos aceptados pero incoherentes entre sí, sobre los 15 tipos de cartucho soportados, con `sp`/`pc` arbitrarios, HALT/STOP/locked, DMA con origen arbitrario, RTC en los límites y `cycles` cerca de `UINT64_MAX`; save → load → 6 frames → `gb_rtc_set_time` → round-trip de SRAM. Sin informes de sanitizer. Limitación: ese harness tampoco detecta H1 revertido, porque casi nunca produce `next_event == 456`; H1 lo cubren la prueba exhaustiva y el test de regresión.
- Terminación: `gb_run_frame`/`gb_run_cycles` miden en `uint64_t` y `cpu_step` consume al menos un M-ciclo en cualquier estado, así que no hay bucles infinitos alcanzables desde un estado.
- Para M6: cargar un estado sustituye la SRAM y restaura `sram_dirty`/`ram_written` del archivo. El frontend debe guardar la SRAM, con backup, tras cargar un estado.
- Menor: si un `.sav` trae una hora válida posterior a la actual, el reloj no avanza hasta que la hora real la alcance (coherente con la spec).
