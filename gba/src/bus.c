/*
 * bus.c — mapa de memoria del GBA (GBATEK §GBA Memory Map).
 * G1: regiones, espejos y rotaciones; 1 ciclo por acceso (los waitstates
 * de WAITCNT y el prefetch del cartucho llegan en G2). E/S mínima:
 * DISPSTAT/VCOUNT/KEYINPUT para que corran las pruebas de CPU.
 * Todo índice derivado de una dirección se acota con una máscara o se
 * compara con el tamaño real del ROM (regla dura 3).
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
    if (!code) t->nreads++;
    if (code) {
        if (addr == t->base_addr) return t->opcode;
        return size == 2 ? (addr & 0xFFFFu) : addr;
    }
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

static inline uint32_t vram_offset(uint32_t addr)
{
    uint32_t o = addr & 0x1FFFFu;
    return o >= 0x18000u ? o - 0x8000u : o;
}

/* E/S de 16 bits (G1: lo justo para las pruebas de CPU). */
static uint16_t io_read16(gba *g, uint32_t off)
{
    switch (off) {
    case 0x004: {
        uint16_t stat = (uint16_t)(g->io[4] | (g->io[5] << 8)) & 0xFF38u;
        if (g->vcount >= 160 && g->vcount < 227) stat |= 1u;
        if (g->line_cycles >= 960) stat |= 2u;
        if (g->vcount == (stat >> 8)) stat |= 4u;
        return stat;
    }
    case 0x006: return g->vcount;
    case 0x130: return (uint16_t)(~g->keys & 0x3FFu);
    default:
        return (uint16_t)(g->io[off] | (g->io[off + 1] << 8));
    }
}

static void io_write16(gba *g, uint32_t off, uint16_t v)
{
    if (off == 0x006 || off == 0x130) return;   /* solo lectura */
    g->io[off] = (uint8_t)v;
    g->io[off + 1] = (uint8_t)(v >> 8);
}

static uint16_t rom_open_bus(uint32_t addr)
{
    /* Más allá del ROM el cartucho devuelve la mitad baja de la dirección / 2. */
    return (uint16_t)(addr >> 1);
}

static inline uint32_t le32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t mem_read16(gba *g, uint32_t addr);

/* Lectura alineada de 32 bits con caminos directos para las regiones de código. */
static inline uint32_t mem_read32(gba *g, uint32_t addr)
{
    switch (addr >> 24) {
    case 0x2: return le32(&g->ewram[addr & 0x3FFFCu]);
    case 0x3: return le32(&g->iwram[addr & 0x7FFCu]);
    case 0x8: case 0x9: case 0xA: case 0xB: case 0xC: case 0xD: {
        uint32_t o = addr & 0x1FFFFFCu;
        if (g->rom && o + 3 < g->rom_size) return le32(&g->rom[o]);
        break;
    }
    default: break;
    }
    return mem_read16(g, addr) | ((uint32_t)mem_read16(g, addr + 2) << 16);
}

static uint16_t mem_read16(gba *g, uint32_t addr)
{
    switch (addr >> 24) {
    case 0x0:
        if (addr < GBA_BIOS_BYTES) {
            uint32_t o = addr & 0x3FFEu;
            return (uint16_t)(g->bios[o] | (g->bios[o + 1] << 8));
        }
        return 0;
    case 0x2: { uint32_t o = addr & 0x3FFFEu; return (uint16_t)(g->ewram[o] | (g->ewram[o + 1] << 8)); }
    case 0x3: { uint32_t o = addr & 0x7FFEu; return (uint16_t)(g->iwram[o] | (g->iwram[o + 1] << 8)); }
    case 0x4:
        if (addr < 0x04000400u) return io_read16(g, addr & 0x3FEu);
        return 0;
    case 0x5: { uint32_t o = addr & 0x3FEu; return (uint16_t)(g->pal[o] | (g->pal[o + 1] << 8)); }
    case 0x6: { uint32_t o = vram_offset(addr) & ~1u; return (uint16_t)(g->vram[o] | (g->vram[o + 1] << 8)); }
    case 0x7: { uint32_t o = addr & 0x3FEu; return (uint16_t)(g->oam[o] | (g->oam[o + 1] << 8)); }
    case 0x8: case 0x9: case 0xA: case 0xB: case 0xC: case 0xD: {
        uint32_t o = addr & 0x1FFFFFEu;
        if (g->rom && o + 1 < g->rom_size) return (uint16_t)(g->rom[o] | (g->rom[o + 1] << 8));
        return rom_open_bus(addr);
    }
    case 0xE: case 0xF: {
        uint8_t b = g->sram[addr & 0x7FFFu];
        return (uint16_t)(b * 0x0101u);
    }
    default:
        return 0;
    }
}

static void mem_write16(gba *g, uint32_t addr, uint16_t v)
{
    switch (addr >> 24) {
    case 0x2: { uint32_t o = addr & 0x3FFFEu; g->ewram[o] = (uint8_t)v; g->ewram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x3: { uint32_t o = addr & 0x7FFEu; g->iwram[o] = (uint8_t)v; g->iwram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x4: if (addr < 0x04000400u) io_write16(g, addr & 0x3FEu, v); break;
    case 0x5: { uint32_t o = addr & 0x3FEu; g->pal[o] = (uint8_t)v; g->pal[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x6: { uint32_t o = vram_offset(addr) & ~1u; g->vram[o] = (uint8_t)v; g->vram[o + 1] = (uint8_t)(v >> 8); break; }
    case 0x7: { uint32_t o = addr & 0x3FEu; g->oam[o] = (uint8_t)v; g->oam[o + 1] = (uint8_t)(v >> 8); break; }
    case 0xE: case 0xF: g->sram[addr & 0x7FFFu] = (uint8_t)(v >> ((addr & 1u) * 8u)); break;
    default: break;
    }
}

uint16_t gba_bus_read16(gba *g, uint32_t addr)
{
    TEST_READ(2, false);
    g->cycles++;
    return mem_read16(g, addr & ~1u);
}

uint32_t gba_bus_read32(gba *g, uint32_t addr)
{
    TEST_READ(4, false);
    g->cycles++;
    return mem_read32(g, addr & ~3u);
}

uint8_t gba_bus_read8(gba *g, uint32_t addr)
{
    TEST_READ(1, false);
    g->cycles++;
    if ((addr >> 24) == 0xE || (addr >> 24) == 0xF) return g->sram[addr & 0x7FFFu];
    return (uint8_t)(mem_read16(g, addr & ~1u) >> ((addr & 1u) * 8u));
}

void gba_bus_write32(gba *g, uint32_t addr, uint32_t v)
{
    TEST_WRITE(4);
    g->cycles++;
    addr &= ~3u;
    mem_write16(g, addr, (uint16_t)v);
    mem_write16(g, addr + 2, (uint16_t)(v >> 16));
}

void gba_bus_write16(gba *g, uint32_t addr, uint16_t v)
{
    TEST_WRITE(2);
    g->cycles++;
    mem_write16(g, addr & ~1u, v);
}

void gba_bus_write8(gba *g, uint32_t addr, uint8_t v)
{
    TEST_WRITE(1);
    g->cycles++;
    switch (addr >> 24) {
    case 0x2: g->ewram[addr & 0x3FFFFu] = v; break;
    case 0x3: g->iwram[addr & 0x7FFFu] = v; break;
    case 0x4:
        if (addr < 0x04000400u) {
            uint32_t off = addr & 0x3FEu;
            uint16_t cur = io_read16(g, off);
            uint16_t nv = (addr & 1u) ? (uint16_t)((cur & 0x00FFu) | (v << 8)) : (uint16_t)((cur & 0xFF00u) | v);
            io_write16(g, off, nv);
        }
        break;
    case 0x5: mem_write16(g, addr & ~1u, (uint16_t)(v * 0x0101u)); break;
    case 0x6:
        /* Byte en VRAM: se duplica en la media palabra (zona de fondos). */
        if (vram_offset(addr) < 0x10000u) mem_write16(g, addr & ~1u, (uint16_t)(v * 0x0101u));
        break;
    case 0xE: case 0xF: g->sram[addr & 0x7FFFu] = v; break;
    default: break;                       /* OAM ignora escrituras de 8 bits */
    }
}

uint32_t gba_bus_fetch32(gba *g, uint32_t addr)
{
    addr &= ~3u;
    TEST_READ(4, true);
    g->cycles++;
    return mem_read32(g, addr);
}

uint16_t gba_bus_fetch16(gba *g, uint32_t addr)
{
    addr &= ~1u;
    TEST_READ(2, true);
    g->cycles++;
    return mem_read16(g, addr & ~1u);
}

void gba_bus_idle(gba *g, uint32_t n)
{
    g->cycles += n;
}

/* G1: solo avanza VCOUNT y marca el final del frame (la PPU llega en G3). */
void gba_video_tick(gba *g, uint32_t n)
{
    g->line_cycles += n;
    while (g->line_cycles >= 1232u) {
        g->line_cycles -= 1232u;
        g->vcount++;
        if (g->vcount == 160) g->frame_done = true;
        if (g->vcount >= 228) g->vcount = 0;
    }
}
