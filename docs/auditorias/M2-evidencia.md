# M2 · Evidencia de los criterios de aceptación

Generada en Claude Code en la nube (Linux, Ubuntu clang version 18.1.3 (1ubuntu1), 4 vCPU) el 2026-09-29, rama `claude/estado-m1-8ow61b` reiniciada desde `main` tras el merge de M1 (ver el commit que contiene este archivo).

## 1. `make -C core test HITO=M2` (incluye todos los casos de M1: sin regresiones)
```
python3 tests/run_suite.py --bin build/gbtest --roms tests/roms --suite tests/suite.txt --hito M2
Hito  Res.  Tipo        Caso                                                               Tiempo  Detalle
M1    PASS  requerido   unit tests                                                           0.0s  PASS: 149 comprobaciones, 0 fallos
M1    PASS  requerido   mooneye-test-suite/acceptance/boot_regs-dmgABC.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/cpu_instrs.gb                                      1.2s  PASS: serie: Passed (frame 3194)
M1    PASS  requerido   blargg/instr_timing/instr_timing.gb                                  0.0s  PASS: serie: Passed (frame 38)
M1    PASS  requerido   blargg/mem_timing/mem_timing.gb                                      0.0s  PASS: serie: Passed (frame 89)
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
M1    PASS  requerido   blargg/cpu_instrs/individual/04-op r,imm.gb                          0.0s  PASS: serie: Passed (frame 163)
M1    PASS  requerido   blargg/cpu_instrs/individual/05-op rp.gb                             0.1s  PASS: serie: Passed (frame 224)
M1    PASS  requerido   blargg/cpu_instrs/individual/06-ld r,r.gb                            0.0s  PASS: serie: Passed (frame 34)
M1    PASS  requerido   blargg/cpu_instrs/individual/07-jr,jp,call,ret,rst.gb                0.0s  PASS: serie: Passed (frame 42)
M1    PASS  requerido   blargg/cpu_instrs/individual/08-misc instrs.gb                       0.0s  PASS: serie: Passed (frame 32)
M1    PASS  requerido   blargg/cpu_instrs/individual/09-op r,r.gb                            0.2s  PASS: serie: Passed (frame 545)
M1    PASS  requerido   blargg/cpu_instrs/individual/10-bit ops.gb                           0.4s  PASS: serie: Passed (frame 828)
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

70/80 PASS · requeridos: 70/70 PASS · HITO=M2
OK: todos los casos requeridos en PASS
exit=0
```

## 2. `make -C core asan HITO=M2`
```
make BUILD=build/asan EXTRA="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all" CFLAGS="-O1 -g" test
clang -std=c11 -Wall -Wextra -Werror -pedantic -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all -Iinclude -Isrc … -o build/asan/gbtest
70/80 PASS · requeridos: 70/70 PASS · HITO=M2
OK: todos los casos requeridos en PASS
exit=0
```

## 3. dmg-acid2 píxel a píxel y captura
```
PASS: acid: framebuffer idéntico a la referencia (frame 19)
sha256(framebuffer volcado) = 95afb92675151023d85092a70d513af19b8ce0577fc05aba4b0051e3ccbfddda
sha256(referencia RGBA)     = 95afb92675151023d85092a70d513af19b8ce0577fc05aba4b0051e3ccbfddda
```
Captura (`build/dmg-acid2.png`, generada con `--dump` + `tools/png2rgba.py --reverse`):

![dmg-acid2](M2-dmg-acid2.png)

## 4. `make -C core check-globals`
```
Sin estado global mutable: OK
exit=0
```

## 5. Rendimiento (no es criterio de M2; control frente a M1)
```
bench: 3600 frames, 60.265 s emulados en 1.518 s reales -> 39.7x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.266 s reales -> 47.6x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.715 s reales -> 35.1x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.075 s reales -> 56.1x tiempo real
```
Con render activo: 35–48× en `cpu_instrs` y 56× en dmg-acid2 (M1, sin render: 47–58×). Sigue por encima de los 20× de M1.

## Notas
- Los 7 `known-fail` de `ppu/*` (tiempo fino de STAT/LCD) quedan fuera de una PPU por scanline (docs/03 §Principios).
- `di_timing-GS` y `halt_ime1_timing2-GS` pasan a ser `requerido`: siguen en PASS con la PPU nueva.
