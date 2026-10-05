/*
 * io.c — registros de E/S del GBA, interrupciones, DMA, timers y tiempos de
 * vídeo (GBATEK §LCD I/O Display Status, §Interrupt Control, §DMA Transfers,
 * §Timers, §Keypad Input, §System Control). Código propio.
 */
#include "internal.h"

static inline uint16_t io_raw(const gba *g, uint32_t off)
{
    return (uint16_t)(g->io[off] | (g->io[off + 1] << 8));
}

static inline void io_set(gba *g, uint32_t off, uint16_t v)
{
    g->io[off] = (uint8_t)v;
    g->io[off + 1] = (uint8_t)(v >> 8);
}

/* ------------------------------------------------------------ interrupciones */

void gba_irq_raise(gba *g, uint16_t bits)
{
    g->if_ |= bits & 0x3FFFu;
}

bool gba_irq_pending(const gba *g)
{
    return (g->ie & g->if_ & 0x3FFFu) != 0;
}

static void keypad_check(gba *g)
{
    uint16_t cnt = g->keycnt;
    if (!(cnt & 0x4000u)) return;
    uint16_t sel = cnt & 0x3FFu, pressed = g->keys & sel;
    bool hit = (cnt & 0x8000u) ? (pressed == sel && sel) : pressed != 0;
    if (hit) gba_irq_raise(g, GBA_IRQ_KEYPAD);
}

/* ------------------------------------------------------------ waitstates */

void gba_bus_update_waitstates(gba *g)
{
    static const uint8_t first[4] = {4, 3, 2, 8};
    uint16_t w = g->waitcnt;
    for (int r = 0; r < 16; r++) {
        g->ws_n16[r] = g->ws_s16[r] = g->ws_n32[r] = g->ws_s32[r] = 1;
    }
    g->ws_n16[2] = g->ws_s16[2] = 3;          /* EWRAM: bus de 16 bits, 2 esperas */
    g->ws_n32[2] = g->ws_s32[2] = 6;
    for (int r = 5; r <= 6; r++) { g->ws_n32[r] = g->ws_s32[r] = 2; }   /* paleta y VRAM: 16 bits */
    struct { int n, s; } ws[3] = {
        {1 + first[(w >> 2) & 3u], 1 + (((w >> 4) & 1u) ? 1 : 2)},
        {1 + first[(w >> 5) & 3u], 1 + (((w >> 7) & 1u) ? 1 : 4)},
        {1 + first[(w >> 8) & 3u], 1 + (((w >> 10) & 1u) ? 1 : 8)}};
    for (int i = 0; i < 3; i++) {
        for (int r = 8 + 2 * i; r <= 9 + 2 * i; r++) {
            g->ws_n16[r] = (uint8_t)ws[i].n;
            g->ws_s16[r] = (uint8_t)ws[i].s;
            g->ws_n32[r] = (uint8_t)(ws[i].n + ws[i].s);
            g->ws_s32[r] = (uint8_t)(2 * ws[i].s);
        }
    }
    uint8_t sram = (uint8_t)(1 + first[w & 3u]);
    for (int r = 14; r <= 15; r++) g->ws_n16[r] = g->ws_s16[r] = g->ws_n32[r] = g->ws_s32[r] = sram;
}

/* ------------------------------------------------------------ DMA */

static void dma_run(gba *g, int ch);

static void dma_latch(gba *g, int ch)
{
    gba_dma *d = &g->dma[ch];
    d->src = d->sad & (ch == 0 ? 0x07FFFFFFu : 0x0FFFFFFFu);
    d->dst = d->dad & (ch == 3 ? 0x0FFFFFFFu : 0x07FFFFFFu);
    uint32_t n = d->cnt_l & (ch == 3 ? 0xFFFFu : 0x3FFFu);
    d->count = n ? n : (ch == 3 ? 0x10000u : 0x4000u);
}

static void dma_write_cnt_h(gba *g, int ch, uint16_t v)
{
    gba_dma *d = &g->dma[ch];
    bool was = d->cnt_h & 0x8000u;
    d->cnt_h = v & (ch == 3 ? 0xFFE0u : 0xF7E0u);
    if (!was && (d->cnt_h & 0x8000u)) {
        dma_latch(g, ch);
        if (((d->cnt_h >> 12) & 3u) == 0) {
            /* Nunca recursiva: una DMA que reescribe su propio CNT_H solo deja
             * el canal pendiente (ROM no confiable, auditoría G2 H1). */
            g->dma_pending |= (uint8_t)(1u << ch);
            if (!g->dma_active) gba_dma_service(g);
        }
    }
}

static void dma_run(gba *g, int ch)
{
    gba_dma *d = &g->dma[ch];
    uint16_t cnt = d->cnt_h;
    unsigned timing = (cnt >> 12) & 3u;
    bool word = cnt & 0x400u;
    unsigned dctl = (cnt >> 5) & 3u, sctl = (cnt >> 7) & 3u;
    uint32_t count = d->count;
    bool fifo = timing == 3 && (ch == 1 || ch == 2);
    if (fifo) { word = true; count = 4; dctl = 2; }
    int32_t step = word ? 4 : 2;
    int32_t sinc = sctl == 0 ? step : sctl == 1 ? -step : 0;
    int32_t dinc = (dctl == 0 || dctl == 3) ? step : dctl == 1 ? -step : 0;
    if (d->src >= 0x08000000u && d->src < 0x0E000000u) sinc = step;   /* ROM: siempre incrementa */
    if (gba_cart_is_eeprom(g, d->dst) || gba_cart_is_eeprom(g, d->src)) gba_eeprom_dma(g, count);
    bool prev = g->dma_active;
    uint8_t prev_ch = g->dma_cur;
    g->dma_active = true;
    g->dma_cur = (uint8_t)ch;
    g->cycles += 2;
    for (uint32_t i = 0; i < count; i++) {
        g->cpu.seq = i != 0;
        if (word) {
            uint32_t v = d->src >= 0x02000000u ? gba_bus_read32(g, d->src & ~3u) : d->latch;
            d->latch = v;
            gba_bus_write32(g, d->dst & ~3u, v);
        } else {
            uint16_t v;
            if (d->src >= 0x02000000u) {
                v = gba_bus_read16(g, d->src & ~1u);
                d->latch = v * 0x10001u;
            } else {
                v = (uint16_t)(d->latch >> ((d->dst & 2u) * 8u));
            }
            gba_bus_write16(g, d->dst & ~1u, v);
        }
        d->src += (uint32_t)sinc;
        d->dst += (uint32_t)dinc;
    }
    g->cpu.seq = false;
    g->dma_active = prev;
    g->dma_cur = prev_ch;
    if ((cnt & 0x200u) && timing != 0) {
        uint32_t n = d->cnt_l & (ch == 3 ? 0xFFFFu : 0x3FFFu);
        d->count = n ? n : (ch == 3 ? 0x10000u : 0x4000u);
        if (dctl == 3) d->dst = d->dad & (ch == 3 ? 0x0FFFFFFFu : 0x07FFFFFFu);
    } else {
        d->cnt_h &= (uint16_t)~0x8000u;
    }
    if (cnt & 0x4000u) gba_irq_raise(g, (uint16_t)(GBA_IRQ_DMA0 << ch));
}

/* Cada canal pendiente se ejecuta como mucho una vez por llamada; lo que una
 * DMA vuelva a dejar pendiente se atiende en el siguiente paso (acotado). */
void gba_dma_service(gba *g)
{
    uint8_t todo = g->dma_pending;
    g->dma_pending = 0;
    for (int ch = 0; ch < 4; ch++) {
        if (!(todo & (1u << ch))) continue;
        gba_dma *d = &g->dma[ch];
        if ((d->cnt_h & 0x8000u) && ((d->cnt_h >> 12) & 3u) == 0) dma_run(g, ch);
    }
}

/* FIFO de sonido medio vacía: la DMA1/2 en modo especial con destino en esa FIFO. */
void gba_dma_fifo(gba *g, uint32_t fifo_addr)
{
    for (int ch = 1; ch <= 2; ch++) {
        gba_dma *d = &g->dma[ch];
        if ((d->cnt_h & 0x8000u) && ((d->cnt_h >> 12) & 3u) == 3 && (d->dad & 0x0FFFFFFCu) == fifo_addr)
            dma_run(g, ch);
    }
}

void gba_dma_trigger(gba *g, int timing)
{
    for (int ch = 0; ch < 4; ch++) {
        gba_dma *d = &g->dma[ch];
        if ((d->cnt_h & 0x8000u) && (int)((d->cnt_h >> 12) & 3u) == timing) {
            if (timing == 3 && ch == 0) continue;     /* DMA0 no tiene modo especial */
            dma_run(g, ch);
        }
    }
}

/* ------------------------------------------------------------ timers */

static const uint8_t timer_shift[4] = {0, 6, 8, 10};

static void timer_add(gba *g, int i, uint32_t inc);

static void timer_overflow(gba *g, int i)
{
    gba_timer *t = &g->timer[i];
    if (t->cnt & 0x40u) gba_irq_raise(g, (uint16_t)(GBA_IRQ_TIMER0 << i));
    if (i < 2) gba_apu_timer_overflow(g, i);
    if (i < 3) {
        gba_timer *n = &g->timer[i + 1];
        if ((n->cnt & 0x80u) && (n->cnt & 0x04u)) timer_add(g, i + 1, 1);
    }
}

static void timer_add(gba *g, int i, uint32_t inc)
{
    gba_timer *t = &g->timer[i];
    while (inc) {
        uint32_t room = 0x10000u - t->counter;
        if (inc < room) {
            t->counter = (uint16_t)(t->counter + inc);
            return;
        }
        inc -= room;
        t->counter = t->reload;
        timer_overflow(g, i);
    }
}

static void timers_tick(gba *g, uint32_t n)
{
    for (int i = 0; i < 4; i++) {
        gba_timer *t = &g->timer[i];
        if (!(t->cnt & 0x80u) || (i > 0 && (t->cnt & 0x04u))) continue;
        unsigned sh = timer_shift[t->cnt & 3u];
        t->sub += n;
        uint32_t inc = t->sub >> sh;
        t->sub &= (1u << sh) - 1u;
        if (inc) timer_add(g, i, inc);
    }
}

static void timer_write_cnt(gba *g, int i, uint16_t v)
{
    gba_timer *t = &g->timer[i];
    bool was = t->cnt & 0x80u;
    t->cnt = v & 0xC7u;
    if (!was && (t->cnt & 0x80u)) {
        t->counter = t->reload;
        t->sub = 0;
    }
}

/* ------------------------------------------------------------ vídeo (tiempos) */

#define GBA_LINE_CYCLES 1232u
#define GBA_HBLANK_CYCLE 1006u

void gba_tick(gba *g, uint32_t n)
{
    if (g->dma_pending && !g->dma_active) gba_dma_service(g);
    g->apu.pending += n;
    timers_tick(g, n);
    g->line_cycles += n;
    for (;;) {
        if (!g->hblank && g->line_cycles >= GBA_HBLANK_CYCLE) {
            g->hblank = true;
            if (g->vcount < 160) gba_ppu_render_line(g, g->vcount);
            if (g->dispstat & 0x10u) gba_irq_raise(g, GBA_IRQ_HBLANK);
            if (g->vcount < 160) gba_dma_trigger(g, 2);
            continue;
        }
        if (g->line_cycles >= GBA_LINE_CYCLES) {
            g->line_cycles -= GBA_LINE_CYCLES;
            g->hblank = false;
            g->vcount = (uint16_t)(g->vcount + 1u);
            if (g->vcount >= 228) g->vcount = 0;
            if (g->vcount == 160) {
                g->frame_done = true;
                gba_ppu_vblank(g);
                if (g->dispstat & 0x08u) gba_irq_raise(g, GBA_IRQ_VBLANK);
                gba_dma_trigger(g, 1);
            }
            if (g->vcount == (g->dispstat >> 8) && (g->dispstat & 0x20u)) gba_irq_raise(g, GBA_IRQ_VCOUNT);
            continue;
        }
        break;
    }
}

uint32_t gba_cycles_to_event(const gba *g)
{
    uint32_t best = g->hblank ? GBA_LINE_CYCLES - g->line_cycles : GBA_HBLANK_CYCLE - g->line_cycles;
    for (int i = 0; i < 4; i++) {
        const gba_timer *t = &g->timer[i];
        if (!(t->cnt & 0x80u) || (i > 0 && (t->cnt & 0x04u))) continue;
        unsigned sh = timer_shift[t->cnt & 3u];
        uint32_t left = ((0x10000u - t->counter) << sh) - t->sub;
        if (left < best) best = left;
    }
    return best ? best : 1;
}

/* ------------------------------------------------------------ registros */

void gba_io_reset(gba *g)
{
    memset(g->io, 0, sizeof g->io);
    memset(g->dma, 0, sizeof g->dma);
    memset(g->timer, 0, sizeof g->timer);
    g->dma_pending = 0;
    g->dma_cur = 0;
    g->ie = g->if_ = g->ime = 0;
    g->dispstat = 0;
    g->keycnt = 0;
    g->waitcnt = 0;
    g->postflg = 1;                      /* tras el arranque de la BIOS */
    io_set(g, 0x134, 0x8000);            /* RCNT */
    io_set(g, 0x020, 0x100); io_set(g, 0x026, 0x100);   /* BG2PA/PD = 1.0 */
    io_set(g, 0x030, 0x100); io_set(g, 0x036, 0x100);   /* BG3PA/PD */
    gba_bus_update_waitstates(g);
}

uint16_t gba_io_read16(gba *g, uint32_t off)
{
    off &= 0x3FEu;
    switch (off) {
    case 0x000: case 0x002: case 0x008: case 0x00A: case 0x00C: case 0x00E:
    case 0x048: case 0x04A: case 0x050: case 0x052:
        return io_raw(g, off);
    case 0x004: {
        uint16_t s = g->dispstat & 0xFF38u;
        if (g->vcount >= 160 && g->vcount < 227) s |= 1u;
        if (g->hblank) s |= 2u;
        if (g->vcount == (g->dispstat >> 8)) s |= 4u;
        return s;
    }
    case 0x006: return g->vcount;
    /* DMA: solo CNT_H se lee (sin el bit de "repetir" en 0-2 no cambia nada). */
    case 0x0BA: return g->dma[0].cnt_h;
    case 0x0C6: return g->dma[1].cnt_h;
    case 0x0D2: return g->dma[2].cnt_h;
    case 0x0DE: return g->dma[3].cnt_h;
    case 0x0B8: case 0x0C4: case 0x0D0: case 0x0DC: return 0;
    case 0x100: case 0x104: case 0x108: case 0x10C: return g->timer[(off - 0x100) >> 2].counter;
    case 0x102: case 0x106: case 0x10A: case 0x10E: return g->timer[(off - 0x100) >> 2].cnt;
    case 0x130: return (uint16_t)(~g->keys & 0x3FFu);
    case 0x132: return g->keycnt;
    case 0x200: return g->ie;
    case 0x202: return g->if_;
    case 0x204: return g->waitcnt;
    case 0x206: return 0;
    case 0x208: return g->ime;
    case 0x20A: return 0;
    case 0x300: return g->postflg;
    default:
        if (off >= 0x060 && off < 0x0A8) return gba_apu_read16(g, off);
        /* Serie: se devuelve tal cual; el resto (solo escritura) lee 0. */
        if (off >= 0x120 && off < 0x160) return io_raw(g, off);
        return 0;
    }
}

void gba_io_write16(gba *g, uint32_t off, uint16_t v)
{
    off &= 0x3FEu;
    switch (off) {
    case 0x004: g->dispstat = v & 0xFF38u; return;
    case 0x006: return;
    case 0x0B0: case 0x0B2: case 0x0BC: case 0x0BE: case 0x0C8: case 0x0CA: case 0x0D4: case 0x0D6: {
        int ch = (int)((off - 0x0B0) / 12u);
        uint32_t base = 0x0B0u + (uint32_t)ch * 12u;
        uint32_t *r = &g->dma[ch].sad;
        uint32_t sh = (off - base) & 2u ? 16u : 0u;
        *r = (*r & ~(0xFFFFu << sh)) | ((uint32_t)v << sh);
        break;
    }
    case 0x0B4: case 0x0B6: case 0x0C0: case 0x0C2: case 0x0CC: case 0x0CE: case 0x0D8: case 0x0DA: {
        int ch = (int)((off - 0x0B4) / 12u);
        uint32_t base = 0x0B4u + (uint32_t)ch * 12u;
        uint32_t sh = (off - base) & 2u ? 16u : 0u;
        g->dma[ch].dad = (g->dma[ch].dad & ~(0xFFFFu << sh)) | ((uint32_t)v << sh);
        break;
    }
    case 0x0B8: case 0x0C4: case 0x0D0: case 0x0DC: g->dma[(off - 0x0B8) / 12u].cnt_l = v; break;
    case 0x0BA: case 0x0C6: case 0x0D2: case 0x0DE: dma_write_cnt_h(g, (int)((off - 0x0BA) / 12u), v); break;
    case 0x100: case 0x104: case 0x108: case 0x10C: g->timer[(off - 0x100) >> 2].reload = v; break;
    case 0x102: case 0x106: case 0x10A: case 0x10E: timer_write_cnt(g, (int)((off - 0x100) >> 2), v); break;
    case 0x130: return;
    case 0x060: case 0x062: case 0x064: case 0x066: case 0x068: case 0x06A: case 0x06C: case 0x06E:
    case 0x070: case 0x072: case 0x074: case 0x076: case 0x078: case 0x07A: case 0x07C: case 0x07E:
    case 0x080: case 0x082: case 0x084: case 0x086: case 0x088: case 0x08A: case 0x08C: case 0x08E:
    case 0x090: case 0x092: case 0x094: case 0x096: case 0x098: case 0x09A: case 0x09C: case 0x09E:
    case 0x0A0: case 0x0A2: case 0x0A4: case 0x0A6:
        gba_apu_write16(g, off, v);
        return;
    case 0x132: g->keycnt = v & 0xC3FFu; keypad_check(g); break;
    case 0x200: g->ie = v & 0x3FFFu; break;
    case 0x202: g->if_ &= (uint16_t)~v; return;
    case 0x204: g->waitcnt = v & 0x5FFFu; gba_bus_update_waitstates(g); break;
    case 0x208: g->ime = v & 1u; break;
    case 0x300:
        /* POSTFLG en el byte bajo; cualquier escritura en HALTCNT (byte alto)
         * para la CPU hasta la siguiente IRQ. STOP (bit 15) se trata como HALT. */
        g->postflg = (uint8_t)(v & 1u);
        g->cpu.halted = true;
        return;
    default: break;
    }
    io_set(g, off, v);
    if (off >= 0x028 && off < 0x030) gba_ppu_reload_ref(g, 2);
    else if (off >= 0x038 && off < 0x040) gba_ppu_reload_ref(g, 3);
}

void gba_io_write8(gba *g, uint32_t off, uint8_t v)
{
    off &= 0x3FFu;
    if (off == 0x301) { g->cpu.halted = true; return; }   /* HALTCNT */
    if (off == 0x300) { g->postflg = v & 1u; return; }
    if (off == 0x202 || off == 0x203) { g->if_ &= (uint16_t)~((uint16_t)v << ((off & 1u) * 8u)); return; }
    if (off >= 0x060 && off < 0x0A8) { gba_apu_write8(g, off, v); return; }
    uint32_t base = off & ~1u;
    uint16_t cur;
    switch (base) {
    case 0x004: cur = g->dispstat; break;
    case 0x0BA: cur = g->dma[0].cnt_h; break;
    case 0x0C6: cur = g->dma[1].cnt_h; break;
    case 0x0D2: cur = g->dma[2].cnt_h; break;
    case 0x0DE: cur = g->dma[3].cnt_h; break;
    case 0x102: case 0x106: case 0x10A: case 0x10E: cur = g->timer[(base - 0x100) >> 2].cnt; break;
    case 0x100: case 0x104: case 0x108: case 0x10C: cur = g->timer[(base - 0x100) >> 2].reload; break;
    case 0x132: cur = g->keycnt; break;
    case 0x200: cur = g->ie; break;
    case 0x204: cur = g->waitcnt; break;
    case 0x208: cur = g->ime; break;
    default: cur = io_raw(g, base); break;
    }
    uint16_t nv = (off & 1u) ? (uint16_t)((cur & 0x00FFu) | (v << 8)) : (uint16_t)((cur & 0xFF00u) | v);
    gba_io_write16(g, base, nv);
}
