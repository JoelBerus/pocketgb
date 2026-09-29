/*
 * cpu.c — CPU SM83 (docs/03-core-spec.md §CPU).
 *
 * Modelo de tiempo: cada acceso a memoria cuesta 1 M-ciclo; primero se avanza
 * el resto del hardware (gb_tick 4 T-ciclos) y después se hace el acceso. Los
 * M-ciclos internos (sin acceso) se modelan con cpu_idle.
 */
#include "internal.h"

/* Accesos de la CPU que el hardware no deja pasar (leen 0xFF, no escriben):
 * - OAM DMA copiando: OAM y el bus del origen (VRAM o el externo: ROM/SRAM/WRAM);
 *   E/S y HRAM siguen accesibles (docs/03-core-spec.md §Mapa de memoria).
 * - PPU: VRAM en modo 3; OAM en modos 2 y 3. */
static inline bool on_vram_bus(uint16_t addr)
{
    return addr >= 0x8000 && addr < 0xA000;
}

static inline bool bus_blocked(const gb *g, uint16_t addr)
{
    if (addr >= 0xFF00 || (addr < 0x8000 && !g->dma.bus_busy))
        return false;   /* camino rápido: ROM y E/S/HRAM */
    if (g->dma.bus_busy) {
        if (addr >= 0xFE00 || on_vram_bus(addr) == on_vram_bus(g->dma.src))
            return true;
    }
    if (on_vram_bus(addr))
        return ppu_vram_blocked(g);
    if (addr >= 0xFE00 && addr < 0xFEA0)
        return ppu_oam_blocked(g);
    return false;
}

static inline uint8_t cpu_read(gb *g, uint16_t addr)
{
    gb_tick(g, 4);
    return bus_blocked(g, addr) ? 0xFF : mmu_read(g, addr);
}

static inline void cpu_write(gb *g, uint16_t addr, uint8_t v)
{
    gb_tick(g, 4);
    if (!bus_blocked(g, addr))
        mmu_write(g, addr, v);
}

static inline void cpu_idle(gb *g)
{
    gb_tick(g, 4);
}

static inline uint8_t fetch8(gb *g)
{
    uint8_t v = cpu_read(g, g->cpu.pc);
    g->cpu.pc++;
    return v;
}

static inline uint16_t fetch16(gb *g)
{
    uint8_t lo = fetch8(g);
    uint8_t hi = fetch8(g);
    return (uint16_t)(hi << 8 | lo);
}

static inline void push16(gb *g, uint16_t v)
{
    g->cpu.sp--;
    cpu_write(g, g->cpu.sp, (uint8_t)(v >> 8));
    g->cpu.sp--;
    cpu_write(g, g->cpu.sp, (uint8_t)v);
}

static inline uint16_t pop16(gb *g)
{
    uint8_t lo = cpu_read(g, g->cpu.sp++);
    uint8_t hi = cpu_read(g, g->cpu.sp++);
    return (uint16_t)(hi << 8 | lo);
}

/* ---- Registros ---- */
#define PAIR(hi, lo) ((uint16_t)((c->hi) << 8 | (c->lo)))
#define SET_PAIR(hi, lo, v) do { uint16_t v_ = (v); c->hi = (uint8_t)(v_ >> 8); c->lo = (uint8_t)v_; } while (0)

static inline uint16_t get_hl(const struct gb_cpu *c) { return PAIR(h, l); }
static inline void set_hl(struct gb_cpu *c, uint16_t v) { SET_PAIR(h, l, v); }

/* r8 por índice: 0 B, 1 C, 2 D, 3 E, 4 H, 5 L, 6 (HL), 7 A */
static inline uint8_t get_r8(gb *g, unsigned i)
{
    struct gb_cpu *c = &g->cpu;
    switch (i & 7) {
    case 0: return c->b;
    case 1: return c->c;
    case 2: return c->d;
    case 3: return c->e;
    case 4: return c->h;
    case 5: return c->l;
    case 6: return cpu_read(g, get_hl(c));
    default: return c->a;
    }
}

static inline void set_r8(gb *g, unsigned i, uint8_t v)
{
    struct gb_cpu *c = &g->cpu;
    switch (i & 7) {
    case 0: c->b = v; break;
    case 1: c->c = v; break;
    case 2: c->d = v; break;
    case 3: c->e = v; break;
    case 4: c->h = v; break;
    case 5: c->l = v; break;
    case 6: cpu_write(g, get_hl(c), v); break;
    default: c->a = v; break;
    }
}

/* rr por índice (grupo SP): 0 BC, 1 DE, 2 HL, 3 SP */
static inline uint16_t get_rr(const struct gb_cpu *c, unsigned i)
{
    switch (i & 3) {
    case 0: return PAIR(b, c);
    case 1: return PAIR(d, e);
    case 2: return PAIR(h, l);
    default: return c->sp;
    }
}

static inline void set_rr(struct gb_cpu *c, unsigned i, uint16_t v)
{
    switch (i & 3) {
    case 0: SET_PAIR(b, c, v); break;
    case 1: SET_PAIR(d, e, v); break;
    case 2: SET_PAIR(h, l, v); break;
    default: c->sp = v; break;
    }
}

/* ---- ALU ---- */
static inline uint8_t zf(uint8_t v) { return v ? 0 : FLAG_Z; }

/* op: 0 ADD, 1 ADC, 2 SUB, 3 SBC, 4 AND, 5 XOR, 6 OR, 7 CP */
static inline void alu(struct gb_cpu *c, unsigned op, uint8_t v)
{
    unsigned a = c->a, carry = (c->f & FLAG_C) ? 1u : 0u, r;
    switch (op & 7) {
    case 0: carry = 0; /* fallthrough */
    case 1:
        r = a + v + carry;
        c->f = (uint8_t)(zf((uint8_t)r) | (((a & 0xF) + (v & 0xF) + carry) > 0xF ? FLAG_H : 0) |
                         (r > 0xFF ? FLAG_C : 0));
        c->a = (uint8_t)r;
        break;
    case 2: case 7: carry = 0; /* fallthrough */
    case 3:
        r = a - v - carry;
        c->f = (uint8_t)(zf((uint8_t)r) | FLAG_N | ((a & 0xF) < (v & 0xF) + carry ? FLAG_H : 0) |
                         (a < (unsigned)v + carry ? FLAG_C : 0));
        if ((op & 7) != 7)
            c->a = (uint8_t)r;
        break;
    case 4: c->a &= v; c->f = (uint8_t)(zf(c->a) | FLAG_H); break;
    case 5: c->a ^= v; c->f = zf(c->a); break;
    default: c->a |= v; c->f = zf(c->a); break;
    }
}

static inline uint8_t inc8(struct gb_cpu *c, uint8_t v)
{
    uint8_t r = (uint8_t)(v + 1);
    c->f = (uint8_t)((c->f & FLAG_C) | zf(r) | ((v & 0xF) == 0xF ? FLAG_H : 0));
    return r;
}

static inline uint8_t dec8(struct gb_cpu *c, uint8_t v)
{
    uint8_t r = (uint8_t)(v - 1);
    c->f = (uint8_t)((c->f & FLAG_C) | zf(r) | FLAG_N | ((v & 0xF) == 0 ? FLAG_H : 0));
    return r;
}

static inline void add_hl(struct gb_cpu *c, uint16_t v)
{
    unsigned hl = get_hl(c), r = hl + v;
    c->f = (uint8_t)((c->f & FLAG_Z) | (((hl & 0xFFF) + (v & 0xFFF)) > 0xFFF ? FLAG_H : 0) |
                     (r > 0xFFFF ? FLAG_C : 0));
    set_hl(c, (uint16_t)r);
}

/* SP + e8 (ADD SP,e y LD HL,SP+e): H y C salen del byte bajo sin signo. */
static inline uint16_t sp_plus_e(struct gb_cpu *c, uint8_t e)
{
    unsigned sp = c->sp;
    c->f = (uint8_t)((((sp & 0xF) + (e & 0xF)) > 0xF ? FLAG_H : 0) |
                     (((sp & 0xFF) + e) > 0xFF ? FLAG_C : 0));
    return (uint16_t)(sp + (uint16_t)(int16_t)(int8_t)e);
}

static inline void daa(struct gb_cpu *c)
{
    unsigned a = c->a;
    uint8_t f = c->f;
    if (!(f & FLAG_N)) {
        if ((f & FLAG_C) || a > 0x99) {
            a += 0x60;
            f |= FLAG_C;
        }
        if ((f & FLAG_H) || (a & 0x0F) > 0x09)
            a += 0x06;
    } else {
        if (f & FLAG_C)
            a -= 0x60;
        if (f & FLAG_H)
            a -= 0x06;
    }
    c->a = (uint8_t)a;
    c->f = (uint8_t)(zf(c->a) | (f & (FLAG_N | FLAG_C)));
}

/* Rotaciones y desplazamientos del prefijo CB (op 0..7). */
static inline uint8_t rot(struct gb_cpu *c, unsigned op, uint8_t v)
{
    unsigned carry_in = (c->f & FLAG_C) ? 1u : 0u, out, r;
    switch (op & 7) {
    case 0: out = v >> 7; r = (unsigned)(v << 1) | out; break;          /* RLC */
    case 1: out = v & 1; r = (v >> 1) | (out << 7); break;              /* RRC */
    case 2: out = v >> 7; r = (unsigned)(v << 1) | carry_in; break;     /* RL */
    case 3: out = v & 1; r = (v >> 1) | (carry_in << 7); break;         /* RR */
    case 4: out = v >> 7; r = (unsigned)(v << 1); break;                /* SLA */
    case 5: out = v & 1; r = (v >> 1) | (v & 0x80); break;              /* SRA */
    case 6: out = 0; r = (unsigned)((v << 4) | (v >> 4)); break;        /* SWAP */
    default: out = v & 1; r = v >> 1; break;                            /* SRL */
    }
    c->f = (uint8_t)(zf((uint8_t)r) | (out ? FLAG_C : 0));
    return (uint8_t)r;
}

/* cc: 0 NZ, 1 Z, 2 NC, 3 C */
static inline bool cond(const struct gb_cpu *c, unsigned cc)
{
    switch (cc & 3) {
    case 0: return !(c->f & FLAG_Z);
    case 1: return (c->f & FLAG_Z) != 0;
    case 2: return !(c->f & FLAG_C);
    default: return (c->f & FLAG_C) != 0;
    }
}

static void execute_cb(gb *g)
{
    struct gb_cpu *c = &g->cpu;
    uint8_t op = fetch8(g);
    unsigned r = op & 7, n = (op >> 3) & 7;
    uint8_t v = get_r8(g, r);
    switch (op >> 6) {
    case 0: set_r8(g, r, rot(c, n, v)); break;
    case 1: /* BIT: sin escritura, (HL) cuesta 3 M-ciclos */
        c->f = (uint8_t)((c->f & FLAG_C) | FLAG_H | ((v >> n) & 1 ? 0 : FLAG_Z));
        break;
    case 2: set_r8(g, r, (uint8_t)(v & ~(1u << n))); break;
    default: set_r8(g, r, (uint8_t)(v | (1u << n))); break;
    }
}

/* Despacho de interrupción: 5 M-ciclos, el primero es el fetch descartado que
 * ya hizo cpu_step. El vector se elige tras escribir el byte alto de PC (si esa
 * escritura cambia IE, la interrupción puede anularse → PC=0). */
static void dispatch_interrupt(gb *g)
{
    struct gb_cpu *c = &g->cpu;
    c->ime = false;
    cpu_idle(g);
    uint16_t ret = c->pc;
    c->sp--;
    cpu_write(g, c->sp, (uint8_t)(ret >> 8));
    uint8_t pending = g->mem.ie & g->mem.if_ & 0x1F;
    c->pc = 0x0000;
    for (unsigned i = 0; i < 5; i++) {
        if (pending & (1u << i)) {
            g->mem.if_ &= (uint8_t)~(1u << i);
            c->pc = (uint16_t)(0x40 + 8 * i);
            break;
        }
    }
    c->sp--;
    cpu_write(g, c->sp, (uint8_t)ret);
    cpu_idle(g);
}

void cpu_reset(gb *g)
{
    struct gb_cpu *c = &g->cpu;
    /* Registros tras el arranque (docs/03-core-spec.md §Arranque) */
    if (!g->cgb.on) {           /* DMG rev. ABC */
        c->a = 0x01;
        c->f = g->cart.rom[0x14D] ? 0xB0 : 0x80;
        c->b = 0x00; c->c = 0x13;
        c->d = 0x00; c->e = 0xD8;
        c->h = 0x01; c->l = 0x4D;
    } else if (!g->cgb.compat) { /* CGB nativo */
        c->a = 0x11; c->f = 0x80;
        c->b = 0x00; c->c = 0x00;
        c->d = 0xFF; c->e = 0x56;
        c->h = 0x00; c->l = 0x0D;
    } else {                    /* CGB con un ROM DMG */
        bool nintendo;
        uint8_t sum = cgb_title_checksum(g, &nintendo);
        c->a = 0x11; c->f = 0x80;
        c->b = nintendo ? sum : 0x00; c->c = 0x00;
        c->d = 0x00; c->e = 0x08;
        bool logo = c->b == 0x43 || c->b == 0x58;
        c->h = logo ? 0x99 : 0x00; c->l = logo ? 0x1A : 0x7C;
    }
    c->sp = 0xFFFE;
    c->pc = 0x0100;
    c->ime = false;
    c->ei_pending = false;
    c->halted = false;
    c->halt_bug = false;
    c->stopped = false;
    c->locked = false;
}

void cpu_step(gb *g)
{
    struct gb_cpu *c = &g->cpu;

    if (c->locked || c->stopped) {
        cpu_idle(g);
        return;
    }
    if (g->cgb.stall) {         /* HDMA: la CPU espera mientras se copia */
        g->cgb.stall--;
        cpu_idle(g);
        return;
    }
    /* Las interrupciones se muestrean al final del M-ciclo de fetch: si hay una
     * pendiente con IME=1, el opcode leído se descarta y sigue el despacho.
     * En HALT la CPU repite ese fetch sin avanzar PC; al despertar con IME=0
     * ejecuta el opcode ya leído (sin M-ciclo extra). */
    uint8_t op = cpu_read(g, c->pc);
    bool pending = (g->mem.ie & g->mem.if_ & 0x1F) != 0;
    if (c->halted) {
        if (!pending)
            return;
        c->halted = false;
    }
    if (c->ime && pending) {
        dispatch_interrupt(g);
        return;
    }
    if (c->ei_pending) {
        c->ei_pending = false;
        c->ime = true;
    }
    if (c->halt_bug)
        c->halt_bug = false;   /* el byte se leerá otra vez */
    else
        c->pc++;

#include "cpu_ops.inc"
}
