# Respuesta a las auditorías Opus de G0 y G1

| ID | Corrección |
|---|---|
| G0-1 | El hook bloquea un blob de 16 KiB si su nombre parece de BIOS, si empieza por el salto de reset `18 00 00 EA` o si su SHA-1 es el publicado de la BIOS de GBA. Probado: `x.rom` de 16 KiB que empieza por ese salto → `bloqueado x.rom (posible BIOS de GBA)`. |
| G0-2 | Mensaje apunta a `tools/fetch-gba-test-roms.sh`; `check-roms` en `.PHONY`. |
| G0-3 | `-fno-common` en `CSTD` y patrón `[bBdDcCsS]`. |
| G0-4 | Comentario corregido (`repo dir sha`). |
| G1-1 | `sst_compare` falla si hubo una lectura de datos sin transacción y si el número de lecturas difiere del de la prueba. La suite sigue 48/48. |
| G1-2 | Corregido a 45 archivos (2 250 000 casos) en G-README, ESTADO, tabla de hitos y evidencia. |
| G1-3 | `iters` acotado a 4 con la invariante documentada. |

```
$ make -C gba test HITO=G1
48/48 sin fallos requeridos (1.0 s)
$ make -C gba check-globals check-symbols
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
```
