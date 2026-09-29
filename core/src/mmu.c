/*
 * mmu.c — mapa de memoria y registros de E/S (docs/03-core-spec.md §Mapa de memoria).
 *
 * Estas funciones no consumen ciclos: la CPU hace gb_tick antes de cada acceso.
 * El bloqueo de VRAM/OAM por modo de la PPU llega en M2. El bloqueo del bus
 * durante el OAM DMA lo aplica la CPU (cpu.c), no estas funciones.
 */
#include <string.h>

#include "internal.h"

/* Bits que leen 1 en FF10–FF3F (Pan Docs, Sound registers). La wave RAM lee tal cual. */
static const uint8_t apu_read_mask[0x30] = {
    0x80, 0x3F, 0x00, 0xFF, 0xBF,                           /* NR10–NR14 */
    0xFF, 0x3F, 0x00, 0xFF, 0xBF,                           /* FF15, NR21–NR24 */
    0x7F, 0xFF, 0x9F, 0xFF, 0xBF,                           /* NR30–NR34 */
    0xFF, 0xFF, 0x00, 0x00, 0xBF,                           /* FF1F, NR41–NR44 */
    0x00, 0x00, 0x70,                                       /* NR50–NR52 */
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,   /* FF27–FF2F */
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0          /* FF30–FF3F */
};

/* Valores post-boot de FF10–FF26 en DMG (Pan Docs, Power Up Sequence). */
static const uint8_t apu_boot[0x17] = {
    0x80, 0xBF, 0xF3, 0xFF, 0xBF, 0xFF, 0x3F, 0x00, 0xFF, 0xBF, 0x7F, 0xFF,
    0x9F, 0xFF, 0xBF, 0xFF, 0xFF, 0x00, 0x00, 0xBF, 0x77, 0xF3, 0xF1
};

void mmu_reset(gb *g)
{
    struct gb_mem *m = &g->mem;
    memset(m->vram, 0, sizeof m->vram);
    memset(m->wram, 0, sizeof m->wram);
    memset(m->oam, 0, sizeof m->oam);
    memset(m->hram, 0, sizeof m->hram);
    memset(m->apu_regs, 0, sizeof m->apu_regs);
    memcpy(m->apu_regs, apu_boot, sizeof apu_boot);
    m->ie = 0x00;
    m->if_ = 0x01;   /* IF = 0xE1 */
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
        return (uint8_t)(g->mem.apu_regs[addr - 0xFF10] | apu_read_mask[addr - 0xFF10]);
    if (addr >= 0xFF40 && addr <= 0xFF4B)
        return ppu_read(g, addr);
    return 0xFF;
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
        g->mem.apu_regs[addr - 0xFF10] = v;
    } else if (addr == 0xFF46) {
        g->ppu.dma = v;
        dma_start(g, v);
    } else if (addr >= 0xFF40 && addr <= 0xFF4B) {
        ppu_write(g, addr, v);
    }
}

uint8_t mmu_read(gb *g, uint16_t addr)
{
    if (addr < 0x8000)
        return cart_rom_read(g, addr);
    if (addr < 0xA000)
        return g->mem.vram[addr - 0x8000];
    if (addr < 0xC000)
        return cart_ram_read(g, addr);
    if (addr < 0xE000)
        return g->mem.wram[addr - 0xC000];
    if (addr < 0xFE00)
        return g->mem.wram[addr - 0xE000];   /* eco de C000–DDFF */
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
        g->mem.vram[addr - 0x8000] = v;
    else if (addr < 0xC000)
        cart_ram_write(g, addr, v);
    else if (addr < 0xE000)
        g->mem.wram[addr - 0xC000] = v;
    else if (addr < 0xFE00)
        g->mem.wram[addr - 0xE000] = v;
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
