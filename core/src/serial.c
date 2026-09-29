/*
 * serial.c — SB/SC, desplazamiento bit a bit MSB primero (docs/03-core-spec.md §Serial).
 */
#include "internal.h"

void serial_reset(gb *g)
{
    g->serial.sb = 0x00;
    g->serial.sc = 0x00;
    g->serial.bits = 0;
    g->serial.out = 0;
}

/* Desplaza un bit: sale el MSB de SB, entra bit_in por el LSB. Devuelve el saliente. */
static uint8_t shift_bit(gb *g, uint8_t bit_in)
{
    struct gb_serial *s = &g->serial;
    uint8_t out = (uint8_t)(s->sb >> 7);
    s->sb = (uint8_t)((s->sb << 1) | (bit_in & 1));
    s->out = (uint8_t)((s->out << 1) | out);
    if (++s->bits == 8) {
        s->bits = 0;
        s->sc &= 0x7F;
        g->mem.if_ |= IRQ_SERIAL;
        if (g->opts.serial_byte_cb)
            g->opts.serial_byte_cb(g->opts.serial_user, s->out);
    }
    return out;
}

static bool transfer_active(const gb *g, bool internal_clock)
{
    uint8_t sc = g->serial.sc;
    return (sc & 0x80) && ((sc & 1) != 0) == internal_clock;
}

void serial_clock_internal(gb *g)
{
    if (!transfer_active(g, true))
        return;
    /* El bit saliente es el MSB actual; el callback devuelve el entrante. */
    uint8_t out = (uint8_t)(g->serial.sb >> 7);
    uint8_t in = 1; /* cable desconectado */
    if (g->opts.serial_bit_cb)
        in = g->opts.serial_bit_cb(g->opts.serial_user, out);
    shift_bit(g, in);
}

uint8_t gb_serial_clock_external(gb *g, uint8_t bit_in)
{
    if (!g || !g->rom_loaded || !transfer_active(g, false))
        return 1;
    return shift_bit(g, bit_in);
}

uint8_t serial_read(const gb *g, uint16_t addr)
{
    if (addr == 0xFF01)
        return g->serial.sb;
    if (cgb_native(g))
        return (uint8_t)(0x7C | g->serial.sc); /* CGB: bits 7, 1 (reloj rápido) y 0 */
    return (uint8_t)(0x7E | g->serial.sc);     /* DMG: solo bits 7 y 0 */
}

void serial_write(gb *g, uint16_t addr, uint8_t v)
{
    if (addr == 0xFF01) {
        g->serial.sb = v;
        return;
    }
    g->serial.sc = v & (cgb_native(g) ? 0x83 : 0x81);
    if (v & 0x80)
        g->serial.bits = 0;
}
