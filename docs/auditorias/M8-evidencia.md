# M8 · Evidencia (núcleo CGB; la prueba en el iPhone es 🍎)

Generada en Claude Code en la nube (Linux, Ubuntu clang version 18.1.3 (1ubuntu1), 4 vCPU) el 2026-09-29, rama `claude/amazing-babbage-lgip5y` desde `main` (`d6905d7`, merge de M5). Código auditado: `f48a4ff`; hallazgos corregidos en `9f1b11c`. Todas las salidas son de `9f1b11c` o posterior (solo cambian docs).

## 1. `make -C core test HITO=M8` (incluye M1–M5: sin regresiones)
```
python3 tests/run_suite.py --bin build/gbtest --roms tests/roms --suite tests/suite.txt --hito M8
Hito  Res.  Tipo        Caso                                                                    Tiempo  Detalle
M1    PASS  requerido   unit tests                                                                0.1s  PASS: 1167 comprobaciones, 0 fallos
M1    PASS  requerido   mooneye-test-suite/acceptance/boot_regs-dmgABC.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/cpu_instrs.gb                                           1.8s  PASS: serie: Passed (frame 3194)
M1    PASS  requerido   blargg/instr_timing/instr_timing.gb                                       0.0s  PASS: serie: Passed (frame 38)
M1    PASS  requerido   blargg/mem_timing/mem_timing.gb                                           0.0s  PASS: serie: Passed (frame 89)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/div_write.gb                          0.1s  PASS: mooneye: registros Fibonacci (frame 52)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/rapid_toggle.gb                       0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00_div_trigger.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01_div_trigger.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10_div_trigger.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11_div_trigger.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_reload.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_write_reloading.gb               0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/timer/tma_write_reloading.gb                0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/ei_sequence.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/ei_timing.gb                                0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/rapid_di_ei.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime0_ei.gb                             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/reti_timing.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/reti_intr_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/individual/01-special.gb                                0.1s  PASS: serie: Passed (frame 139)
M1    PASS  requerido   blargg/cpu_instrs/individual/02-interrupts.gb                             0.0s  PASS: serie: Passed (frame 25)
M1    PASS  requerido   blargg/cpu_instrs/individual/03-op sp,hl.gb                               0.1s  PASS: serie: Passed (frame 140)
M1    PASS  requerido   blargg/cpu_instrs/individual/04-op r,imm.gb                               0.1s  PASS: serie: Passed (frame 163)
M1    PASS  requerido   blargg/cpu_instrs/individual/05-op rp.gb                                  0.1s  PASS: serie: Passed (frame 224)
M1    PASS  requerido   blargg/cpu_instrs/individual/06-ld r,r.gb                                 0.0s  PASS: serie: Passed (frame 34)
M1    PASS  requerido   blargg/cpu_instrs/individual/07-jr,jp,call,ret,rst.gb                     0.0s  PASS: serie: Passed (frame 42)
M1    PASS  requerido   blargg/cpu_instrs/individual/08-misc instrs.gb                            0.0s  PASS: serie: Passed (frame 32)
M1    PASS  requerido   blargg/cpu_instrs/individual/09-op r,r.gb                                 0.3s  PASS: serie: Passed (frame 545)
M1    PASS  requerido   blargg/cpu_instrs/individual/10-bit ops.gb                                0.5s  PASS: serie: Passed (frame 828)
M1    PASS  requerido   blargg/cpu_instrs/individual/11-op a,(hl).gb                              0.6s  PASS: serie: Passed (frame 1050)
M1    PASS  requerido   mooneye-test-suite/acceptance/instr/daa.gb                                0.0s  PASS: mooneye: registros Fibonacci (frame 23)
M1    PASS  requerido   mooneye-test-suite/acceptance/interrupts/ie_push.gb                       0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/mem_oam.gb                             0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/reg_f.gb                               0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/bits/unused_hwio-GS.gb                      0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/if_ie_registers.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/intr_timing.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/div_timing.gb                               0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime0_nointr_timing.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime1_timing.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/add_sp_e_timing.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_cc_timing.gb                           0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_timing.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/jp_cc_timing.gb                             0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/jp_timing.gb                                0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/ld_hl_sp_e_timing.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_timing.gb                           0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/pop_timing.gb                               0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/push_timing.gb                              0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/ret_cc_timing.gb                            0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/ret_timing.gb                               0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/rst_timing.gb                               0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_cc_timing2.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/call_timing2.gb                             0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_restart.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma_start.gb                            0.0s  PASS: mooneye: registros Fibonacci (frame 17)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/basic.gb                            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/reg_read.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   mooneye-test-suite/acceptance/di_timing-GS.gb                             0.0s  PASS: mooneye: registros Fibonacci (frame 16)
M1    PASS  requerido   mooneye-test-suite/acceptance/halt_ime1_timing2-GS.gb                     0.0s  PASS: mooneye: registros Fibonacci (frame 19)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/boot_div-dmgABCmgb.gb                       0.0s  FAIL: mooneye: registros de fallo (frame 11)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/boot_hwio-dmgABCmgb.gb                      0.0s  FAIL: mooneye: registros de fallo (frame 11)
M1    FAIL  known-fail  mooneye-test-suite/acceptance/serial/boot_sclk_align-dmgABCmgb.gb         0.0s  FAIL: mooneye: registros de fallo (frame 11)
M2    PASS  requerido   dmg-acid2/dmg-acid2.gb                                                    0.0s  PASS: acid: framebuffer idéntico a la referencia (frame 19)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_0_timing.gb                      0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_mode0_timing.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_mode3_timing.gb                  0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    PASS  requerido   mooneye-test-suite/acceptance/ppu/intr_2_oam_ok_timing.gb                 0.0s  PASS: mooneye: registros Fibonacci (frame 13)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/hblank_ly_scx_timing-GS.gb              0.0s  FAIL: mooneye: registros de fallo (frame 14)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/intr_1_2_timing-GS.gb                   0.0s  FAIL: mooneye: registros de fallo (frame 14)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/intr_2_mode0_timing_sprites.gb          0.0s  FAIL: mooneye: registros de fallo (frame 26)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/lcdon_timing-GS.gb                      0.0s  FAIL: mooneye: registros de fallo (frame 20)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/lcdon_write_timing-GS.gb                0.0s  FAIL: mooneye: registros de fallo (frame 50)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/stat_lyc_onoff.gb                       0.0s  FAIL: mooneye: registros de fallo (frame 11)
M2    FAIL  known-fail  mooneye-test-suite/acceptance/ppu/vblank_stat_intr-GS.gb                  0.0s  FAIL: mooneye: registros de fallo (frame 19)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_bank1.gb                       0.1s  PASS: mooneye: registros Fibonacci (frame 179)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_bank2.gb                       0.1s  PASS: mooneye: registros Fibonacci (frame 176)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_mode.gb                        0.1s  PASS: mooneye: registros Fibonacci (frame 179)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_ramg.gb                        0.2s  PASS: mooneye: registros Fibonacci (frame 349)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/ram_256kb.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 56)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/ram_64kb.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 56)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_16Mb.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_1Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_2Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_4Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_512kb.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/rom_8Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_16Mb.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_1Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_2Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_32Mb.gb                         0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_4Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_512kb.gb                        0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_64Mb.gb                         0.1s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_8Mb.gb                          0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M3    PASS  requerido   mbc3-tester/mbc3-tester.gb                                                0.0s  PASS: frames: framebuffer idéntico a la referencia (frame 40)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-basic-tests-dmg.png]                       0.7s  PASS: frames: framebuffer idéntico a la referencia (frame 1200)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-range-tests-dmg.png]                       0.5s  PASS: frames: framebuffer idéntico a la referencia (frame 900)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-sub-second-writes-dmg.png]                 1.4s  PASS: frames: framebuffer idéntico a la referencia (frame 2100)
M3    FAIL  known-fail  mooneye-test-suite/emulator-only/mbc1/multicart_rom_8Mb.gb                0.0s  FAIL: mooneye: registros de fallo (frame 12)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/01-registers.gb                              0.0s  PASS: blargg: código 0 (frame 51)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/02-len ctr.gb                                0.3s  PASS: blargg: código 0 (frame 566)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/03-trigger.gb                                0.4s  PASS: blargg: código 0 (frame 1010)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/04-sweep.gb                                  0.0s  PASS: blargg: código 0 (frame 75)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/05-sweep details.gb                          0.0s  PASS: blargg: código 0 (frame 73)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/06-overflow on trigger.gb                    0.0s  PASS: blargg: código 0 (frame 56)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/07-len sweep period sync.gb                  0.0s  PASS: blargg: código 0 (frame 36)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/08-len ctr during power.gb                   0.0s  PASS: blargg: código 0 (frame 83)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/11-regs after power.gb                       0.0s  PASS: blargg: código 0 (frame 44)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/09-wave read while on.gb                     0.0s  FAIL: blargg: código 1 (frame 38)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/10-wave trigger while on.gb                  0.1s  FAIL: blargg: código 1 (frame 235)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/12-wave write while on.gb                    0.1s  FAIL: blargg: código 1 (frame 235)
M8    PASS  requerido   mooneye-test-suite/misc/boot_regs-cgb.gb (cgb)                            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   cgb-acid2/cgb-acid2.gbc (cgb) [cgb-acid2.png]                             0.0s  PASS: acid: framebuffer idéntico a la referencia (frame 24)
M8    PASS  requerido   dmg-acid2/dmg-acid2.gb (cgb) [dmg-acid2-cgb.png]                          0.0s  PASS: acid: framebuffer idéntico a la referencia (frame 19)
M8    PASS  requerido   mooneye-test-suite/misc/boot_div-cgbABCDE.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/misc/bits/unused_hwio-C.gb (cgb)                       0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   blargg/cpu_instrs/cpu_instrs.gb (cgb)                                     1.5s  PASS: serie: Passed (frame 1774)
M8    PASS  requerido   blargg/instr_timing/instr_timing.gb (cgb)                                 0.0s  PASS: serie: Passed (frame 38)
M8    PASS  requerido   blargg/mem_timing/mem_timing.gb (cgb)                                     0.0s  PASS: serie: Passed (frame 89)
M8    PASS  requerido   blargg/mem_timing-2/mem_timing.gb (cgb)                                   0.1s  PASS: blargg: código 0 (frame 166)
M8    PASS  requerido   blargg/interrupt_time/interrupt_time.gb (cgb) [interrupt_time-cgb.png]    0.2s  PASS: frames: framebuffer idéntico a la referencia (frame 200)
M8    PASS  requerido   blargg/halt_bug.gb (cgb)                                                  0.1s  PASS: frames: framebuffer idéntico a la referencia (frame 200)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/01-registers.gb (cgb)                        0.0s  PASS: blargg: código 0 (frame 51)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/02-len ctr.gb (cgb)                          0.2s  PASS: blargg: código 0 (frame 566)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/03-trigger.gb (cgb)                          0.5s  PASS: blargg: código 0 (frame 1010)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/04-sweep.gb (cgb)                            0.0s  PASS: blargg: código 0 (frame 75)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/05-sweep details.gb (cgb)                    0.0s  PASS: blargg: código 0 (frame 73)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/06-overflow on trigger.gb (cgb)              0.0s  PASS: blargg: código 0 (frame 56)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/07-len sweep period sync.gb (cgb)            0.0s  PASS: blargg: código 0 (frame 36)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/08-len ctr during power.gb (cgb)             0.1s  PASS: blargg: código 0 (frame 148)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/10-wave trigger while on.gb (cgb)            0.1s  PASS: blargg: código 0 (frame 235)
M8    PASS  requerido   blargg/cgb_sound/rom_singles/11-regs after power.gb (cgb)                 0.0s  PASS: blargg: código 0 (frame 55)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/div_write.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 52)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/rapid_toggle.gb (cgb)                 0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim00_div_trigger.gb (cgb)            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim01_div_trigger.gb (cgb)            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim10_div_trigger.gb (cgb)            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tim11_div_trigger.gb (cgb)            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_reload.gb (cgb)                  0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tima_write_reloading.gb (cgb)         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/timer/tma_write_reloading.gb (cgb)          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/ei_sequence.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/ei_timing.gb (cgb)                          0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/rapid_di_ei.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/halt_ime0_ei.gb (cgb)                       0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/reti_timing.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 15)
M8    PASS  requerido   mooneye-test-suite/acceptance/reti_intr_timing.gb (cgb)                   0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/if_ie_registers.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/intr_timing.gb (cgb)                        0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/div_timing.gb (cgb)                         0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/basic.gb (cgb)                      0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/reg_read.gb (cgb)                   0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   mooneye-test-suite/acceptance/oam_dma/sources-GS.gb (cgb)                 0.0s  PASS: mooneye: registros Fibonacci (frame 37)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_16Mb.gb (cgb)                   0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_1Mb.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_2Mb.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_32Mb.gb (cgb)                   0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_4Mb.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_512kb.gb (cgb)                  0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_64Mb.gb (cgb)                   0.1s  PASS: mooneye: registros Fibonacci (frame 14)
M8    PASS  requerido   mooneye-test-suite/emulator-only/mbc5/rom_8Mb.gb (cgb)                    0.0s  PASS: mooneye: registros Fibonacci (frame 14)
M8    FAIL  known-fail  mooneye-test-suite/misc/boot_hwio-C.gb (cgb)                              0.0s  FAIL: mooneye: registros de fallo (frame 11)
M8    FAIL  known-fail  mooneye-test-suite/misc/ppu/vblank_stat_intr-C.gb (cgb)                   0.0s  FAIL: mooneye: registros de fallo (frame 19)
M8    FAIL  known-fail  blargg/cgb_sound/rom_singles/09-wave read while on.gb (cgb)               0.0s  FAIL: blargg: código 1 (frame 38)
M8    FAIL  known-fail  blargg/cgb_sound/rom_singles/12-wave.gb (cgb)                             0.0s  FAIL: blargg: código 2 (frame 30)
M8    FAIL  known-fail  cgb-acid-hell/cgb-acid-hell.gbc (cgb) [cgb-acid-hell.png]                 0.0s  FAIL: acid: 2 píxeles distintos (primero en x=80 y=68) (frame 18)

157/176 PASS · requeridos: 157/157 PASS · HITO=M8
OK: todos los casos requeridos en PASS
```

## 2. `make -C core asan HITO=M8` (ASan + UBSan)
```
M1    PASS  requerido   unit tests                                                                0.4s  PASS: 1167 comprobaciones, 0 fallos
M8    PASS  requerido   mooneye-test-suite/misc/boot_regs-cgb.gb (cgb)                            0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M8    PASS  requerido   cgb-acid2/cgb-acid2.gbc (cgb) [cgb-acid2.png]                             0.1s  PASS: acid: framebuffer idéntico a la referencia (frame 24)
M8    PASS  requerido   dmg-acid2/dmg-acid2.gb (cgb) [dmg-acid2-cgb.png]                          0.0s  PASS: acid: framebuffer idéntico a la referencia (frame 19)
157/176 PASS · requeridos: 157/157 PASS · HITO=M8
OK: todos los casos requeridos en PASS
líneas con 'runtime error' o 'AddressSanitizer': 0
```

## 3. `make -C core check-globals check-header`
```
Sin estado global mutable: OK
pocketgb.h compila aislado: OK
```

## 4. `make -C core fuzz FUZZ_SECONDS=600` (fuzzers con modelo DMG / CGB / compatibilidad)
```
#1884	DONE   cov: 1309 ft: 3772 corp: 490/5088Kb lim: 32768 exec/s: 3 rss: 366Mb
Done 1884 runs in 602 second(s)
#6795	DONE   cov: 819 ft: 1136 corp: 42/1062Kb lim: 174904 exec/s: 11 rss: 372Mb
Done 6795 runs in 601 second(s)
exit=0
```

| Fuzzer | Ejecuciones | exec/s | Cobertura (cov / ft) | Crashes |
|---|---|---|---|---|
| fuzz_load_rom | 1884 | 3 | 1309 / 3772 (M5: 1189 / 4461 en 300 s) | 0 |
| fuzz_state_load | 6795 | 11 | 819 / 1136 | 0 |

## 5. Rendimiento (`gbtest --bench 3000`, sin sanitizers)
```
dmg-acid2 (DMG): bench: 3000 frames, 50.220 s emulados en 1.841 s reales -> 27.3x tiempo real
cgb-acid2 (CGB): bench: 3000 frames, 50.221 s emulados en 1.907 s reales -> 26.3x tiempo real
```
Medición alternada antes/después en la misma máquina (dos rondas): `d6905d7` 31,2 y 31,7×; M8 29,8 y 29,2× en DMG (~6 % más lento). Las cifras de arriba son de otra pasada con más ruido de la máquina: comparar solo mediciones alternadas. Hay que medir en el iPhone (🍎).

## 6. Criterios del hito
| Criterio | Resultado |
|---|---|
| cgb-acid2 idéntico | PASS (sección 1, frame 24) |
| dmg-acid2 en CGB (compatibilidad) idéntico a `dmg-acid2-cgb.png` | PASS (sección 1, frame 19) |
| `boot_regs-cgb` | PASS |
| M1–M5 sin regresiones | 157/157 requeridos, también con ASan |
| iPhone: Amarillo en color, Rojo con paleta de compatibilidad | 🍎 pendiente (Joel) |
