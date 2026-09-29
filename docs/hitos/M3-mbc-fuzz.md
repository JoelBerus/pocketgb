# M3 · MBC1/3/5 + SRAM + RTC + save states + fuzzing

☁️ Nube (preferible: libFuzzer disponible) o Mac con `brew install llvm`. Spec: [03](../03-core-spec.md) §Cabecera, §MBC, §Save states, §Seguridad.

**Archivos:** `core/src/cart.c` (completo), `core/src/rtc.c`, `core/src/state.c`, `core/fuzz/fuzz_load_rom.c`, `core/fuzz/fuzz_state_load.c`, `core/tests/unit_cart.c`, `core/tests/unit_state.c`.

**Tareas**
1. Validación completa de la cabecera, `gb_rom_info`, cálculo de la huella.
2. MBC1 (sin multicart), MBC3 con RTC (latch, halt, carry, persistencia de 48 bytes VBA/BGB), MBC5 (con bit de rumble enmascarado en `0x1C–0x1E`).
3. `gb_sram*`, con la señal "dirty + flanco de disable".
4. Save states según el formato de la spec, con CRC32 propio (tabla generada en tiempo de compilación, sin dependencias).
5. Fuzzers.

**Criterios de aceptación**
- [ ] `make -C core test HITO=M3` → casos M3 de [06](../06-testing.md) en PASS, más los unit tests de bancos (ROM sintético de 2 MiB con el número de banco en cada banco) y el round-trip de estado.
- [ ] `make -C core fuzz FUZZ_SECONDS=600` → 0 crashes en ambos fuzzers. Se reporta `exec/s` y cobertura.
- [ ] Unit test: estado con CRC alterado, huella distinta, longitud de sección > archivo → rechazados con el código de error correcto.
- [ ] Unit test: `.sav` de Pokémon Rojo = 32 768 bytes (sin RTC) y de Amarillo = 32 768 bytes. Los tamaños salen de la cabecera sintética `0x13`/`0x1B` con RAM `0x03`.
