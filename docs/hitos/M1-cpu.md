# M1 · CPU SM83 + MMU + timer + interrupciones (headless)

☁️ Nube o Mac. Spec: [03-core-spec](../03-core-spec.md) §CPU, §Timer, §Mapa de memoria, §Arranque, §Serial.

**Archivos:** `core/src/{gb.c, cpu.c, cpu_ops.inc, mmu.c, timer.c, serial.c, joypad.c, cart.c (solo ROM-only + MBC1 básico para Blargg), internal.h}`, `core/tests/runner.c`, `core/tests/run_suite.py` (lee `suite.txt`, filtra por `HITO`, imprime la tabla PASS/FAIL), `core/tests/suite.txt`, `core/tests/unit_*.c`, `tools/fetch-test-roms.sh` ejecutado.

**Tareas**
1. `struct gb` en `internal.h` con subestructuras `cpu`, `mem`, `timer`, `ppu` (solo `LY` y modos, para que los tests que esperan VBlank no se cuelguen), `serial`, `cart`.
2. Decodificador por `switch` (o tabla generada) con los 512 opcodes. Cada acceso a memoria con `cpu_read/cpu_write`, que llaman a `gb_tick(gb, 4)`.
3. Interrupciones, `EI` diferido, `HALT` y bug de HALT, opcodes ilegales → locked.
4. Timer con flanco de bajada y recarga retrasada de `TIMA`.
5. Serial con callback, el runner en modos `serial` y `mooneye`.
6. `gb_run_frame`: ejecutar hasta completar 70 224 T-ciclos (o hasta VBlank de la PPU mínima).

**Criterios de aceptación** (pegar la salida en el commit de cierre)
- [ ] `make -C core test HITO=M1` → todos los casos M1 de [06](../06-testing.md) en PASS.
- [ ] `make -C core asan HITO=M1` → PASS, sin reportes de ASan/UBSan.
- [ ] `make -C core check-globals` → vacío. Hace `nm build/libpocketgb.a | grep -E ' [bBdD] '`: no hay datos mutables globales ni `static`; las tablas `const` van en secciones de solo lectura.
- [ ] Rendimiento: `build/gbtest ... --bench 3600` ≥ 20× tiempo real en la máquina de desarrollo (colchón para el iPhone).
