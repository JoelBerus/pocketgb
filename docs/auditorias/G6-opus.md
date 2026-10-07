# G6 · Auditoría Opus (respaldo): save states y determinismo + auditoría del núcleo `gba/`

Auditor: subagente Opus sin historial del desarrollo. Rama `g6-gba-states`, commit `74412ef`. Fecha 2026-10-05.

## Veredicto: APROBAR CON CAMBIOS

La arquitectura de `state.c` está bien: validación sobre una copia, CRC, huella del ROM, longitud fija y comprobación de coherencia. La suite, ASan y los `check-*` se reproducen tal como dice la evidencia. Aun así, dos campos del estado se aceptan con rangos que el núcleo no tolera, y eso produce comportamiento indefinido a partir de un estado no confiable (regla dura 3). He confirmado el primero en ejecución. Ninguno de los dos es una escritura fuera de búfer, así que no considero que bloqueen el hito. Deben corregirse antes de cerrar G6, porque G7 expondrá los estados a la app.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G6-1 | alta | `gba/src/state.c:151` → `gba/src/apu.c:194` | `ch->pos` se acepta hasta 63 en los cuatro canales. En los canales de pulso (0 y 1), `duty_table[duty] >> (7 - pos)` hace un desplazamiento negativo, que es UB. | Arnés propio (`audio_psg.gba`, 45 frames; canal 0 con `enabled=1`, `timer=100`; se pone `pos=63` en el estado y se recalcula el CRC): `gba_state_load` devuelve `GBA_OK` y después UBSan informa `apu.c:194:38: runtime error: shift exponent -56 is negative`. | Rango por canal: `pos ≤ 7` en los canales 0 y 1, `≤ 63` en el 2 y sin uso en el 3. Comprobarlo en `consistent()` y añadir el caso a `unit.c`. |
| G6-2 | alta | `gba/src/state.c:183-184` → `gba/src/cart.c:276`, `cart.c:282-284` | `rtc.offset` y `rtc_base` se cargan como `int64` sin acotar. En tiempo de ejecución están limitados (`gba_rtc_set_time` a ±2·`GBA_RTC_MAX_OFFSET`, `rtc_commit` y `gba_rtc_load` a ±`GBA_RTC_MAX_OFFSET`). Con valores extremos, `rtc_now()` desborda un entero con signo (UB) y `civil()` convierte el año a `int` fuera de rango. | Inspección del código: no hay `I64` con límites ni comprobación en `consistent()`. No lo reproduje en ejecución porque hace falta un ROM con RTC que lea la hora. El fuzzer no puede llegar a este camino (ver G6-4). | Validar `|offset| ≤ GBA_RTC_MAX_OFFSET` y `|rtc_base| ≤ 2·GBA_RTC_MAX_OFFSET` al cargar. |
| G6-3 | media | `gba/src/state.c:168-171` | El estado sobrescribe `save_type`, `save_bytes`, `has_rtc` y los 128 KiB de `save` (la partida). Hay dos consecuencias. (a) Si la instancia se creó con otro `opts.save_type` u `opts.rtc` para el mismo ROM, cargar el estado cambia el tamaño del `.sav` que verá la app. (b) Cargar un estado antiguo devuelve la partida de batería a su versión anterior y puede marcarla como sucia, así que la app podría sobrescribir un `.sav` más reciente con uno más viejo (regla dura 6, riesgo de perder la partida). | Lectura del código. Es el mismo diseño que mGBA, pero no está documentado en la API ni en `10-gba-spec.md` §Save states. | Rechazar el estado si `save_type` o `has_rtc` no coinciden con los de la instancia (o documentarlo). En G7, exigir backup del `.sav` antes de `gba_state_load` y decidir de forma explícita si un estado restaura la SRAM. Anotarlo en `pocketgba.h`. |
| G6-4 | media | `gba/fuzz/fuzz_state_load.c:24-30` | El ROM del fuzzer es un `b .` con `FLASH1M_V` y opciones `NULL`. No tiene RTC y no enciende la APU, así que las mutaciones casi nunca dejan un canal habilitado con `timer>0` y nunca llegan a `rtc_now()`. Por eso los 600 s no detectaron G6-1 ni G6-2. | 7 882 ejecuciones en 600 s según la evidencia. Hay cobertura estructural, pero no semántica. | Usar una semilla que fije `has_rtc` (`opts.rtc = GBA_RTC_ON`) y `sample_rate`, y mutar también estados tomados de `audio_psg` y `flash128`, o añadir un corpus con esos estados. |
| G6-5 | baja | `gba/src/state.c:174`, `cart.c:209-218` | `eeprom.nbits` se acepta hasta 64 sin tener en cuenta `phase` ni `addr_bits` (por ejemplo, fase 1 con `nbits=40` y `abits=6`). No es inseguro: el contador `uint8_t` da la vuelta. Pero la EEPROM se queda en un estado inalcanzable durante unos 200 bits. | Lectura del código. | En `consistent()`, exigir `nbits < abits` en la fase 1 y `nbits < 64` en la fase 2. |
| G6-6 | baja | `gba/src/state.c:258` | `gba_state_load` hace `malloc` de una copia de aproximadamente 1,3 MB. No viola la regla 4 porque no ocurre dentro de `gba_run_frame`, pero conviene documentar que la función puede fallar con `GBA_ERR_OUT_OF_MEMORY` y que no debe llamarse desde el hilo de audio. | `grep malloc`: solo aparece en `gba.c:78` (ROM) y en `state.c:258`. | Documentarlo en el header. |

## Auditoría completa del núcleo (resumen)
- **Reglas duras 1 y 2:** `git log --all --name-only` no contiene ningún `.gba`, `.gb`, `.gbc`, `.sav`, `.bin` ni archivo de estado. Las PNG de `gba/tests/ref/` son capturas de homebrew propio. No encontré código copiado de proyectos GPL; las cabeceras citan GBATEK y SkyEmu (MIT).
- **Regla 4:** `make -C gba check-globals check-symbols check-header` en OK. No hay `malloc` en el camino de `gba_run_frame`. Los únicos `static` mutables son tablas `static const`. El determinismo lo cubre el modo `det` (dos instancias), que reproduje.
- **Regla 3 (ROM no confiable):** se mantienen los controles de G4 (`rom_mask`, lectura más allá del archivo, `save_bytes` frente al tipo). En los estados, la EEPROM (`read_addr < save_bytes/8`), el banco de Flash, `bank` frente a CPSR, `dma_active` y `rtc_base_cycles ≤ cycles` están bien validados. Las dos excepciones son G6-1 y G6-2.
- **Determinismo de la carga:** `apu.charge` no se serializa, pero depende solo de `opts.sample_rate`, que es igual para la misma instancia. `mix_l` y `mix_r` se recalculan mediante `mix_dirty`. `ws_*` se recalculan con `gba_bus_update_waitstates`. Es correcto.

## Criterios del hito
| Criterio | Verificado por el auditor | Resultado |
|---|---|---|
| Estado completo, validación sobre copia, errores tipificados | sí (lectura de `state.c`) | Cumple, salvo G6-1, G6-2 y G6-5. |
| Modo `state` (7 ROMs) y modo `det` (3) | sí: `make -C gba test HITO=G6` | `91/91 sin fallos requeridos (15.8 s)` |
| ASan + UBSan en toda la suite | sí: `make -C gba asan HITO=G6` | `91/91 sin fallos requeridos (41.8 s)` |
| `check-header`, `check-globals` y `check-symbols` | sí | `Símbolos de los dos núcleos sin colisiones: OK` |
| Fuzzers de 600 s | no (no repetí los 600 s) | La evidencia es plausible, pero la cobertura es insuficiente (G6-4). |

## Notas
- G6-1 se reprodujo con un arnés temporal fuera del repo (en el scratchpad), enlazado con `gba/src/*.c` y `-fsanitize=address,undefined`.
- Para G6-2 no tengo reproducción dinámica. Lo deduzco de la asimetría entre los límites que se aplican en tiempo de ejecución y los que no se aplican al cargar.
- Recomiendo cerrar G6 cuando G6-1 a G6-4 estén corregidos (G6-3 al menos documentado) y haya un test unitario por cada uno.
