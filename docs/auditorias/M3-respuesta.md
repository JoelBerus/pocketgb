# M3 · Respuesta a la auditoría (docs/auditorias/M3-opus.md)

Veredicto de la vuelta 1: **RECHAZAR** por H1. Todo se corrigió en `21e9271`. Evidencia nueva: [M3-evidencia](M3-evidencia.md).

| ID | Decisión | Qué se hizo |
|---|---|---|
| H1 (bloqueante) | corregido | `gb_state_load` ya no confía en `ppu.mode` ni en `ppu.next_event`: `ppu_resync` los recalcula desde LY/dot/LCDC (con el LCD apagado, LY=dot=0 y modo 0). Se exige `dot % 4 == 0` y `mode3_end ∈ [252, 319]`. `render_line` ignora LY ≥ 144 (defensa en profundidad). Test de regresión en `unit_state.c` con el PoC del auditor (LY=143, dot=452, modo 3), que corre también con ASan. |
| H2 | corregido | Hora Unix del RTC acotada a `[0, 2^40)`. Un estado con una hora fuera de rango → `STATE_CORRUPT`. Un `.sav` con una hora fuera de rango → "sin hora" (se carga la partida y el reloj no avanza). `gb_rtc_set_time` ignora horas inválidas y resta en `uint64_t` con ambos operandos acotados. `rtc_reset` y `rtc_tick` no salen del rango. Tests de regresión. |
| H3 | corregido (con límite) | `fuzz_state_load` añade (3) mutación estructurada por secciones sobre un estado válido (asigna bytes en los campos pequeños de cada sección y recalcula el CRC) y (4) `.sav` hostil en `gb_sram_load` seguido de `gb_rtc_set_time` y frames. Cobertura 442 → 688. Límite: el fuzzer no encuentra H1 en 300 s aunque se quite la corrección (hacen falta tres valores exactos a la vez); lo cubren el test de regresión y la defensa en el render. `fuzz_load_rom` sigue a ~7 exec/s porque emula 30 frames con ASan por entrada, como pide 06. |
| H4 | corregido | `rtc_write` marca `ram_written` si el cartucho tiene batería: un cambio de hora del juego activa `gb_sram_dirty()` en el siguiente flanco de deshabilitar. Tiene test. |
| H5 | corregido | Comentario de `rtc_add_seconds`: peor caso ≈ 8 h 4 min. |
| Nota: `.sav` con RTC de 44 bytes | corregido | `gb_sram_load` también acepta el bloque antiguo de 44 bytes (hora u32): rechazar una partida válida es peor. Tiene test. |
| Nota: tabla CRC literal | aceptado | La spec dice "tabla constante"; está verificada contra el polinomio y el vector `123456789`. |
| Nota: MBC5 y nibble bajo | aceptado | Pan Docs (MBC5, *RAM Enable*): "Actual MBCs actually enable RAM when writing any value whose bottom 4 bits equal $A". |

## Vuelta 2 ([M3-opus-v2](M3-opus-v2.md)): APROBAR CON CAMBIOS

| ID | Decisión | Qué se hizo |
|---|---|---|
| N1 | corregido | `docs/06-testing.md` describe la mutación tal como está en el código (tuplas de 4 bytes que asignan valores; en secciones grandes, solo los primeros o últimos 32 bytes). |
| N2 | corregido | `fuzz_state_load` alterna el cartucho según el primer byte: MBC3+RTC+RAM, MBC1+RAM, MBC5+RAM o ROM-only. Al hacerlo, el fuzzer encontró en unos segundos un desbordamiento **en el propio fuzzer**: copiaba el bloque RTC de 48 bytes en un `.sav` más pequeño. Corregido, no afecta al núcleo. La corrida oficial de 2×600 s se repitió con esta versión (ver la evidencia). |
| Nota M6 | anotado | En `docs/ESTADO.md` (pendiente de M6): tras cargar un estado, el frontend debe guardar la SRAM, con backup. |
