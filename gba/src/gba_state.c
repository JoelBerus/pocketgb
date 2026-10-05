/*
 * state.c — save states del núcleo GBA (docs/10-gba-spec.md §Save states).
 *
 *   "PGBA" | u32 versión | u8 sha256_rom[32] | u32 longitud de la carga |
 *   carga (campos en orden fijo, little-endian) | u32 crc32
 *
 * Cada campo se serializa por separado (sin memcpy de structs). Un único
 * recorrido (`visit`) sirve para medir, guardar y cargar. La carga trabaja
 * sobre una COPIA de la instancia: lee cada campo, valida su rango y la
 * coherencia entre campos (regla dura 3: el archivo no es confiable) y solo
 * si todo es correcto copia el resultado; ante cualquier error la instancia
 * no cambia. El tamaño es constante para una misma versión. CRC-32 IEEE
 * (polinomio reflejado 0xEDB88320) con tabla de 16 entradas.
 */
#include "gba_internal.h"
#include <stdlib.h>

enum { STATE_VERSION = 1, HEADER_BYTES = 4 + 4 + 32 + 4, CRC_BYTES = 4 };
enum { V_MEASURE, V_SAVE, V_LOAD };

static uint32_t gba_crc32(const uint8_t *p, size_t n)
{
    static const uint32_t tab[16] = {
        0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC, 0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
        0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C, 0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C};
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++) {
        c ^= p[i];
        c = (c >> 4) ^ tab[c & 15u];
        c = (c >> 4) ^ tab[c & 15u];
    }
    return ~c;
}

typedef struct visitor {
    int mode;
    uint8_t *out;
    const uint8_t *in;
    size_t pos, cap;
    bool bad;
} visitor;

static void v_raw(visitor *v, void *p, size_t n)
{
    if (v->mode == V_SAVE) memcpy(v->out + v->pos, p, n);
    else if (v->mode == V_LOAD) {
        if (v->pos + n > v->cap) { v->bad = true; v->pos += n; return; }
        memcpy(p, v->in + v->pos, n);
    }
    v->pos += n;
}

static void v_uint(visitor *v, void *p, unsigned bytes, uint64_t max)
{
    uint64_t x = 0;
    if (v->mode == V_SAVE) {
        switch (bytes) {
        case 1: x = *(uint8_t *)p; break;
        case 2: x = *(uint16_t *)p; break;
        case 4: x = *(uint32_t *)p; break;
        default: x = *(uint64_t *)p; break;
        }
        for (unsigned i = 0; i < bytes; i++) v->out[v->pos + i] = (uint8_t)(x >> (8 * i));
    } else if (v->mode == V_LOAD) {
        if (v->pos + bytes > v->cap) { v->bad = true; v->pos += bytes; return; }
        for (unsigned i = 0; i < bytes; i++) x |= (uint64_t)v->in[v->pos + i] << (8 * i);
        if (x > max) v->bad = true;
        switch (bytes) {
        case 1: *(uint8_t *)p = (uint8_t)x; break;
        case 2: *(uint16_t *)p = (uint16_t)x; break;
        case 4: *(uint32_t *)p = (uint32_t)x; break;
        default: *(uint64_t *)p = x; break;
        }
    }
    v->pos += bytes;
}

#define U8(f, max)  v_uint(v, &(f), 1, (max))
#define U16(f, max) v_uint(v, &(f), 2, (max))
#define U32(f, max) v_uint(v, &(f), 4, (max))
#define U64(f)      v_uint(v, &(f), 8, UINT64_MAX)
#define B(f) do { uint8_t b_ = (f) ? 1 : 0; U8(b_, 1); (f) = b_ != 0; } while (0)
#define I32(f, lo, hi) do { uint32_t u_ = (uint32_t)(f); U32(u_, UINT32_MAX); (f) = (int32_t)u_; \
        if (v->mode == V_LOAD && ((f) < (lo) || (f) > (hi))) v->bad = true; } while (0)
#define I64(f) do { uint64_t u_ = (uint64_t)(f); U64(u_); (f) = (int64_t)u_; } while (0)
#define RAW(a) v_raw(v, (a), sizeof(a))
/* double por sus bits (IEEE 754); al cargar debe ser finito. */
#define D(f) do { uint64_t u_; memcpy(&u_, &(f), 8); U64(u_); memcpy(&(f), &u_, 8); \
        if (v->mode == V_LOAD && ((u_ >> 52) & 0x7FFu) == 0x7FFu) v->bad = true; } while (0)

static uint32_t type_bytes(unsigned t)
{
    switch (t) {
    case GBA_SAVE_SRAM: return 32u * 1024u;
    case GBA_SAVE_FLASH64: return 64u * 1024u;
    case GBA_SAVE_FLASH128: return 128u * 1024u;
    case GBA_SAVE_EEPROM512: return 512u;
    case GBA_SAVE_EEPROM8K: return 8u * 1024u;
    default: return 0;
    }
}

static void visit(visitor *v, gba *g)
{
    gba_arm *c = &g->cpu;
    for (int i = 0; i < 16; i++) U32(c->r[i], UINT32_MAX);
    U32(c->cpsr, UINT32_MAX);
    U32(c->spsr, UINT32_MAX);
    for (int i = 0; i < 5; i++) { U32(c->bank_fiq_r8[i], UINT32_MAX); U32(c->bank_usr_r8[i], UINT32_MAX); }
    for (int i = 0; i < ARM_BANKS; i++) {
        U32(c->bank_r13[i], UINT32_MAX); U32(c->bank_r14[i], UINT32_MAX); U32(c->bank_spsr[i], UINT32_MAX);
    }
    U32(c->pipe[0], UINT32_MAX); U32(c->pipe[1], UINT32_MAX);
    U8(c->bank, ARM_BANKS - 1);
    B(c->flushed); B(c->halted); B(c->seq);

    RAW(g->ewram); RAW(g->iwram); RAW(g->io); RAW(g->pal); RAW(g->vram); RAW(g->oam);
    U32(g->bios_last, UINT32_MAX);
    U64(g->cycles);
    U32(g->line_cycles, 1231);
    U16(g->vcount, 227);
    U16(g->dispstat, 0xFF38);
    U16(g->keycnt, 0xC3FF);
    B(g->frame_done); B(g->hblank);
    U16(g->ie, 0x3FFF); U16(g->if_, 0x3FFF); U16(g->ime, 1); U8(g->postflg, 1);
    U16(g->waitcnt, 0x5FFF);
    U32(g->last_fetch_addr, UINT32_MAX); B(g->last_was_fetch);
    B(g->dma_active); U8(g->dma_cur, 3); U8(g->dma_pending, 15); B(g->hle_waiting);
    for (int i = 0; i < 4; i++) {
        gba_dma *d = &g->dma[i];
        U32(d->sad, UINT32_MAX); U32(d->dad, UINT32_MAX); U16(d->cnt_l, 0xFFFF); U16(d->cnt_h, 0xFFE0);
        U32(d->src, UINT32_MAX); U32(d->dst, UINT32_MAX); U32(d->count, 0x10000); U32(d->latch, UINT32_MAX);
    }
    for (int i = 0; i < 4; i++) {
        gba_timer *t = &g->timer[i];
        U16(t->reload, 0xFFFF); U16(t->counter, 0xFFFF); U16(t->cnt, 0xC7); U32(t->sub, 1023);
    }
    for (int i = 0; i < 2; i++) { I32(g->ppu.ref_x[i], INT32_MIN, INT32_MAX); I32(g->ppu.ref_y[i], INT32_MIN, INT32_MAX); }

    gba_apu *a = &g->apu;
    B(a->power);
    U32(a->pending, 1u << 24);
    U32(a->fs_counter, 32768);
    RAW(a->regs);
    U8(a->fs_step, 7);
    for (int i = 0; i < 4; i++) {
        gba_apu_ch *ch = &a->ch[i];
        B(ch->enabled); B(ch->dac); B(ch->len_en);
        U16(ch->length, 256); U16(ch->freq, 2047);
        I32(ch->timer, 0, 1 << 24);
        U8(ch->duty, 3); U8(ch->pos, 63);
        U8(ch->env.vol, 15); U8(ch->env.init, 15); U8(ch->env.period, 7); U8(ch->env.timer, 8); B(ch->env.up);
    }
    U8(a->sweep_timer, 8); U16(a->sweep_shadow, 0xFFFF); B(a->sweep_enabled); B(a->sweep_neg_used);
    RAW(a->wave);
    U8(a->wave_bank_play, 1); U8(a->wave_sample, 15); U16(a->lfsr, 0x7FFF);
    U16(a->cnt_h, 0x770F); U16(a->bias, 0xC3FE);
    RAW(a->fifo);
    for (int k = 0; k < 2; k++) { U8(a->fifo_head[k], 31); U8(a->fifo_len[k], 32); }
    for (int k = 0; k < 2; k++) { uint8_t s = (uint8_t)a->fifo_sample[k]; U8(s, 255); a->fifo_sample[k] = (int8_t)s; }
    /* Fase del remuestreo y condensador del pasa-altos: el audio tras cargar es
     * idéntico al de la partida original (determinismo). */
    U32(a->phase, GBA_CLOCK_HZ - 1);
    I64(a->acc_l); I64(a->acc_r);
    U32(a->acc_n, 1u << 20);
    D(a->cap_l); D(a->cap_r);

    { unsigned t = g->save_type; U32(t, GBA_SAVE_EEPROM8K); g->save_type = (gba_save_type)t; }
    U32(g->save_bytes, 128u * 1024u);
    B(g->save_dirty); B(g->has_rtc);
    RAW(g->save);
    U8(g->flash.state, 4); B(g->flash.erase_armed); B(g->flash.id_mode); U8(g->flash.bank, 1);
    gba_eeprom *e = &g->eeprom;
    U8(e->addr_bits, 14); U8(e->phase, 3); U8(e->cmd, 3); U8(e->nbits, 64);
    U32(e->addr, 0x3FFF); U64(e->data); U32(e->read_addr, 1023);
    { uint8_t rp = (uint8_t)e->read_pos; U8(rp, 255); e->read_pos = (int8_t)rp;
      if (v->mode == V_LOAD && (e->read_pos < -1 || e->read_pos > 67)) v->bad = true; }
    U32(e->dma_len, 0x10000);
    gba_rtc *r = &g->rtc;
    U8(r->data, 15); U8(r->dir, 15); U8(r->ctrl, 1); U8(r->state, 2); U8(r->cmd, 7);
    U8(r->bitpos, 7); U8(r->bytepos, 8); U8(r->nbytes, 8); U8(r->shift, 255);
    RAW(r->buf);
    U8(r->status, 0xFF); B(r->reading); I64(r->offset);
    I64(g->rtc_base); U64(g->rtc_base_cycles);
    for (size_t i = 0; i < GBA_SCREEN_W * GBA_SCREEN_H; i++) U32(g->framebuffer[i], UINT32_MAX);
}

/* Coherencia entre campos tras leer una copia (además de los rangos). */
static bool consistent(const gba *g)
{
    const gba_arm *c = &g->cpu;
    int bank;
    switch ((c->cpsr | 0x10u) & 0x1Fu) {
    case ARM_MODE_FIQ: bank = ARM_BANK_FIQ; break;
    case ARM_MODE_IRQ: bank = ARM_BANK_IRQ; break;
    case ARM_MODE_SVC: bank = ARM_BANK_SVC; break;
    case ARM_MODE_ABT: bank = ARM_BANK_ABT; break;
    case ARM_MODE_UND: bank = ARM_BANK_UND; break;
    default: bank = ARM_BANK_USR; break;
    }
    if (c->bank != bank || !(c->cpsr & 0x10u)) return false;
    if (g->save_type == GBA_SAVE_AUTO || g->save_bytes != type_bytes(g->save_type)) return false;
    if (g->save_type != GBA_SAVE_FLASH128 && g->flash.bank != 0) return false;
    const gba_eeprom *e = &g->eeprom;
    if (e->addr_bits != 0 && e->addr_bits != 6 && e->addr_bits != 14) return false;
    if (g->save_bytes && e->read_addr >= g->save_bytes / 8u && e->read_pos >= 0) return false;
    if (g->rtc_base_cycles > g->cycles) return false;
    if (g->dma_active) return false;                 /* nunca a mitad de una DMA */
    for (int i = 0; i < 4; i++) if (g->apu.ch[i].enabled && g->apu.ch[i].timer <= 0) return false;
    if (g->apu.fs_counter == 0) return false;
    /* Los canales de pulso indexan 8 pasos de duty (apu.c, desplazamiento 7 - pos). */
    if (g->apu.ch[0].pos > 7 || g->apu.ch[1].pos > 7) return false;
    /* Mismos límites que mantienen cart.c (RTC por GPIO) y gba_rtc_set_time. */
    if (g->rtc.offset > GBA_RTC_MAX_OFFSET || g->rtc.offset < -GBA_RTC_MAX_OFFSET) return false;
    if (g->rtc_base > GBA_RTC_MAX_OFFSET * 2 || g->rtc_base < -GBA_RTC_MAX_OFFSET * 2) return false;
    return true;
}

size_t gba_state_size(const gba *g)
{
    if (!g || !g->rom) return 0;
    visitor v = {V_MEASURE, NULL, NULL, 0, 0, false};
    visit(&v, (gba *)g);
    return HEADER_BYTES + v.pos + CRC_BYTES;
}

static void put32(uint8_t *p, uint32_t x)
{
    p[0] = (uint8_t)x; p[1] = (uint8_t)(x >> 8); p[2] = (uint8_t)(x >> 16); p[3] = (uint8_t)(x >> 24);
}

static uint32_t get32(const uint8_t *p)
{
    return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
}

gba_result gba_state_save(const gba *g, uint8_t *out, size_t cap)
{
    if (!g || !out) return GBA_ERR_NULL_ARG;
    if (!g->rom) return GBA_ERR_NO_ROM;
    size_t total = gba_state_size(g);
    if (cap < total) return GBA_ERR_BUFFER_TOO_SMALL;
    memcpy(out, "PGBA", 4);
    put32(out + 4, STATE_VERSION);
    memcpy(out + 8, g->fingerprint, 32);
    put32(out + 40, (uint32_t)(total - HEADER_BYTES - CRC_BYTES));
    visitor v = {V_SAVE, out + HEADER_BYTES, NULL, 0, total, false};
    visit(&v, (gba *)g);
    put32(out + total - CRC_BYTES, gba_crc32(out, total - CRC_BYTES));
    return GBA_OK;
}

gba_result gba_state_load(gba *g, const uint8_t *data, size_t len)
{
    if (!g || !data) return GBA_ERR_NULL_ARG;
    if (!g->rom) return GBA_ERR_NO_ROM;
    if (len < HEADER_BYTES + CRC_BYTES || memcmp(data, "PGBA", 4) != 0) return GBA_ERR_STATE_MAGIC;
    if (get32(data + 4) != STATE_VERSION) return GBA_ERR_STATE_VERSION;
    if (memcmp(data + 8, g->fingerprint, 32) != 0) return GBA_ERR_STATE_ROM_MISMATCH;
    size_t payload = get32(data + 40);
    if (len != gba_state_size(g) || payload != len - HEADER_BYTES - CRC_BYTES) return GBA_ERR_STATE_CORRUPT;
    if (gba_crc32(data, len - CRC_BYTES) != get32(data + len - CRC_BYTES)) return GBA_ERR_STATE_CORRUPT;
    /* Copia de trabajo (~1,3 MB): se reserva aquí, fuera de gba_run_frame. */
    gba *tmp = malloc(sizeof *tmp);
    if (!tmp) return GBA_ERR_OUT_OF_MEMORY;
    memcpy(tmp, g, sizeof *tmp);
    visitor v = {V_LOAD, NULL, data + HEADER_BYTES, 0, payload, false};
    visit(&v, tmp);
    /* El medio de guardado debe coincidir con el de la sesión: un estado de otra
     * configuración cambiaría el tamaño del .sav que la app escribe. */
    bool same_media = tmp->save_type == g->save_type && tmp->save_bytes == g->save_bytes &&
                      tmp->has_rtc == g->has_rtc;
    if (v.bad || v.pos != payload || !same_media || !consistent(tmp)) {
        free(tmp);
        return GBA_ERR_STATE_CORRUPT;
    }
    tmp->apu.mix_dirty = true;
    memcpy(g, tmp, sizeof *g);
    free(tmp);
    gba_bus_update_waitstates(g);
    return GBA_OK;
}
