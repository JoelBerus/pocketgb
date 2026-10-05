/*
 * arm7.c — CPU ARM7TDMI (ARMv4T) del GBA: modos, bancos, pipeline y los dos
 * juegos de instrucciones. Código propio a partir de GBATEK §ARM CPU y del
 * manual técnico del ARM7TDMI (ARM DDI 0029). Sin tablas globales: la
 * clasificación de opcodes vive en la instancia (gba_arm_init_tables).
 *
 * Modelo del pipeline (el mismo que asumen las pruebas SingleStepTests):
 * pipe[0] es la instrucción que se ejecuta, pipe[1] la siguiente ya leída y
 * r15 apunta a la que se lee durante la ejecución (dirección + 8 en ARM,
 * + 4 en Thumb). Una instrucción que escribe r15 marca `flushed` y al final
 * del paso se rellenan las dos etapas desde el nuevo PC.
 */
#include "internal.h"

/* ---------------------------------------------------------------- modos */

static int arm_bank_of(uint32_t mode)
{
    switch (mode & 0x1Fu) {
    case ARM_MODE_FIQ: return ARM_BANK_FIQ;
    case ARM_MODE_IRQ: return ARM_BANK_IRQ;
    case ARM_MODE_SVC: return ARM_BANK_SVC;
    case ARM_MODE_ABT: return ARM_BANK_ABT;
    case ARM_MODE_UND: return ARM_BANK_UND;
    default: return ARM_BANK_USR;   /* usuario, sistema y modos inválidos */
    }
}

static void arm_switch_bank(gba_arm *c, int nb)
{
    int ob = c->bank;
    if (ob == nb) return;
    if (ob == ARM_BANK_FIQ) memcpy(c->bank_fiq_r8, &c->r[8], sizeof c->bank_fiq_r8);
    else memcpy(c->bank_usr_r8, &c->r[8], sizeof c->bank_usr_r8);
    c->bank_r13[ob] = c->r[13];
    c->bank_r14[ob] = c->r[14];
    c->bank_spsr[ob] = c->spsr;
    if (nb == ARM_BANK_FIQ) memcpy(&c->r[8], c->bank_fiq_r8, sizeof c->bank_fiq_r8);
    else memcpy(&c->r[8], c->bank_usr_r8, sizeof c->bank_usr_r8);
    c->r[13] = c->bank_r13[nb];
    c->r[14] = c->bank_r14[nb];
    c->spsr = c->bank_spsr[nb];
    c->bank = (uint8_t)nb;
}

void gba_arm_set_cpsr(gba *g, uint32_t value)
{
    value |= 0x10u;                 /* M4 está fijo a 1 en el ARM7TDMI */
    arm_switch_bank(&g->cpu, arm_bank_of(value));
    g->cpu.cpsr = value;
}

static inline void arm_write_pc(gba *g, uint32_t v)
{
    g->cpu.r[15] = v;
    g->cpu.flushed = true;
}

void gba_arm_branch(gba *g, uint32_t addr)
{
    arm_write_pc(g, addr);
}

void gba_arm_flush(gba *g)
{
    gba_arm *c = &g->cpu;
    if (c->cpsr & ARM_T) {
        c->pipe[0] = gba_bus_fetch16_n(g, c->r[15]);
        c->pipe[1] = gba_bus_fetch16(g, c->r[15] + 2);
        c->r[15] += 4;
    } else {
        c->pipe[0] = gba_bus_fetch32_n(g, c->r[15]);
        c->pipe[1] = gba_bus_fetch32(g, c->r[15] + 4);
        c->r[15] += 8;
    }
}

/* Entra en una excepción: guarda CPSR en el SPSR del modo nuevo, pasa a ARM
 * con IRQ deshabilitadas y salta al vector. */
static void arm_exception(gba *g, uint32_t mode, uint32_t vector, uint32_t lr)
{
    gba_arm *c = &g->cpu;
    uint32_t old = c->cpsr;
    gba_arm_set_cpsr(g, (old & ~(0x1Fu | ARM_T)) | mode | ARM_I);
    c->spsr = old;
    c->r[14] = lr;
    arm_write_pc(g, vector);
}

static void arm_undefined(gba *g)
{
    gba_arm *c = &g->cpu;
    uint32_t next = c->r[15] - ((c->cpsr & ARM_T) ? 2u : 4u);
    arm_exception(g, ARM_MODE_UND, 0x04, next);
}

static void arm_swi(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    /* Sin BIOS real: HLE en el modo del llamador (no en las pruebas de CPU). */
#ifdef GBA_TEST_HOOKS
    if (!g->test.active)
#endif
    if (!g->bios_loaded) {
        uint32_t number = (c->cpsr & ARM_T) ? (op & 0xFFu) : ((op >> 16) & 0xFFu);
        gba_hle_swi(g, number);
        g->bios_last = 0xE3A02004u;
        return;
    }
    uint32_t next = c->r[15] - ((c->cpsr & ARM_T) ? 2u : 4u);
    arm_exception(g, ARM_MODE_SVC, 0x08, next);
}

void gba_arm_irq(gba *g)
{
    gba_arm *c = &g->cpu;
    /* LR = siguiente instrucción + 4 (el manejador vuelve con SUBS PC, LR, #4). */
    uint32_t lr = (c->cpsr & ARM_T) ? c->r[15] : c->r[15] - 4u;
    arm_exception(g, ARM_MODE_IRQ, 0x18, lr);
    gba_arm_flush(g);
    c->flushed = false;
    c->halted = false;
}

void gba_arm_reset(gba *g, bool skip_bios)
{
    gba_arm *c = &g->cpu;
    memset(c, 0, sizeof *c);
    c->bank = ARM_BANK_USR;
    if (skip_bios) {
        /* Estado tras el arranque de la BIOS (GBATEK §BIOS RAM Usage). */
        c->cpsr = ARM_MODE_SVC;
        c->bank = ARM_BANK_SVC;
        c->r[13] = 0x03007FE0;          /* SVC */
        c->bank_r13[ARM_BANK_IRQ] = 0x03007FA0;
        c->bank_r13[ARM_BANK_USR] = 0x03007F00;
        gba_arm_set_cpsr(g, ARM_MODE_SYS);
        c->r[15] = 0x08000000;
    } else {
        c->cpsr = ARM_MODE_SVC | ARM_I | ARM_F;
        c->bank = ARM_BANK_SVC;
        c->r[15] = 0;
    }
    gba_arm_flush(g);
}

/* ---------------------------------------------------------------- utilidades */

static inline bool arm_cond(uint32_t cond, uint32_t f)
{
    bool n = (f >> 31) & 1, z = (f >> 30) & 1, cf = (f >> 29) & 1, v = (f >> 28) & 1;
    switch (cond) {
    case 0x0: return z;
    case 0x1: return !z;
    case 0x2: return cf;
    case 0x3: return !cf;
    case 0x4: return n;
    case 0x5: return !n;
    case 0x6: return v;
    case 0x7: return !v;
    case 0x8: return cf && !z;
    case 0x9: return !cf || z;
    case 0xA: return n == v;
    case 0xB: return n != v;
    case 0xC: return !z && n == v;
    case 0xD: return z || n != v;
    case 0xE: return true;
    default: return false;           /* NV: nunca en ARMv4 */
    }
}

static inline void arm_set_nz(gba_arm *c, uint32_t res)
{
    c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z)) | (res & ARM_N) | (res ? 0 : ARM_Z);
}

static inline void arm_set_nzcv(gba_arm *c, uint32_t res, uint32_t carry, uint32_t ovf)
{
    c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z | ARM_C | ARM_V)) | (res & ARM_N) | (res ? 0 : ARM_Z) |
              (carry ? ARM_C : 0) | (ovf ? ARM_V : 0);
}

static inline uint32_t arm_add(gba_arm *c, uint32_t a, uint32_t b, uint32_t cin, bool s)
{
    uint64_t wide = (uint64_t)a + b + cin;
    uint32_t res = (uint32_t)wide;
    if (s) arm_set_nzcv(c, res, (uint32_t)(wide >> 32), ((~(a ^ b) & (a ^ res)) >> 31));
    return res;
}

/* a - b - !cin, con el acarreo de ARM (1 = sin préstamo). */
static inline uint32_t arm_sub(gba_arm *c, uint32_t a, uint32_t b, uint32_t cin, bool s)
{
    uint32_t res = a - b - (cin ^ 1u);
    if (s) {
        uint32_t carry = (uint64_t)a >= (uint64_t)b + (cin ^ 1u);
        arm_set_nzcv(c, res, carry, (((a ^ b) & (a ^ res)) >> 31));
    }
    return res;
}

/* Desplazamiento con cantidad inmediata (5 bits; 0 tiene significados especiales). */
static inline uint32_t arm_shift_imm(uint32_t type, uint32_t v, uint32_t amt, uint32_t *carry)
{
    switch (type) {
    case 0: /* LSL */
        if (amt == 0) return v;
        *carry = (v >> (32 - amt)) & 1;
        return v << amt;
    case 1: /* LSR; #0 = #32 */
        if (amt == 0) { *carry = v >> 31; return 0; }
        *carry = (v >> (amt - 1)) & 1;
        return v >> amt;
    case 2: /* ASR; #0 = #32 */
        if (amt == 0) { *carry = v >> 31; return (uint32_t)-(int32_t)(v >> 31); }
        *carry = (v >> (amt - 1)) & 1;
        return (uint32_t)((int32_t)v >> amt);
    default: /* ROR; #0 = RRX */
        if (amt == 0) {
            uint32_t res = (*carry << 31) | (v >> 1);
            *carry = v & 1;
            return res;
        }
        *carry = (v >> (amt - 1)) & 1;
        return gba_ror32(v, amt);
    }
}

/* Desplazamiento con cantidad en registro (byte bajo; 0 = sin cambios). */
static inline uint32_t arm_shift_reg(uint32_t type, uint32_t v, uint32_t amt, uint32_t *carry)
{
    if (amt == 0) return v;
    switch (type) {
    case 0:
        if (amt < 32) { *carry = (v >> (32 - amt)) & 1; return v << amt; }
        *carry = amt == 32 ? (v & 1) : 0;
        return 0;
    case 1:
        if (amt < 32) { *carry = (v >> (amt - 1)) & 1; return v >> amt; }
        *carry = amt == 32 ? (v >> 31) : 0;
        return 0;
    case 2:
        if (amt < 32) { *carry = (v >> (amt - 1)) & 1; return (uint32_t)((int32_t)v >> amt); }
        *carry = v >> 31;
        return (uint32_t)-(int32_t)(v >> 31);
    default:
        amt &= 31u;
        if (amt == 0) { *carry = v >> 31; return v; }
        *carry = (v >> (amt - 1)) & 1;
        return gba_ror32(v, amt);
    }
}

/* Lecturas de datos con las rotaciones del ARM7 para direcciones desalineadas. */
static inline uint32_t arm_load32(gba *g, uint32_t addr)
{
    return gba_ror32(gba_bus_read32(g, addr & ~3u), (addr & 3u) * 8u);
}

static inline uint32_t arm_load16(gba *g, uint32_t addr)
{
    return gba_ror32(gba_bus_read16(g, addr & ~1u), (addr & 1u) * 8u);
}

static inline uint32_t arm_load_s16(gba *g, uint32_t addr)
{
    uint16_t v = gba_bus_read16(g, addr & ~1u);
    if (addr & 1u) return (uint32_t)(int32_t)(int8_t)(v >> 8);
    return (uint32_t)(int32_t)(int16_t)v;
}

/* Ciclos internos de una multiplicación según el multiplicador (GBATEK). */
static inline uint32_t arm_mul_cycles(uint32_t rs, bool sign_ext)
{
    uint32_t m = 4;
    if (sign_ext) {
        if ((rs >> 8) == 0 || (rs >> 8) == 0xFFFFFFu) m = 1;
        else if ((rs >> 16) == 0 || (rs >> 16) == 0xFFFFu) m = 2;
        else if ((rs >> 24) == 0 || (rs >> 24) == 0xFFu) m = 3;
    } else {
        if ((rs >> 8) == 0) m = 1;
        else if ((rs >> 16) == 0) m = 2;
        else if ((rs >> 24) == 0) m = 3;
    }
    return m;
}

/* ---------------------------------------------------------------- clasificación */

static uint8_t arm_classify(uint32_t hi, uint32_t lo)
{
    /* hi = bits 27-20, lo = bits 7-4 */
    uint32_t op = (hi << 20) | (lo << 4);
    if ((op & 0x0FFFFFF0u) == 0x012FFF10u || ((hi == 0x12) && lo == 0x1)) return ARM_OP_BX;
    if ((op & 0x0FC000F0u) == 0x00000090u) return ARM_OP_MUL;
    if ((op & 0x0F8000F0u) == 0x00800090u) return ARM_OP_MULL;
    if ((op & 0x0FB000F0u) == 0x01000090u) return ARM_OP_SWP;
    if ((op & 0x0E000090u) == 0x00000090u) return ARM_OP_HALF;
    if ((op & 0x0C000000u) == 0x00000000u) {
        /* TST/TEQ/CMP/CMN sin S: transferencias del PSR. */
        if ((op & 0x01900000u) == 0x01000000u) return (op & 0x00200000u) ? ARM_OP_MSR : ARM_OP_MRS;
        return ARM_OP_DP;
    }
    if ((op & 0x0C000000u) == 0x04000000u) {
        if ((op & 0x02000010u) == 0x02000010u) return ARM_OP_UNDEF;
        return ARM_OP_SDT;
    }
    if ((op & 0x0E000000u) == 0x08000000u) return ARM_OP_LDM;
    if ((op & 0x0E000000u) == 0x0A000000u) return ARM_OP_B;
    if ((op & 0x0F000000u) == 0x0F000000u) return ARM_OP_SWI;
    return ARM_OP_UNDEF;                 /* coprocesador: el GBA no tiene */
}

static uint8_t thumb_classify(uint32_t op)
{
    if ((op & 0xF800u) == 0x1800u) return THUMB_OP_ADDSUB;
    if ((op & 0xE000u) == 0x0000u) return THUMB_OP_SHIFT;
    if ((op & 0xE000u) == 0x2000u) return THUMB_OP_IMM;
    if ((op & 0xFC00u) == 0x4000u) return THUMB_OP_ALU;
    if ((op & 0xFC00u) == 0x4400u) return THUMB_OP_HIREG;
    if ((op & 0xF800u) == 0x4800u) return THUMB_OP_LDRPC;
    if ((op & 0xF200u) == 0x5000u) return THUMB_OP_LDSTREG;
    if ((op & 0xF200u) == 0x5200u) return THUMB_OP_LDSTSX;
    if ((op & 0xE000u) == 0x6000u) return THUMB_OP_LDSTIMM;
    if ((op & 0xF000u) == 0x8000u) return THUMB_OP_LDSTH;
    if ((op & 0xF000u) == 0x9000u) return THUMB_OP_LDSTSP;
    if ((op & 0xF000u) == 0xA000u) return THUMB_OP_ADDR;
    if ((op & 0xFF00u) == 0xB000u) return THUMB_OP_ADDSP;
    if ((op & 0xF600u) == 0xB400u) return THUMB_OP_PUSHPOP;
    if ((op & 0xF000u) == 0xC000u) return THUMB_OP_LDMSTM;
    if ((op & 0xFF00u) == 0xDF00u) return THUMB_OP_SWI;
    if ((op & 0xF000u) == 0xD000u) return THUMB_OP_BCC;
    if ((op & 0xF800u) == 0xE000u) return THUMB_OP_B;
    if ((op & 0xF800u) == 0xF000u) return THUMB_OP_BL1;
    if ((op & 0xF800u) == 0xF800u) return THUMB_OP_BL2;
    return THUMB_OP_UNDEF;
}

void gba_arm_init_tables(gba *g)
{
    for (uint32_t i = 0; i < 4096; i++) g->arm_lut[i] = arm_classify(i >> 4, i & 15u);
    for (uint32_t i = 0; i < 1024; i++) g->thumb_lut[i] = thumb_classify(i << 6);
}

/* ---------------------------------------------------------------- ARM */

static void arm_op_dp(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t opc = (op >> 21) & 15u, rn = (op >> 16) & 15u, rd = (op >> 12) & 15u;
    bool s = (op >> 20) & 1u;
    uint32_t cflag = (c->cpsr >> 29) & 1u, sc = cflag, op2, a;
    if (op & (1u << 25)) {
        uint32_t rot = ((op >> 8) & 15u) * 2u;
        op2 = gba_ror32(op & 0xFFu, rot);
        if (rot) sc = op2 >> 31;
        a = c->r[rn];
    } else if (op & 0x10u) {
        /* Desplazamiento por registro: un ciclo interno; el PC se lee como +12. */
        uint32_t rm = op & 15u;
        uint32_t amt = c->r[(op >> 8) & 15u] & 0xFFu;
        gba_bus_idle(g, 1);
        uint32_t v = c->r[rm] + (rm == 15 ? 4u : 0u);
        op2 = arm_shift_reg((op >> 5) & 3u, v, amt, &sc);
        a = c->r[rn] + (rn == 15 ? 4u : 0u);
    } else {
        op2 = arm_shift_imm((op >> 5) & 3u, c->r[op & 15u], (op >> 7) & 31u, &sc);
        a = c->r[rn];
    }
    /* S con Rd=15: en modos con SPSR se restaura el CPSR; en usuario/sistema
     * las banderas se calculan como siempre. */
    bool restore = s && rd == 15 && c->bank != ARM_BANK_USR;
    bool flags = s && !restore;
    uint32_t res = 0;
    bool write = true;
    switch (opc) {
    case 0x0: res = a & op2; break;
    case 0x1: res = a ^ op2; break;
    case 0x2: res = arm_sub(c, a, op2, 1, flags); break;
    case 0x3: res = arm_sub(c, op2, a, 1, flags); break;
    case 0x4: res = arm_add(c, a, op2, 0, flags); break;
    case 0x5: res = arm_add(c, a, op2, cflag, flags); break;
    case 0x6: res = arm_sub(c, a, op2, cflag, flags); break;
    case 0x7: res = arm_sub(c, op2, a, cflag, flags); break;
    case 0x8: res = a & op2; write = false; break;
    case 0x9: res = a ^ op2; write = false; break;
    case 0xA: res = arm_sub(c, a, op2, 1, flags); write = false; break;
    case 0xB: res = arm_add(c, a, op2, 0, flags); write = false; break;
    case 0xC: res = a | op2; break;
    case 0xD: res = op2; break;
    case 0xE: res = a & ~op2; break;
    default: res = ~op2; break;
    }
    bool logical = opc <= 1 || opc == 8 || opc == 9 || opc >= 12;
    if (flags && logical)
        c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z | ARM_C)) | (res & ARM_N) | (res ? 0 : ARM_Z) | (sc ? ARM_C : 0);
    if (restore) gba_arm_set_cpsr(g, c->spsr);
    if (write) {
        if (rd == 15) arm_write_pc(g, res);
        else c->r[rd] = res;
    }
}

static void arm_op_mrs(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t v = (op & (1u << 22)) ? (c->bank == ARM_BANK_USR ? c->cpsr : c->spsr) : c->cpsr;
    c->r[(op >> 12) & 15u] = v;
}

static void arm_op_msr(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t v;
    if (op & (1u << 25)) v = gba_ror32(op & 0xFFu, ((op >> 8) & 15u) * 2u);
    else v = c->r[op & 15u];
    uint32_t mask = 0;
    if (op & (1u << 19)) mask |= 0xFF000000u;
    if (op & (1u << 18)) mask |= 0x00FF0000u;
    if (op & (1u << 17)) mask |= 0x0000FF00u;
    if (op & (1u << 16)) mask |= 0x000000FFu;
    if (op & (1u << 22)) {
        if (c->bank != ARM_BANK_USR) c->spsr = (c->spsr & ~mask) | (v & mask);
    } else {
        if ((c->cpsr & 0x1Fu) == ARM_MODE_USR) mask &= 0xFF000000u;
        gba_arm_set_cpsr(g, (c->cpsr & ~mask) | (v & mask));
    }
}

static void arm_op_bx(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t v = c->r[op & 15u];
    if (v & 1u) c->cpsr |= ARM_T;
    else c->cpsr &= ~ARM_T;
    arm_write_pc(g, v & ~1u);
}

static void arm_op_mul(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t rd = (op >> 16) & 15u, rn = (op >> 12) & 15u, rs = (op >> 8) & 15u, rm = op & 15u;
    uint32_t vm = c->r[rm] + (rm == 15 ? 4u : 0u), vs = c->r[rs] + (rs == 15 ? 4u : 0u);
    uint32_t acc = (op & (1u << 21)) ? c->r[rn] + (rn == 15 ? 4u : 0u) : 0;
    uint32_t res = vm * vs + acc;
    uint32_t cyc = arm_mul_cycles(vs, true) + ((op & (1u << 21)) ? 1u : 0u);
    gba_bus_idle(g, cyc);
    if (op & (1u << 20)) {
        arm_set_nz(c, res);
        bool cf = gba_arm_mul_carry(GBA_MUL_SHORT, vm, vs, acc);
        c->cpsr = (c->cpsr & ~ARM_C) | (cf ? ARM_C : 0);
    }
    if (rd == 15) arm_write_pc(g, res);
    else c->r[rd] = res;
}

static void arm_op_mull(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t hi = (op >> 16) & 15u, lo = (op >> 12) & 15u, rs = (op >> 8) & 15u, rm = op & 15u;
    bool sign = (op >> 22) & 1u;
    uint32_t vm = c->r[rm] + (rm == 15 ? 4u : 0u), vs = c->r[rs] + (rs == 15 ? 4u : 0u);
    uint64_t res;
    if (sign) res = (uint64_t)((int64_t)(int32_t)vm * (int64_t)(int32_t)vs);
    else res = (uint64_t)vm * vs;
    uint32_t cyc = arm_mul_cycles(vs, sign) + 1;
    uint64_t acc = 0;
    if (op & (1u << 21)) {
        acc = ((uint64_t)(c->r[hi] + (hi == 15 ? 4u : 0u)) << 32) | (c->r[lo] + (lo == 15 ? 4u : 0u));
        res += acc;
        cyc++;
    }
    gba_bus_idle(g, cyc);
    if (op & (1u << 20)) {
        bool cf = gba_arm_mul_carry(sign ? GBA_MUL_LONG_SIGNED : GBA_MUL_LONG_UNSIGNED, vm, vs, acc);
        c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z | ARM_C)) | ((uint32_t)(res >> 32) & ARM_N) | (res ? 0 : ARM_Z) |
                  (cf ? ARM_C : 0);
    }
    c->r[lo] = (uint32_t)res;
    c->r[hi] = (uint32_t)(res >> 32);
    if (lo == 15 || hi == 15) arm_write_pc(g, c->r[15]);
}

static void arm_op_swp(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t rn = (op >> 16) & 15u, rd = (op >> 12) & 15u, rm = op & 15u;
    uint32_t addr = c->r[rn] + (rn == 15 ? 4u : 0u), src = c->r[rm] + (rm == 15 ? 4u : 0u), tmp;
    if (op & (1u << 22)) {
        tmp = gba_bus_read8(g, addr);
        gba_bus_write8(g, addr, (uint8_t)src);
    } else {
        tmp = arm_load32(g, addr);
        gba_bus_write32(g, addr, src);
    }
    gba_bus_idle(g, 1);
    if (rd == 15) arm_write_pc(g, tmp);
    else c->r[rd] = tmp;
}

static void arm_op_half(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    bool p = (op >> 24) & 1u, u = (op >> 23) & 1u, w = (op >> 21) & 1u, l = (op >> 20) & 1u;
    uint32_t rn = (op >> 16) & 15u, rd = (op >> 12) & 15u, sh = (op >> 5) & 3u;
    uint32_t off = (op & (1u << 22)) ? (((op >> 4) & 0xF0u) | (op & 0xFu)) : c->r[op & 15u];
    uint32_t base = c->r[rn];
    uint32_t moved = u ? base + off : base - off;
    uint32_t addr = p ? moved : base;
    bool wb = !p || w;
    if (l) {
        uint32_t v;
        if (sh == 1) v = arm_load16(g, addr);
        else if (sh == 2) v = (uint32_t)(int32_t)(int8_t)gba_bus_read8(g, addr);
        else v = arm_load_s16(g, addr);
        if (wb) c->r[rn] = moved;
        gba_bus_idle(g, 1);
        if (rd == 15) arm_write_pc(g, v);
        else c->r[rd] = v;
    } else if (sh == 1) {
        uint32_t v = c->r[rd] + (rd == 15 ? 4u : 0u);
        gba_bus_write16(g, addr, (uint16_t)v);
        if (wb) c->r[rn] = moved;
    } else {
        /* LDRD/STRD no existen en ARMv4: el ARM7TDMI no hace nada útil. */
        if (wb) c->r[rn] = moved;
    }
    if (wb && rn == 15 && !(l && rd == 15)) arm_write_pc(g, c->r[15] + 4u);
}

static void arm_op_sdt(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    bool p = (op >> 24) & 1u, u = (op >> 23) & 1u, b = (op >> 22) & 1u, w = (op >> 21) & 1u,
         l = (op >> 20) & 1u;
    uint32_t rn = (op >> 16) & 15u, rd = (op >> 12) & 15u, off;
    if (op & (1u << 25)) {
        uint32_t carry = (c->cpsr >> 29) & 1u;
        off = arm_shift_imm((op >> 5) & 3u, c->r[op & 15u], (op >> 7) & 31u, &carry);
    } else {
        off = op & 0xFFFu;
    }
    uint32_t base = c->r[rn];
    uint32_t moved = u ? base + off : base - off;
    uint32_t addr = p ? moved : base;
    bool wb = !p || w;
    if (l) {
        uint32_t v = b ? gba_bus_read8(g, addr) : arm_load32(g, addr);
        if (wb) c->r[rn] = moved;
        gba_bus_idle(g, 1);
        if (rd == 15) arm_write_pc(g, v);
        else c->r[rd] = v;
    } else {
        uint32_t v = c->r[rd] + (rd == 15 ? 4u : 0u);
        if (b) gba_bus_write8(g, addr, (uint8_t)v);
        else gba_bus_write32(g, addr, v);
        if (wb) c->r[rn] = moved;
    }
    if (wb && rn == 15 && !(l && rd == 15)) arm_write_pc(g, c->r[15] + 4u);
}

static void arm_op_ldm(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    bool p = (op >> 24) & 1u, u = (op >> 23) & 1u, s = (op >> 22) & 1u, w = (op >> 21) & 1u,
         l = (op >> 20) & 1u;
    uint32_t rn = (op >> 16) & 15u;
    uint32_t list = op & 0xFFFFu;
    uint32_t bytes;
    if (list == 0) {
        list = 0x8000u;       /* lista vacía: se transfiere r15 y la base se mueve 0x40 */
        bytes = 0x40;
    } else {
        bytes = (uint32_t)__builtin_popcount(list) * 4u;
    }
    uint32_t base = c->r[rn];
    uint32_t new_base = u ? base + bytes : base - bytes;
    uint32_t addr;
    if (u) addr = p ? base + 4u : base;
    else addr = p ? base - bytes : base - bytes + 4u;

    /* S sin r15 en LDM (o en cualquier STM): registros del banco de usuario. */
    bool user = s && !(l && (list & 0x8000u));
    int saved_bank = c->bank;
    if (user) arm_switch_bank(c, ARM_BANK_USR);

    bool first = true;
    if (l) {
        if (w) c->r[rn] = new_base;
        for (uint32_t i = 0; i < 16; i++) {
            if (!(list & (1u << i))) continue;
            uint32_t v = gba_bus_read32(g, addr & ~3u);
            c->seq = true;
            addr += 4;
            if (i == 15) arm_write_pc(g, v);
            else c->r[i] = v;
        }
        gba_bus_idle(g, 1);
    } else {
        for (uint32_t i = 0; i < 16; i++) {
            if (!(list & (1u << i))) continue;
            uint32_t v = c->r[i];
            if (i == rn && !first && w) v = new_base;
            else if (i == 15) v += 4u;
            gba_bus_write32(g, addr, v);
            c->seq = true;
            addr += 4;
            first = false;
        }
        if (w) c->r[rn] = new_base;
    }
    c->seq = false;
    if (user) arm_switch_bank(c, saved_bank);
    if (s && l && (list & 0x8000u) && c->bank != ARM_BANK_USR) gba_arm_set_cpsr(g, c->spsr);
    if (w && rn == 15) arm_write_pc(g, c->r[15]);
}

static void arm_op_b(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t off = (uint32_t)((int32_t)(op << 8) >> 6);
    if (op & (1u << 24)) c->r[14] = c->r[15] - 4u;
    arm_write_pc(g, c->r[15] + off);
}

static void arm_execute(gba *g, uint32_t op)
{
    if (!arm_cond(op >> 28, g->cpu.cpsr)) return;
    switch (g->arm_lut[((op >> 16) & 0xFF0u) | ((op >> 4) & 0xFu)]) {
    case ARM_OP_DP: arm_op_dp(g, op); break;
    case ARM_OP_MRS: arm_op_mrs(g, op); break;
    case ARM_OP_MSR: arm_op_msr(g, op); break;
    case ARM_OP_BX: arm_op_bx(g, op); break;
    case ARM_OP_MUL: arm_op_mul(g, op); break;
    case ARM_OP_MULL: arm_op_mull(g, op); break;
    case ARM_OP_SWP: arm_op_swp(g, op); break;
    case ARM_OP_HALF: arm_op_half(g, op); break;
    case ARM_OP_SDT: arm_op_sdt(g, op); break;
    case ARM_OP_LDM: arm_op_ldm(g, op); break;
    case ARM_OP_B: arm_op_b(g, op); break;
    case ARM_OP_SWI: arm_swi(g, op); break;
    default: arm_undefined(g); break;
    }
}

/* ---------------------------------------------------------------- Thumb */

static void thumb_execute(gba *g, uint32_t op)
{
    gba_arm *c = &g->cpu;
    uint32_t *r = c->r;
    uint32_t cflag = (c->cpsr >> 29) & 1u;
    switch (g->thumb_lut[op >> 6]) {
    case THUMB_OP_SHIFT: {
        uint32_t rd = op & 7u, carry = cflag;
        uint32_t res = arm_shift_imm((op >> 11) & 3u, r[(op >> 3) & 7u], (op >> 6) & 31u, &carry);
        r[rd] = res;
        c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z | ARM_C)) | (res & ARM_N) | (res ? 0 : ARM_Z) | (carry ? ARM_C : 0);
        break;
    }
    case THUMB_OP_ADDSUB: {
        uint32_t rd = op & 7u, a = r[(op >> 3) & 7u];
        uint32_t b = (op & (1u << 10)) ? ((op >> 6) & 7u) : r[(op >> 6) & 7u];
        r[rd] = (op & (1u << 9)) ? arm_sub(c, a, b, 1, true) : arm_add(c, a, b, 0, true);
        break;
    }
    case THUMB_OP_IMM: {
        uint32_t rd = (op >> 8) & 7u, imm = op & 0xFFu;
        switch ((op >> 11) & 3u) {
        case 0: r[rd] = imm; arm_set_nz(c, imm); break;
        case 1: arm_sub(c, r[rd], imm, 1, true); break;
        case 2: r[rd] = arm_add(c, r[rd], imm, 0, true); break;
        default: r[rd] = arm_sub(c, r[rd], imm, 1, true); break;
        }
        break;
    }
    case THUMB_OP_ALU: {
        uint32_t rd = op & 7u, rs = r[(op >> 3) & 7u], a = r[rd], res, carry = cflag;
        switch ((op >> 6) & 15u) {
        case 0x0: res = a & rs; r[rd] = res; arm_set_nz(c, res); break;
        case 0x1: res = a ^ rs; r[rd] = res; arm_set_nz(c, res); break;
        case 0x2: case 0x3: case 0x4: case 0x7: {
            static const uint8_t type_of[8] = {0, 0, 0, 1, 2, 0, 0, 3};
            gba_bus_idle(g, 1);
            res = arm_shift_reg(type_of[(op >> 6) & 7u], a, rs & 0xFFu, &carry);
            r[rd] = res;
            c->cpsr = (c->cpsr & ~(ARM_N | ARM_Z | ARM_C)) | (res & ARM_N) | (res ? 0 : ARM_Z) | (carry ? ARM_C : 0);
            break;
        }
        case 0x5: r[rd] = arm_add(c, a, rs, cflag, true); break;
        case 0x6: r[rd] = arm_sub(c, a, rs, cflag, true); break;
        case 0x8: arm_set_nz(c, a & rs); break;
        case 0x9: r[rd] = arm_sub(c, 0, rs, 1, true); break;
        case 0xA: arm_sub(c, a, rs, 1, true); break;
        case 0xB: arm_add(c, a, rs, 0, true); break;
        case 0xC: res = a | rs; r[rd] = res; arm_set_nz(c, res); break;
        case 0xD:
            gba_bus_idle(g, arm_mul_cycles(a, true));
            res = a * rs; r[rd] = res; arm_set_nz(c, res);
            if (gba_arm_mul_carry(GBA_MUL_SHORT, rs, a, 0)) c->cpsr |= ARM_C;
            else c->cpsr &= ~ARM_C;
            break;
        case 0xE: res = a & ~rs; r[rd] = res; arm_set_nz(c, res); break;
        default: res = ~rs; r[rd] = res; arm_set_nz(c, res); break;
        }
        break;
    }
    case THUMB_OP_HIREG: {
        uint32_t rd = (op & 7u) | ((op >> 4) & 8u), rs = (op >> 3) & 15u;
        uint32_t v = r[rs];
        switch ((op >> 8) & 3u) {
        case 0:
            if (rd == 15) arm_write_pc(g, (r[15] + v) & ~1u);
            else r[rd] += v;
            break;
        case 1: arm_sub(c, r[rd], v, 1, true); break;
        case 2:
            if (rd == 15) arm_write_pc(g, v & ~1u);
            else r[rd] = v;
            break;
        default:
            if (v & 1u) c->cpsr |= ARM_T;
            else c->cpsr &= ~ARM_T;
            arm_write_pc(g, v & ~1u);
            break;
        }
        break;
    }
    case THUMB_OP_LDRPC: {
        uint32_t addr = (r[15] & ~2u) + ((op & 0xFFu) << 2);
        r[(op >> 8) & 7u] = arm_load32(g, addr);
        gba_bus_idle(g, 1);
        break;
    }
    case THUMB_OP_LDSTREG: {
        uint32_t rd = op & 7u, addr = r[(op >> 3) & 7u] + r[(op >> 6) & 7u];
        switch ((op >> 10) & 3u) {
        case 0: gba_bus_write32(g, addr, r[rd]); break;
        case 1: gba_bus_write8(g, addr, (uint8_t)r[rd]); break;
        case 2: r[rd] = arm_load32(g, addr); gba_bus_idle(g, 1); break;
        default: r[rd] = gba_bus_read8(g, addr); gba_bus_idle(g, 1); break;
        }
        break;
    }
    case THUMB_OP_LDSTSX: {
        uint32_t rd = op & 7u, addr = r[(op >> 3) & 7u] + r[(op >> 6) & 7u];
        switch ((op >> 10) & 3u) {
        case 0: gba_bus_write16(g, addr, (uint16_t)r[rd]); break;
        case 1: r[rd] = (uint32_t)(int32_t)(int8_t)gba_bus_read8(g, addr); gba_bus_idle(g, 1); break;
        case 2: r[rd] = arm_load16(g, addr); gba_bus_idle(g, 1); break;
        default: r[rd] = arm_load_s16(g, addr); gba_bus_idle(g, 1); break;
        }
        break;
    }
    case THUMB_OP_LDSTIMM: {
        uint32_t rd = op & 7u, base = r[(op >> 3) & 7u], imm = (op >> 6) & 31u;
        switch ((op >> 11) & 3u) {
        case 0: gba_bus_write32(g, base + imm * 4u, r[rd]); break;
        case 1: r[rd] = arm_load32(g, base + imm * 4u); gba_bus_idle(g, 1); break;
        case 2: gba_bus_write8(g, base + imm, (uint8_t)r[rd]); break;
        default: r[rd] = gba_bus_read8(g, base + imm); gba_bus_idle(g, 1); break;
        }
        break;
    }
    case THUMB_OP_LDSTH: {
        uint32_t rd = op & 7u, addr = r[(op >> 3) & 7u] + ((op >> 6) & 31u) * 2u;
        if (op & (1u << 11)) { r[rd] = arm_load16(g, addr); gba_bus_idle(g, 1); }
        else gba_bus_write16(g, addr, (uint16_t)r[rd]);
        break;
    }
    case THUMB_OP_LDSTSP: {
        uint32_t rd = (op >> 8) & 7u, addr = r[13] + ((op & 0xFFu) << 2);
        if (op & (1u << 11)) { r[rd] = arm_load32(g, addr); gba_bus_idle(g, 1); }
        else gba_bus_write32(g, addr, r[rd]);
        break;
    }
    case THUMB_OP_ADDR: {
        uint32_t base = (op & (1u << 11)) ? r[13] : (r[15] & ~2u);
        r[(op >> 8) & 7u] = base + ((op & 0xFFu) << 2);
        break;
    }
    case THUMB_OP_ADDSP: {
        uint32_t imm = (op & 0x7Fu) << 2;
        r[13] = (op & 0x80u) ? r[13] - imm : r[13] + imm;
        break;
    }
    case THUMB_OP_PUSHPOP: {
        uint32_t list = op & 0xFFu;
        bool extra = (op >> 8) & 1u;
        if (op & (1u << 11)) {             /* POP */
            uint32_t addr = r[13];
            if (list == 0 && !extra) {
                arm_write_pc(g, gba_bus_read32(g, addr & ~3u));
                r[13] = addr + 0x40u;
                gba_bus_idle(g, 1);
                break;
            }
            for (uint32_t i = 0; i < 8; i++) {
                if (!(list & (1u << i))) continue;
                r[i] = gba_bus_read32(g, addr & ~3u);
                addr += 4;
            }
            if (extra) {
                arm_write_pc(g, gba_bus_read32(g, addr & ~3u) & ~1u);
                addr += 4;
            }
            r[13] = addr;
            gba_bus_idle(g, 1);
        } else {                           /* PUSH */
            if (list == 0 && !extra) {
                r[13] -= 0x40u;
                gba_bus_write32(g, r[13], r[15] + 2u);
                break;
            }
            uint32_t n = (uint32_t)__builtin_popcount(list) + (extra ? 1u : 0u);
            uint32_t addr = r[13] - n * 4u;
            r[13] = addr;
            for (uint32_t i = 0; i < 8; i++) {
                if (!(list & (1u << i))) continue;
                gba_bus_write32(g, addr, r[i]);
                addr += 4;
            }
            if (extra) gba_bus_write32(g, addr, r[14]);
        }
        break;
    }
    case THUMB_OP_LDMSTM: {
        uint32_t rb = (op >> 8) & 7u, list = op & 0xFFu, addr = r[rb];
        if (list == 0) {
            if (op & (1u << 11)) arm_write_pc(g, gba_bus_read32(g, addr & ~3u));
            else gba_bus_write32(g, addr, r[15] + 2u);
            r[rb] = addr + 0x40u;
            break;
        }
        uint32_t new_base = addr + (uint32_t)__builtin_popcount(list) * 4u;
        if (op & (1u << 11)) {
            r[rb] = new_base;
            for (uint32_t i = 0; i < 8; i++) {
                if (!(list & (1u << i))) continue;
                r[i] = gba_bus_read32(g, addr & ~3u);
                addr += 4;
            }
            gba_bus_idle(g, 1);
        } else {
            bool first = true;
            for (uint32_t i = 0; i < 8; i++) {
                if (!(list & (1u << i))) continue;
                uint32_t v = (i == rb && !first) ? new_base : r[i];
                gba_bus_write32(g, addr, v);
                addr += 4;
                first = false;
            }
            r[rb] = new_base;
        }
        break;
    }
    case THUMB_OP_BCC:
        if (arm_cond((op >> 8) & 15u, c->cpsr))
            arm_write_pc(g, r[15] + (uint32_t)((int32_t)(int8_t)(op & 0xFFu) * 2));
        break;
    case THUMB_OP_SWI: arm_swi(g, op); break;
    case THUMB_OP_B:
        arm_write_pc(g, r[15] + (uint32_t)(((int32_t)(op << 21)) >> 20));
        break;
    case THUMB_OP_BL1:
        r[14] = r[15] + (uint32_t)(((int32_t)(op << 21)) >> 9);
        break;
    case THUMB_OP_BL2: {
        uint32_t next = r[15] - 2u;
        arm_write_pc(g, (r[14] + ((op & 0x7FFu) << 1)) & ~1u);
        r[14] = next | 1u;
        break;
    }
    default: arm_undefined(g); break;
    }
}

/* ---------------------------------------------------------------- paso */

void gba_arm_step(gba *g)
{
    gba_arm *c = &g->cpu;
    uint32_t op = c->pipe[0];
    c->pipe[0] = c->pipe[1];
    c->flushed = false;
    if (c->cpsr & ARM_T) {
        c->pipe[1] = gba_bus_fetch16(g, c->r[15]);
        thumb_execute(g, op & 0xFFFFu);
        if (!c->flushed) c->r[15] += 2;
    } else {
        c->pipe[1] = gba_bus_fetch32(g, c->r[15]);
        arm_execute(g, op);
        if (!c->flushed) c->r[15] += 4;
    }
    if (c->flushed) gba_arm_flush(g);
}
