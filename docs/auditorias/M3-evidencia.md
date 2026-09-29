# M3 · Evidencia de los criterios de aceptación

Generada en Claude Code en la nube (Linux, Ubuntu clang version 18.1.3 (1ubuntu1), 4 vCPU) el 2026-09-29, rama `claude/estado-m1-8ow61b` reiniciada desde `main` tras el merge de M2. Código: commit `6efe9d9` (este archivo va en el commit siguiente, que solo añade documentación).

## 1. `make -C core test HITO=M3` (incluye M1 y M2: sin regresiones)
```
python3 tests/run_suite.py --bin build/gbtest --roms tests/roms --suite tests/suite.txt --hito M3
Hito  Res.  Tipo        Caso                                                               Tiempo  Detalle
M1    PASS  requerido   unit tests                                                           0.1s  PASS: 233 comprobaciones, 0 fallos
M1    PASS  requerido   mooneye-test-suite/acceptance/boot_regs-dmgABC.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/cpu_instrs.gb                                      1.2s  PASS: serie: Passed (frame 3194)
M1    PASS  requerido   blargg/instr_timing/instr_timing.gb                                  0.0s  PASS: serie: Passed (frame 38)
M1    PASS  requerido   blargg/mem_timing/mem_timing.gb                                      0.1s  PASS: serie: Passed (frame 89)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/div_write.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 52)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/rapid_toggle.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00_div_trigger.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01_div_trigger.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10_div_trigger.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11_div_trigger.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_reload.gb                   0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_write_reloading.gb          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tma_write_reloading.gb           0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/ei_sequence.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/ei_timing.gb                           0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/rapid_di_ei.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime0_ei.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/reti_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/reti_intr_timing.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/individual/01-special.gb                           0.1s  PASS: serie: Passed (frame 139)
M1    PASS  requerido   blargg/cpu_instrs/individual/02-interrupts.gb                        0.0s  PASS: serie: Passed (frame 25)
M1    PASS  requerido   blargg/cpu_instrs/individual/03-op sp,hl.gb                          0.1s  PASS: serie: Passed (frame 140)
M1    PASS  requerido   blargg/cpu_instrs/individual/04-op r,imm.gb                          0.1s  PASS: serie: Passed (frame 163)
M1    PASS  requerido   blargg/cpu_instrs/individual/05-op rp.gb                             0.1s  PASS: serie: Passed (frame 224)
M1    PASS  requerido   blargg/cpu_instrs/individual/06-ld r,r.gb                            0.0s  PASS: serie: Passed (frame 34)
M1    PASS  requerido   blargg/cpu_instrs/individual/07-jr,jp,call,ret,rst.gb                0.0s  PASS: serie: Passed (frame 42)
M1    PASS  requerido   blargg/cpu_instrs/individual/08-misc instrs.gb                       0.0s  PASS: serie: Passed (frame 32)
M1    PASS  requerido   blargg/cpu_instrs/individual/09-op r,r.gb                            0.2s  PASS: serie: Passed (frame 545)
M1    PASS  requerido   blargg/cpu_instrs/individual/10-bit ops.gb                           0.3s  PASS: serie: Passed (frame 828)
M1    PASS  requerido   blargg/cpu_instrs/individual/11-op a,(hl).gb                         0.4s  PASS: serie: Passed (frame 1050)
M1    PASS  requerido   mooneye-test-suite/acceptance/instr/daa.gb                           0.0s  PASS: mooneye: registros Fibonacci (frame 23)
M1    PASS  requerido   mooneye-test-suite/acceptance/interrupts/ie_push.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/mem_oam.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/reg_f.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/unused_hwio-GS.gb                 0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/if_ie_registers.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/intr_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/div_timing.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime0_nointr_timing.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime1_timing.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/add_sp_e_timing.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_cc_timing.gb                      0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/jp_cc_timing.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/jp_timing.gb                           0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/ld_hl_sp_e_timing.gb                   0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_timing.gb                      0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/pop_timing.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/push_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/ret_cc_timing.gb                       0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/ret_timing.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/rst_timing.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_cc_timing2.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_timing2.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_restart.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_start.gb                       0.0s  PASS: mooneye: registros Fibonacci (frame 17)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/basic.gb                       0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/reg_read.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/di_timing-GS.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 16)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime1_timing2-GS.gb                0.0s  PASS: mooneye: registros Fibonacci (frame 19)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/boot_div-dmgABCmgb.gb                  0.0s  FAIL: mooneye: registros de fallo (frame 11)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/boot_hwio-dmgABCmgb.gb                 0.0s  FAIL: mooneye: registros de fallo (frame 11)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/serial/boot_sclk_align-dmgABCmgb.gb    0.0s  FAIL: mooneye: registros de fallo (frame 11)
M2    PASS  requerido   dmg-acid2/dmg-acid2.gb                                               0.0s  PASS: acid: framebuffer idéntico a la referencia (frame 19)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_0_timing.gb                 0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_mode0_timing.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_mode3_timing.gb             0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_oam_ok_timing.gb            0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/hblank_ly_scx_timing-GS.gb         0.0s  FAIL: mooneye: registros de fallo (frame 14)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/intr_1_2_timing-GS.gb              0.0s  FAIL: mooneye: registros de fallo (frame 14)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/intr_2_mode0_timing_sprites.gb     0.0s  FAIL: mooneye: registros de fallo (frame 26)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/lcdon_timing-GS.gb                 0.0s  FAIL: mooneye: registros de fallo (frame 20)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/lcdon_write_timing-GS.gb           0.0s  FAIL: mooneye: registros de fallo (frame 50)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/stat_lyc_onoff.gb                  0.0s  FAIL: mooneye: registros de fallo (frame 11)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/vblank_stat_intr-GS.gb             0.0s  FAIL: mooneye: registros de fallo (frame 19)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_bank1.gb                  0.1s  PASS: mooneye: registros Fibonacci (frame 179)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_bank2.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 176)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_mode.gb                   0.1s  PASS: mooneye: registros Fibonacci (frame 179)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_ramg.gb                   0.1s  PASS: mooneye: registros Fibonacci (frame 349)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/ram_256kb.gb                   0.0s  PASS: mooneye: registros Fibonacci (frame 56)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/ram_64kb.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 56)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_16Mb.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_1Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_2Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_4Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_512kb.gb                   0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_8Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_16Mb.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_1Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_2Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_32Mb.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_4Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_512kb.gb                   0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_64Mb.gb                    0.1s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_8Mb.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mbc3-tester/mbc3-tester.gb                                           0.0s  PASS: frames: framebuffer idéntico a la referencia (frame 40)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-basic-tests-dmg.png]                  0.4s  PASS: frames: framebuffer idéntico a la referencia (frame 1200)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-range-tests-dmg.png]                  0.5s  PASS: frames: framebuffer idéntico a la referencia (frame 900)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-sub-second-writes-dmg.png]            0.7s  PASS: frames: framebuffer idéntico a la referencia (frame 2100)
M3    FAIL  known-fail  mooneye-test-suite/emulator-only/mbc1/multicart_rom_8Mb.gb           0.0s  FAIL: mooneye: registros de fallo (frame 12)

94/105 PASS · requeridos: 94/94 PASS · HITO=M3
OK: todos los casos requeridos en PASS
exit=0
```

## 2. `make -C core asan HITO=M3`
```
make BUILD=build/asan EXTRA="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all" CFLAGS="-O1 -g" test
clang -std=c11 -Wall -Wextra -Werror -pedantic -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all -DGB_TEST_HOOKS -Iinclude -Isrc … -o build/asan/gbtest
94/105 PASS · requeridos: 94/94 PASS · HITO=M3
OK: todos los casos requeridos en PASS
exit=0
```

## 3. `make -C core fuzz FUZZ_SECONDS=600` (libFuzzer + ASan + UBSan, `-fno-sanitize-recover=all`)
```
build/gbtest --fuzz-seeds fuzz/corpus
INFO: Seed: 102549045
INFO:        8 files found in fuzz/corpus/fuzz_load_rom
#4731	DONE   cov: 912 ft: 2962 corp: 580/664Kb lim: 32768 exec/s: 7 rss: 361Mb
Done 4731 runs in 601 second(s)
INFO: Seed: 2800718169
INFO:       23 files found in fuzz/corpus/fuzz_state_load
#129348	DONE   cov: 442 ft: 849 corp: 40/1762Kb lim: 142531 exec/s: 215 rss: 417Mb
Done 129348 runs in 601 second(s)
exit=0
```
| Fuzzer | Ejecuciones | exec/s | Cobertura (cov / ft) | Crashes |
|---|---|---|---|---|
| `fuzz_load_rom` | 4 731 | 7 | 912 / 2 962 | 0 |
| `fuzz_state_load` | 129 348 | 215 | 442 / 849 | 0 |

`fuzz_load_rom` es lento a propósito: cada entrada emula 30 frames con ASan, más el round-trip de SRAM y de save state. En una corrida corta previa encontró un bug real: un estado guardado con `dma.index = 160` (DMA terminado) no se podía cargar. Está corregido, con validación cruzada de `active`/`index` y un test de regresión en `unit_state.c`.

## 4. Unit tests pedidos por el hito (dentro de `gbtest --unit`, caso `unit tests` de la tabla)
- Bancos con ROM sintético de 2 MiB (número de banco en cada banco): MBC1 (bits altos y modo 1), MBC3 (7 bits, 0→1, 0x20 accesible) y MBC5 (0 válido, bit 8), en `unit_cart.c`.
- Round-trip de estado: guardar → cargar en otra instancia → 20 frames → framebuffer y estado idénticos (`unit_state.c`).
- Rechazos con su código: CRC alterado → `STATE_CORRUPT`; huella distinta → `STATE_ROM_MISMATCH`; longitud de sección > archivo → `STATE_CORRUPT`; además magic, versión, truncado, campo fuera de rango.
- `.sav` de Rojo (`0x13`, RAM `0x03`) y de Amarillo (`0x1B`, RAM `0x03`) = 32 768 bytes.
- OOM inyectado (`GB_TEST_HOOKS`) en la 1.ª y 2.ª reserva → `GB_ERR_OUT_OF_MEMORY`, instancia sin ROM y usable (sin fugas con ASan).
```
PASS: 233 comprobaciones, 0 fallos
```

## 5. `make -C core check-globals`
```
Sin estado global mutable: OK
exit=0
```

## 6. Rendimiento (control)
```
bench: 3600 frames, 60.265 s emulados en 1.293 s reales -> 46.6x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.317 s reales -> 45.8x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.307 s reales -> 46.1x tiempo real
```
