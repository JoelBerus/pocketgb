# M9 · Auditoría (subagente Opus de respaldo, sin historial del desarrollo)

Auditado: rama `m9-link-virtual` (`da23059` código, `32ef789` docs) frente a `main` (`47b7f12`), con `docs/auditorias/PROMPT.md`. Codex no está disponible en la nube. Este archivo resume fielmente el informe que el coordinador recibió del auditor.

## Veredicto: APROBAR CON CAMBIOS

Sin bloqueantes. Fuera de `core/src/link.c` no cambia nada en `core/src`, así que no hay regresión para una sola instancia.

## Criterios del hito
| Criterio | Resultado del auditor |
|---|---|
| Test headless: dos instancias intercambian bytes | PASS en las 11 combinaciones |
| (a) Deriva ≤ 44 tras 10⁶ vueltas | PASS. La mutación a deadline relativo da 4 fallos: el test la detecta |
| (b) Causalidad | Parcial. Sin el adelanto del par, 8 fallos (detectado), pero con los huecos H1 y H2 |
| (c) 0xFF sin esclavo | PASS |
| (d) Intercambio, framebuffer y realineado | PASS. Mutaciones detectadas: framebuffer vivo en vez del de respaldo, 1 fallo; sin realineado, 2 |

## Comprobaciones
- `make -C core test HITO=M9` y `make -C core asan HITO=M9`: 157/157 requeridos.
- `make -C core check-globals check-header`: OK.
- Fuzz de 60 s, 3 fuzzers, sin crashes.
- Sin `malloc` en el avance; `link.c` compila con `-Weverything` en Linux.

## Hallazgos
| ID | Severidad | Lugar | Problema | Corrección sugerida |
|---|---|---|---|---|
| H1 | media | `link.c:210/225` | El orden de cada bloque se decide solo con `SC` bit 0. Si los dos lados tienen el bit 0 a 1 y solo uno transfiere (esclavo con `SC=0x01` que luego pasa a `0x80`, maestro en el lado 1), corre primero el esclavo y en el pulso no se le adelanta: recibe un bit que por causalidad no le toca. Reproducido con esclavo en el lado 0 (`SC=0x01`), maestro en el lado 1 (`SC=0x81`, flanco a ~300 T) y el esclavo escribiendo `SC=0x80` hacia los 400 T: `s.bits=1` en vez de 0. | En `internal_clock`, priorizar la transferencia activa `(sc & 0x81) == 0x81` antes que el bit 0. Añadir el caso a `unit_link.c` y documentar que un maestro que activa `SC=0x81` a mitad de bloque puede encontrarse al par hasta 456 T por delante. |
| H2 | media | `unit_link.c:479` | El caso (b′) solo se prueba con el maestro en el lado 0; la mutación `first = 0u` (orden fijo) pasa los tests. | Añadir `causality_case(t, 1, 92, false)` y comprobar que esa mutación falla. |
| H3 | baja | `link.c`, `unit_link.c` | El flag `busy` no es lo que evita la recursión con dos maestros: `gb_serial_clock_external` ya devuelve 1. | Corregir los comentarios (defensa en profundidad); si se puede, un test que lo necesite de verdad; si no, decirlo. |
| H4 | baja | `gb_link_attach` | Acepta una instancia que ya está conectada a otro cable (`serial_bit_cb == link_bit_cb`). | Rechazarla (devolver false o dejar el lado vacío), con test. Documentar en M9-link-virtual.md que la app debe tener un único dueño que llame a detach antes de destroy. |
| H5 | baja | `fuzz_link.c`, Makefile | El fuzzer apenas explora: la semilla mide 8 bytes y se queda en `lim: 8`. | Semillas largas (programas de intercambio de `unit_link.c` vía `gbtest --fuzz-seeds`) y/o `-len_control=0 -max_len` solo para fuzz_link. Volver a fuzzear 300 s y anotar la cobertura. |
| H6 | baja | `port_ensure` | El framebuffer de respaldo al realinear un lado (tras `gb_load_rom`/`gb_state_load`). | No hace falta tocarlo; dejarlo anotado como aceptado. |

## Pendiente 🍎
- Intercambio Rojo ↔ Amarillo en el iPhone (Kadabra evoluciona).
- Las dos SRAM se guardan por la ruta de M6 tras el intercambio.
