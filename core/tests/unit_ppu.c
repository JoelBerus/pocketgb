/* unit_ppu.c — modos, LCD apagado, bloqueo de VRAM/OAM y render básico (M2). */
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

/* Avanza la PPU sola hasta (ly, dot). */
static void seek(gb *g, uint8_t ly, uint16_t dot)
{
    for (int i = 0; i < 200000 && !(g->ppu.ly == ly && g->ppu.dot == dot); i++)
        ppu_tick(g, 4);
}

void unit_ppu(struct ut *t)
{
    gb *g = make_gb();
    CHECK(t, g != NULL);
    if (!g)
        return;

    /* Modos por dot en una línea visible */
    seek(g, 10, 0);
    CHECK(t, g->ppu.mode == 2 && ppu_oam_blocked(g) && !ppu_vram_blocked(g));
    seek(g, 10, 80);
    CHECK(t, g->ppu.mode == 3 && ppu_oam_blocked(g) && ppu_vram_blocked(g));
    seek(g, 10, 300);
    CHECK(t, g->ppu.mode == 0 && !ppu_oam_blocked(g) && !ppu_vram_blocked(g));
    seek(g, 144, 0);
    CHECK(t, g->ppu.mode == 0);              /* la VBlank llega 4 dots después */
    g->mem.if_ = 0;
    ppu_tick(g, 4);
    CHECK(t, g->ppu.mode == 1 && (g->mem.if_ & IRQ_VBLANK));

    /* LYC=LY con STAT bit 6: IRQ en el flanco de subida */
    ppu_write(g, 0xFF45, 20);
    ppu_write(g, 0xFF41, 0x40);
    g->mem.if_ = 0;
    seek(g, 20, 0);
    CHECK(t, (g->mem.if_ & IRQ_STAT) && (ppu_read(g, 0xFF41) & 0x04));
    ppu_write(g, 0xFF41, 0x00);

    /* Render: tile 1 de color 3 en la esquina del fondo; objeto 0 sin prioridad BG. */
    for (int i = 0; i < 16; i++)
        g->mem.vram[16 + i] = 0xFF;          /* tile 1 = color 3 */
    g->mem.vram[0x1800] = 1;                 /* mapa 9800: (0,0) → tile 1 */
    g->mem.oam[0] = 16 + 8;                  /* objeto en y=8, x=0 */
    g->mem.oam[1] = 8;
    g->mem.oam[2] = 1;
    g->mem.oam[3] = 0x00;
    ppu_write(g, 0xFF47, 0xE4);              /* BGP identidad */
    ppu_write(g, 0xFF48, 0xE4);
    ppu_write(g, 0xFF40, 0x93);              /* LCD, BG, OBJ, tiles en 8000 */
    seek(g, 0, 0);
    seek(g, 10, 0);
    const uint32_t *fb = gb_framebuffer(g);
    CHECK(t, fb[0] == 0xFF000000u);                      /* fondo color 3 = negro */
    CHECK(t, fb[8] == 0xFFFFFFFFu);                      /* fuera del tile: color 0 */
    CHECK(t, fb[9 * GB_SCREEN_W + 0] == 0xFF000000u);    /* objeto (color 3) en y=9 */

    /* Prioridad BG (attr bit 7): el objeto queda debajo del fondo ≠ 0 y encima del color 0.
     * Objeto 1 en x=4 (y=0..7) con OBP0 que pinta el color 3 como gris claro. */
    g->mem.oam[4] = 16;
    g->mem.oam[5] = 8 + 4;
    g->mem.oam[6] = 1;
    g->mem.oam[7] = 0x80;
    ppu_write(g, 0xFF48, 0x40);              /* OBP0: color 3 → tono 1 */
    seek(g, 0, 0);
    seek(g, 10, 0);
    CHECK(t, fb[4] == 0xFF000000u);          /* fondo color 3 gana */
    CHECK(t, fb[8] == 0xFFAAAAAAu);          /* fondo color 0: el objeto se ve */

    /* Flip X + 10 objetos por línea: un 11.º objeto en la misma línea no se dibuja. */
    for (int i = 0; i < 16; i++)
        g->mem.vram[32 + i] = (i & 1) ? 0x00 : 0x80;   /* tile 2: solo la columna 0, color 1 */
    for (int i = 0; i < 40; i++)
        g->mem.oam[i * 4] = 0;               /* todos fuera */
    for (int i = 0; i < 11; i++) {
        g->mem.oam[i * 4] = 16 + 40;         /* y = 40 */
        g->mem.oam[i * 4 + 1] = (uint8_t)(8 + 10 * i);
        g->mem.oam[i * 4 + 2] = 2;
        g->mem.oam[i * 4 + 3] = 0x20;        /* flip X: el píxel pasa a la columna 7 */
    }
    ppu_write(g, 0xFF48, 0xE4);
    seek(g, 0, 0);
    seek(g, 42, 0);
    const uint32_t *l40 = fb + 40 * GB_SCREEN_W;
    CHECK(t, l40[7] == 0xFFAAAAAAu && l40[0] == 0xFFFFFFFFu);   /* flip X */
    CHECK(t, l40[10 * 9 + 7] == 0xFFAAAAAAu);                   /* 10.º objeto: sí */
    CHECK(t, l40[10 * 10 + 7] == 0xFFFFFFFFu);                  /* 11.º objeto: no */

    /* LCD apagado: LY=0, modo 0, pantalla blanca y VRAM accesible. */
    ppu_write(g, 0xFF40, 0x13);
    CHECK(t, g->ppu.ly == 0 && (ppu_read(g, 0xFF41) & 3) == 0);
    CHECK(t, fb[0] == 0xFFFFFFFFu && !ppu_vram_blocked(g) && !ppu_oam_blocked(g));
    uint8_t ly = g->ppu.ly;
    ppu_tick(g, 456);
    CHECK(t, g->ppu.ly == ly);

    gb_destroy(g);
}
