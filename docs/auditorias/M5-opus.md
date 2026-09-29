# M5 (núcleo) · Auditoría (fallback: subagente Opus sin historial, en la nube)

> Auditor: subagente Opus nuevo con `docs/auditorias/PROMPT.md`, solo sobre la parte de núcleo (☁️); la integración iOS (🍎) quedó fuera. Commit auditado: `818af80`. Informe resumido fielmente.

## Veredicto: APROBAR CON CAMBIOS

Hay un hallazgo medio (H1): un estado hostil con el CRC recalculado provoca UB aritmético (desbordamiento de entero con signo); se corrige con una línea de validación. El resto son hallazgos bajos.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `core/src/state.c:316-319` (validación), efecto en `core/src/apu.c:402-433` | `gb_state_load` acepta un canal con `enabled = 1` y `timer = 0`. `apu_sync` excluye ese canal del cálculo de `step`, le resta el tramo entero y `channel_step` solo suma un periodo por iteración: el temporizador baja sin cota (~67 000 por frame con freq 2047) hasta desbordar un `int32` (UB) a los ~32 000 frames; mientras tanto suena a una frecuencia errónea y el estado guardado deja de ser recargable. En ejecución normal no ocurre (`trigger` siempre carga un periodo > 0). | PoC `poc_timer.c`: estado parcheado (canal 1 activo, freq 0x7FF, timer 0) con CRC recalculado → `GB_OK`; con 40 000 frames, UBSan: `src/apu.c:427:26: runtime error: signed integer overflow: -2147480544 - 8192 cannot be represented in type 'int32_t'`. | Rechazar `enabled && timer == 0` al cargar; defensa en profundidad con un `while` en `apu_sync`; test en `unit_state.c`. |
| H2 | baja | `docs/03-core-spec.md` §APU, `M5-evidencia.md` §6 | El +7 % se midió con `cpu_instrs` (sin sonido). El catch-up itera por evento: el peor caso de un ROM (4 canales a la frecuencia máxima) duplica el coste. | Bench del auditor, 3600 frames a 48 kHz: silencio 39,9×/32,8×; peor caso 20,8×/18,8× tiempo real. | Documentar el peor caso y medirlo en el iPhone (parte 🍎). |
| H3 | baja | `core/src/apu.c:62-65` | Pan Docs (NR43): con shift 14 o 15 el canal 4 no recibe relojes y el LFSR queda congelado; el código lo sigue relojando con periodo `112 << 15`. | Lectura del código. | No avanzar el LFSR si `(nr43 >> 4) >= 14`. |
| H4 | baja | `core/tests/unit_apu.c:34` | La comprobación de la wave RAM es tautológica. Sin unit tests de ruido, onda, envolvente, round-trip de la sección APU ni del invariante de H1 (en parte los cubren Blargg 01–08/11). | Lectura del test. | Usar un valor conocido y añadir casos de estado hostil y de ruido. |

## Criterios del hito
| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `make -C core test HITO=M5` → `dmg_sound` 01–06 PASS | sí | `103/117 PASS · requeridos: 103/103 PASS`; pasan 01–08 y 11; 09/10/12 `known-fail`. |
| `make -C core asan HITO=M5` | sí | OK. |
| `check-globals` | sí | OK; `apu.c` solo tiene tablas `static const`. |
| `--wav`: 48 kHz sin clipping sostenido, pico reportado | sí | `dmg_sound.gb`: 1 718 608 frames, pico 15349 (−6,6 dBFS), 0 saturadas; `file`: PCM 16 bits estéreo 48 kHz. |
| Fuzzing de 300 s | no | No se reejecutó; con H1 hacen falta ~32 000 frames, así que el fuzzer no lo habría visto. |
| 10 min sin underruns / A9 | no | 🍎, fuera de alcance. |

## Notas
- Reglas duras OK: sin ROMs/saves/estados en el historial, `apu.c` propio (tablas = datos de Pan Docs), sin `malloc` en `gb_run_frame` (el anillo va dentro de `struct gb_apu`).
- Catch-up: sin accesos al APU sin sincronizar; `apu_sync` siempre termina (`step ≥ 1`); `phase`, `acc_*` y `rate*step` no desbordan en [8 000, 192 000] Hz. `gb_audio_available` puede ir algo por detrás entre frames (benigno).
- Resto de la sección APU del estado: índices y desplazamientos acotados; solo H1. `power = 0` con canales activos da bits imposibles en NR52, pero no es inseguro.
- Pan Docs correcto: frame sequencer, reloj extra de longitud y recarga max−1, sweep (sombra, periodo 0→8, segunda comprobación, quitar el negativo), DAC, apagado por NR52, LFSR 15/7 bits, volumen de la onda. Sin emular (permitido): "zombie mode" de la envolvente, retardo de disparo y lecturas de la wave RAM con el canal 3 activo.
- Determinismo: la emulación lo es; `phase` y `cap_*` no se reiniciaban al cargar un estado, así que el PCM justo después de cargar dependía del historial (no afecta al framebuffer ni a los registros).
