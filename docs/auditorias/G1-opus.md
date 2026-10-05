# Auditoría G1 (CPU ARM7TDMI) · Opus de respaldo
Commit auditado: `0eeaf06`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G1-1 | media | gba/src/bus.c:26, gba/tests/runner.c `sst_compare` | `missing_read` se marca pero nunca se consulta; tampoco se cuentan las lecturas. | Con la comprobación añadida en la copia, 48/48 siguen en PASS: hoy no hay PASS falso, pero falta la red. | Hacer de `missing_read` un fallo y comparar el número de lecturas. |
| G1-2 | baja | G-README, ESTADO | Dicen 47 archivos; son 45 `.json.bin`. | `ls v1/*.json.bin \| wc -l` → 45. | Corregir. |
| G1-3 | baja | gba/src/arm_mulcarry.c `correction[iters]` | `iters` sin tope explícito (la invariante lo deja en 1..4). | Lectura; UBSan limpio. | Acotar o documentar. |

## Criterios del hito
| Criterio | Verificado por el auditor | Resultado |
|---|---|---|
| SingleStepTests 100 % | sí | 48/48; cada archivo 50000/50000 (45 archivos). |
| arm/thumb/memory.gba | sí | PASS, r12 == 0. |
| ASan + UBSan | sí | 48/48 (5 000 casos por archivo). |
| bench | sí | 13,9×. |
| Regla dura 2 | parcial | `arm_mulcarry.c` cumple zlib (aviso, marca de modificado, origen). Sin texto GPL en `gba/src`. No se hizo análisis de similitud con NBA/mGBA. |
| Regla dura 3 | sí (lectura) | ROM acotado a `rom_size`, resto por máscaras; tamaño validado antes de leer la cabecera. |
| Regla dura 4 | sí | Sin globals, sin malloc en `gba_run_frame`, sin fuentes de azar. |
