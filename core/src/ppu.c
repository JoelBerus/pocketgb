/*
 * ppu.c — PPU DMG por scanline (docs/03-core-spec.md §PPU).
 *
 * 456 dots por línea y 154 líneas; modos 2 (80) → 3 (172 + SCX%8 + objetos)
 * → 0; modo 1 en VBlank. La línea se renderiza entera al entrar en modo 0,
 * con los registros vigentes en ese momento (basta para dmg-acid2 y Pokémon).
 */
#include "internal.h"

enum { DOTS_PER_LINE = 456, LINES = 154, VBLANK_LINE = 144, MODE2_END = 80, MODE3_BASE = 172 };
/* En la línea 144, el modo 1 y la IRQ de VBlank llegan 4 dots después de que
 * LY cambie (verificado con Mooneye di_timing-GS y halt_ime1_timing2-GS). */
enum { VBLANK_DELAY = 4 };
/* Penalización simplificada del modo 3 por cada objeto de la línea. */
enum { OBJ_PENALTY = 6 };

static uint16_t next_event(const struct gb_ppu *p);

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
    if (p->dot < p->mode3_end)
        return 3;
    return 0;
}

static void clear_screen(gb *g)
{
    uint32_t white = g->opts.dmg_palette[0];
    for (size_t i = 0; i < GB_SCREEN_W * GB_SCREEN_H; i++)
        g->framebuffer[i] = white;
}

/* Comienzo de línea: latch de WY y reinicio del contador de ventana por frame. */
static void line_start(gb *g)
{
    struct gb_ppu *p = &g->ppu;
    p->mode3_end = MODE2_END + MODE3_BASE;
    p->obj_count = 0;
    if (p->ly == 0) {
        p->window_line = 0;
        p->wy_triggered = false;
    }
    if (p->ly < VBLANK_LINE && p->ly == p->wy)
        p->wy_triggered = true;
}

/* Búsqueda OAM (fin del modo 2): los 10 primeros objetos, en orden de OAM,
 * que cubren LY. Cuentan aunque su X los deje fuera de la pantalla. */
static void oam_scan(gb *g)
{
    struct gb_ppu *p = &g->ppu;
    unsigned height = (p->lcdc & 0x04) ? 16 : 8;
    unsigned line = p->ly + 16u;
    p->obj_height = (uint8_t)height;
    p->obj_count = 0;
    for (uint8_t i = 0; i < 40 && p->obj_count < PPU_MAX_OBJS; i++) {
        unsigned y = g->mem.oam[i * 4];
        if (line >= y && line < y + height)
            p->objs[p->obj_count++] = i;
    }
    p->mode3_end = (uint16_t)(MODE2_END + MODE3_BASE + (p->scx & 7) + OBJ_PENALTY * p->obj_count);
}

/* Los dos bytes (plano bajo y alto) de una fila de tile. `addr` = offset del tile en VRAM. */
static void tile_row(const gb *g, uint16_t addr, unsigned row, uint8_t *lo, uint8_t *hi)
{
    uint16_t a = (uint16_t)((addr + row * 2) & 0x1FFF);
    *lo = g->mem.vram[a];
    *hi = g->mem.vram[(a + 1) & 0x1FFF];
}

static uint8_t row_pixel(uint8_t lo, uint8_t hi, unsigned col)
{
    unsigned bit = 7 - col;
    return (uint8_t)(((hi >> bit) & 1) << 1 | ((lo >> bit) & 1));
}

/* Offset del tile de fondo/ventana según LCDC bit 4 (0x8000 sin signo / 0x8800 con signo). */
static uint16_t bg_tile_addr(uint8_t lcdc, uint8_t tile)
{
    if (lcdc & 0x10)
        return (uint16_t)(tile * 16);
    return (uint16_t)(0x1000 + (int8_t)tile * 16);
}

/* Rellena bg[x0..159] con una fila del mapa `map` empezando en la columna de mapa `mx`. */
static void draw_tiles(const gb *g, uint8_t lcdc, uint16_t map, unsigned my, unsigned mx,
                       unsigned x0, uint8_t *bg)
{
    uint8_t lo = 0, hi = 0;
    for (unsigned x = x0; x < GB_SCREEN_W; x++, mx++) {
        unsigned px = mx & 0xFF;
        if (x == x0 || (px & 7) == 0) {
            uint8_t tile = g->mem.vram[map + (my / 8) * 32 + px / 8];
            tile_row(g, bg_tile_addr(lcdc, tile), my & 7, &lo, &hi);
        }
        bg[x] = row_pixel(lo, hi, px & 7);
    }
}

static uint32_t shade(const gb *g, uint8_t palette, uint8_t idx)
{
    return g->opts.dmg_palette[(palette >> (idx * 2)) & 3];
}

static void render_line(gb *g)
{
    struct gb_ppu *p = &g->ppu;
    uint8_t lcdc = p->lcdc;
    uint32_t *out = g->framebuffer + (size_t)p->ly * GB_SCREEN_W;
    uint8_t bg[GB_SCREEN_W] = { 0 };

    /* Fondo y ventana: en DMG, LCDC bit 0 = 0 los deja en blanco (color 0). */
    if (lcdc & 0x01) {
        draw_tiles(g, lcdc, (lcdc & 0x08) ? 0x1C00 : 0x1800, (p->ly + p->scy) & 0xFF, p->scx, 0, bg);
        if ((lcdc & 0x20) && p->wy_triggered && p->wx <= 166) {
            int wx0 = (int)p->wx - 7;
            unsigned x0 = wx0 < 0 ? 0 : (unsigned)wx0;
            draw_tiles(g, lcdc, (lcdc & 0x40) ? 0x1C00 : 0x1800, p->window_line,
                       x0 - (unsigned)wx0, x0, bg);
            p->window_line++;
        }
    }
    uint32_t pal[4];
    for (uint8_t i = 0; i < 4; i++)
        pal[i] = shade(g, p->bgp, i);
    for (unsigned x = 0; x < GB_SCREEN_W; x++)
        out[x] = pal[bg[x]];

    if (!(lcdc & 0x02) || p->obj_count == 0)
        return;

    /* Prioridad DMG: menor X y, a igual X, menor índice OAM. Orden por inserción. */
    uint8_t order[PPU_MAX_OBJS];
    unsigned n = p->obj_count;
    for (unsigned i = 0; i < n; i++) {
        uint8_t o = p->objs[i];
        unsigned j = i;
        while (j > 0 && g->mem.oam[order[j - 1] * 4 + 1] > g->mem.oam[o * 4 + 1]) {
            order[j] = order[j - 1];
            j--;
        }
        order[j] = o;
    }

    /* Altura de la búsqueda OAM: si LCDC.2 u OAM (DMA) cambian durante el modo 3,
     * se descarta el objeto que ya no cubre la línea en vez de dibujar basura. */
    unsigned height = p->obj_height;
    bool claimed[GB_SCREEN_W] = { false };
    for (unsigned k = 0; k < n; k++) {
        const uint8_t *o = &g->mem.oam[order[k] * 4];
        int oy = (int)o[0] - 16, ox = (int)o[1] - 8;
        uint8_t tile = o[2], attr = o[3];
        unsigned row = (unsigned)(p->ly - oy);
        if (row >= height)
            continue;
        if (attr & 0x40)
            row = height - 1 - row;
        if (height == 16)
            tile &= 0xFE;
        uint8_t lo, hi;
        tile_row(g, (uint16_t)(tile * 16 + (row / 8) * 16), row & 7, &lo, &hi);
        uint8_t obp = (attr & 0x10) ? p->obp1 : p->obp0;
        for (unsigned col = 0; col < 8; col++) {
            int sx = ox + (int)col;
            if (sx < 0 || sx >= GB_SCREEN_W || claimed[sx])
                continue;
            uint8_t idx = row_pixel(lo, hi, (attr & 0x20) ? 7 - col : col);
            if (idx == 0)
                continue;       /* transparente: deja ver objetos de menor prioridad */
            claimed[sx] = true; /* el píxel es de este objeto aunque lo tape el fondo */
            if ((attr & 0x80) && bg[sx] != 0)
                continue;
            out[sx] = shade(g, obp, idx);
        }
    }
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
    line_start(g);
    p->mode = mode_for(p);
    p->next_event = next_event(p);
    p->stat_line = false;
    p->frame_done = false;
    clear_screen(g);
}

/* Próximo dot de la línea en que puede cambiar algo (modo, LY o IRQ). */
static uint16_t next_event(const struct gb_ppu *p)
{
    if (p->ly < VBLANK_LINE) {
        if (p->dot < MODE2_END)
            return MODE2_END;
        if (p->dot < p->mode3_end)
            return p->mode3_end;
    } else if (p->ly == VBLANK_LINE && p->dot < VBLANK_DELAY) {
        return VBLANK_DELAY;
    }
    return DOTS_PER_LINE;
}

/* Avanza `dots` (múltiplo de 4) de 4 en 4. Entre eventos solo cuenta dots:
 * la línea STAT no cambia si no cambian el modo, LY o los registros. */
void ppu_tick(gb *g, unsigned dots)
{
    struct gb_ppu *p = &g->ppu;
    if (!(p->lcdc & 0x80))
        return;
    for (; dots >= 4; dots -= 4) {
        p->dot = (uint16_t)(p->dot + 4);
        if (p->dot < p->next_event)
            continue;
        if (p->dot >= DOTS_PER_LINE) {
            p->dot = (uint16_t)(p->dot - DOTS_PER_LINE);
            p->ly = (uint8_t)((p->ly + 1) % LINES);
            line_start(g);
        }
        if (p->ly == VBLANK_LINE && p->dot == VBLANK_DELAY) {
            g->mem.if_ |= IRQ_VBLANK;
            p->frame_done = true;
        }
        uint8_t old = p->mode;
        if (old == 2 && p->dot >= MODE2_END)
            oam_scan(g);
        p->mode = mode_for(p);
        if (old == 3 && p->mode == 0)
            render_line(g);
        update_stat_line(g);
        p->next_event = next_event(p);
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
            /* LCD apagado: LY=0, modo 0 y pantalla en blanco. */
            p->ly = 0;
            p->dot = 0;
            p->mode = 0;
            p->stat_line = false;
            clear_screen(g);
        } else if (!was_on && (v & 0x80)) {
            p->ly = 0;
            p->dot = 0;
            line_start(g);
            p->mode = mode_for(p);
            p->next_event = next_event(p);
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
