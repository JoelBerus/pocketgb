/*
 * apu.c — sonido DMG (docs/03-core-spec.md §APU; Pan Docs "Audio" y "Audio details").
 *
 * Canales: 1 (pulso + sweep), 2 (pulso), 3 (onda), 4 (ruido). gb_tick solo
 * acumula T-ciclos pendientes; apu_sync los procesa de evento en evento
 * ("catch-up") antes de cada lectura o escritura de un registro de sonido, de
 * cada paso del frame sequencer y al final de cada gb_run_frame/gb_run_cycles.
 * Así cada escritura se oye en su ciclo exacto (A9: gritos de Pikachu) sin
 * pagar el APU en cada M-ciclo. El frame sequencer (512 Hz) lo dispara
 * timer.c en el flanco de bajada del bit 12 del contador DIV.
 *
 * Salida: DAC por canal (−15..15), NR51 (paneo) y NR50 (volumen), caja
 * integradora hasta `opts.sample_rate`, pasa-altos tipo condensador y un
 * anillo de frames estéreo int16 que el frontend vacía con gb_audio_read.
 */
#include <string.h>

#include "internal.h"

enum { NR10 = 0x00, NR11, NR12, NR13, NR14, NR21 = 0x06, NR22, NR23, NR24,
       NR30 = 0x0A, NR31, NR32, NR33, NR34, NR41 = 0x10, NR42, NR43, NR44,
       NR50 = 0x14, NR51, NR52, WAVE = 0x20 };

/* Bits que leen 1 en FF10–FF3F (Pan Docs, Sound registers). La wave RAM lee tal cual. */
static const uint8_t read_mask[0x30] = {
    0x80, 0x3F, 0x00, 0xFF, 0xBF,                           /* NR10–NR14 */
    0xFF, 0x3F, 0x00, 0xFF, 0xBF,                           /* FF15, NR21–NR24 */
    0x7F, 0xFF, 0x9F, 0xFF, 0xBF,                           /* NR30–NR34 */
    0xFF, 0xFF, 0x00, 0x00, 0xBF,                           /* FF1F, NR41–NR44 */
    0x00, 0x00, 0x70,                                       /* NR50–NR52 */
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,   /* FF27–FF2F */
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0          /* FF30–FF3F */
};

/* Valores post-boot de FF10–FF25 en DMG (Pan Docs, Power Up Sequence). NR52 = F1 aparte. */
static const uint8_t boot_regs[0x16] = {
    0x80, 0xBF, 0xF3, 0xFF, 0xBF, 0xFF, 0x3F, 0x00, 0xFF, 0xBF, 0x7F, 0xFF,
    0x9F, 0xFF, 0xBF, 0xFF, 0xFF, 0x00, 0x00, 0xBF, 0x77, 0xF3
};

static const uint8_t duty_table[4] = { 0x01, 0x81, 0x87, 0x7E };   /* bit 7 = primer paso */
static const uint8_t noise_divisor[8] = { 8, 16, 32, 48, 64, 80, 96, 112 };

static bool length_step_next(const struct gb_apu *a)
{
    /* La longitud se cuenta en los pasos 0, 2, 4 y 6: si el siguiente es impar,
     * estamos en la "primera mitad" del periodo de longitud. */
    return (a->fs_step & 1) == 0;
}

static uint16_t length_max(int ch)
{
    return ch == 2 ? 256 : 64;
}

static int32_t channel_period(const struct gb_apu *a, int ch)
{
    const struct gb_apu_ch *c = &a->ch[ch];
    switch (ch) {
    case 0: case 1: return (2048 - c->freq) * 4;
    case 2:         return (2048 - c->freq) * 2;
    default: {
        uint8_t nr43 = a->regs[NR43];
        return (int32_t)noise_divisor[nr43 & 7] << (nr43 >> 4);
    }
    }
}

/* ---- Sweep del canal 1 ---- */

static uint16_t sweep_calc(struct gb_apu *a)
{
    uint8_t nr10 = a->regs[NR10];
    uint16_t delta = (uint16_t)(a->sweep_shadow >> (nr10 & 7));
    uint16_t next;
    if (nr10 & 0x08) {
        next = (uint16_t)(a->sweep_shadow - delta);
        a->sweep_neg_used = true;
    } else {
        next = (uint16_t)(a->sweep_shadow + delta);
    }
    if (next > 2047)
        a->ch[0].enabled = false;
    return next;
}

static void sweep_clock(struct gb_apu *a)
{
    uint8_t period = (a->regs[NR10] >> 4) & 7;
    if (a->sweep_timer > 0)
        a->sweep_timer--;
    if (a->sweep_timer != 0)
        return;
    a->sweep_timer = period ? period : 8;
    if (!a->sweep_enabled || period == 0)
        return;
    uint16_t next = sweep_calc(a);
    if (next <= 2047 && (a->regs[NR10] & 7)) {
        a->sweep_shadow = next;
        a->ch[0].freq = next;
        (void)sweep_calc(a);   /* segunda comprobación: solo desborde */
    }
}

/* ---- Frame sequencer: longitud (256 Hz), sweep (128 Hz), envolvente (64 Hz) ---- */

static void length_clock(struct gb_apu_ch *c)
{
    if (c->len_en && c->length > 0 && --c->length == 0)
        c->enabled = false;
}

static void envelope_clock(struct gb_apu_env *e)
{
    if (e->period == 0)
        return;
    if (e->timer > 0)
        e->timer--;
    if (e->timer != 0)
        return;
    e->timer = e->period;
    if (e->up && e->vol < 15)
        e->vol++;
    else if (!e->up && e->vol > 0)
        e->vol--;
}

void apu_frame_step(gb *g)
{
    struct gb_apu *a = &g->apu;
    apu_sync(g);
    if (!a->power)
        return;
    a->mix_dirty = true;
    uint8_t step = a->fs_step;
    if ((step & 1) == 0)
        for (int i = 0; i < 4; i++)
            length_clock(&a->ch[i]);
    if (step == 2 || step == 6)
        sweep_clock(a);
    if (step == 7) {
        envelope_clock(&a->ch[0].env);
        envelope_clock(&a->ch[1].env);
        envelope_clock(&a->ch[3].env);
    }
    a->fs_step = (uint8_t)((step + 1) & 7);
}

/* ---- Registros ---- */

static void env_write(struct gb_apu_ch *c, uint8_t v)
{
    c->env.init = v >> 4;
    c->env.up = (v & 0x08) != 0;
    c->env.period = v & 7;
    c->dac = (v & 0xF8) != 0;
    if (!c->dac)
        c->enabled = false;
}

static void trigger(struct gb_apu *a, int ch)
{
    struct gb_apu_ch *c = &a->ch[ch];
    c->enabled = c->dac;
    if (c->length == 0) {
        c->length = length_max(ch);
        if (c->len_en && !length_step_next(a))
            c->length--;
    }
    c->timer = channel_period(a, ch);
    if (ch != 2) {
        c->env.vol = c->env.init;
        c->env.timer = c->env.period ? c->env.period : 8;
    }
    if (ch == 2) {
        c->pos = 0;
    } else if (ch == 3) {
        a->lfsr = 0x7FFF;
    } else if (ch == 0) {
        uint8_t nr10 = a->regs[NR10];
        uint8_t period = (nr10 >> 4) & 7;
        a->sweep_shadow = c->freq;
        a->sweep_timer = period ? period : 8;
        a->sweep_enabled = period != 0 || (nr10 & 7) != 0;
        a->sweep_neg_used = false;
        if (nr10 & 7)
            (void)sweep_calc(a);
    }
}

/* NRx4: bits altos de la frecuencia, habilitar longitud y disparo. */
static void control_write(struct gb_apu *a, int ch, uint8_t v)
{
    struct gb_apu_ch *c = &a->ch[ch];
    if (ch != 3)
        c->freq = (uint16_t)((c->freq & 0xFF) | (v & 7) << 8);
    bool was = c->len_en;
    c->len_en = (v & 0x40) != 0;
    /* Habilitar la longitud en la primera mitad del periodo la cuenta una vez extra. */
    if (!was && c->len_en && !length_step_next(a) && c->length > 0) {
        if (--c->length == 0 && !(v & 0x80))
            c->enabled = false;
    }
    if (v & 0x80)
        trigger(a, ch);
}

static void power_off(struct gb_apu *a)
{
    for (int i = 0; i < 4; i++) {
        uint16_t len = a->ch[i].length;   /* DMG: los contadores de longitud se conservan */
        memset(&a->ch[i], 0, sizeof a->ch[i]);
        a->ch[i].length = len;
    }
    memset(a->regs, 0, WAVE);             /* todo salvo la wave RAM */
    a->sweep_timer = 0;
    a->sweep_shadow = 0;
    a->sweep_enabled = a->sweep_neg_used = false;
    a->power = false;
}

uint8_t apu_read(gb *g, uint16_t addr)
{
    apu_sync(g);
    const struct gb_apu *a = &g->apu;
    unsigned i = addr - 0xFF10u;
    if (i == NR52) {
        uint8_t v = (uint8_t)(0x70 | (a->power ? 0x80 : 0));
        for (int c = 0; c < 4; c++)
            if (a->ch[c].enabled)
                v |= (uint8_t)(1u << c);
        return v;
    }
    return (uint8_t)(a->regs[i] | read_mask[i]);
}

void apu_write(gb *g, uint16_t addr, uint8_t v)
{
    apu_sync(g);
    struct gb_apu *a = &g->apu;
    unsigned i = addr - 0xFF10u;
    a->mix_dirty = true;
    if (i >= WAVE) {
        a->regs[i] = v;                   /* la wave RAM se escribe siempre */
        return;
    }
    if (i == NR52) {
        if (a->power && !(v & 0x80)) {
            power_off(a);
        } else if (!a->power && (v & 0x80)) {
            a->power = true;
            a->fs_step = 0;
            for (int c = 0; c < 4; c++)
                a->ch[c].pos = 0;
            a->wave_sample = 0;
        }
        return;
    }
    if (!a->power) {
        /* DMG: apagado solo se pueden escribir los contadores de longitud. */
        if (i == NR11 || i == NR21 || i == NR41)
            a->ch[i == NR11 ? 0 : i == NR21 ? 1 : 3].length = (uint16_t)(64 - (v & 0x3F));
        else if (i == NR31)
            a->ch[2].length = (uint16_t)(256 - v);
        return;
    }
    a->regs[i] = v;
    switch (i) {
    case NR10:
        /* Quitar el modo negativo después de haberlo usado apaga el canal 1. */
        if (!(v & 0x08) && a->sweep_neg_used)
            a->ch[0].enabled = false;
        break;
    case NR11: case NR21: {
        struct gb_apu_ch *c = &a->ch[i == NR11 ? 0 : 1];
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
        a->ch[2].dac = (v & 0x80) != 0;
        if (!a->ch[2].dac)
            a->ch[2].enabled = false;
        break;
    case NR31: a->ch[2].length = (uint16_t)(256 - v); break;
    case NR41: a->ch[3].length = (uint16_t)(64 - (v & 0x3F)); break;
    default: break;                       /* NR32, NR43, NR50, NR51: se leen de regs */
    }
}

/* ---- Generadores y mezcla ---- */

/* Salida digital 0..15 de un canal (0 si está parado). */
static uint8_t channel_digital(const struct gb_apu *a, int ch)
{
    const struct gb_apu_ch *c = &a->ch[ch];
    if (!c->enabled)
        return 0;
    switch (ch) {
    case 0: case 1:
        return ((duty_table[c->duty] >> (7 - c->pos)) & 1) ? c->env.vol : 0;
    case 2: {
        uint8_t code = (a->regs[NR32] >> 5) & 3;
        return code ? (uint8_t)(a->wave_sample >> (code - 1)) : 0;
    }
    default:
        return (a->lfsr & 1) ? 0 : c->env.vol;
    }
}

/* Paso del generador de un canal (el temporizador acaba de llegar a 0). */
static void channel_step(struct gb_apu *a, int ch)
{
    struct gb_apu_ch *c = &a->ch[ch];
    int32_t period = channel_period(a, ch);
    c->timer += period > 0 ? period : 8;
    if (ch <= 1) {
        c->pos = (uint8_t)((c->pos + 1) & 7);
    } else if (ch == 2) {
        c->pos = (uint8_t)((c->pos + 1) & 31);
        uint8_t byte = a->regs[WAVE + c->pos / 2];
        a->wave_sample = (c->pos & 1) ? (byte & 0x0F) : (byte >> 4);
    } else {
        uint16_t x = (uint16_t)((a->lfsr ^ (a->lfsr >> 1)) & 1);
        a->lfsr = (uint16_t)((a->lfsr >> 1) | (x << 14));
        if (a->regs[NR43] & 0x08)
            a->lfsr = (uint16_t)((a->lfsr & ~(1u << 6)) | (x << 6));
    }
}

/* Mezcla de los cuatro DAC con NR51 (paneo) y NR50 (volumen). */
static void mix(struct gb_apu *a)
{
    int32_t left = 0, right = 0;
    if (a->power) {
        uint8_t nr51 = a->regs[NR51];
        for (int ch = 0; ch < 4; ch++) {
            if (!a->ch[ch].dac)
                continue;                 /* DAC apagado: salida 0 */
            int32_t v = channel_digital(a, ch) * 2 - 15;
            if (nr51 & (0x10u << ch))
                left += v;
            if (nr51 & (0x01u << ch))
                right += v;
        }
        uint8_t nr50 = a->regs[NR50];
        left *= ((nr50 >> 4) & 7) + 1;
        right *= (nr50 & 7) + 1;
    }
    a->mix_l = left;
    a->mix_r = right;
    a->mix_dirty = false;
}

static void emit_sample(gb *g)
{
    struct gb_apu *a = &g->apu;
    /* Pasa-altos tipo condensador (quita la componente continua, como el hardware). */
    double in_l = a->acc_n ? (double)a->acc_l / a->acc_n : 0.0;
    double in_r = a->acc_n ? (double)a->acc_r / a->acc_n : 0.0;
    double out_l = in_l - a->cap_l, out_r = in_r - a->cap_r;
    a->cap_l = in_l - out_l * a->charge;
    a->cap_r = in_r - out_r * a->charge;
    a->acc_l = a->acc_r = 0;
    a->acc_n = 0;

    if (a->count == APU_BUF_FRAMES) {
        a->dropped++;                     /* el frontend no está leyendo: se descarta */
        return;
    }
    /* Mezcla ±480 (4 canales × ±15 × volumen 8). Tras el pasa-altos, un salto de
     * extremo a extremo puede llegar a ±960: ×32 → ±30720, nunca satura. */
    double s[2] = { out_l * 32.0, out_r * 32.0 };
    uint32_t idx = (a->head + a->count) % APU_BUF_FRAMES;
    for (int k = 0; k < 2; k++) {
        double v = s[k] > 32767.0 ? 32767.0 : s[k] < -32768.0 ? -32768.0 : s[k];
        a->buf[idx * 2 + k] = (int16_t)v;
    }
    a->count++;
}

void apu_sync(gb *g)
{
    struct gb_apu *a = &g->apu;
    uint32_t left = a->pending, rate = g->opts.sample_rate;
    a->pending = 0;
    while (left > 0) {
        /* Tramo hasta el próximo evento: paso de un canal activo o muestra de salida. */
        uint32_t step = left;
        if (a->power)
            for (int ch = 0; ch < 4; ch++)
                if (a->ch[ch].enabled && a->ch[ch].timer > 0 && (uint32_t)a->ch[ch].timer < step)
                    step = (uint32_t)a->ch[ch].timer;
        if (rate) {
            uint32_t to_sample = (GB_CLOCK_HZ - a->phase + rate - 1) / rate;
            if (to_sample == 0)
                to_sample = 1;
            if (to_sample < step)
                step = to_sample;
            if (a->mix_dirty)
                mix(a);
            a->acc_l += a->mix_l * (int32_t)step;
            a->acc_r += a->mix_r * (int32_t)step;
            a->acc_n += step;
            a->phase += rate * step;
            if (a->phase >= GB_CLOCK_HZ) {
                a->phase -= GB_CLOCK_HZ;
                emit_sample(g);
            }
        }
        /* El disparo recarga el temporizador: un canal parado no necesita contar. */
        if (a->power)
            for (int ch = 0; ch < 4; ch++) {
                struct gb_apu_ch *c = &a->ch[ch];
                if (!c->enabled)
                    continue;
                c->timer -= (int32_t)step;
                if (c->timer <= 0) {
                    channel_step(a, ch);
                    a->mix_dirty = true;
                }
            }
        left -= step;
    }
}

void apu_reset(gb *g)
{
    struct gb_apu *a = &g->apu;
    memset(a, 0, sizeof *a);
    a->lfsr = 0x7FFF;
    /* Carga del condensador por muestra: 0.999958^(T-ciclos por muestra) (Pan Docs). */
    a->charge = 1.0;
    if (g->opts.sample_rate) {
        uint32_t cycles = GB_CLOCK_HZ / g->opts.sample_rate;
        for (uint32_t i = 0; i < cycles; i++)
            a->charge *= 0.999958;
    }
    /* Estado post-boot: APU encendido y registros de Pan Docs; el disparo de NR14
     * deja el canal 1 activo, como indica NR52 = F1 tras el boot ROM. */
    a->power = true;
    for (unsigned i = 0; i < sizeof boot_regs; i++)
        apu_write(g, (uint16_t)(0xFF10 + i), boot_regs[i]);
    a->ch[0].env.vol = 0;                 /* el sonido del logo ya se ha apagado */
    a->mix_dirty = true;
}

/* ---- API pública ---- */

size_t gb_audio_available(const gb *g)
{
    return (g && g->rom_loaded) ? g->apu.count : 0;
}

size_t gb_audio_read(gb *g, int16_t *out, size_t max_frames)
{
    if (!g || !g->rom_loaded || !out)
        return 0;
    apu_sync(g);
    struct gb_apu *a = &g->apu;
    size_t n = a->count < max_frames ? a->count : max_frames;
    for (size_t i = 0; i < n; i++) {
        uint32_t idx = (a->head + (uint32_t)i) % APU_BUF_FRAMES;
        out[2 * i] = a->buf[idx * 2];
        out[2 * i + 1] = a->buf[idx * 2 + 1];
    }
    a->head = (uint32_t)((a->head + n) % APU_BUF_FRAMES);
    a->count -= (uint32_t)n;
    return n;
}
