/*
 * ppu.c — PPU del GBA por scanline (GBATEK §LCD Video Controller): modos
 * 0–5, fondos de texto y afines, bitmaps, objetos normales y afines,
 * ventanas 0/1/OBJ, mosaico, mezcla alfa, aclarar y oscurecer. Código propio.
 *
 * Cada línea visible se dibuja al empezar su HBlank con los registros de ese
 * momento (los cambios hechos en el HBlank anterior, por IRQ o DMA, ya
 * cuentan). No se emulan los cambios a mitad de línea.
 */
#include "internal.h"

#define TRANSPARENT 0x8000u

static inline uint16_t rd16(const uint8_t *m, uint32_t o) { return (uint16_t)(m[o] | (m[o + 1] << 8)); }
static inline uint16_t ioreg(const gba *g, uint32_t off) { return rd16(g->io, off); }
static inline uint16_t pal16(const gba *g, uint32_t idx) { return rd16(g->pal, (idx & 0x1FFu) * 2u) & 0x7FFFu; }

static inline int32_t sext28(uint32_t v) { return (int32_t)(v << 4) >> 4; }

void gba_ppu_reload_ref(gba *g, unsigned bg)
{
    unsigned i = bg - 2u;
    uint32_t base = bg == 2 ? 0x28u : 0x38u;
    g->ppu.ref_x[i] = sext28((uint32_t)ioreg(g, base) | ((uint32_t)ioreg(g, base + 2) << 16));
    g->ppu.ref_y[i] = sext28((uint32_t)ioreg(g, base + 4) | ((uint32_t)ioreg(g, base + 6) << 16));
}

void gba_ppu_vblank(gba *g)
{
    gba_ppu_reload_ref(g, 2);
    gba_ppu_reload_ref(g, 3);
}

/* ------------------------------------------------------------ fondos */

static void render_text_bg(gba *g, unsigned n, unsigned line, uint16_t *out)
{
    uint16_t cnt = ioreg(g, 0x08u + n * 2u);
    uint32_t char_base = ((cnt >> 2) & 3u) * 0x4000u;
    uint32_t screen_base = ((cnt >> 8) & 31u) * 0x800u;
    bool c256 = cnt & 0x80u;
    unsigned size = cnt >> 14;
    unsigned w = (size & 1u) ? 512u : 256u, h = (size & 2u) ? 512u : 256u;
    unsigned hofs = ioreg(g, 0x10u + n * 4u) & 0x1FFu, vofs = ioreg(g, 0x12u + n * 4u) & 0x1FFu;
    uint16_t mosaic = ioreg(g, 0x4C);
    unsigned mh = (cnt & 0x40u) ? (mosaic & 15u) + 1u : 1u, mv = (cnt & 0x40u) ? ((mosaic >> 4) & 15u) + 1u : 1u;
    unsigned y = line - line % mv;
    unsigned py = (y + vofs) & (h - 1u);
    if (mh == 1) {
        /* Camino rápido: una lectura de mapa por tesela, 8 píxeles por vuelta. */
        unsigned x = 0;
        while (x < GBA_SCREEN_W) {
            unsigned px = (x + hofs) & (w - 1u);
            unsigned block = (px >> 8) + ((py >> 8) * (w >> 8));
            uint32_t map = screen_base + block * 0x800u + ((py & 255u) >> 3) * 64u + ((px & 255u) >> 3) * 2u;
            unsigned run = 8u - (px & 7u);
            if (run > GBA_SCREEN_W - x) run = GBA_SCREEN_W - x;
            if (map >= 0x10000u) {
                for (unsigned k = 0; k < run; k++) out[x + k] = TRANSPARENT;
                x += run;
                continue;
            }
            uint16_t e = rd16(g->vram, map);
            unsigned ty = (e & 0x800u) ? 7u - (py & 7u) : (py & 7u);
            uint32_t tile = e & 0x3FFu;
            bool hf = e & 0x400u;
            if (c256) {
                uint32_t row = char_base + tile * 64u + ty * 8u;
                for (unsigned k = 0; k < run; k++) {
                    unsigned tx = (px + k) & 7u;
                    if (hf) tx = 7u - tx;
                    uint32_t a = row + tx;
                    unsigned idx = a < 0x10000u ? g->vram[a] : 0;
                    out[x + k] = idx ? pal16(g, idx) : TRANSPARENT;
                }
            } else {
                uint32_t row = char_base + tile * 32u + ty * 4u;
                unsigned pbase = (e >> 12) * 16u;
                if (row + 3u >= 0x10000u) {
                    for (unsigned k = 0; k < run; k++) out[x + k] = TRANSPARENT;
                } else {
                    uint32_t bits = (uint32_t)g->vram[row] | ((uint32_t)g->vram[row + 1] << 8) |
                                    ((uint32_t)g->vram[row + 2] << 16) | ((uint32_t)g->vram[row + 3] << 24);
                    for (unsigned k = 0; k < run; k++) {
                        unsigned tx = (px + k) & 7u;
                        if (hf) tx = 7u - tx;
                        unsigned idx = (bits >> (tx * 4u)) & 15u;
                        out[x + k] = idx ? pal16(g, pbase + idx) : TRANSPARENT;
                    }
                }
            }
            x += run;
        }
        return;
    }
    for (unsigned x = 0; x < GBA_SCREEN_W; x++) {
        unsigned sx = x - x % mh;
        unsigned px = (sx + hofs) & (w - 1u);
        unsigned block = (px >> 8) + ((py >> 8) * (w >> 8));
        uint32_t map = screen_base + block * 0x800u + ((py & 255u) >> 3) * 64u + ((px & 255u) >> 3) * 2u;
        if (map >= 0x10000u) { out[x] = TRANSPARENT; continue; }
        uint16_t e = rd16(g->vram, map);
        unsigned tx = px & 7u, ty = py & 7u;
        if (e & 0x400u) tx = 7u - tx;
        if (e & 0x800u) ty = 7u - ty;
        uint32_t tile = e & 0x3FFu;
        unsigned idx;
        if (c256) {
            uint32_t a = char_base + tile * 64u + ty * 8u + tx;
            if (a >= 0x10000u) { out[x] = TRANSPARENT; continue; }
            idx = g->vram[a];
        } else {
            uint32_t a = char_base + tile * 32u + ty * 4u + (tx >> 1);
            if (a >= 0x10000u) { out[x] = TRANSPARENT; continue; }
            idx = (g->vram[a] >> ((tx & 1u) * 4u)) & 15u;
            if (idx) idx += (e >> 12) * 16u;
        }
        out[x] = idx ? pal16(g, idx) : TRANSPARENT;
    }
}

static void render_affine_bg(gba *g, unsigned n, unsigned line, uint16_t *out)
{
    uint16_t cnt = ioreg(g, 0x08u + n * 2u);
    uint32_t char_base = ((cnt >> 2) & 3u) * 0x4000u;
    uint32_t screen_base = ((cnt >> 8) & 31u) * 0x800u;
    unsigned size = 128u << (cnt >> 14);
    bool wrap = cnt & 0x2000u;
    uint32_t base = n == 2 ? 0x20u : 0x30u;
    int32_t pa = (int16_t)ioreg(g, base), pc = (int16_t)ioreg(g, base + 4);
    int32_t rx = g->ppu.ref_x[n - 2], ry = g->ppu.ref_y[n - 2];
    uint16_t mosaic = ioreg(g, 0x4C);
    unsigned mh = (cnt & 0x40u) ? (mosaic & 15u) + 1u : 1u;
    (void)line;
    for (unsigned x = 0; x < GBA_SCREEN_W; x++) {
        unsigned sx = x - x % mh;
        int32_t tx = (rx + pa * (int32_t)sx) >> 8, ty = (ry + pc * (int32_t)sx) >> 8;
        if (wrap) {
            tx &= (int32_t)size - 1;
            ty &= (int32_t)size - 1;
        } else if (tx < 0 || ty < 0 || tx >= (int32_t)size || ty >= (int32_t)size) {
            out[x] = TRANSPARENT;
            continue;
        }
        uint32_t map = screen_base + ((uint32_t)ty >> 3) * (size >> 3) + ((uint32_t)tx >> 3);
        if (map >= 0x10000u) { out[x] = TRANSPARENT; continue; }
        uint32_t a = char_base + (uint32_t)g->vram[map] * 64u + ((uint32_t)ty & 7u) * 8u + ((uint32_t)tx & 7u);
        if (a >= 0x10000u) { out[x] = TRANSPARENT; continue; }
        unsigned idx = g->vram[a];
        out[x] = idx ? pal16(g, idx) : TRANSPARENT;
    }
}

static void render_bitmap_bg(gba *g, unsigned mode, uint16_t *out)
{
    uint16_t cnt = ioreg(g, 0x0C);
    int32_t pa = (int16_t)ioreg(g, 0x20), pc = (int16_t)ioreg(g, 0x24);
    int32_t rx = g->ppu.ref_x[0], ry = g->ppu.ref_y[0];
    uint32_t page = (ioreg(g, 0) & 0x10u) ? 0xA000u : 0;
    unsigned w = mode == 5 ? 160u : 240u, h = mode == 5 ? 128u : 160u;
    uint16_t mosaic = ioreg(g, 0x4C);
    unsigned mh = (cnt & 0x40u) ? (mosaic & 15u) + 1u : 1u;
    for (unsigned x = 0; x < GBA_SCREEN_W; x++) {
        unsigned sx = x - x % mh;
        int32_t tx = (rx + pa * (int32_t)sx) >> 8, ty = (ry + pc * (int32_t)sx) >> 8;
        if (tx < 0 || ty < 0 || tx >= (int32_t)w || ty >= (int32_t)h) { out[x] = TRANSPARENT; continue; }
        uint32_t i = (uint32_t)ty * w + (uint32_t)tx;
        if (mode == 3) {
            out[x] = rd16(g->vram, i * 2u) & 0x7FFFu;
        } else if (mode == 4) {
            unsigned idx = g->vram[page + i];
            out[x] = idx ? pal16(g, idx) : TRANSPARENT;
        } else {
            out[x] = rd16(g->vram, page + i * 2u) & 0x7FFFu;
        }
    }
}

/* ------------------------------------------------------------ objetos */

static const uint8_t obj_w[3][4] = {{8, 16, 32, 64}, {16, 32, 32, 64}, {8, 8, 16, 32}};
static const uint8_t obj_h[3][4] = {{8, 16, 32, 64}, {8, 8, 16, 32}, {16, 32, 32, 64}};

static void render_objs(gba *g, unsigned line)
{
    gba_ppu *p = &g->ppu;
    for (unsigned x = 0; x < GBA_SCREEN_W; x++) {
        p->obj[x] = TRANSPARENT;
        p->obj_prio[x] = 4;
        p->obj_semi[x] = 0;
        p->obj_win[x] = 0;
    }
    uint16_t dispcnt = ioreg(g, 0);
    if (!(dispcnt & 0x1000u)) return;
    bool one_d = dispcnt & 0x40u;
    bool bitmap = (dispcnt & 7u) >= 3;
    uint16_t mosaic = ioreg(g, 0x4C);
    unsigned mh = (mosaic >> 8 & 15u) + 1u, mv = (mosaic >> 12 & 15u) + 1u;
    for (unsigned i = 0; i < 128; i++) {
        uint16_t a0 = rd16(g->oam, i * 8u), a1 = rd16(g->oam, i * 8u + 2u), a2 = rd16(g->oam, i * 8u + 4u);
        bool affine = a0 & 0x100u;
        if (!affine && (a0 & 0x200u)) continue;          /* desactivado */
        unsigned mode = (a0 >> 10) & 3u;
        if (mode == 3) continue;
        unsigned shape = a0 >> 14, sz = a1 >> 14;
        if (shape == 3) continue;
        int w = obj_w[shape][sz], h = obj_h[shape][sz];
        int bw = w, bh = h;
        if (affine && (a0 & 0x200u)) { bw *= 2; bh *= 2; }
        int y0 = a0 & 0xFF;
        int dy = (int)((line - (unsigned)y0) & 0xFFu);
        if (dy >= bh) continue;
        bool mos = a0 & 0x1000u;
        if (mos && mv > 1) {
            /* Mosaico vertical alineado a la pantalla, sin salir del objeto. */
            int my = (int)(line - line % mv);
            int top = y0 < 160 ? y0 : y0 - 256;
            if (my < top) my = top;
            dy = my - top;
            if (dy >= bh) dy = bh - 1;
        }
        int x0 = a1 & 0x1FF;
        if (x0 >= 240) x0 -= 512;
        bool c256 = a0 & 0x2000u;
        unsigned tile = a2 & 0x3FFu, prio = (a2 >> 10) & 3u, palno = a2 >> 12;
        int32_t pa = 256, pb = 0, pc = 0, pd = 256;
        if (affine) {
            unsigned grp = ((a1 >> 9) & 31u) * 32u;
            pa = (int16_t)rd16(g->oam, grp + 6u);
            pb = (int16_t)rd16(g->oam, grp + 14u);
            pc = (int16_t)rd16(g->oam, grp + 22u);
            pd = (int16_t)rd16(g->oam, grp + 30u);
        }
        unsigned row_tiles = one_d ? (unsigned)(w / 8) * (c256 ? 2u : 1u) : 32u;
        /* Con mosaico horizontal, el último bloque se extiende hasta su límite. */
        int span = bw;
        if (mos && mh > 1) {
            int end = x0 + bw;
            if (end < (int)GBA_SCREEN_W && end % (int)mh) end += (int)mh - end % (int)mh;
            span = end - x0;
        }
        for (int sx = 0; sx < span; sx++) {
            int x = x0 + sx;
            if (x < 0 || x >= (int)GBA_SCREEN_W) continue;
            int tx, ty;
            if (affine) {
                int ax = (mos && mh > 1) ? sx - x % (int)mh : sx;   /* coordenadas al inicio del bloque */
                int cx = ax - bw / 2, cy = dy - bh / 2;
                tx = ((pa * cx + pb * cy) >> 8) + w / 2;
                ty = ((pc * cx + pd * cy) >> 8) + h / 2;
                if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
            } else {
                int ix = (mos && mh > 1) ? sx - x % (int)mh : sx;
                if (ix < 0) ix = 0;
                if (ix > w - 1) ix = w - 1;
                tx = (a1 & 0x1000u) ? w - 1 - ix : ix;
                ty = (a1 & 0x2000u) ? h - 1 - dy : dy;
            }
            unsigned tnum, idx;
            uint32_t addr;
            if (c256) {
                tnum = (tile & ~1u) + (unsigned)(ty >> 3) * row_tiles + (unsigned)(tx >> 3) * 2u;
                addr = 0x10000u + (tnum & 0x3FFu) * 32u + (unsigned)(ty & 7) * 8u + (unsigned)(tx & 7);
                if (bitmap && addr < 0x14000u) continue;
                idx = g->vram[addr];
            } else {
                tnum = tile + (unsigned)(ty >> 3) * row_tiles + (unsigned)(tx >> 3);
                addr = 0x10000u + (tnum & 0x3FFu) * 32u + (unsigned)(ty & 7) * 4u + (unsigned)(tx & 7) / 2u;
                if (bitmap && addr < 0x14000u) continue;
                idx = (g->vram[addr] >> ((tx & 1) * 4)) & 15u;
                if (idx) idx += palno * 16u;
            }
            if (mode == 2) {
                if (idx) p->obj_win[x] = 1;
                continue;
            }
            /* Peculiaridad del hardware: también un píxel transparente rebaja la
             * prioridad (y la marca de semitransparencia) del búfer de objetos,
             * aunque el color siga siendo el del objeto opaco anterior. */
            if (prio < p->obj_prio[x] && (idx || !(p->obj[x] & TRANSPARENT))) {
                p->obj_prio[x] = (uint8_t)prio;
                p->obj_semi[x] = mode == 1;      /* también la marca de semitransparencia */
                if (idx) p->obj[x] = pal16(g, 256u + idx);
            }
        }
    }
}

/* ------------------------------------------------------------ composición */

static inline bool win_axis(unsigned v, unsigned a, unsigned b, unsigned limit)
{
    if (b > limit || a > b) {               /* GBATEK: valores fuera de rango */
        if (a > b) return v >= a || v < b;
        b = limit;
    }
    return v >= a && v < b;
}

static inline uint16_t blend_alpha(uint16_t a, uint16_t b, unsigned eva, unsigned evb)
{
    unsigned r = ((a & 31u) * eva + (b & 31u) * evb) >> 4;
    unsigned gg = (((a >> 5) & 31u) * eva + ((b >> 5) & 31u) * evb) >> 4;
    unsigned bb = (((a >> 10) & 31u) * eva + ((b >> 10) & 31u) * evb) >> 4;
    if (r > 31) r = 31;
    if (gg > 31) gg = 31;
    if (bb > 31) bb = 31;
    return (uint16_t)(r | (gg << 5) | (bb << 10));
}

static inline uint16_t blend_bright(uint16_t a, unsigned evy, bool up)
{
    unsigned c[3] = {a & 31u, (a >> 5) & 31u, (a >> 10) & 31u};
    for (int i = 0; i < 3; i++) c[i] = up ? c[i] + (((31u - c[i]) * evy) >> 4) : c[i] - ((c[i] * evy) >> 4);
    return (uint16_t)(c[0] | (c[1] << 5) | (c[2] << 10));
}

static inline uint32_t to_rgba(uint16_t c)
{
    uint32_t r = c & 31u, gg = (c >> 5) & 31u, b = (c >> 10) & 31u;
    r = (r << 3) | (r >> 2);
    gg = (gg << 3) | (gg >> 2);
    b = (b << 3) | (b >> 2);
    return r | (gg << 8) | (b << 16) | 0xFF000000u;
}

void gba_ppu_render_line(gba *g, unsigned line)
{
    if (line >= GBA_SCREEN_H) return;
    gba_ppu *p = &g->ppu;
    uint32_t *fb = &g->framebuffer[line * GBA_SCREEN_W];
    uint16_t dispcnt = ioreg(g, 0);
    if (dispcnt & 0x80u) {                  /* forced blank: blanco */
        for (unsigned x = 0; x < GBA_SCREEN_W; x++) fb[x] = 0xFFFFFFFFu;
        goto advance;
    }
    {
        unsigned mode = dispcnt & 7u;
        bool bg_on[4] = {false, false, false, false};
        for (unsigned n = 0; n < 4; n++) {
            bool en = dispcnt & (0x100u << n);
            bool exists = mode == 0 || (mode == 1 && n <= 2) || (mode == 2 && n >= 2) || (mode >= 3 && mode <= 5 && n == 2);
            bg_on[n] = en && exists;
            if (!bg_on[n]) continue;
            if (mode >= 3) render_bitmap_bg(g, mode, p->bg[n]);
            else if (mode == 0 || (mode == 1 && n < 2)) render_text_bg(g, n, line, p->bg[n]);
            else render_affine_bg(g, n, line, p->bg[n]);
        }
        render_objs(g, line);

        unsigned bg_prio[4];
        for (unsigned n = 0; n < 4; n++) bg_prio[n] = ioreg(g, 0x08u + n * 2u) & 3u;
        /* Fondos activos ordenados por (prioridad, número), una vez por línea. */
        unsigned order[4], norder = 0;
        for (unsigned pr = 0; pr < 4; pr++)
            for (unsigned n = 0; n < 4; n++)
                if (bg_on[n] && bg_prio[n] == pr) order[norder++] = n;
        uint16_t bldcnt = ioreg(g, 0x50), bldalpha = ioreg(g, 0x52), bldy = ioreg(g, 0x54);
        unsigned eva = bldalpha & 31u, evb = (bldalpha >> 8) & 31u, evy = bldy & 31u;
        if (eva > 16) eva = 16;
        if (evb > 16) evb = 16;
        if (evy > 16) evy = 16;
        unsigned bmode = (bldcnt >> 6) & 3u;
        bool win0 = dispcnt & 0x2000u, win1 = dispcnt & 0x4000u, winobj = dispcnt & 0x8000u;
        bool any_win = win0 || win1 || winobj;
        uint16_t winin = ioreg(g, 0x48), winout = ioreg(g, 0x4A);
        uint16_t w0h = ioreg(g, 0x40), w1h = ioreg(g, 0x42), w0v = ioreg(g, 0x44), w1v = ioreg(g, 0x46);
        bool in0v = win0 && win_axis(line, w0v >> 8, w0v & 0xFFu, 160);
        bool in1v = win1 && win_axis(line, w1v >> 8, w1v & 0xFFu, 160);
        uint16_t backdrop = pal16(g, 0);
        for (unsigned x = 0; x < GBA_SCREEN_W; x++) {
            unsigned mask = 0x3F;
            if (any_win) {
                if (in0v && win_axis(x, w0h >> 8, w0h & 0xFFu, 240)) mask = winin & 0x3Fu;
                else if (in1v && win_axis(x, w1h >> 8, w1h & 0xFFu, 240)) mask = (winin >> 8) & 0x3Fu;
                else if (winobj && p->obj_win[x]) mask = (winout >> 8) & 0x3Fu;
                else mask = winout & 0x3Fu;
            }
            uint16_t c1 = backdrop, c2 = backdrop;
            unsigned l1 = 5, l2 = 5, found = 0;
            bool semi = false;
            bool obj_here = !(p->obj[x] & TRANSPARENT) && (mask & 0x10u);
            unsigned op = obj_here ? p->obj_prio[x] : 4u;
            for (unsigned k = 0; k <= norder && found < 2; k++) {
                /* El objeto va delante de los fondos de su misma prioridad. */
                if (op < 4 && (k == norder || bg_prio[order[k]] >= op)) {
                    if (found == 0) { c1 = p->obj[x]; l1 = 4; semi = p->obj_semi[x]; }
                    else { c2 = p->obj[x]; l2 = 4; }
                    found++;
                    op = 4;
                    if (found == 2) break;
                }
                if (k == norder) break;
                unsigned n = order[k];
                if (!(mask & (1u << n))) continue;
                uint16_t c = p->bg[n][x];
                if (c & TRANSPARENT) continue;
                if (found == 0) { c1 = c; l1 = n; }
                else { c2 = c; l2 = n; }
                found++;
            }
            uint16_t out = c1;
            bool second = bldcnt & (0x100u << l2);
            /* GBATEK: un objeto semitransparente es siempre primer objetivo y usa
             * mezcla alfa si debajo hay un segundo objetivo; si no, el efecto activo. */
            if (semi && second) {
                out = blend_alpha(c1, c2, eva, evb);
            } else if ((mask & 0x20u) && (semi || (bldcnt & (1u << l1)))) {
                if (bmode == 1 && second) out = blend_alpha(c1, c2, eva, evb);
                else if (bmode == 2) out = blend_bright(c1, evy, true);
                else if (bmode == 3) out = blend_bright(c1, evy, false);
            }
            fb[x] = to_rgba(out);
        }
    }
advance:
    /* Las referencias afines avanzan PB/PD por línea dibujada. */
    p->ref_x[0] += (int16_t)ioreg(g, 0x22);
    p->ref_y[0] += (int16_t)ioreg(g, 0x26);
    p->ref_x[1] += (int16_t)ioreg(g, 0x32);
    p->ref_y[1] += (int16_t)ioreg(g, 0x36);
}
