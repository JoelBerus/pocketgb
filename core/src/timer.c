/*
 * timer.c — DIV/TIMA/TMA/TAC (docs/03-core-spec.md §Timer).
 *
 * El contador interno de 16 bits avanza 4 por M-ciclo. TIMA sube en el flanco
 * de bajada de (bit observado AND habilitado), lo que incluye los glitches al
 * escribir DIV o TAC. El mismo contador da el reloj interno de la serie
 * (flanco de bajada del bit 8 → 8192 Hz) y el frame sequencer del APU
 * (flanco de bajada del bit 12 → 512 Hz).
 */
#include "internal.h"

/* TAC bits 1–0 → bit del contador observado */
static const uint8_t tac_bit[4] = { 9, 3, 5, 7 };

static bool timer_signal(const struct gb_timer *t, uint16_t counter)
{
    return (t->tac & 4) && ((counter >> tac_bit[t->tac & 3]) & 1);
}

static void tima_increment(gb *g)
{
    struct gb_timer *t = &g->timer;
    if (t->tima == 0xFF) {
        /* Durante un M-ciclo TIMA vale 0; después se carga TMA y se pide la IRQ. */
        t->tima = 0;
        t->reload = TIMA_RELOAD_A;
    } else {
        t->tima++;
    }
}

/* Cambia el contador y aplica los efectos de los flancos de bajada. */
static void counter_set(gb *g, uint16_t next)
{
    struct gb_timer *t = &g->timer;
    uint16_t prev = t->counter;
    t->counter = next;
    if (timer_signal(t, prev) && !timer_signal(t, next))
        tima_increment(g);
    /* Serie: bit 8 (8192 Hz); en CGB con SC bit 1, bit 3 (262144 Hz). */
    uint16_t sbit = (cgb_native(g) && (g->serial.sc & 2)) ? 0x0008 : 0x0100;
    if ((prev & sbit) && !(next & sbit))
        serial_clock_internal(g);
    /* Frame sequencer a 512 Hz: bit 12, o bit 13 en doble velocidad. */
    uint16_t fbit = g->cgb.double_speed ? 0x2000 : 0x1000;
    if ((prev & fbit) && !(next & fbit))
        apu_frame_step(g);
}

void timer_reset(gb *g)
{
    struct gb_timer *t = &g->timer;
    /* DMG ABC: contador interno 0xABCC al llegar a PC=0x100 (DIV=0xAB).
     * CGB con un ROM DMG: 0x2674 (el único valor con el que pasa Mooneye
     * misc/boot_div-cgbABCDE; el arranque real tarda algo más con licencia
     * Nintendo). CGB nativo: sin verificar, se deja el de DMG. */
    t->counter = g->cgb.compat ? 0x2674 : 0xABCC;
    t->tima = 0;
    t->tma = 0;
    t->tac = 0;
    t->reload = TIMA_RELOAD_NONE;
}

void timer_tick(gb *g)
{
    struct gb_timer *t = &g->timer;
    if (t->reload == TIMA_RELOAD_B) {
        t->reload = TIMA_RELOAD_NONE;
    } else if (t->reload == TIMA_RELOAD_A) {
        t->tima = t->tma;
        g->mem.if_ |= IRQ_TIMER;
        t->reload = TIMA_RELOAD_B;
    }
    counter_set(g, (uint16_t)(t->counter + 4));
}

uint8_t timer_read(const gb *g, uint16_t addr)
{
    const struct gb_timer *t = &g->timer;
    switch (addr) {
    case 0xFF04: return (uint8_t)(t->counter >> 8);
    case 0xFF05: return t->tima;
    case 0xFF06: return t->tma;
    case 0xFF07: return (uint8_t)(0xF8 | t->tac);
    default:     return 0xFF;
    }
}

void timer_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_timer *t = &g->timer;
    switch (addr) {
    case 0xFF04:
        counter_set(g, 0);
        break;
    case 0xFF05:
        if (t->reload == TIMA_RELOAD_B)
            break;                          /* en el ciclo de recarga gana TMA */
        if (t->reload == TIMA_RELOAD_A)
            t->reload = TIMA_RELOAD_NONE;   /* cancela la recarga y la IRQ */
        t->tima = v;
        break;
    case 0xFF06:
        t->tma = v;
        if (t->reload == TIMA_RELOAD_B)
            t->tima = v;
        break;
    case 0xFF07: {
        bool before = timer_signal(t, t->counter);
        t->tac = v & 7;
        if (before && !timer_signal(t, t->counter))
            tima_increment(g);
        break;
    }
    default:
        break;
    }
}
