/*
 * dma.c — OAM DMA (docs/03-core-spec.md §PPU "OAM DMA" y §Mapa de memoria).
 *
 * Escribir FF46 arranca, tras 1 M-ciclo de preparación, una copia de 160 bytes
 * (1 por M-ciclo) de XX00–XX9F a OAM. Mientras se copia, la CPU (DMG) solo
 * accede a HRAM. Una nueva escritura reinicia la copia; la anterior sigue
 * ocupando el bus durante la preparación de la nueva.
 * Se adelanta a M1 porque las pruebas de tiempo de Mooneye (reti_timing) la usan.
 */
#include "internal.h"

enum { OAM_BYTES = 0xA0 };

void dma_reset(gb *g)
{
    struct gb_dma *d = &g->dma;
    d->active = false;
    d->bus_busy = false;
    d->start_delay = 0;
    d->index = 0;
    d->src = 0;
    d->next_src = 0;
}

void dma_start(gb *g, uint8_t page)
{
    struct gb_dma *d = &g->dma;
    /* Origen ≥ 0xE0 se lee de WRAM (XX - 0x20). */
    d->next_src = (uint16_t)((page >= 0xE0 ? page - 0x20 : page) << 8);
    d->start_delay = 1;
}

void dma_tick(gb *g)
{
    struct gb_dma *d = &g->dma;
    d->bus_busy = false;
    if (d->active) {
        g->mem.oam[d->index] = mmu_read(g, (uint16_t)(d->src + d->index));
        d->bus_busy = true;
        if (++d->index == OAM_BYTES)
            d->active = false;
    }
    if (d->start_delay && --d->start_delay == 0) {
        d->active = true;
        d->src = d->next_src;
        d->index = 0;
    }
}
