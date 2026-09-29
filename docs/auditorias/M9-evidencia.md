# M9 · Evidencia (núcleo: cable link virtual; la prueba en el iPhone es 🍎)

Secciones 1–8: código de `da23059`. **Sección 9: correcciones de la auditoría ([M9-opus](M9-opus.md), [M9-respuesta](M9-respuesta.md)) en `f1cec9f`; sustituye a las cifras anteriores donde cambian.**

Generada en Claude Code en la nube (Linux, Ubuntu clang version 18.1.3 (1ubuntu1), 4 vCPU) el 2026-09-29, rama `m9-link-virtual` desde `main` (`47b7f12`, M8 cerrado).

## Qué se añadió
- `core/src/link.c` + API `gb_link_*` en `core/include/pocketgb.h` (documentada en [03-core-spec](../03-core-spec.md) §Serial → Cable link virtual): `gb_link_create/destroy/attach/detach`, `gb_link_run_cycles`, `gb_link_run_frame`, `gb_link_framebuffer`, `GB_LINK_BLOCK_CYCLES` (456).
- Ningún otro archivo de `core/src` cambia: el camino de una instancia sola es el mismo código.
- `core/tests/unit_link.c` (registrado en `unit.h` y `runner.c`), `core/fuzz/fuzz_link.c` (+ semilla en `gbtest --fuzz-seeds`), `gbtest --bench-link N`.

## 1. Tests del cable (`gbtest --unit`, sin sanitizers)
```
  unit cgb      ok
  link deriva (DMG, 1000000 vueltas): max |t_a - t_b| = 20, max exceso = 20
  link deriva (CGB doble velocidad, 100000 vueltas): max |t_a - t_b| = 12, max exceso = 12
  link DMG(81) <-> DMG(80): 32/32 bytes en 2 frames
  link DMG(80) <-> DMG(81): 32/32 bytes en 2 frames
  link CGB(81) <-> CGB(80): 32/32 bytes en 2 frames
  link CGB(83) <-> CGB(80): 32/32 bytes en 1 frames
  link CGB(80) <-> CGB(83): 32/32 bytes en 1 frames
  link CGB-2x(81) <-> DMG(80): 32/32 bytes en 1 frames
  link DMG(81) <-> CGB-2x(80): 32/32 bytes en 2 frames
  link CGB-2x(83) <-> CGB-2x(80): 32/32 bytes en 1 frames
  link CGB(80) <-> CGB-2x(83): 32/32 bytes en 1 frames
  link compat(81) <-> DMG(80): 32/32 bytes en 2 frames
  link CGB(81) <-> compat(80): 32/32 bytes en 2 frames
  unit link     ok
PASS: 1273 comprobaciones, 0 fallos
```
| Test | Qué comprueba | Resultado |
|---|---|---|
| (a) deriva | Lado 0: NOP (4 T), lado 1: cadena de CALL (24 T). 10⁶ bloques de 456: tras **cada** bloque `T ≤ t_i`, exceso < 24 (el error no se acumula) y `\|t_a - t_b\| ≤ 44` | máx. 20 (y 12 con CALL en doble velocidad, 10⁵ bloques) |
| (b) causalidad | Primer flanco del maestro a ~300 T; el esclavo escribe `SC=0x80` a ~200 T, dentro del mismo bloque [0, 456). Tras el bloque ya pasó 1 bit; al final `0xAA`/`0x55` cruzados, `SC.7=0` e IRQ serie en los dos. Igual con el maestro en el lado 1 | PASS |
| (b′) sin adelantos | Mismo caso con `SC=0x80` a ~400 T (después del flanco): el primer bit NO se transfiere; el maestro recibe `0xD5` y el esclavo se queda con 7 bits | PASS |
| (c) sin esclavo | Par conectado con `SC=0` → `0xFF`; cable suelto (lado 1 NULL) → `0xFF`; los dos con reloj interno → los dos `0xFF`, sin recursión; sin instancias o sin ROM no se cuelga | PASS |
| (d) criterio del hito | Dos ROMs sintéticos (en memoria con `ut_make_rom`) intercambian 16 bytes en cada sentido por sondeo de `SC`, 11 combinaciones: DMG con el maestro en cada lado, CGB, reloj rápido (SC bit 1), doble velocidad frente a normal, rápido + doble velocidad, compatibilidad | 32/32 en las 11 |
| framebuffer | Un ROM invierte BGP en cada VBlank; con el avance terminando a mitad de pantalla, `gb_link_framebuffer` es siempre un frame entero y nuevo (6/6), mientras `gb_framebuffer` está cortado (6/6). LCD apagado: la pantalla actual | PASS |
| robustez | `serial_byte_cb` del llamador sigue llamándose con su `user`; save state cargado en un lado y recarga del ROM → realineado (un bloque, sin ráfaga) e intercambio correcto después; `detach` devuelve los callbacks; conectar una instancia consigo misma = solo lado 0 | PASS |

Entre las ROMs de prueba libres (`core/tests/roms`) no hay ninguna de serie entre dos instancias (solo `boot_sclk_align`, de una instancia y known-fail desde M1).

## 2. `make -C core test HITO=M9` (M1–M8 sin regresiones)
```
M1    PASS  requerido   unit tests                                                               12.4s  PASS: 1273 comprobaciones, 0 fallos
...
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
```
Los 19 FAIL son los `known-fail` de M1–M8, sin cambios.

## 3. `make -C core asan HITO=M9` (ASan + UBSan)
```
M1    PASS  requerido   unit tests                                                               32.1s  PASS: 1273 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
líneas con 'runtime error' o 'AddressSanitizer': 0
```

## 4. `make -C core check-globals check-header`
```
Sin estado global mutable: OK
pocketgb.h compila aislado: OK
```
`nm` sí lista `link.o` (`T gb_link_*`, `t link_bit_cb`, sin símbolos `b/B/d/D`): el OK no es el falso positivo de un `nm` fallido. También compila con `gcc -std=c11 -Wall -Wextra -Werror -pedantic`.

## 5. `make -C core fuzz FUZZ_SECONDS=300`
```
#1588	DONE   cov: 1183 ft: 2788 corp: 299/2196b lim: 8 exec/s: 5 rss: 352Mb
Done 1588 runs in 301 second(s)
#889	DONE   cov: 1069 ft: 2256 corp: 183/1104Kb lim: 32768 exec/s: 2 rss: 355Mb
Done 889 runs in 301 second(s)
#3233	DONE   cov: 760 ft: 1000 corp: 27/1816Kb lim: 174904 exec/s: 10 rss: 367Mb
Done 3233 runs in 301 second(s)
rc=0
```

| Fuzzer | Ejecuciones | exec/s | Cobertura (cov / ft) | Crashes / aborts |
|---|---|---|---|---|
| fuzz_link (nuevo) | 1588 | 5 | 1183 / 2788 | 0 (incluida la invariante `\|t_a - t_b\| ≤ 44`) |
| fuzz_load_rom | 889 | 2 | 1069 / 2256 | 0 |
| fuzz_state_load | 3233 | 10 | 760 / 1000 | 0 |

Limitación: en 300 s el control de longitud de libFuzzer solo llegó a entradas de 8 bytes en `fuzz_link` (prólogo SB/SC + unos pocos opcodes por lado), así que el código arbitrario fue corto; los caminos del cable (reentrada, realineado, cable suelto, framebuffers) sí se alcanzan por los bits de opciones. Con más tiempo (600 s o `-len_control=0`) crecería.

**Mutación:** con el adelanto del par desactivado (`if (l->running && 0)` en `link_bit_cb`), `gbtest --unit` da `FAIL: 1273 comprobaciones, 8 fallos` (causalidad con el maestro en cada lado y un intercambio); con el código real, 0 fallos. Los tests detectan la pérdida de causalidad.

## 6. Rendimiento (`gbtest --bench 3000`, sin sanitizers)
Medición alternada en la misma máquina: `main` original (`47b7f12`, compilado en un worktree aparte) frente a M9, dmg-acid2, `--bench 3000 --model dmg`:

| Ronda | Antes (47b7f12) | Después (M9) |
|---|---|---|
| 1 | 26,8× | 26,1× |
| 2 | 29,1× | 30,2× |
| 3 | 28,0× | 29,7× |
| 4 | 30,6× | 28,7× |
| 5 | 27,3× | 27,5× |
| 6 | 25,9× | 27,8× |
| **Media** | **28,0×** | **28,3×** |

Sin diferencia fuera del ruido (±2×), como se espera: M9 no toca ningún archivo de `core/src` salvo el nuevo `link.c`, que una instancia sola no ejecuta. Rondas 1–3 antes del fuzzing y 4–6 después.

Dos instancias en el cable (`--bench-link 3000`, dmg-acid2 en los dos lados): 14,3×, 15,0×, 14,5×, 14,4× y 15,1× tiempo real para el par (cgb-acid2: 14,5× y 13,1×). Frente a dos instancias sueltas (~2 × 1,7 s = 3,4 s por 3000 frames), el coste del lockstep en bloques de 456 queda dentro del ruido (3,3–3,5 s). Hay que medirlo en el iPhone (🍎): el par necesita ≥ 2× tiempo real.

## 7. Criterios del hito
| Criterio | Resultado |
|---|---|
| Test headless: dos instancias con un ROM de prueba serie intercambian bytes | PASS con ROMs sintéticos (sección 1, (d)) |
| (a) deriva ≤ 44 tras 10⁶ vueltas · (b) causalidad · (c) 0xFF sin esclavo | PASS (sección 1) |
| M1–M8 sin regresiones | 157/157 requeridos, también con ASan |
| iPhone: intercambio Rojo ↔ Amarillo, Kadabra evoluciona | 🍎 pendiente (UI + Joel) |
| Las dos SRAM se guardan con la ruta normal tras el intercambio | 🍎 pendiente |

## 8. Decisiones y riesgos
- **Orden dentro del bloque** (corregido en H1, ver §9): corre primero el lado con una transferencia de reloj interno activa (`SC & 0x81 == 0x81`) y, si ninguno la tiene, el del `SC` bit 0 = 1. Si el maestro corriera segundo, el par ya estaría hasta 456 T-ciclos por delante en el pulso (no se puede rebobinar): no rompe el intercambio (el esclavo espera con `SC=0x80`), pero le quitaría margen. Pokémon deja `SC=0x01` en el maestro entre bytes, así que el orden es estable.
- **Relojes por instancia:** el cable guarda un desplazamiento por lado; tras `gb_load_rom`/`gb_state_load` el siguiente avance realinea (fuera de `[T, T+912]`) y reinstala los callbacks. Un adelanto dentro de un pulso se limita a 4 bloques; y fuera de `gb_link_run_cycles` (si la app llamara a `gb_run_frame` con el cable puesto) no hay adelanto, solo se entrega el bit.
- **Contrato con la app:** un único dueño que llama a `gb_link_detach` antes de `gb_destroy` (el cable guarda punteros a las instancias). Una instancia no puede estar en dos cables: `gb_link_attach` devuelve `false` y deja ese lado vacío (H4). Todo en el mismo hilo.
- **Framebuffer al realinear (H6, aceptado):** tras `gb_load_rom`/`gb_state_load` se copia el framebuffer vivo al de respaldo; el siguiente VBlank lo sustituye por un frame completo.
- **Framebuffers de respaldo:** 2 × 92 KiB dentro del contexto; una copia por frame y lado. La app debe pintar `gb_link_framebuffer`.
- **Audio:** el anillo del juego no activo se llena y descarta (contador `dropped`), inocuo.

## 9. Correcciones de la auditoría (`f1cec9f`)

### `gbtest --unit` y `make -C core test HITO=M9`
```
M1    PASS  requerido   unit tests                                                               12.9s  PASS: 1314 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
```
Tests nuevos: `causality_case` con el maestro en el lado 1 y `SC=0x80` después del flanco (H2); esclavo con `SC=0x01` (bit 0 a 1 en los dos lados) en el lado 0 y en el lado 1, antes y después del flanco (H1); `ownership` (H4). De 1273 a 1314 comprobaciones.

### `make -C core asan HITO=M9`
```
M1    PASS  requerido   unit tests                                                               32.6s  PASS: 1314 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
líneas con 'runtime error' o 'AddressSanitizer': 0
```

### `make -C core check-globals check-header`
```
Sin estado global mutable: OK
pocketgb.h compila aislado: OK
```

### Mutaciones de `link.c` (script en el scratchpad; `link.c` restaurado y `--unit` en PASS después)
| Mutación | `gbtest --unit` |
|---|---|
| Orden antiguo (solo `SC` bit 0) (H1) | FAIL: 3 fallos (`s->serial.bits`, `m->serial.sb == 0xD5`, `bits == 7`) |
| Orden fijo, `first = 0u` (H2; antes pasaba) | FAIL: 6 fallos |
| Sin adelanto del par en el pulso | FAIL: 11 fallos |
| Sin rechazo de una instancia de otro cable (H4) | FAIL: 5 fallos |
| Sin el flag `busy` (H3) | PASS: 0 fallos. Mutante equivalente: `busy` es defensa en profundidad (el par va por detrás y `gb_serial_clock_external` sobre un maestro devuelve 1) |

### `make -C core fuzz FUZZ_SECONDS=300` (H5: semillas largas y `-len_control=0 -max_len=16384` para `fuzz_link`)
El corpus de `fuzz_link` se borró antes para medir solo semillas + 300 s; los otros dos fuzzers parten del corpus de la pasada anterior.
```
INFO: seed corpus: files: 7 min: 8b max: 98b total: 536b rss: 32Mb
#8	INITED cov: 829 ft: 1173 corp: 7/536b exec/s: 8 rss: 41Mb
#1614	DONE   cov: 1267 ft: 4160 corp: 494/56Kb lim: 16384 exec/s: 5 rss: 355Mb
Done 1614 runs in 301 second(s)
#927	DONE   cov: 1142 ft: 2787 corp: 282/1606Kb lim: 32768 exec/s: 3 rss: 353Mb
Done 927 runs in 302 second(s)
#3360	DONE   cov: 838 ft: 1384 corp: 58/1686Kb lim: 174904 exec/s: 11 rss: 364Mb
Done 3360 runs in 301 second(s)
rc=0
```
| Fuzzer | Ejecuciones | Cobertura (cov / ft) | Antes (§5) | Crashes / aborts |
|---|---|---|---|---|
| fuzz_link | 1614 | 1267 / 4160, `lim: 16384`, corpus 494 entradas / 56 KB | 1183 / 2788, `lim: 8` | 0 |
| fuzz_load_rom | 927 | 1142 / 2787 | 1069 / 2256 | 0 |
| fuzz_state_load | 3360 | 838 / 1384 | 760 / 1000 | 0 |

El rendimiento de una instancia sola no cambia: las correcciones solo tocan `link.c` y los tests.
