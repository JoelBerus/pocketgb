# Respuesta a la auditoría Opus de G4

| ID | Corrección | Prueba |
|---|---|---|
| A1 | El tamaño de la EEPROM solo lo decide un `.sav` mientras no esté confirmado (`addr_bits == 0`). Confirmado por un `.sav` o por la DMA, una recarga de otro tamaño devuelve `GBA_ERR_SAVE_SIZE` y no cambia nada. | Unit: DMA de 81 → 8192; después DMA de 9 y `.sav` de 512 → rechazado, sigue 8192. `.sav` de 8192 y después uno de 512 → rechazado. |
| M1 | Documentado en la spec y en la cabecera: sin `.sav` ni ajuste, el tamaño se confirma con la primera DMA (la biblioteca de Nintendo siempre la usa); la app debe pedir `gba_save_size()` en cada guardado. | — |
| M2 | `.rtc` con desplazamiento fuera de ±200 años → `GBA_ERR_SAVE_SIZE`; la escritura de fecha del juego no lo saca de ese rango; `gba_rtc_set_time` acota la hora del anfitrión. | Unit: desplazamiento `0x7FFF…` rechazado sin cambiar el estado. |
| M3 | `eeprom8k`: bloque 1000 escrito y leído, y el bloque 1000 & 63 sigue vacío. `rtc`: fijar 2031-02-03 04:05:06 y leerla, modo 12 h (16 h → 4 + PM) y comando con los bits invertidos. | Suite G4. |
| B1 | `dirty` solo si cambia un byte (SRAM, EEPROM, programar la Flash); programar la Flash solo baja bits (`&=`), como el chip. | Unit. |
| B2 | Una secuencia rota desarma el borrado; `F0` suelto sale del modo ID. | Unit: borrado armado, escritura ajena, `AA 55 10` → no borra. |
| B3 | En 12 h, el bit PM suma 12 h al fijar la hora. | `rtc.gba`. |
| B4 | `fuzz_cart` (SRAM, Flash, EEPROM, GPIO y DMA3 directos, cada medio con RTC): ~1200 ejecuciones/s; campaña de 600 s en la evidencia. | — |
