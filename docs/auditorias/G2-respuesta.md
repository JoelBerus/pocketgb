# Respuesta a la auditoría Opus de G2

| ID | Corrección | Test |
|---|---|---|
| H1 | Una DMA inmediata ya no se ejecuta de forma recursiva: activar el canal lo marca pendiente y `gba_dma_service` ejecuta cada canal pendiente como mucho una vez por llamada (desde la escritura, si no hay otra DMA en curso, o desde `gba_tick`). Una DMA que se reactiva a sí misma queda acotada a una ejecución por paso. | `test_dma_self_retrigger` (el caso del auditor). |
| H2 | `hle_waiting` marca que la SWI espera su IRQ; al reejecutarse ya no descarta, sea `IntrWait` o `VBlankIntrWait`. Se limpia al volver y al encender. | `test_vblank_intr_wait`: una vuelta por frame en 5 frames. |
| H3 | `r3 = q < 0 ? 0u - (uint32_t)q : (uint32_t)q`. | `Div(0x80000000, 1)`. |
| H4 | `MidiKey2Freq` en entero: tablas Q16 de 2^(-s/12) y 2^(-f/3072) (calculadas), desplazamiento por octava y saturación a 32 bits. Sin libm. | Tecla 180 → base; 168 → mitad; entradas máximas → `0xFFFFFFFF`. |
| H5 | `dma_cur` guarda el canal activo; el bus abierto devuelve su latch. | — |
| H6 | Campo `open_bus` eliminado. | — |
| H7 | Huffman válido de 8 bits ("ABBA"), LZ77 a VRAM con distancia 1 ("QQQQQQ") y el malformado comprueba que no escribe. | `test_hle_decompress`. |
| H8 | Evidencia rehecha solo con la salida de G2. | — |

```
$ make -C gba test HITO=G2
51/51 sin fallos requeridos
$ make -C gba asan HITO=G2
51/51 sin fallos requeridos
```
