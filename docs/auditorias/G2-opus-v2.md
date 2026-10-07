# Auditoría G2, segunda vuelta · Opus de respaldo
Commit auditado: `dd87966`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS
H1–H8 corregidos. Construcciones hostiles de DMA (ping-pong entre canales, anillo de 4, DMA de HBlank/VBlank con repetición sobre IE/IF/IME y HALTCNT, autorreactivación con contador 0x10000) terminan con ASan + UBSan limpios, `dma_pending = 0` y ~270 000 ciclos por frame. Cero avisos de UBSan en 590 000 llamadas a MidiKey2Freq.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| N1 | baja | hle.c `case 0x1F` | Con tecla > 180 el truncado Q16 se amplifica al desplazar a la izquierda (b=7, k=229, f=255: 64 en vez de 125,7). | Comparación contra la fórmula de GBATEK en double. | Más precisión antes de desplazar o redondeo. |
| N2 | baja | hle.c `hle_intr_wait` | `hle_waiting` puede quedar activo si el manejador no vuelve a la SWI (SoftReset desde la IRQ). No cuelga. | Lectura. | Limpiarlo en SoftReset. |
| N3 | baja | unit.c `test_dma_self_retrigger` | Termina en `CHECK(true)`. | Lectura. | Comprobar pendientes, canal desactivado y ciclos acotados. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| memory/bios/nes.gba | sí | 51/51 |
| ASan + UBSan | sí | 51/51 y pruebas hostiles limpias |
| Sin globals ni colisiones | sí | OK |
| Reglas 1–4 | sí | Sin binarios; índices nuevos acotados; tablas `const`; determinista |
