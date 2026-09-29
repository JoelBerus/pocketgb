# M8 + D1 · Respuesta a la auditoría Codex ([M8-D1-codex.md](M8-D1-codex.md))

| ID | Estado | Qué se hizo |
|---|---|---|
| H1 (media) | corregido | `bus_blocked` clasifica por separado los buses de VRAM, cartucho (`0000–7FFF`, `A000–BFFF`) y WRAM/eco (`C000–FDFF`) cuando `g->cgb.on`. Así, en CGB un OAM DMA desde WRAM no bloquea la ejecución desde ROM y uno desde ROM no bloquea el acceso a WRAM. La rama DMG conserva el modelo anterior: VRAM frente al bus externo compartido. `docs/03-core-spec.md` documenta ambos modelos. |
| H2 (baja) | ya corregido | `docs/hitos/D-README.md` y `D1-evidencia.md` indican once capturas D1 y especifican que `launch` existe solo en oscuro. No requirió cambios adicionales en esta corrección. |

## Regresión de H1

`core/tests/unit_cgb.c` prueba las dos combinaciones señaladas por la auditoría:

- DMA WRAM→OAM mientras la CPU ejecuta desde ROM.
- DMA ROM→OAM mientras código en HRAM lee WRAM.

Cada combinación se ejecuta en CGB nativo, CGB en compatibilidad y DMG. Antes del cambio fallaban las cuatro comprobaciones CGB y pasaban las dos DMG; después pasan las seis. La suite unitaria queda en 1179 comprobaciones y 0 fallos.

## Verificación

```text
make -C core test HITO=M8
  157/176 PASS · requeridos: 157/157 PASS · HITO=M8
  OK: todos los casos requeridos en PASS

make -C core asan HITO=M8
  PASS: 1179 comprobaciones, 0 fallos
  157/176 PASS · requeridos: 157/157 PASS · HITO=M8
  OK: todos los casos requeridos en PASS
  Sin errores de AddressSanitizer ni UBSan

make -C core check-globals
  Sin estado global mutable: OK
```
