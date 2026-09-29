/*
 * mmu.c — mapa de memoria y registros de E/S (docs/03-core-spec.md §Mapa de memoria).
 *
 * Estas funciones no consumen ciclos: la CPU hace gb_tick antes de cada acceso.
 * El bloqueo de VRAM/OAM por modo de la PPU y el del bus durante el OAM DMA
 * los aplica la CPU (cpu.c), no estas funciones. CGB: VRAM con 2 bancos (VBK),
 * WRAM con 8 (SVBK) y registros FF4C–FF7F en cgb.c.
 */
#include <string.h>

#include "internal.h"

void mmu_reset(gb *g)
{
    struct gb_mem *m = &g->mem;
    memset(m->vram, 0, sizeof m->vram);
    memset(m->wram, 0, sizeof m->wram);
    memset(m->oam, 0, sizeof m->oam);
    memset(m->hram, 0, sizeof m->hram);
    m->ie = 0x00;
    m->if_ = 0x01;   /* IF = 0xE1 */
    m->vbk = 0;
    m->svbk = 0;
    m->wram_bank = 1;
}

/* WRAM: C000–CFFF banco 0; D000–DFFF el banco de SVBK (1 en DMG). `off` < 0x2000. */
static inline uint8_t *wram_ptr(gb *g, uint16_t off)
{
    if (off < 0x1000)
        return &g->mem.wram[off];
    return &g->mem.wram[(uint32_t)(g->mem.wram_bank & 7) * 0x1000 + (off - 0x1000)];
}

static inline uint8_t *vram_ptr(gb *g, uint16_t off)
{
    return &g->mem.vram[(g->mem.vbk & 1) * 0x2000 + (off & 0x1FFF)];
}

static uint8_t io_read(gb *g, uint16_t addr)
{
    if (addr == 0xFF00)
        return joypad_read(g);
    if (addr == 0xFF01 || addr == 0xFF02)
        return serial_read(g, addr);
    if (addr >= 0xFF04 && addr <= 0xFF07)
        return timer_read(g, addr);
    if (addr == 0xFF0F)
        return (uint8_t)(0xE0 | g->mem.if_);
    if (addr >= 0xFF10 && addr <= 0xFF3F)
        return apu_read(g, addr);
    if (addr >= 0xFF40 && addr <= 0xFF4B)
        return ppu_read(g, addr);
    return cgb_io_read(g, addr);   /* FF4C–FF7F: 0xFF salvo en CGB */
}

static void io_write(gb *g, uint16_t addr, uint8_t v)
{
    if (addr == 0xFF00) {
        joypad_write(g, v);
    } else if (addr == 0xFF01 || addr == 0xFF02) {
        serial_write(g, addr, v);
    } else if (addr >= 0xFF04 && addr <= 0xFF07) {
        timer_write(g, addr, v);
    } else if (addr == 0xFF0F) {
        g->mem.if_ = v & 0x1F;
    } else if (addr >= 0xFF10 && addr <= 0xFF3F) {
        apu_write(g, addr, v);
    } else if (addr == 0xFF46) {
        g->ppu.dma = v;
        dma_start(g, v);
    } else if (addr >= 0xFF40 && addr <= 0xFF4B) {
        ppu_write(g, addr, v);
    } else {
        cgb_io_write(g, addr, v);
    }
}

uint8_t mmu_read(gb *g, uint16_t addr)
{
    if (addr < 0x8000)
        return cart_rom_read(g, addr);
    if (addr < 0xA000)
        return *vram_ptr(g, (uint16_t)(addr - 0x8000));
    if (addr < 0xC000)
        return cart_ram_read(g, addr);
    if (addr < 0xE000)
        return *wram_ptr(g, (uint16_t)(addr - 0xC000));
    if (addr < 0xFE00)
        return *wram_ptr(g, (uint16_t)(addr - 0xE000));   /* eco de C000–DDFF */
    if (addr < 0xFEA0)
        return g->mem.oam[addr - 0xFE00];
    if (addr < 0xFF00)
        return 0x00;                          /* no usable (DMG) */
    if (addr < 0xFF80)
        return io_read(g, addr);
    if (addr < 0xFFFF)
        return g->mem.hram[addr - 0xFF80];
    return g->mem.ie;
}

void mmu_write(gb *g, uint16_t addr, uint8_t v)
{
    if (addr < 0x8000)
        cart_rom_write(g, addr, v);
    else if (addr < 0xA000)
        *vram_ptr(g, (uint16_t)(addr - 0x8000)) = v;
    else if (addr < 0xC000)
        cart_ram_write(g, addr, v);
    else if (addr < 0xE000)
        *wram_ptr(g, (uint16_t)(addr - 0xC000)) = v;
    else if (addr < 0xFE00)
        *wram_ptr(g, (uint16_t)(addr - 0xE000)) = v;
    else if (addr < 0xFEA0)
        g->mem.oam[addr - 0xFE00] = v;
    else if (addr < 0xFF00)
        return;
    else if (addr < 0xFF80)
        io_write(g, addr, v);
    else if (addr < 0xFFFF)
        g->mem.hram[addr - 0xFF80] = v;
    else
        g->mem.ie = v;
}
