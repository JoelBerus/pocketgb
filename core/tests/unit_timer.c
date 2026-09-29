/* unit_timer.c — flancos del timer y recarga retrasada de TIMA (docs/03-core-spec.md §Timer). */
#include <stdlib.h>

#include "unit.h"

static gb *make_gb(void)
{
    static const uint8_t loop[] = { 0x18, 0xFE };   /* JR -2 */
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, loop, sizeof loop);
    gb *g = gb_create();
    if (!rom || !g || gb_load_rom(g, rom, 0x8000, NULL) != GB_OK) {
        free(rom);
        gb_destroy(g);
        return NULL;
    }
    free(rom);
    return g;
}

static void ticks(gb *g, int n)
{
    while (n-- > 0)
        timer_tick(g);
}

void unit_timer(struct ut *t)
{
    gb *g = make_gb();
    CHECK(t, g != NULL);
    if (!g)
        return;

    /* Valor post-boot: DIV = 0xAB */
    CHECK(t, mmu_read(g, 0xFF04) == 0xAB);
    CHECK(t, mmu_read(g, 0xFF07) == 0xF8);

    /* TAC=01 observa el bit 3: TIMA sube cada 16 T-ciclos, en el flanco de bajada. */
    g->timer.counter = 0;
    timer_write(g, 0xFF07, 0x05);
    ticks(g, 3);                       /* contador 12: bit 3 todavía a 1 */
    CHECK(t, g->timer.tima == 0);
    ticks(g, 1);                       /* contador 16: flanco de bajada */
    CHECK(t, g->timer.tima == 1);
    ticks(g, 4);
    CHECK(t, g->timer.tima == 2);

    /* Escribir DIV con el bit observado a 1 produce un flanco. */
    g->timer.counter = 0x0008;
    g->timer.tima = 0;
    timer_write(g, 0xFF04, 0x12);
    CHECK(t, g->timer.counter == 0);
    CHECK(t, g->timer.tima == 1);

    /* Escribir DIV con el bit a 0 no. */
    g->timer.counter = 0x0004;
    timer_write(g, 0xFF04, 0);
    CHECK(t, g->timer.tima == 1);

    /* Deshabilitar en TAC con el bit a 1: flanco. */
    g->timer.counter = 0x0008;
    timer_write(g, 0xFF07, 0x00);
    CHECK(t, g->timer.tima == 2);

    /* Cambiar la selección de un bit a 1 (bit 3) a uno a 0 (bit 9): flanco. */
    timer_write(g, 0xFF07, 0x05);
    timer_write(g, 0xFF07, 0x04);
    CHECK(t, g->timer.tima == 3);

    /* Desbordamiento: 1 M-ciclo con TIMA=0 y sin IRQ; después TMA y la IRQ. */
    timer_write(g, 0xFF07, 0x05);
    g->timer.counter = 12;
    g->timer.tima = 0xFF;
    g->timer.tma = 0x42;
    g->mem.if_ = 0;
    ticks(g, 1);
    CHECK(t, g->timer.tima == 0x00);
    CHECK(t, !(g->mem.if_ & IRQ_TIMER));
    ticks(g, 1);
    CHECK(t, g->timer.tima == 0x42);
    CHECK(t, g->mem.if_ & IRQ_TIMER);

    /* Escribir TIMA en el M-ciclo con TIMA=0 cancela la recarga y la IRQ. */
    g->timer.counter = 12;
    g->timer.tima = 0xFF;
    g->mem.if_ = 0;
    ticks(g, 1);
    timer_write(g, 0xFF05, 0x10);
    ticks(g, 1);
    CHECK(t, g->timer.tima == 0x10);
    CHECK(t, !(g->mem.if_ & IRQ_TIMER));

    /* En el M-ciclo de la recarga, escribir TIMA se ignora y escribir TMA también llega a TIMA. */
    g->timer.counter = 12;
    g->timer.tima = 0xFF;
    ticks(g, 2);
    timer_write(g, 0xFF05, 0x10);
    CHECK(t, g->timer.tima == 0x42);
    timer_write(g, 0xFF06, 0x77);
    CHECK(t, g->timer.tima == 0x77);
    ticks(g, 1);
    timer_write(g, 0xFF05, 0x10);
    CHECK(t, g->timer.tima == 0x10);

    gb_destroy(g);
}
