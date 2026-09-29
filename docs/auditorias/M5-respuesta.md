# M5 (núcleo) · Respuesta a la auditoría ([M5-opus](M5-opus.md))

Veredicto: **APROBAR CON CAMBIOS**. Todo se corrigió en el commit de cierre del núcleo de M5 (el que contiene este archivo).

| ID | Decisión | Qué se hizo |
|---|---|---|
| H1 | corregido | `gb_state_load` rechaza un canal activo con `timer == 0` (`STATE_CORRUPT`). Defensa en profundidad: en `apu_sync`, `channel_step` se repite (`while`) hasta que el temporizador vuelve a ser > 0. Test en `unit_apu.c` (estado con el temporizador del canal 3 a 0 → rechazado). |
| H2 | corregido (documentado) | `docs/03-core-spec.md` §APU: el +7 % es con un ROM sin sonido; el peor caso (4 canales a frecuencia máxima) baja a ~19–21× en Linux y hay que medirlo en el iPhone. Anotado en `ESTADO.md` para la parte 🍎. |
| H3 | corregido | Con shift 14–15 en NR43 el LFSR no avanza. Test en `unit_apu.c`. |
| H4 | corregido | Comprobación tautológica eliminada; tests nuevos de ruido (avanza, y se congela con shift 14), onda (canal 3 activo leyendo la wave RAM) y estado hostil (H1). |
| Nota: determinismo del PCM tras cargar | corregido | `gb_state_load` reinicia `phase` y el condensador del pasa-altos: el audio tras cargar ya no depende del historial. |

Verificación tras las correcciones:
```
make -C core test HITO=M5   → 103/117 PASS · requeridos: 103/103 PASS · OK
make -C core asan HITO=M5   → requeridos: 103/103 PASS · OK
make -C core check-globals  → Sin estado global mutable: OK
gbtest --unit               → PASS: 1080 comprobaciones, 0 fallos
make fuzz FUZZ_SECONDS=120  → 0 crashes (fuzz_load_rom cov 1194, fuzz_state_load cov 867)
```
