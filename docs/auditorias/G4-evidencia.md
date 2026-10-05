# G4 · Evidencia (cartucho, saves y RTC)

Rama `g4-gba-saves`, 2026-10-05, Linux (clang 18.1.3).

## Qué se implementó
- `gba/src/cart.c`: detección del medio por las cadenas de la biblioteca de Nintendo, SRAM 32 KiB, Flash 64/128 KiB (comandos, ID, borrado de chip y sector, banco), EEPROM 512 B / 8 KiB por bits con autodetección de tamaño, RTC S-3511A por GPIO; API `gba_save_*` y `gba_rtc_*`.
- Bus: SRAM/Flash con bus de 8 bits (lecturas replicadas, escrituras rotadas: la CPU pasa ahora la dirección sin alinear y el bus alinea), EEPROM en `0x0D…`, GPIO en `0x080000C4–C9`; la DMA avisa a la EEPROM de su longitud.
- `gba_options.rtc` (auto/sí/no) y la hora local en `unix_time`.
- ROMs homebrew propias que se autoverifican: `eeprom.c` (512 B y 8 KiB) y `rtc.c`; modo `hb`/`hb-rtc` del runner.
- Fuzzer `gba/fuzz/fuzz_load_rom.c` y `make -C gba fuzz`.

## Salida
```
pocketgba.h compila aislado: OK
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
PASS  G1 unit: PASS unit: 0 fallos
PASS  G1 gba-tests/arm/arm.gba: PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
PASS  G1 gba-tests/thumb/thumb.gba: PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)
PASS  G2 gba-tests/memory/memory.gba: PASS tests/roms/gba-tests/memory/memory.gba (2 frames)
PASS  G2 gba-tests/bios/bios.gba: PASS tests/roms/gba-tests/bios/bios.gba (3 frames)
PASS  G2 gba-tests/nes/nes.gba: PASS tests/roms/gba-tests/nes/nes.gba (2 frames)
PASS  G3 gba-tests/ppu/hello.gba: PASS tests/roms/gba-tests/ppu/hello.gba (idéntico a la referencia)
PASS  G3 gba-tests/ppu/shades.gba: PASS tests/roms/gba-tests/ppu/shades.gba (idéntico a la referencia)
PASS  G3 gba-tests/ppu/stripes.gba: PASS tests/roms/gba-tests/ppu/stripes.gba (idéntico a la referencia)
PASS  G4 gba-tests/save/sram.gba: PASS tests/roms/gba-tests/save/sram.gba (2 frames)
PASS  G4 gba-tests/save/flash64.gba: PASS tests/roms/gba-tests/save/flash64.gba (75 frames)
PASS  G4 gba-tests/save/flash128.gba: PASS tests/roms/gba-tests/save/flash128.gba (75 frames)
PASS  G4 gba-tests/save/none.gba: PASS tests/roms/gba-tests/save/none.gba (2 frames)
PASS  G4 eeprom.gba: PASS build/hb/eeprom.gba (1 frames)
PASS  G4 eeprom8k.gba: PASS build/hb/eeprom8k.gba (1 frames)
PASS  G4 rtc.gba: PASS build/hb/rtc.gba (131 frames)

75/75 sin fallos requeridos (6.7 s)
75/75 sin fallos requeridos (13.8 s)
OK: todos los casos requeridos en PASS
```

Las suites de SingleStepTests y las 16 escenas de PPU siguen en PASS (omitidas; incluidas en el 75/75). La segunda línea "75/75" es `make asan`; la última, el núcleo GB (`make -C core test HITO=M9`).

## Fuzzing
```
$ make -C gba fuzz FUZZ_SECONDS=600
#14	INITED cov: 726 ft: 1156 corp: 13/32Kb exec/s: 0 rss: 46Mb
Done 5336 runs in 601 second(s)
Done 5336 runs in 601 second(s)
salida: 0
```
Sin crashes, fugas ni avisos de ASan/UBSan (cada ejecución: carga del ROM, partida y RTC del tamaño esperado, 3 frames con botones de la entrada). La semilla del corpus son las ROMs de jsmolka.

### Fuzzer del cartucho (tras la auditoría, M3)
```
$ make -C gba fuzz-one FUZZER=fuzz_cart FUZZ_SECONDS=600
Done 370715 runs in 601 second(s)
salida: 0
```
Sin crashes ni avisos de ASan/UBSan (bus de cartucho, Flash, EEPROM y RTC con secuencias arbitrarias).
