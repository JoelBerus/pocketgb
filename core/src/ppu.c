/*
 * ppu.c — PPU mínima de M1: solo tiempos (docs/03-core-spec.md §PPU).
 *
 * 456 dots por línea y 154 líneas; modos 2 (80) → 3 (172) → 0 (204); modo 1 en
 * VBlank. LY, LYC, línea STAT con flanco de subida e IRQ de VBlank, para que las
 * pruebas que esperan VBlank no se cuelguen. El render por scanline llega en M2.
 */
#include "internal.h"

enum { DOTS_PER_LINE = 456, LINES = 154, VBLANK_LINE = 144, MODE2_END = 80, MODE3_END = 252 };
/* En la línea 144, el modo 1 y la IRQ de VBlank llegan 4 dots después de que
 * LY cambie (verificado con Mooneye di_timing-GS y halt_ime1_timing2-GS). */
enum { VBLANK_DELAY = 4 };

static void update_stat_line(gb *g)
{
    struct gb_ppu *p = &g->ppu;
    bool line = false;
    if (p->lcdc & 0x80) {
        line = ((p->stat & 0x40) && p->ly == p->lyc) ||
               ((p->stat & 0x08) && p->mode == 0) ||
               ((p->stat & 0x10) && p->mode == 1) ||
               ((p->stat & 0x20) && p->mode == 2);
    }
    if (line && !p->stat_line)
        g->mem.if_ |= IRQ_STAT;
    p->stat_line = line;
}

static uint8_t mode_for(const struct gb_ppu *p)
{
    if (p->ly == VBLANK_LINE && p->dot < VBLANK_DELAY)
        return 0;
    if (p->ly >= VBLANK_LINE)
        return 1;
    if (p->dot < MODE2_END)
        return 2;
    if (p->dot < MODE3_END)
        return 3;
    return 0;
}

static void clear_screen(gb *g)
{
    uint32_t white = g->opts.dmg_palette[0];
    for (size_t i = 0; i < GB_SCREEN_W * GB_SCREEN_H; i++)
        g->framebuffer[i] = white;
}

void ppu_reset(gb *g)
{
    struct gb_ppu *p = &g->ppu;
    p->lcdc = 0x91;
    p->stat = 0x00;     /* bits 3–6; el modo y la coincidencia se calculan */
    p->scy = p->scx = 0;
    p->lyc = 0;
    p->dma = 0xFF;
    p->bgp = 0xFC;
    p->obp0 = p->obp1 = 0xFF;
    p->wy = p->wx = 0;
    p->ly = 0;
    p->dot = 0;
    p->mode = mode_for(p);
    p->stat_line = false;
    p->frame_done = false;
    clear_screen(g);
}

/* Avanza `dots` (múltiplo de 4) de 4 en 4. */
void ppu_tick(gb *g, unsigned dots)
{
    struct gb_ppu *p = &g->ppu;
    if (!(p->lcdc & 0x80))
        return;
    for (; dots >= 4; dots -= 4) {
        p->dot = (uint16_t)(p->dot + 4);
        if (p->dot >= DOTS_PER_LINE) {
            p->dot = (uint16_t)(p->dot - DOTS_PER_LINE);
            p->ly = (uint8_t)((p->ly + 1) % LINES);
        }
        if (p->ly == VBLANK_LINE && p->dot == VBLANK_DELAY) {
            g->mem.if_ |= IRQ_VBLANK;
            p->frame_done = true;
        }
        p->mode = mode_for(p);
        update_stat_line(g);
    }
}

uint8_t ppu_read(const gb *g, uint16_t addr)
{
    const struct gb_ppu *p = &g->ppu;
    switch (addr) {
    case 0xFF40: return p->lcdc;
    case 0xFF41: {
        uint8_t v = (uint8_t)(0x80 | (p->stat & 0x78));
        if (p->lcdc & 0x80) {
            v |= p->mode;
            if (p->ly == p->lyc)
                v |= 0x04;
        }
        return v;
    }
    case 0xFF42: return p->scy;
    case 0xFF43: return p->scx;
    case 0xFF44: return p->ly;
    case 0xFF45: return p->lyc;
    case 0xFF46: return p->dma;
    case 0xFF47: return p->bgp;
    case 0xFF48: return p->obp0;
    case 0xFF49: return p->obp1;
    case 0xFF4A: return p->wy;
    case 0xFF4B: return p->wx;
    default:     return 0xFF;
    }
}

void ppu_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_ppu *p = &g->ppu;
    switch (addr) {
    case 0xFF40: {
        bool was_on = (p->lcdc & 0x80) != 0;
        p->lcdc = v;
        if (was_on && !(v & 0x80)) {
            p->ly = 0;
            p->dot = 0;
            p->mode = 0;
            p->stat_line = false;
            clear_screen(g);
        } else if (!was_on && (v & 0x80)) {
            p->ly = 0;
            p->dot = 0;
            p->mode = mode_for(p);
            update_stat_line(g);
        }
        break;
    }
    case 0xFF41: p->stat = v & 0x78; update_stat_line(g); break;
    case 0xFF42: p->scy = v; break;
    case 0xFF43: p->scx = v; break;
    case 0xFF44: break; /* solo lectura */
    case 0xFF45: p->lyc = v; update_stat_line(g); break;
    case 0xFF47: p->bgp = v; break;
    case 0xFF48: p->obp0 = v; break;
    case 0xFF49: p->obp1 = v; break;
    case 0xFF4A: p->wy = v; break;
    case 0xFF4B: p->wx = v; break;
    default: break; /* FF46 (DMA) lo gestiona mmu.c */
    }
}
