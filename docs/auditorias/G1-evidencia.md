# G1 · Evidencia (CPU ARM7TDMI)

Rama `g0-gba-instrucciones`, 2026-10-05, Linux (clang 18.1.3, -O2).

## Qué se implementó
- `gba/src/arm7.c`: modos y bancos, pipeline de 2 etapas, ARM (proceso de datos, PSR, multiplicaciones, SWP, transferencias simples, de media palabra y múltiples, saltos, SWI, indefinidas) y Thumb (los 19 formatos). Tablas de clasificación por instancia.
- `gba/src/arm_mulcarry.c`: bandera C de las multiplicaciones (zlib, zaydlang 2024; aviso conservado y marcado como modificado).
- `gba/src/bus.c`: mapa de memoria con espejos, rotaciones, bus abierto del cartucho acotado al tamaño real, DISPSTAT/VCOUNT/KEYINPUT mínimos y 1 ciclo por acceso (waitstates en G2).
- Casos límite fijados por SingleStepTests (generadas con NanoBoyAdvance): ver `docs/10-gba-spec.md` §CPU.

## Salida
```
PASS  G1 unit: PASS unit: 0 fallos
PASS  G1 ARM7TDMI/v1/arm_b_bl.json.bin: PASS arm_b_bl.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_bx.json.bin: PASS arm_bx.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_cdp.json.bin: PASS arm_cdp.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_data_proc_immediate.json.bin: PASS arm_data_proc_immediate.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_data_proc_immediate_shift.json.bin: PASS arm_data_proc_immediate_shift.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_data_proc_register_shift.json.bin: PASS arm_data_proc_register_shift.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_ldm_stm.json.bin: PASS arm_ldm_stm.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_ldr_str_immediate_offset.json.bin: PASS arm_ldr_str_immediate_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_ldr_str_register_offset.json.bin: PASS arm_ldr_str_register_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_ldrh_strh.json.bin: PASS arm_ldrh_strh.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_ldrsb_ldrsh.json.bin: PASS arm_ldrsb_ldrsh.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_mcr_mrc.json.bin: PASS arm_mcr_mrc.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_mrs.json.bin: PASS arm_mrs.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_msr_imm.json.bin: PASS arm_msr_imm.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_msr_reg.json.bin: PASS arm_msr_reg.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_mul_mla.json.bin: PASS arm_mul_mla.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_mull_mlal.json.bin: PASS arm_mull_mlal.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_stc_ldc.json.bin: PASS arm_stc_ldc.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_swi.json.bin: PASS arm_swi.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/arm_swp.json.bin: PASS arm_swp.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_add_cmp_mov_hi.json.bin: PASS thumb_add_cmp_mov_hi.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_add_sp_or_pc.json.bin: PASS thumb_add_sp_or_pc.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_add_sub.json.bin: PASS thumb_add_sub.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_add_sub_sp.json.bin: PASS thumb_add_sub_sp.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_b.json.bin: PASS thumb_b.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_bcc.json.bin: PASS thumb_bcc.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_bl_blx_prefix.json.bin: PASS thumb_bl_blx_prefix.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_bl_suffix.json.bin: PASS thumb_bl_suffix.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_bx.json.bin: PASS thumb_bx.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_data_proc.json.bin: PASS thumb_data_proc.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldm_stm.json.bin: PASS thumb_ldm_stm.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldr_pc_rel.json.bin: PASS thumb_ldr_pc_rel.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldr_str_imm_offset.json.bin: PASS thumb_ldr_str_imm_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldr_str_reg_offset.json.bin: PASS thumb_ldr_str_reg_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldr_str_sp_rel.json.bin: PASS thumb_ldr_str_sp_rel.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldrb_strb_imm_offset.json.bin: PASS thumb_ldrb_strb_imm_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldrh_strh_imm_offset.json.bin: PASS thumb_ldrh_strh_imm_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldrh_strh_reg_offset.json.bin: PASS thumb_ldrh_strh_reg_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldrsb_strb_reg_offset.json.bin: PASS thumb_ldrsb_strb_reg_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_ldrsh_ldrsb_reg_offset.json.bin: PASS thumb_ldrsh_ldrsb_reg_offset.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_lsl_lsr_asr.json.bin: PASS thumb_lsl_lsr_asr.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_mov_cmp_add_sub.json.bin: PASS thumb_mov_cmp_add_sub.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_push_pop.json.bin: PASS thumb_push_pop.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_swi.json.bin: PASS thumb_swi.json.bin: 50000/50000
PASS  G1 ARM7TDMI/v1/thumb_undefined_bcc.json.bin: PASS thumb_undefined_bcc.json.bin: 50000/50000
PASS  G1 gba-tests/arm/arm.gba: PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
PASS  G1 gba-tests/thumb/thumb.gba: PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)

48/48 sin fallos requeridos (1.1 s)
PASS gba/tests/roms/gba-tests/memory/memory.gba (2 frames)
bench: 3000 frames en 3.83 s = 783 fps (13.1x tiempo real)
PASS  G1 gba-tests/thumb/thumb.gba: PASS tests/roms/gba-tests/thumb/thumb.gba (2 frames)

48/48 sin fallos requeridos (1.4 s)
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
```

El núcleo GB no cambia: `make -C core test HITO=M9` → 157/157 requeridos (última línea de arriba).

`bios.gba` (necesita la HLE de G2) y `nes.gba` todavía no pasan; entran como criterio de G2.
