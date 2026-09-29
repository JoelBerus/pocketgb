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

    /* Render: tile 1 de color 3 en la esquina del fondo; objeto 0 con prioridad BG. */
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

    /* LCD apagado: LY=0, modo 0, pantalla blanca y VRAM accesible. */
    ppu_write(g, 0xFF40, 0x13);
    CHECK(t, g->ppu.ly == 0 && (ppu_read(g, 0xFF41) & 3) == 0);
    CHECK(t, fb[0] == 0xFFFFFFFFu && !ppu_vram_blocked(g) && !ppu_oam_blocked(g));
    uint8_t ly = g->ppu.ly;
    ppu_tick(g, 456);
    CHECK(t, g->ppu.ly == ly);

    gb_destroy(g);
}
