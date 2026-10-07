# Auditoría G4 (cartucho, medio de guardado y RTC) · Opus de respaldo
Commit auditado: `907f21a`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A1 | alta | cart.c `gba_save_load` | Con `GBA_SAVE_AUTO` acepta 512 u 8192 aunque la DMA ya confirmara el tamaño: recargar un .sav de otro tamaño a mitad de sesión cambia el tamaño y el siguiente guardado trunca la partida (regla 6). | Lectura: la condición no mira `addr_bits`. | Cambiar el tamaño solo sin confirmar; test. |
| M1 | media | cart.c | Sin .sav ni ajuste, si un juego accede a la EEPROM por CPU bit a bit (no por DMA) se usan 6 bits. Raro: la biblioteca de Nintendo usa DMA. | Lectura. | Documentar o no aceptar escrituras sin tamaño. |
| M2 | media | cart.c `gba_rtc_load`, `rtc_now` | Un desplazamiento extremo en el .rtc desborda un entero con signo (UB). | UBSan con 0x7fffffffffffffff. | Acotar al cargar y saturar al escribir. |
| M3 | media | tests | Sin pruebas de la transición 512 → 8 KiB, bloque ≥ 64, escritura de fecha, 12 h, comando invertido. | Lectura. | Añadirlas. |
| B1 | baja | cart.c | `dirty` con toda escritura aunque no cambie nada; programar la Flash asigna en vez de `&=`. | Lectura. | Comparar antes; `&=`. |
| B2 | baja | cart.c | `erase_armed` sobrevive a secuencias rotas; F0 suelto no reinicia. | Lectura. | Desarmar y aceptar F0 suelto. |
| B3 | baja | cart.c | Fijar la hora en 12 h ignora PM. | Lectura. | Sumar 12 h. |
| B4 | baja | fuzz_load_rom.c | El fuzzer apenas llega al cartucho (9 exec/s). | 60 s: cov 726. | Fuzzer específico del cartucho. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| sram/flash64/flash128/none | sí | 75/75 |
| EEPROM 512/8K y RTC (homebrew) | sí | PASS (cobertura limitada, M3) |
| Tests unitarios | sí | 0 fallos; falta A1 |
| ASan + UBSan | sí | 75/75; arnés hostil limpio salvo M2 |
| Fuzzer 600 s | parcial | 60 s sin crashes |
| Reglas 3, 4 y 6 | sí | Índices acotados; sin globals; RTC por ciclos; `gba_reset` no borra la partida; el cambio de la CPU (dirección sin alinear) no rompe otras regiones ni SingleStepTests. |
