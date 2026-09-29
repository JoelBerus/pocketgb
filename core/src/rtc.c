/*
 * rtc.c — reloj de tiempo real del MBC3 (docs/03-core-spec.md §MBC; Pan Docs MBC3).
 *
 * El tiempo avanza con el reloj emulado (determinista). Los contadores tienen
 * el ancho real del chip: S y M de 6 bits, H de 5 y día de 9. Un valor fuera de
 * rango sigue contando hasta desbordar su ancho, y ese desborde pone el registro
 * a 0 SIN acarreo (rtc3test "range tests"). Escribir S reinicia el sub-segundo.
 * Persistencia: bloque de 48 bytes compatible con VBA/BGB al final del .sav.
 */
#include "internal.h"

static const uint8_t reg_mask[RTC_REGS] = { 0x3F, 0x3F, 0x1F, 0xFF, 0xC1 };

void rtc_reset(gb *g, int64_t unix_time)
{
    struct gb_rtc *r = &g->cart.rtc;
    for (int i = 0; i < RTC_REGS; i++)
        r->reg[i] = r->latched[i] = 0;
    r->sub = 0;
    r->wall_sub = 0;
    r->latch_last = 0xFF;
    r->unix = rtc_unix_valid(unix_time) ? unix_time : 0;
}

/* Un segundo del RTC, con los acarreos y desbordes del hardware. */
static void rtc_second(struct gb_rtc *r)
{
    uint8_t *s = &r->reg[RTC_S], *m = &r->reg[RTC_M], *h = &r->reg[RTC_H];
    *s = (uint8_t)((*s + 1) & 0x3F);
    if (*s != 60)
        return;
    *s = 0;
    *m = (uint8_t)((*m + 1) & 0x3F);
    if (*m != 60)
        return;
    *m = 0;
    *h = (uint8_t)((*h + 1) & 0x1F);
    if (*h != 24)
        return;
    *h = 0;
    unsigned day = ((unsigned)(r->reg[RTC_DH] & 1) << 8 | r->reg[RTC_DL]) + 1;
    if (day > 0x1FF) {
        day = 0;
        r->reg[RTC_DH] |= 0x80;   /* acarreo: se queda hasta que el juego lo borre */
    }
    r->reg[RTC_DL] = (uint8_t)day;
    r->reg[RTC_DH] = (uint8_t)((r->reg[RTC_DH] & 0xFE) | (day >> 8));
}

void rtc_add_seconds(struct gb_rtc *r, uint64_t seconds)
{
    /* Mientras algún campo esté fuera de rango, segundo a segundo
     * (peor caso ≈ 8 h 4 min: H 24→31 y M 60→63). */
    while (seconds > 0 && (r->reg[RTC_S] > 59 || r->reg[RTC_M] > 59 || r->reg[RTC_H] > 23)) {
        rtc_second(r);
        seconds--;
    }
    if (seconds == 0)
        return;
    uint64_t t = r->reg[RTC_S] + 60u * r->reg[RTC_M] + 3600u * r->reg[RTC_H] + seconds;
    r->reg[RTC_S] = (uint8_t)(t % 60);
    r->reg[RTC_M] = (uint8_t)(t / 60 % 60);
    r->reg[RTC_H] = (uint8_t)(t / 3600 % 24);
    uint64_t day = ((uint64_t)(r->reg[RTC_DH] & 1) << 8 | r->reg[RTC_DL]) + t / 86400;
    if (day > 0x1FF) {
        day &= 0x1FF;
        r->reg[RTC_DH] |= 0x80;
    }
    r->reg[RTC_DL] = (uint8_t)day;
    r->reg[RTC_DH] = (uint8_t)((r->reg[RTC_DH] & 0xFE) | (day >> 8));
}

void rtc_tick(gb *g, unsigned tcycles)
{
    struct gb_rtc *r = &g->cart.rtc;
    r->wall_sub += tcycles;
    if (r->wall_sub >= RTC_CYCLES_PER_SECOND) {
        r->wall_sub -= RTC_CYCLES_PER_SECOND;
        if (rtc_unix_valid(r->unix + 1))
            r->unix++;   /* el tiempo emulado también es tiempo transcurrido */
    }
    if (r->reg[RTC_DH] & 0x40)
        return;      /* halt: el reloj (y su sub-segundo) se detiene */
    r->sub += tcycles;
    if (r->sub >= RTC_CYCLES_PER_SECOND) {
        r->sub -= RTC_CYCLES_PER_SECOND;
        rtc_second(r);
    }
}

uint8_t rtc_read(const gb *g)
{
    const struct gb_cart *c = &g->cart;
    return c->rtc.latched[c->rtc_reg];
}

void rtc_write(gb *g, uint8_t v)
{
    struct gb_rtc *r = &g->cart.rtc;
    int i = g->cart.rtc_reg;
    r->reg[i] = v & reg_mask[i];
    r->latched[i] = r->reg[i];   /* lo escrito se lee de vuelta sin nuevo latch */
    if (g->cart.has_battery)
        g->cart.ram_written = true;   /* el juego cambió la hora: señal de guardado (H4) */
    if (i == RTC_S)
        r->sub = 0;
}

void rtc_latch_write(gb *g, uint8_t v)
{
    struct gb_rtc *r = &g->cart.rtc;
    if (r->latch_last == 0x00 && v == 0x01)
        for (int i = 0; i < RTC_REGS; i++)
            r->latched[i] = r->reg[i];
    r->latch_last = v;
}

static void put32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)(v >> (8 * i));
}

static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

/* Formato VBA/BGB: 5×u32 vivos (S M H DL DH), 5×u32 latched, u64 hora Unix; little-endian. */
void rtc_serialize(const struct gb_rtc *r, uint8_t out[RTC_SAVE_BYTES])
{
    for (int i = 0; i < RTC_REGS; i++) {
        put32(out + 4 * i, r->reg[i]);
        put32(out + 20 + 4 * i, r->latched[i]);
    }
    uint64_t t = (uint64_t)r->unix;
    put32(out + 40, (uint32_t)t);
    put32(out + 44, (uint32_t)(t >> 32));
}

void rtc_deserialize(struct gb_rtc *r, const uint8_t in[RTC_SAVE_BYTES])
{
    for (int i = 0; i < RTC_REGS; i++) {
        r->reg[i] = (uint8_t)(get32(in + 4 * i) & reg_mask[i]);
        r->latched[i] = (uint8_t)(get32(in + 20 + 4 * i) & reg_mask[i]);
    }
    r->unix = (int64_t)((uint64_t)get32(in + 40) | (uint64_t)get32(in + 44) << 32);
    r->sub = 0;
}
