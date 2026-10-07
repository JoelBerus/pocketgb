/*
 * apu.c — sonido del GBA (GBATEK §GBA Sound Controller): los cuatro canales
 * PSG heredados del Game Boy y los dos canales DirectSound (FIFO A/B).
 *
 * Adaptación propia del diseño de core/src/apu.c (mismo proyecto, MIT): los
 * canales PSG cuentan en ciclos del GBA (cuatro por ciclo del Game Boy), el
 * secuenciador de 512 Hz va cada 32768 ciclos y el canal de onda tiene dos
 * bancos de 32 muestras. gba_tick solo acumula ciclos pendientes y
 * gba_apu_sync los procesa de evento en evento ("catch-up") antes de tocar un
 * registro de sonido, al sacar una muestra de una FIFO y al final de cada
 * frame. Salida: caja integradora a `opts.sample_rate`, pasa-altos tipo
 * condensador y un anillo de frames estéreo int16.
 */
#include "gba_internal.h"

enum { NR10 = 0, NR11, NR12, NR13, NR14, NR21, NR22, NR23, NR24, NR30, NR31, NR32,
       NR33, NR34, NR41, NR42, NR43, NR44, NR50, NR51, NR52 };

#define FS_PERIOD 32768u                    /* 16 777 216 / 512 */

static const uint8_t duty_table[4] = {0x01, 0x81, 0x87, 0x7E};
static const uint8_t noise_divisor[8] = {8, 16, 32, 48, 64, 80, 96, 112};

/* Registro del GBA (byte) → registro estilo Game Boy; 0xFF = no es PSG. */
static uint8_t psg_index(uint32_t off)
{
    switch (off) {
    case 0x60: return NR10;
    case 0x62: return NR11; case 0x63: return NR12;
    case 0x64: return NR13; case 0x65: return NR14;
    case 0x68: return NR21; case 0x69: return NR22;
    case 0x6C: return NR23; case 0x6D: return NR24;
    case 0x70: return NR30;
    case 0x72: return NR31; case 0x73: return NR32;
    case 0x74: return NR33; case 0x75: return NR34;
    case 0x78: return NR41; case 0x79: return NR42;
    case 0x7C: return NR43; case 0x7D: return NR44;
    case 0x80: return NR50; case 0x81: return NR51;
    case 0x84: return NR52;
    default: return 0xFF;
    }
}

static bool length_step_next(const gba_apu *a) { return (a->fs_step & 1) == 0; }
static uint16_t length_max(int ch) { return ch == 2 ? 256 : 64; }

static int32_t channel_period(const gba_apu *a, int ch)
{
    const gba_apu_ch *c = &a->ch[ch];
    switch (ch) {
    case 0: case 1: return (2048 - c->freq) * 16;
    case 2: return (2048 - c->freq) * 8;
    default: {
        uint8_t nr43 = a->regs[NR43];
        return ((int32_t)noise_divisor[nr43 & 7] << (nr43 >> 4)) * 4;
    }
    }
}

static uint16_t sweep_calc(gba_apu *a)
{
    uint8_t nr10 = a->regs[NR10];
    uint16_t delta = (uint16_t)(a->sweep_shadow >> (nr10 & 7));
    uint16_t next;
    if (nr10 & 0x08) { next = (uint16_t)(a->sweep_shadow - delta); a->sweep_neg_used = true; }
    else next = (uint16_t)(a->sweep_shadow + delta);
    if (next > 2047) a->ch[0].enabled = false;
    return next;
}

static void sweep_clock(gba_apu *a)
{
    uint8_t period = (a->regs[NR10] >> 4) & 7;
    if (a->sweep_timer > 0) a->sweep_timer--;
    if (a->sweep_timer != 0) return;
    a->sweep_timer = period ? period : 8;
    if (!a->sweep_enabled || period == 0) return;
    uint16_t next = sweep_calc(a);
    if (next <= 2047 && (a->regs[NR10] & 7)) {
        a->sweep_shadow = next;
        a->ch[0].freq = next;
        (void)sweep_calc(a);
    }
}

static void length_clock(gba_apu_ch *c)
{
    if (c->len_en && c->length > 0 && --c->length == 0) c->enabled = false;
}

static void envelope_clock(gba_apu_env *e)
{
    if (e->period == 0) return;
    if (e->timer > 0) e->timer--;
    if (e->timer != 0) return;
    e->timer = e->period;
    if (e->up && e->vol < 15) e->vol++;
    else if (!e->up && e->vol > 0) e->vol--;
}

static void frame_step(gba_apu *a)
{
    if (!a->power) return;
    a->mix_dirty = true;
    uint8_t step = a->fs_step;
    if ((step & 1) == 0)
        for (int i = 0; i < 4; i++) length_clock(&a->ch[i]);
    if (step == 2 || step == 6) sweep_clock(a);
    if (step == 7) {
        envelope_clock(&a->ch[0].env);
        envelope_clock(&a->ch[1].env);
        envelope_clock(&a->ch[3].env);
    }
    a->fs_step = (uint8_t)((step + 1) & 7);
}

static void env_write(gba_apu_ch *c, uint8_t v)
{
    c->env.init = v >> 4;
    c->env.up = (v & 0x08) != 0;
    c->env.period = v & 7;
    c->dac = (v & 0xF8) != 0;
    if (!c->dac) c->enabled = false;
}

static void trigger(gba_apu *a, int ch)
{
    gba_apu_ch *c = &a->ch[ch];
    c->enabled = c->dac;
    if (c->length == 0) {
        c->length = length_max(ch);
        if (c->len_en && !length_step_next(a)) c->length--;
    }
    c->timer = channel_period(a, ch);
    if (ch != 2) {
        c->env.vol = c->env.init;
        c->env.timer = c->env.period ? c->env.period : 8;
    }
    if (ch == 2) {
        c->pos = 0;
        a->wave_bank_play = (a->regs[NR30] >> 6) & 1u;
    } else if (ch == 3) {
        a->lfsr = 0x7FFF;
    } else if (ch == 0) {
        uint8_t nr10 = a->regs[NR10], period = (nr10 >> 4) & 7;
        a->sweep_shadow = c->freq;
        a->sweep_timer = period ? period : 8;
        a->sweep_enabled = period != 0 || (nr10 & 7) != 0;
        a->sweep_neg_used = false;
        if (nr10 & 7) (void)sweep_calc(a);
    }
}

static void control_write(gba_apu *a, int ch, uint8_t v)
{
    gba_apu_ch *c = &a->ch[ch];
    if (ch != 3) c->freq = (uint16_t)((c->freq & 0xFF) | (v & 7) << 8);
    bool was = c->len_en;
    c->len_en = (v & 0x40) != 0;
    if (!was && c->len_en && !length_step_next(a) && c->length > 0) {
        if (--c->length == 0 && !(v & 0x80)) c->enabled = false;
    }
    if (v & 0x80) trigger(a, ch);
}

static void power_off(gba_apu *a)
{
    for (int i = 0; i < 4; i++) memset(&a->ch[i], 0, sizeof a->ch[i]);
    memset(a->regs, 0, sizeof a->regs);
    a->sweep_timer = 0;
    a->sweep_shadow = 0;
    a->sweep_enabled = a->sweep_neg_used = false;
    a->power = false;
}

/* ------------------------------------------------------------ generadores */

static uint8_t wave_nibble(const gba_apu *a, unsigned pos)
{
    /* 32 muestras por banco; con "dimensión 2" se recorren los dos bancos. */
    /* GBATEK: el bit 6 elige el banco que suena (con efecto inmediato). */
    unsigned bank = (a->regs[NR30] >> 6) & 1u;
    if (a->regs[NR30] & 0x20) bank = (bank + (pos >> 5)) & 1u;
    uint8_t byte = a->wave[bank][(pos & 31u) >> 1];
    return (pos & 1u) ? (byte & 0x0F) : (byte >> 4);
}

static int32_t channel_digital(const gba_apu *a, int ch)
{
    const gba_apu_ch *c = &a->ch[ch];
    if (!c->enabled) return 0;
    switch (ch) {
    case 0: case 1:
        return ((duty_table[c->duty] >> (7 - c->pos)) & 1) ? c->env.vol : 0;
    case 2: {
        uint8_t nr32 = a->regs[NR32];
        int32_t s = a->wave_sample;
        if (nr32 & 0x80) return s * 3 / 4;          /* forzar 75 % */
        uint8_t code = (nr32 >> 5) & 3;
        return code ? s >> (code - 1) : 0;
    }
    default:
        return (a->lfsr & 1) ? 0 : c->env.vol;
    }
}

static void channel_step(gba_apu *a, int ch)
{
    gba_apu_ch *c = &a->ch[ch];
    int32_t period = channel_period(a, ch);
    c->timer += period > 0 ? period : 32;
    if (ch <= 1) {
        c->pos = (uint8_t)((c->pos + 1) & 7);
    } else if (ch == 2) {
        unsigned span = (a->regs[NR30] & 0x20) ? 64u : 32u;
        c->pos = (uint8_t)((c->pos + 1) % span);
        a->wave_sample = wave_nibble(a, c->pos);
    } else if ((a->regs[NR43] >> 4) < 14) {
        uint16_t x = (uint16_t)((a->lfsr ^ (a->lfsr >> 1)) & 1);
        a->lfsr = (uint16_t)((a->lfsr >> 1) | (x << 14));
        if (a->regs[NR43] & 0x08) a->lfsr = (uint16_t)((a->lfsr & ~(1u << 6)) | (x << 6));
    }
}

/* PSG (±480 a volumen máximo, como el GB) × SOUNDCNT_H bits 0-1 (25/50/100 %),
 * más los DirectSound (muestra int8 × 4 al 100 %, × 2 al 50 %): un DirectSound
 * a fondo suena 4 veces más que un canal PSG a fondo, como en el oráculo. */
static void mix(gba *g)
{
    gba_apu *a = &g->apu;
    int32_t left = 0, right = 0;
    uint16_t cnth = a->cnt_h;
    if (a->power) {
        uint8_t nr51 = a->regs[NR51];
        int32_t pl = 0, pr = 0;
        for (int ch = 0; ch < 4; ch++) {
            if (!a->ch[ch].dac) continue;
            int32_t v = channel_digital(a, ch) * 2 - 15;
            if (nr51 & (0x10u << ch)) pl += v;
            if (nr51 & (0x01u << ch)) pr += v;
        }
        uint8_t nr50 = a->regs[NR50];
        pl *= ((nr50 >> 4) & 7) + 1;
        pr *= (nr50 & 7) + 1;
        unsigned ratio = cnth & 3u;                   /* 0 = 25 %, 1 = 50 %, 2 = 100 % */
        if (ratio == 3) ratio = 2;
        left = pl >> (2 - ratio);
        right = pr >> (2 - ratio);
        for (int k = 0; k < 2; k++) {
            int32_t s = (int32_t)a->fifo_sample[k] * ((cnth & (4u << k)) ? 4 : 2);
            if (cnth & (0x200u << (k * 4))) left += s;
            if (cnth & (0x100u << (k * 4))) right += s;
        }
    }
    a->mix_l = left;
    a->mix_r = right;
    a->mix_dirty = false;
}

static void emit_sample(gba *g)
{
    gba_apu *a = &g->apu;
    double in_l = a->acc_n ? (double)a->acc_l / a->acc_n : 0.0;
    double in_r = a->acc_n ? (double)a->acc_r / a->acc_n : 0.0;
    double out_l = in_l - a->cap_l, out_r = in_r - a->cap_r;
    a->cap_l = in_l - out_l * a->charge;
    a->cap_r = in_r - out_r * a->charge;
    a->acc_l = a->acc_r = 0;
    a->acc_n = 0;
    if (a->count == GBA_APU_BUF_FRAMES) { a->dropped++; return; }
    /* Mezcla máxima ±1504 (PSG ±480 + 2 × ±512): ×20 → ±30080. Un salto de extremo
     * a extremo tras el pasa-altos puede pasar de ahí y se satura. */
    double s[2] = {out_l * 20.0, out_r * 20.0};
    uint32_t idx = (a->head + a->count) % GBA_APU_BUF_FRAMES;
    for (int k = 0; k < 2; k++) {
        double v = s[k] > 32767.0 ? 32767.0 : s[k] < -32768.0 ? -32768.0 : s[k];
        a->buf[idx * 2 + k] = (int16_t)v;
    }
    a->count++;
}

void gba_apu_sync(gba *g)
{
    gba_apu *a = &g->apu;
    uint32_t left = a->pending, rate = g->opts.sample_rate;
    a->pending = 0;
    while (left > 0) {
        uint32_t step = left;
        if (a->fs_counter < step) step = a->fs_counter ? a->fs_counter : 1;
        if (a->power)
            for (int ch = 0; ch < 4; ch++)
                if (a->ch[ch].enabled && a->ch[ch].timer > 0 && (uint32_t)a->ch[ch].timer < step)
                    step = (uint32_t)a->ch[ch].timer;
        if (rate) {
            uint32_t to_sample = (GBA_CLOCK_HZ - a->phase + rate - 1) / rate;
            if (to_sample == 0) to_sample = 1;
            if (to_sample < step) step = to_sample;
            if (a->mix_dirty) mix(g);
            a->acc_l += (int64_t)a->mix_l * step;
            a->acc_r += (int64_t)a->mix_r * step;
            a->acc_n += step;
            a->phase += rate * step;
            if (a->phase >= GBA_CLOCK_HZ) {
                a->phase -= GBA_CLOCK_HZ;
                emit_sample(g);
            }
        }
        if (a->power)
            for (int ch = 0; ch < 4; ch++) {
                gba_apu_ch *c = &a->ch[ch];
                if (!c->enabled) continue;
                c->timer -= (int32_t)step;
                if (c->timer <= 0) {
                    while (c->timer <= 0) channel_step(a, ch);
                    a->mix_dirty = true;
                }
            }
        a->fs_counter -= step;
        if (a->fs_counter == 0) {
            a->fs_counter = FS_PERIOD;
            frame_step(a);
        }
        left -= step;
    }
}

/* ------------------------------------------------------------ DirectSound */

static void fifo_reset(gba_apu *a, int k)
{
    a->fifo_len[k] = 0;
    a->fifo_head[k] = 0;
}

void gba_apu_fifo_write(gba *g, int k, uint32_t v, unsigned bytes)
{
    gba_apu *a = &g->apu;
    for (unsigned i = 0; i < bytes; i++) {
        if (a->fifo_len[k] >= 32) break;          /* llena: se ignora */
        a->fifo[k][(a->fifo_head[k] + a->fifo_len[k]) & 31u] = (int8_t)(v >> (8 * i));
        a->fifo_len[k]++;
    }
}

void gba_apu_timer_overflow(gba *g, int timer)
{
    gba_apu *a = &g->apu;
    if (!a->power) return;
    for (int k = 0; k < 2; k++) {
        uint16_t cnth = a->cnt_h;
        if ((int)((cnth >> (10 + k * 4)) & 1u) != timer) continue;
        if (!(cnth & (0x300u << (k * 4)))) continue;     /* canal sin salida */
        gba_apu_sync(g);
        if (a->fifo_len[k]) {
            a->fifo_sample[k] = a->fifo[k][a->fifo_head[k]];
            a->fifo_head[k] = (uint8_t)((a->fifo_head[k] + 1) & 31u);
            a->fifo_len[k]--;
        }
        a->mix_dirty = true;
        if (a->fifo_len[k] <= 16) gba_dma_fifo(g, k == 0 ? 0x040000A0u : 0x040000A4u);
    }
}

/* ------------------------------------------------------------ registros */

uint16_t gba_apu_read16(gba *g, uint32_t off)
{
    gba_apu *a = &g->apu;
    gba_apu_sync(g);
    if (off >= 0x90 && off < 0xA0) {
        /* Se lee el banco que NO suena. */
        unsigned bank = ((a->regs[NR30] >> 6) & 1u) ^ 1u;
        unsigned i = off - 0x90;
        return (uint16_t)(a->wave[bank][i] | (a->wave[bank][i + 1] << 8));
    }
    switch (off) {
    case 0x60: return a->regs[NR10] & 0x7Fu;
    case 0x62: return (uint16_t)((a->regs[NR11] & 0xC0u) | (a->regs[NR12] << 8));
    case 0x64: return (uint16_t)((a->regs[NR14] & 0x40u) << 8);
    case 0x68: return (uint16_t)((a->regs[NR21] & 0xC0u) | (a->regs[NR22] << 8));
    case 0x6C: return (uint16_t)((a->regs[NR24] & 0x40u) << 8);
    case 0x70: return a->regs[NR30] & 0xE0u;
    case 0x72: return (uint16_t)((a->regs[NR32] & 0xE0u) << 8);
    case 0x74: return (uint16_t)((a->regs[NR34] & 0x40u) << 8);
    case 0x78: return (uint16_t)(a->regs[NR42] << 8);
    case 0x7C: return (uint16_t)(a->regs[NR43] | ((a->regs[NR44] & 0x40u) << 8));
    case 0x80: return (uint16_t)((a->regs[NR50] & 0x77u) | (a->regs[NR51] << 8));
    case 0x82: return a->cnt_h & 0x770Fu;
    case 0x84: {
        uint16_t v = a->power ? 0x80 : 0;
        for (int c = 0; c < 4; c++) if (a->ch[c].enabled) v |= (uint16_t)(1u << c);
        return v;
    }
    case 0x88: return a->bias;
    default: return 0;
    }
}

static void psg_write8(gba *g, uint8_t i, uint8_t v)
{
    gba_apu *a = &g->apu;
    a->mix_dirty = true;
    if (i == NR52) {
        if (a->power && !(v & 0x80)) power_off(a);
        else if (!a->power && (v & 0x80)) {
            a->power = true;
            a->fs_step = 0;
            for (int c = 0; c < 4; c++) a->ch[c].pos = 0;
            a->wave_sample = 0;
        }
        return;
    }
    if (!a->power) return;
    a->regs[i] = v;
    switch (i) {
    case NR10: if (!(v & 0x08) && a->sweep_neg_used) a->ch[0].enabled = false; break;
    case NR11: case NR21: {
        gba_apu_ch *c = &a->ch[i == NR11 ? 0 : 1];
        c->duty = v >> 6;
        c->length = (uint16_t)(64 - (v & 0x3F));
        break;
    }
    case NR12: env_write(&a->ch[0], v); break;
    case NR22: env_write(&a->ch[1], v); break;
    case NR42: env_write(&a->ch[3], v); break;
    case NR13: a->ch[0].freq = (uint16_t)((a->ch[0].freq & 0x700) | v); break;
    case NR23: a->ch[1].freq = (uint16_t)((a->ch[1].freq & 0x700) | v); break;
    case NR33: a->ch[2].freq = (uint16_t)((a->ch[2].freq & 0x700) | v); break;
    case NR14: control_write(a, 0, v); break;
    case NR24: control_write(a, 1, v); break;
    case NR34: control_write(a, 2, v); break;
    case NR44: control_write(a, 3, v); break;
    case NR30:
        a->wave_bank_play = (v >> 6) & 1u;
        a->ch[2].dac = (v & 0x80) != 0;
        if (!a->ch[2].dac) a->ch[2].enabled = false;
        break;
    case NR31: a->ch[2].length = (uint16_t)(256 - v); break;
    case NR41: a->ch[3].length = (uint16_t)(64 - (v & 0x3F)); break;
    default: break;
    }
}

void gba_apu_write8(gba *g, uint32_t off, uint8_t v)
{
    gba_apu *a = &g->apu;
    gba_apu_sync(g);
    if (off >= 0x90 && off < 0xA0) {
        unsigned bank = ((a->regs[NR30] >> 6) & 1u) ^ 1u;
        a->wave[bank][off - 0x90] = v;
        return;
    }
    if (off >= 0xA0 && off < 0xA8) {
        gba_apu_fifo_write(g, off >= 0xA4 ? 1 : 0, v, 1);
        return;
    }
    if (off == 0x82 || off == 0x83) {
        uint16_t nv = off == 0x82 ? (uint16_t)((a->cnt_h & 0xFF00u) | v) : (uint16_t)((a->cnt_h & 0x00FFu) | (v << 8));
        gba_apu_write16(g, 0x82, nv);
        return;
    }
    if (off == 0x88 || off == 0x89) {
        a->bias = off == 0x88 ? (uint16_t)((a->bias & 0xFF00u) | v) : (uint16_t)((a->bias & 0x00FFu) | (v << 8));
        a->bias &= 0xC3FEu;
        return;
    }
    uint8_t i = psg_index(off);
    if (i != 0xFF) psg_write8(g, i, v);
}

void gba_apu_write16(gba *g, uint32_t off, uint16_t v)
{
    gba_apu *a = &g->apu;
    if (off == 0x82) {
        gba_apu_sync(g);
        a->cnt_h = v & 0x770Fu;
        if (v & 0x0800u) fifo_reset(a, 0);
        if (v & 0x8000u) fifo_reset(a, 1);
        a->mix_dirty = true;
        return;
    }
    if (off == 0xA0 || off == 0xA2 || off == 0xA4 || off == 0xA6) {
        gba_apu_sync(g);
        gba_apu_fifo_write(g, off >= 0xA4 ? 1 : 0, v, 2);
        return;
    }
    gba_apu_write8(g, off, (uint8_t)v);
    gba_apu_write8(g, off + 1, (uint8_t)(v >> 8));
}

void gba_apu_reset(gba *g)
{
    gba_apu *a = &g->apu;
    memset(a, 0, sizeof *a);
    a->lfsr = 0x7FFF;
    a->bias = 0x200;
    a->fs_counter = FS_PERIOD;
    a->charge = 1.0;
    if (g->opts.sample_rate) {
        /* Mismo pasa-altos que el núcleo GB: 0.999958 por ciclo de 4,19 MHz. */
        uint32_t cycles = GBA_CLOCK_HZ / 4u / g->opts.sample_rate;
        for (uint32_t i = 0; i < cycles; i++) a->charge *= 0.999958;
    }
    a->mix_dirty = true;
}

size_t gba_audio_available(const gba *g)
{
    return (g && g->rom) ? g->apu.count : 0;
}

size_t gba_audio_read(gba *g, int16_t *out, size_t max_frames)
{
    if (!g || !g->rom || !out) return 0;
    gba_apu_sync(g);
    gba_apu *a = &g->apu;
    size_t n = a->count < max_frames ? a->count : max_frames;
    for (size_t i = 0; i < n; i++) {
        uint32_t idx = (a->head + (uint32_t)i) % GBA_APU_BUF_FRAMES;
        out[2 * i] = a->buf[idx * 2];
        out[2 * i + 1] = a->buf[idx * 2 + 1];
    }
    a->head = (uint32_t)((a->head + n) % GBA_APU_BUF_FRAMES);
    a->count -= (uint32_t)n;
    return n;
}
