# M1 · Respuesta a la auditoría (docs/auditorias/M1-opus.md)

Veredicto recibido: **APROBAR CON CAMBIOS**, 7 hallazgos de severidad baja y ninguno bloqueante. Todos se resolvieron en el commit de cierre de M1 (el que contiene este archivo).

| ID | Decisión | Qué se hizo |
|---|---|---|
| M1-01 | corregido | `gb_tick` no avanza el timer mientras la CPU está en STOP (`core/src/gb.c`): DIV queda a 0 hasta que se pulsa un botón. Tiene un unit test en `unit_cpu.c`. La PPU sigue avanzando: es la simplificación de "HALT profundo" que marca la spec. |
| M1-02 | corregido | `sram_dirty` solo se activa si hay batería, y `cart_reset` limpia `ram_written`/`sram_dirty` (`core/src/cart.c`). Tiene un unit test para el tipo `0x02`. |
| M1-03 | corregido | El comentario de `GB_ERR_CGB_ONLY` en `pocketgb.h` dice ahora "hasta M8: con cualquier modelo". |
| M1-04 | diferido (M3) | `gb_state_*` y `gb_rtc_set_time` son tareas de M3. No se añaden stubs para que el fallo sea explícito al enlazar. Queda anotado en `docs/ESTADO.md` (§Pendiente en el núcleo). |
| M1-05 | corregido | `docs/03-core-spec.md` apunta ahora a `gb_cpu_locked()`. |
| M1-06 | diferido (M3) | En `docs/06-testing.md` el test de fallo de memoria inyectado queda marcado como de M3. |
| M1-07 | corregido | `docs/ESTADO.md` y `docs/hitos/README.md` están actualizados. |

Nota "sin verificar" (`EI` justo antes de `HALT` con IRQ pendiente): queda como tarea para M2/M3 con el oráculo SameBoy (ver ESTADO).

Verificación tras las correcciones:
```
make -C core test HITO=M1   → 65/68 PASS · requeridos: 63/63 PASS · OK
make -C core asan HITO=M1   → 65/68 PASS · requeridos: 63/63 PASS · OK
make -C core check-globals  → Sin estado global mutable: OK
gbtest --unit               → PASS: 136 comprobaciones, 0 fallos
gbtest cpu_instrs.gb --bench 3600 → 51.3x tiempo real
```
