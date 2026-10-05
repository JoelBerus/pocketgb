/*
 * bus.c — mapa de memoria y tiempos de acceso del GBA (GBATEK §GBA Memory
 * Map, §Memory Control / WAITCNT, §Unpredictable Things / Open bus).
 * Todo índice derivado de una dirección se acota con una máscara o se compara
 * con el tamaño real del ROM (regla dura 3).
 *
 * Tiempos: cada acceso cuesta los ciclos de su región (tabla ws_* recalculada
 * al escribir WAITCNT), secuencial (S) o no secuencial (N). Las lecturas de
 * código son S si siguen a la anterior; con el prefetch del cartucho activo
 * (WAITCNT bit 14) una lectura S de código en el ROM cuesta 1 ciclo
 * (aproximación del búfer de prefetch, ver docs/10-gba-spec.md).
 */
#include "internal.h"

#ifdef GBA_TEST_HOOKS
/* SingleStepTests: las lecturas de datos se responden con las transacciones
 * grabadas; las de código, con el opcode en base_addr o la propia dirección. */
static uint32_t test_read(gba *g, uint32_t addr, uint32_t size, bool code)
{
    gba_test_bus *t = &g->test;
    for (uint32_t i = 0; i < t->ntxn; i++) {
        gba_test_txn *x = &t->txn[i];
        if (x->kind == (code ? 0u : 1u) && x->size == size && (x->addr & ~(size - 1u)) == addr) {
            if (!code) t->nreads++;
            return x->data;
        }
    }
    if (code) {
        if (addr == t->base_addr) return t->opcode;
        return size == 2 ? (addr & 0xFFFFu) : addr;
    }
    t->nreads++;
    t->missing_read = true;
    return 0;
}

static void test_write(gba *g, uint32_t addr, uint32_t size, uint32_t v)
{
    gba_test_bus *t = &g->test;
    if (t->nwrites < GBA_TEST_MAX_TXN) {
        gba_test_txn *x = &t->writes[t->nwrites++];
        x->kind = 2; x->size = size; x->addr = addr; x->data = v;
    }
}
#define TEST_READ(sz, code) do { if (g->test.active) return (test_read(g, addr, (sz), (code))); } while (0)
#define TEST_WRITE(sz) do { if (g->test.active) { test_write(g, addr, (sz), v); return; } } while (0)
#else
#define TEST_READ(sz, code) do { } while (0)
#define TEST_WRITE(sz) do { } while (0)
#endif

static inline uint32_t le32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline uint32_t vram_offset(uint32_t addr)
{
    uint32_t o = addr & 0x1FFFFu;
    return o >= 0x18000u ? o - 0x8000u : o;
}

/* ------------------------------------------------------------ tiempos */

static inline void charge(gba *g, uint32_t addr, bool wide, bool seq)
{
    unsigned r = (addr >> 24) & 15u;
    if (addr >> 28) r = 1;                        /* fuera del mapa: 1 ciclo */
    g->cycles += wide ? (seq ? g->ws_s32[r] : g->ws_n32[r]) : (seq ? g->ws_s16[r] : g->ws_n16[r]);
    g->last_was_fetch = false;
}

static inline void charge_fetch(gba *g, uint32_t addr, bool wide, bool force_n)
{
    unsigned r = (addr >> 24) & 15u;
    if (addr >> 28) r = 1;
    bool seq = !force_n && g->last_was_fetch && addr == g->last_fetch_addr + (wide ? 4u : 2u);
    if (seq && r >= 8 && r <= 13 && (g->waitcnt & 0x4000u)) g->cycles += wide ? 2u : 1u;
    else g->cycles += wide ? (seq ? g->ws_s32[r] : g->ws_n32[r]) : (seq ? g->ws_s16[r] : g->ws_n16[r]);
    g->last_fetch_addr = addr;
    g->last_was_fetch = true;
}

/* ------------------------------------------------------------ bus abierto */

static uint32_t open_bus(const gba *g)
{
    if (g->dma_active) return g->dma[0].latch;   /* aproximación: último dato de DMA */
    const gba_arm *c = &g->cpu;
    if (c->cpsr & ARM_T) {
        uint32_t h = c->pipe[1] & 0xFFFFu;
        return h | (h << 16);
    }
    return c->pipe[1];
}

static inline bool pc_in_bios(const gba *g)
{
    return g->cpu.r[15] < GBA_BIOS_BYTES + 8u;
}

/* ------------------------------------------------------------ lectura */

static uint16_t mem_read16(gba *g, uint32_t addr);

static uint32_t mem_read32(gba *g, uint32_t addr)
{
    switch (addr >> 24) {
    case 0x0:
        if (addr < GBA_BIOS_BYTES) return pc_in_bios(g) ? le32(&g->bios[addr & 0x3FFCu]) : g->bios_last;
        return open_bus(g);
    case 0x2: return le32(&g->ewram[addr & 0x3FFFCu]);
    case 0x3: return le32(&g->iwram[addr & 0x7FFCu]);
    case 0x5: return le32(&g->pal[addr & 0x3FCu]);
    case 0x6: return le32(&g->vram[vram_offset(addr) & ~3u]);
    case 0x7: return le32(&g->oam[addr & 0x3FCu]);
    case 0x8: case 0x9: case 0xA: case 0xB: case 0xC: case 0xD: {
        uint32_t o = addr & 0x1FFFFFCu;
        if (g->rom && o + 3 < g->rom_size) return le32(&g->rom[o]);
        break;
    }
    case 0x1:
        return open_bus(g);
    default:
        if (addr >> 28) return open_bus(g);
        break;
    }
    return mem_read16(g, addr) | ((uint32_t)mem_read16(g, addr + 2) << 16);
}

static uint16_t mem_read16(gba *g, uint32_t addr)
{
    switch (addr >> 24) {
    case 0x0:
        if (addr < GBA_BIOS_BYTES) {
            uint32_t w = pc_in_bios(g) ? le32(&g->bios[addr & 0x3FFCu]) : g->bios_last;
            return (uint16_t)(w >> ((addr & 2u) * 8u));
        }
        return (uint16_t)(open_bus(g) >> ((addr & 2u) * 8u));
    case 0x2: { uint32_t o = addr & 0x3FFFEu; return (uint16_t)(g->ewram[o] | (g->ewram[o + 1] << 8)); }
    case 0x3: { uint32_t o = addr & 0x7FFEu; return (uint16_t)(g->iwram[o] | (g->iwram[o + 1] << 8)); }
    case 0x4:
        if (addr < 0x04000400u) return gba_io_read16(g, addr & 0x3FEu);
        return (uint16_t)(open_bus(g) >> ((addr & 2u) * 8u));
    case 0x5: { uint32_t o = addr & 0x3FEu; return (uint16_t)(g->pal[o] | (g->pal[o + 1] << 8)); }
    case 0x6: { uint32_t o = vram_offset(addr) & ~1u; return (uint16_t)(g->vram[o] | (g->vram[o + 1] << 8)); }
    case 0x7: { uint32_t o = addr & 0x3FEu; return (uint16_t)(g->oam[o] | (g->oam[o + 1] << 8)); }
    case 0x8: case 0x9: case 0xA: case 0xB: case 0xC: case 0xD: {
        uint32_t o = addr & 0x1FFFFFEu;
        if (g->rom && o + 1 < g->rom_size) return (uint16_t)(g->rom[o] | (g->rom[o + 1] << 8));
        return (uint16_t)(addr >> 1);             /* bus abierto del cartucho */
    }
    case 0xE: case 0xF: {
        uint8_t b = g->sram[addr & 0x7FFFu];
        return (uint16_t)(b * 0x0101u);
    }
    default:
        return (uint16_t)(open_bus(g) >> ((addr & 2u) * 8u));
    }
}

/* ------------------------------------------------------------ escritura */

static void mem_write16(gba *g, uint32_t addr, uint16_t v)
{
    switch (addr >> 24) {
    case 0x2: { uint32_t o = addr & 0x3FFFEu; g->ewram[o] = (uint8_t)v; g->ewram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x3: { uint32_t o = addr & 0x7FFEu; g->iwram[o] = (uint8_t)v; g->iwram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x4: if (addr < 0x04000400u) gba_io_write16(g, addr & 0x3FEu, v); break;
    case 0x5: { uint32_t o = addr & 0x3FEu; g->pal[o] = (uint8_t)v; g->pal[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x6: { uint32_t o = vram_offset(addr) & ~1u; g->vram[o] = (uint8_t)v; g->vram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x7: { uint32_t o = addr & 0x3FEu; g->oam[o] = (uint8_t)v; g->oam[o + 1] = (uint8_t)(v >> 8); break; }
    case 0xE: case 0xF: g->sram[addr & 0x7FFFu] = (uint8_t)(v >> ((addr & 1u) * 8u)); break;
    default: break;
    }
}

/* ------------------------------------------------------------ API del bus */

uint32_t gba_bus_read32(gba *g, uint32_t addr)
{
    TEST_READ(4, false);
    addr &= ~3u;
    charge(g, addr, true, g->cpu.seq);
    uint32_t v = mem_read32(g, addr);
    g->open_bus = v;
    return v;
}

uint16_t gba_bus_read16(gba *g, uint32_t addr)
{
    TEST_READ(2, false);
    addr &= ~1u;
    charge(g, addr, false, g->cpu.seq);
    return mem_read16(g, addr);
}

uint8_t gba_bus_read8(gba *g, uint32_t addr)
{
    TEST_READ(1, false);
    charge(g, addr, false, false);
    if ((addr >> 24) == 0xE || (addr >> 24) == 0xF) return g->sram[addr & 0x7FFFu];
    return (uint8_t)(mem_read16(g, addr & ~1u) >> ((addr & 1u) * 8u));
}

void gba_bus_write32(gba *g, uint32_t addr, uint32_t v)
{
    TEST_WRITE(4);
    addr &= ~3u;
    charge(g, addr, true, g->cpu.seq);
    if ((addr >> 24) == 0x4 && addr < 0x04000400u) {
        /* E/S: primero la media palabra baja (p. ej. DMA SAD antes que CNT). */
        gba_io_write16(g, addr & 0x3FEu, (uint16_t)v);
        gba_io_write16(g, (addr + 2) & 0x3FEu, (uint16_t)(v >> 16));
        return;
    }
    if ((addr >> 24) == 0xE || (addr >> 24) == 0xF) {
        g->sram[addr & 0x7FFFu] = (uint8_t)v;
        return;
    }
    mem_write16(g, addr, (uint16_t)v);
    mem_write16(g, addr + 2, (uint16_t)(v >> 16));
}

void gba_bus_write16(gba *g, uint32_t addr, uint16_t v)
{
    TEST_WRITE(2);
    addr &= ~1u;
    charge(g, addr, false, g->cpu.seq);
    mem_write16(g, addr, v);
}

void gba_bus_write8(gba *g, uint32_t addr, uint8_t v)
{
    TEST_WRITE(1);
    charge(g, addr, false, false);
    switch (addr >> 24) {
    case 0x2: g->ewram[addr & 0x3FFFFu] = v; break;
    case 0x3: g->iwram[addr & 0x7FFFu] = v; break;
    case 0x4: if (addr < 0x04000400u) gba_io_write8(g, addr & 0x3FFu, v); break;
    case 0x5: mem_write16(g, addr & ~1u, (uint16_t)(v * 0x0101u)); break;
    case 0x6:
        /* Byte en VRAM: se duplica en la media palabra solo en la zona de fondos
         * (64 KiB en modos 0-2; 80 KiB en los modos bitmap 3-5). */
        if (vram_offset(addr) < ((g->io[0] & 7u) >= 3 ? 0x14000u : 0x10000u))
            mem_write16(g, addr & ~1u, (uint16_t)(v * 0x0101u));
        break;
    case 0xE: case 0xF: g->sram[addr & 0x7FFFu] = v; break;
    default: break;                       /* OAM ignora escrituras de 8 bits */
    }
}

uint32_t gba_bus_fetch32(gba *g, uint32_t addr)
{
    addr &= ~3u;
    TEST_READ(4, true);
    charge_fetch(g, addr, true, false);
    if (addr < GBA_BIOS_BYTES) return g->bios_last = le32(&g->bios[addr]);
    return mem_read32(g, addr);
}

uint16_t gba_bus_fetch16(gba *g, uint32_t addr)
{
    addr &= ~1u;
    TEST_READ(2, true);
    charge_fetch(g, addr, false, false);
    if (addr < GBA_BIOS_BYTES) {
        g->bios_last = le32(&g->bios[addr & ~3u]);
        return (uint16_t)(g->bios_last >> ((addr & 2u) * 8u));
    }
    return mem_read16(g, addr);
}

uint32_t gba_bus_fetch32_n(gba *g, uint32_t addr)
{
    addr &= ~3u;
    TEST_READ(4, true);
    charge_fetch(g, addr, true, true);
    if (addr < GBA_BIOS_BYTES) return g->bios_last = le32(&g->bios[addr]);
    return mem_read32(g, addr);
}

uint16_t gba_bus_fetch16_n(gba *g, uint32_t addr)
{
    addr &= ~1u;
    TEST_READ(2, true);
    charge_fetch(g, addr, false, true);
    if (addr < GBA_BIOS_BYTES) {
        g->bios_last = le32(&g->bios[addr & ~3u]);
        return (uint16_t)(g->bios_last >> ((addr & 2u) * 8u));
    }
    return mem_read16(g, addr);
}

void gba_bus_idle(gba *g, uint32_t n)
{
    g->cycles += n;
    g->last_was_fetch = false;
}
