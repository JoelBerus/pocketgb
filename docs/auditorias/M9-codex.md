## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | alta | [core/src/link.c:60](/Users/joelbermudez/Documents/workspace/pocketgb-m9/core/src/link.c:60) | Un save state aceptado puede provocar overflow firmado/UB al conectarlo al cable. | `gb_state_load` acepta `g->cycles` hasta `UINT64_MAX` en [state.c:424](/Users/joelbermudez/Documents/workspace/pocketgb-m9/core/src/state.c:424). El cable lo convierte a `int64_t` y calcula desplazamientos en las líneas 60, 133 y 193. Con `cycles = 0x8000000000000000`, la conversión produce típicamente `INT64_MIN`; negarlo o calcular `T - INT64_MIN` desborda un `int64_t`. El fuzzer de estados puede recomputar CRC, pero `fuzz_link` solo carga estados recién guardados y no cubre esta combinación. | Validar `cycles <= INT64_MAX` durante `gb_state_load`, o reescribir el reloj del cable con aritmética `uint64_t` y diferencias comprobadas. Añadir un test/fuzzer combinado que cargue contadores extremos y después invoque `gb_link_attach`/`gb_link_run_cycles` bajo UBSan. |
| H2 | media | [core/src/link.c:132](/Users/joelbermudez/Documents/workspace/pocketgb-m9/core/src/link.c:132) | El realineado tras `gb_state_load` no garantiza el lockstep anunciado. | Solo se realinea si `local_time < T` o `local_time > T+912`. Un estado válido situado, por ejemplo, en `T+900` queda sin realinear; tras el siguiente bloque el otro lado queda aproximadamente en `T+456`, por lo que la diferencia puede rondar 444 ciclos y rompe la garantía pública `≤44`. El unit test únicamente carga un estado de tres frames en el pasado; [fuzz_link.c:107](/Users/joelbermudez/Documents/workspace/pocketgb-m9/core/fuzz/fuzz_link.c:107) desactiva expresamente la invariante después de cargar estados. | Detectar explícitamente cambios de época por `gb_state_load`, o reducir la tolerancia al exceso máximo realmente alcanzable por una instrucción. Añadir estados válidos en `T+45`, `T+500` y `T+912`, comprobando la invariante inmediatamente después del primer bloque. Ajustar el contrato si deliberadamente se acepta un bloque desalineado. |
| H3 | baja | [core/fuzz/fuzz_link.c:63](/Users/joelbermudez/Documents/workspace/pocketgb-m9/core/fuzz/fuzz_link.c:63) | La campaña documentada apenas exploró el “código arbitrario” anunciado por el fuzzer. | La propia evidencia informa que, tras 300 s, libFuzzer solo alcanzó entradas de 8 bytes: cuatro bytes de control y unos cuatro bytes repartidos entre ambos programas. Esto cubre opciones del harness, pero muy poca interacción de instrucciones con el cable. Además, la receta general del repo usa 600 s. | Incorporar semillas de mayor longitud que ejecuten transferencias y cambios de maestro, usar `-len_control=0` cuando corresponda y ejecutar al menos los 600 s previstos. Mantener la limitación explícita hasta obtener esa campaña. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| Test headless: dos instancias con ROM de prueba serie intercambian bytes correctamente; deriva, causalidad y cable suelto | no | El código contiene 11 combinaciones y la evidencia `32/32` es internamente plausible: las 106 comprobaciones nuevas explican el aumento de 1167 a 1273. No pude reejecutarlo porque el sandbox deniega crear directorios temporales y no había binario existente. H1 y H2 dejan casos relevantes sin cubrir. |
| iPhone: intercambio Rojo ↔ Amarillo; Kadabra evoluciona | no | Marcado correctamente como pendiente. No existe evidencia de prueba en dispositivo. |
| Las dos SRAM se guardan por la ruta normal tras el intercambio | no | Marcado correctamente como pendiente. M9 no modifica la ruta de guardado ni aporta prueba integrada con las dos SRAM. |

## Notas

- Commit auditado: `32ef78907af914bb81672a1834b1ba24a0428c28`; implementación principal: `da230592f5735a0fc25c58f74541c941ee329c32`.
- `47b7f12` sí es el merge-base de M9. Que el `main` local actual sea `ca6bec5` se debe al merge posterior de D1; la evidencia corresponde plausiblemente al código auditado.
- Se revisó `git log --all --stat`, todas las rutas históricas y los blobs por firma de cabecera Game Boy. No aparecieron ROMs, boot ROMs, `.sav`, estados ni blobs con el logo de Nintendo.
- No encontré indicios de código copiado de GPL/AGPL en M9.
- M9 no cambia archivos iOS, no introduce red y no toca la ruta de SRAM.
- `link.c` no tiene estado global mutable, I/O ni reservas durante `gb_link_run_cycles`/`gb_link_run_frame`; la única reserva está en `gb_link_create`.
- La implementación básica de `SB`/`SC`, desplazamiento MSB-first, 8 bits, limpieza de `SC.7`, interrupción serie y frecuencias normal/rápida concuerda con la especificación del proyecto y Pan Docs.
- No pude reejecutar compilación, ASan/UBSan ni `check-globals`: el sandbox bloqueó tanto `/tmp` como la creación de caché de Clang. Esto no es un hallazgo del proyecto.

