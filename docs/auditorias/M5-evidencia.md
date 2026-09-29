# M5 · Evidencia (núcleo del APU; la integración iOS es 🍎)

Generada en Claude Code en la nube (Linux, Ubuntu clang version 18.1.3 (1ubuntu1), 4 vCPU) el 2026-09-29, rama `claude/estado-m1-8ow61b` reiniciada desde `main` tras el merge de M3 (`1a9f9b3`). Ver el commit que contiene este archivo.

## 1. `make -C core test HITO=M5` (incluye M1–M3: sin regresiones)
```
python3 tests/run_suite.py --bin build/gbtest --roms tests/roms --suite tests/suite.txt --hito M5
Hito  Res.  Tipo        Caso                                                               Tiempo  Detalle
M1    PASS  requerido   unit tests                                                           0.1s  PASS: 1073 comprobaciones, 0 fallos
M1    PASS  requerido   mooneye-test-suite/acceptance/boot_regs-dmgABC.gb                    0.0s  PASS: mooneye: registros Fibonacci (frame 11)
M1    PASS  requerido   blargg/cpu_instrs/cpu_instrs.gb                                      1.9s  PASS: serie: Passed (frame 3194)
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
M1    PASS  requerido   blargg/cpu_instrs/individual/04-op r,imm.gb                          0.1s  PASS: serie: Passed (frame 163)
M1    PASS  requerido   blargg/cpu_instrs/individual/05-op rp.gb                             0.1s  PASS: serie: Passed (frame 224)
M1    PASS  requerido   blargg/cpu_instrs/individual/06-ld r,r.gb                            0.0s  PASS: serie: Passed (frame 34)
M1    PASS  requerido   blargg/cpu_instrs/individual/07-jr,jp,call,ret,rst.gb                0.0s  PASS: serie: Passed (frame 42)
M1    PASS  requerido   blargg/cpu_instrs/individual/08-misc instrs.gb                       0.0s  PASS: serie: Passed (frame 32)
M1    PASS  requerido   blargg/cpu_instrs/individual/09-op r,r.gb                            0.3s  PASS: serie: Passed (frame 545)
M1    PASS  requerido   blargg/cpu_instrs/individual/10-bit ops.gb                           0.6s  PASS: serie: Passed (frame 828)
M1    PASS  requerido   blargg/cpu_instrs/individual/11-op a,(hl).gb                         0.6s  PASS: serie: Passed (frame 1050)
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
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_bank2.gb                  0.1s  PASS: mooneye: registros Fibonacci (frame 176)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_mode.gb                   0.1s  PASS: mooneye: registros Fibonacci (frame 179)
M3    PASS  requerido   mooneye-test-suite/emulator-only/mbc1/bits_ramg.gb                   0.2s  PASS: mooneye: registros Fibonacci (frame 349)
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
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-basic-tests-dmg.png]                  0.7s  PASS: frames: framebuffer idéntico a la referencia (frame 1200)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-range-tests-dmg.png]                  0.5s  PASS: frames: framebuffer idéntico a la referencia (frame 900)
M3    PASS  requerido   rtc3test/rtc3test.gb [rtc3test-sub-second-writes-dmg.png]            1.0s  PASS: frames: framebuffer idéntico a la referencia (frame 2100)
M3    FAIL  known-fail  mooneye-test-suite/emulator-only/mbc1/multicart_rom_8Mb.gb           0.0s  FAIL: mooneye: registros de fallo (frame 12)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/01-registers.gb                         0.0s  PASS: blargg: código 0 (frame 51)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/02-len ctr.gb                           0.2s  PASS: blargg: código 0 (frame 566)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/03-trigger.gb                           0.4s  PASS: blargg: código 0 (frame 1010)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/04-sweep.gb                             0.0s  PASS: blargg: código 0 (frame 75)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/05-sweep details.gb                     0.0s  PASS: blargg: código 0 (frame 73)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/06-overflow on trigger.gb               0.0s  PASS: blargg: código 0 (frame 56)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/07-len sweep period sync.gb             0.0s  PASS: blargg: código 0 (frame 36)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/08-len ctr during power.gb              0.0s  PASS: blargg: código 0 (frame 83)
M5    PASS  requerido   blargg/dmg_sound/rom_singles/11-regs after power.gb                  0.0s  PASS: blargg: código 0 (frame 44)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/09-wave read while on.gb                0.0s  FAIL: blargg: código 1 (frame 38)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/10-wave trigger while on.gb             0.1s  FAIL: blargg: código 1 (frame 235)
M5    FAIL  known-fail  blargg/dmg_sound/rom_singles/12-wave write while on.gb               0.1s  FAIL: blargg: código 1 (frame 235)

103/117 PASS · requeridos: 103/103 PASS · HITO=M5
OK: todos los casos requeridos en PASS
exit=0
```

## 2. `make -C core asan HITO=M5`
```
make BUILD=build/asan EXTRA="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all" CFLAGS="-O1 -g" test
clang -std=c11 -Wall -Wextra -Werror -pedantic -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all -DGB_TEST_HOOKS -Iinclude -Isrc … -o build/asan/gbtest -lm
103/117 PASS · requeridos: 103/103 PASS · HITO=M5
OK: todos los casos requeridos en PASS
exit=0
```

## 3. `gbtest <rom> --wav`: WAV de 48 kHz, estéreo, 16 bits, sin saturación, con el pico reportado
```
$ build/gbtest tests/roms/blargg/dmg_sound/dmg_sound.gb --mode blargg --max-frames 2400 --wav build/dmg_sound.wav
  wav: 1718608 frames a 48000 Hz, pico 15349 (-6.6 dBFS), 0 muestras saturadas
FAIL: blargg: código 9 (frame 2138)
$ build/gbtest "tests/roms/blargg/dmg_sound/rom_singles/03-trigger.gb" --mode blargg --max-frames 3000 --wav build/trigger.wav
  wav: 812091 frames a 48000 Hz, pico 15305 (-6.6 dBFS), 0 muestras saturadas
PASS: blargg: código 0 (frame 1010)
$ file build/dmg_sound.wav
build/dmg_sound.wav: RIFF (little-endian) data, WAVE audio, Microsoft PCM, 16 bit, stereo 48000 Hz
```
El combinado `dmg_sound.gb` falla en el #9 (wave RAM con el canal 3 activo: `known-fail`), pero sirve igual como fuente de audio. Pico −6,6 dBFS y 0 muestras saturadas: la escala ×32 deja margen para el peor salto tras el pasa-altos.

## 4. `make -C core check-globals`
```
Sin estado global mutable: OK
exit=0
```

## 5. Fuzzing (el formato de estado pasa a v2 con la sección `APU `): `make -C core fuzz FUZZ_SECONDS=300`
```
INFO: Seed: 3105566861
INFO:     1097 files found in fuzz/corpus/fuzz_load_rom
#1235	DONE   cov: 1189 ft: 4461 corp: 684/677Kb lim: 32768 exec/s: 4 rss: 353Mb
Done 1235 runs in 301 second(s)
INFO: Seed: 3608483304
INFO:      492 files found in fuzz/corpus/fuzz_state_load
#3425	DONE   cov: 856 ft: 1810 corp: 175/445Kb lim: 141979 exec/s: 11 rss: 370Mb
Done 3425 runs in 301 second(s)
exit=0
```

## 6. Rendimiento
Instrucciones ejecutadas (callgrind, `cpu_instrs --bench 200`, determinista): M3 = 765 M; M5 procesando el APU M-ciclo a M-ciclo = 1 007 M (+31 %); M5 con catch-up (versión final) = 817 M (+7 %). Tiempo real (máquina ruidosa):
```
bench: 3600 frames, 60.265 s emulados en 1.946 s reales -> 31.0x tiempo real
bench: 3600 frames, 60.265 s emulados en 2.083 s reales -> 28.9x tiempo real
bench: 3600 frames, 60.265 s emulados en 1.612 s reales -> 37.4x tiempo real
```

## Pendiente (🍎, no se puede hacer en la nube)
- `AudioOutput.swift` (AVAudioEngine), `RingBuffer.swift` y pacing guiado por el audio.
- 10 min en el iPhone con 0 underruns.
- A9: Joel escucha el grito de Pikachu en Amarillo y lo compara con el oráculo.
