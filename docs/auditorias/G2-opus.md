# Auditoría G2 (bus, E/S, DMA, timers, IRQ, HLE) · Opus de respaldo
Commit auditado: `3054d1d`. Ejecutado en una copia propia.

## Veredicto: RECHAZAR

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | io.c `dma_write_cnt_h` → `dma_run` | Una DMA3 con destino fijo en `0x040000DE` y datos `{0x0000, 0x8040}` se reactiva a sí misma de forma recursiva sin fin. | ASan: `stack-overflow ... io.c in dma_run`; sin sanitizer, SIGSEGV. | Sin recursión: canal pendiente y servicio iterativo. Test. |
| H2 | bloqueante | hle.c `VBlankIntrWait` | Al reejecutarse tras la IRQ vuelve a descartar y nunca vuelve. | Test con `swi 0x05`: tras 5 frames, r5=0. | Distinguir la primera entrada de las reejecuciones. Test. |
| H3 | alta | hle.c `hle_div` | `Div(INT32_MIN, 1)`: `-q` desborda (UB). | UBSan `negation of -2147483648`. | Negar en sin signo. |
| H4 | alta | hle.c `MidiKey2Freq` | Conversión double→uint32 desborda (UB) y `pow()` no es determinista entre libm. | UBSan `3.46246e+11 is outside the range`. | Entero con tablas y saturación. |
| H5 | media | bus.c `open_bus` | Durante la DMA devuelve siempre el latch del canal 0. | Lectura. | Latch del canal activo. |
| H6 | baja | bus.c / internal.h | `open_bus` (campo) se escribe y no se lee. | grep. | Eliminar. |
| H7 | baja | tests/unit.c | El test de Huffman malformado no comprueba nada; no hay Huffman válido ni LZ77 a VRAM con distancia 1. | Lectura. | Añadirlos. |
| H8 | baja | G2-evidencia.md | Mezcla salida del núcleo GB y un error de `check-roms`. | Lectura. | Pegar solo la salida de G2. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| memory/bios/nes.gba | sí | PASS (51/51). |
| Tests unitarios | sí | Pasan; sin VBlankIntrWait (H2) y Huffman vacío (H7). |
| ASan + UBSan | sí / no | Suite limpia; con entradas hostiles: H1, H3, H4. |
| Sin globals ni malloc en run_frame | sí | Solo tablas `const`. |

## Notas
- Reglas 1 y 2: el manejador de IRQ de 6 instrucciones es la secuencia que documenta GBATEK y está determinada por la función; las tres palabras sueltas son valores publicados por GBATEK. Aceptable y de minimis; no equivale a distribuir la BIOS. Sin indicios de copia de mGBA ni NanoBoyAdvance.
- Regla 3: LZ77, RL, Huffman y Diff acotados a 256 KiB; Huffman con tope; índices de E/S y canales DMA bien acotados. El único problema de terminación era H1.
- GBATEK: máscaras y contadores de DMA, repetición, timers, despertar de HALT, bandera de VBlank y waitstates, correctos.
