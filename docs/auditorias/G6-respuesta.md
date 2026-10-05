# Respuesta a la auditoría Opus de G6

| ID | Corrección |
|---|---|
| G6-1 | `consistent()` exige `pos ≤ 7` en los canales de pulso 0 y 1 (`apu.c` desplaza `7 - pos`). Test unitario: estado con `pos = 9` y CRC correcto → `GBA_ERR_STATE_CORRUPT`. |
| G6-2 | `rtc.offset` se limita a ±`GBA_RTC_MAX_OFFSET` y `rtc_base` al doble, los mismos límites que mantienen `cart.c` y `gba_rtc_set_time`. La constante pasa a `internal.h`. Tests unitarios con `INT64_MAX`/`INT64_MIN` → rechazados. |
| G6-3 | La carga rechaza un estado cuyo tipo de guardado, tamaño o RTC no coincidan con la sesión (test unitario cambiando `has_rtc`). `pocketgba.h` documenta que cargar un estado restaura la partida de ese momento y que la app debe respaldar el `.sav` antes; queda como requisito de G7 (SaveStore). |
| G6-4 | `fuzz_state_load` arranca con RTC encendido y la APU activa (pulsos 1 y 2, onda) antes de guardar el estado que muta, así que alcanza los caminos de G6-1 y G6-2. Nueva campaña de 600 s abajo. |
| G6-5 | Se queda así: `nbits ≤ 64` ya acota el acceso y la máquina de la EEPROM se recupera sola con el siguiente comando; validar todas las combinaciones fase/bits añade complejidad sin ganar seguridad. |
| G6-6 | Comentario en `gba_state_load` sobre la copia de ~1,3 MB reservada fuera de `gba_run_frame`. |

```
$ make -C gba test HITO=G6   → PASS unit: 0 fallos; 91/91 sin fallos requeridos (16.7 s)
$ make -C gba asan HITO=G6   → PASS unit: 0 fallos; 91/91 sin fallos requeridos (42.1 s)
$ make -C gba check-header check-globals check-symbols
pocketgba.h compila aislado: OK
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
```

Campaña del fuzzer de estados tras G6-4 (la cobertura inicial sube de 249 a 571 aristas):
```
$ make -C gba fuzz-one FUZZER=fuzz_state_load FUZZ_SECONDS=600
#32	INITED cov: 571 ft: 822 corp: 31/495b exec/s: 10 rss: 109Mb
Done 6376 runs in 601 second(s)
salida: 0
```
