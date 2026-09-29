/*
 * joypad.c — P1/JOYP (FF00) (docs/03-core-spec.md §Joypad).
 * El núcleo no filtra direcciones opuestas: eso lo hace el frontend.
 */
#include "internal.h"

/* Nibble bajo de P1 (0 = pulsado) para una selección y una máscara de botones. */
static uint8_t low_nibble(uint8_t select, uint8_t buttons)
{
    uint8_t pressed = 0;
    if (!(select & 0x10))
        pressed |= (uint8_t)((buttons >> 4) & 0x0F);   /* cruceta: R L U D */
    if (!(select & 0x20))
        pressed |= (uint8_t)(buttons & 0x0F);          /* A B Select Start */
    return (uint8_t)(~pressed & 0x0F);
}

/* Pide la interrupción si algún bit seleccionado pasa de 1 a 0. */
static void update(gb *g, uint8_t select, uint8_t buttons)
{
    uint8_t before = low_nibble(g->joy.select, g->joy.buttons);
    uint8_t after = low_nibble(select, buttons);
    g->joy.select = select;
    g->joy.buttons = buttons;
    if (before & ~after)
        g->mem.if_ |= IRQ_JOYPAD;
}

void joypad_reset(gb *g)
{
    g->joy.select = 0x30;
    /* los botones pulsados los mantiene el frontend entre resets */
}

uint8_t joypad_read(const gb *g)
{
    return (uint8_t)(0xC0 | g->joy.select | low_nibble(g->joy.select, g->joy.buttons));
}

void joypad_write(gb *g, uint8_t v)
{
    update(g, v & 0x30, g->joy.buttons);
}

void joypad_set(gb *g, uint8_t mask)
{
    update(g, g->joy.select, mask);
    if (mask)
        g->cpu.stopped = false;
}
