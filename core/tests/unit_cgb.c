/* unit_cgb.c — modo CGB (M8): selección de modelo, arranque, bancos, paletas,
 * HDMA, doble velocidad, paletas de compatibilidad y save states. */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

static const uint8_t loop_prog[] = { 0x18, 0xFE };   /* JR -2 */

/* ROM sintético con flag CGB `flag`, cargado con el modelo `model`. */
static gb *load_cgb(uint8_t flag, gb_model model, const uint8_t *prog, size_t len)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, prog, len);
    if (!rom)
        return NULL;
    rom[0x143] = flag;
    for (unsigned i = 0; i < 0x20; i++)
        rom[0x200 + i] = (uint8_t)(0xA0 + i);          /* origen de las pruebas de HDMA */
    ut_fix_header_checksum(rom);
    gb_options o;
    gb_options_default(&o);
    o.model = model;
    gb *g = gb_create();
    if (!g || gb_load_rom(g, rom, 0x8000, &o) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static void model_and_boot(struct ut *t)
{
    gb_rom_info info;
    gb *g = load_cgb(0x80, GB_MODEL_AUTO, loop_prog, sizeof loop_prog);
    CHECK(t, g != NULL);
    if (g) {
        CHECK(t, gb_rom_info_get(g, &info) == GB_OK && info.cgb_mode && !info.cgb_compat);
        CHECK(t, g->cpu.a == 0x11 && g->cpu.f == 0x80 && g->cpu.b == 0x00 && g->cpu.c == 0x00);
        CHECK(t, g->cpu.d == 0xFF && g->cpu.e == 0x56 && g->cpu.h == 0x00 && g->cpu.l == 0x0D);
        CHECK(t, mmu_read(g, 0xFF4D) == 0x7E && mmu_read(g, 0xFF4F) == 0xFE);
        CHECK(t, mmu_read(g, 0xFF70) == 0xF8 && mmu_read(g, 0xFF55) == 0xFF);
        CHECK(t, mmu_read(g, 0xFF6C) == 0xFE && mmu_read(g, 0xFF02) == 0x7C);
        CHECK(t, g->cgb.bg_rgba[0] == 0xFFFFFFFFu);      /* paletas de fondo en blanco */
        gb_destroy(g);
    }
    g = load_cgb(0xC0, GB_MODEL_AUTO, loop_prog, sizeof loop_prog);   /* solo CGB */
    CHECK(t, g != NULL && g->cgb.on && !g->cgb.compat);
    gb_destroy(g);
    g = load_cgb(0x80, GB_MODEL_DMG, loop_prog, sizeof loop_prog);    /* compatible, forzado a DMG */
    CHECK(t, g != NULL && !g->cgb.on && g->cpu.a == 0x01);
    if (g) {
        CHECK(t, mmu_read(g, 0xFF4F) == 0xFF && mmu_read(g, 0xFF70) == 0xFF);
        gb_destroy(g);
    }
    g = load_cgb(0x00, GB_MODEL_AUTO, loop_prog, sizeof loop_prog);   /* ROM DMG: DMG */
    CHECK(t, g != NULL && !g->cgb.on);
    gb_destroy(g);

    /* ROM DMG en CGB: compatibilidad; los registros de CGB no se ven. */
    g = load_cgb(0x00, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    CHECK(t, g != NULL);
    if (g) {
        CHECK(t, gb_rom_info_get(g, &info) == GB_OK && info.cgb_mode && info.cgb_compat);
        CHECK(t, g->cpu.a == 0x11 && g->cpu.b == 0x00 && g->cpu.d == 0x00 && g->cpu.e == 0x08);
        CHECK(t, g->cpu.h == 0x00 && g->cpu.l == 0x7C);
        mmu_write(g, 0xFF4F, 0x01);
        mmu_write(g, 0xFF70, 0x03);
        CHECK(t, g->mem.vbk == 0 && g->mem.wram_bank == 1);
        CHECK(t, mmu_read(g, 0xFF4F) == 0xFF && mmu_read(g, 0xFF69) == 0xFF);
        CHECK(t, g->cgb.opri == 1);
        gb_destroy(g);
    }
}

static void banks_and_palettes(struct ut *t)
{
    gb *g = load_cgb(0x80, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    CHECK(t, g != NULL);
    if (!g)
        return;
    /* VRAM: 2 bancos */
    mmu_write(g, 0xFF4F, 0x01);
    mmu_write(g, 0x8000, 0xAB);
    CHECK(t, mmu_read(g, 0xFF4F) == 0xFF && g->mem.vram[0x2000] == 0xAB);
    mmu_write(g, 0xFF4F, 0x00);
    CHECK(t, mmu_read(g, 0x8000) == 0x00);
    /* WRAM: SVBK 0 → banco 1; C000–CFFF siempre banco 0; eco incluido */
    mmu_write(g, 0xFF70, 0x00);
    mmu_write(g, 0xD000, 0x11);
    mmu_write(g, 0xFF70, 0x03);
    mmu_write(g, 0xD000, 0x33);
    mmu_write(g, 0xC000, 0x99);
    CHECK(t, mmu_read(g, 0xD000) == 0x33 && mmu_read(g, 0xF000) == 0x33);
    mmu_write(g, 0xFF70, 0x01);
    CHECK(t, mmu_read(g, 0xD000) == 0x11 && mmu_read(g, 0xC000) == 0x99);
    CHECK(t, g->mem.wram[0x3000] == 0x33);

    /* Paletas: autoincremento y RGB555 → RGBA con (c << 3) | (c >> 2) */
    mmu_write(g, 0xFF40, 0x11);              /* LCD apagado: sin bloqueo de modo 3 */
    mmu_write(g, 0xFF68, 0x80);
    const uint8_t data[] = { 0xFF, 0x7F, 0x1F, 0x00, 0xE0, 0x03, 0x00, 0x7C };
    for (size_t i = 0; i < sizeof data; i++)
        mmu_write(g, 0xFF69, data[i]);
    CHECK(t, mmu_read(g, 0xFF68) == 0xC8);
    CHECK(t, g->cgb.bg_rgba[0] == 0xFFFFFFFFu && g->cgb.bg_rgba[1] == 0xFF0000FFu);
    CHECK(t, g->cgb.bg_rgba[2] == 0xFF00FF00u && g->cgb.bg_rgba[3] == 0xFFFF0000u);
    mmu_write(g, 0xFF68, 0x02);              /* sin autoincremento */
    CHECK(t, mmu_read(g, 0xFF69) == 0x1F && mmu_read(g, 0xFF68) == 0x42);
    mmu_write(g, 0xFF6A, 0xBF);              /* índice 63 + autoincremento: da la vuelta */
    mmu_write(g, 0xFF6B, 0x55);
    CHECK(t, g->cgb.obj_pal[63] == 0x55 && mmu_read(g, 0xFF6A) == 0xC0);
    gb_destroy(g);
}

static void hdma(struct ut *t)
{
    gb *g = load_cgb(0x80, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    CHECK(t, g != NULL);
    if (!g)
        return;
    /* General: 2 bloques de 0x0200 a VRAM 0x8010, de golpe; la CPU queda parada. */
    mmu_write(g, 0xFF51, 0x02);
    mmu_write(g, 0xFF52, 0x0F);              /* los 4 bits bajos se ignoran */
    mmu_write(g, 0xFF53, 0xE0);              /* solo bits 12–8 de VRAM */
    mmu_write(g, 0xFF54, 0x10);
    mmu_write(g, 0xFF55, 0x01);
    CHECK(t, g->mem.vram[0x0010] == 0xA0 && g->mem.vram[0x002F] == 0xBF);
    CHECK(t, mmu_read(g, 0xFF55) == 0xFF && g->cgb.stall == 16);
    CHECK(t, g->cgb.hdma_dst == 0x0030 && g->cgb.hdma_src == 0x0220);

    /* HBlank: un bloque por línea visible. */
    mmu_write(g, 0xFF4F, 0x01);              /* destino en el banco 1 */
    mmu_write(g, 0xFF51, 0x02);
    mmu_write(g, 0xFF52, 0x00);
    mmu_write(g, 0xFF53, 0x01);
    mmu_write(g, 0xFF54, 0x00);
    mmu_write(g, 0xFF55, 0x81);
    CHECK(t, mmu_read(g, 0xFF55) == 0x01 && g->cgb.hdma_active);
    gb_run_cycles(g, 456);
    CHECK(t, mmu_read(g, 0xFF55) == 0x00 && g->mem.vram[0x2100] == 0xA0);
    CHECK(t, g->mem.vram[0x2110] == 0x00);
    gb_run_cycles(g, 456);
    CHECK(t, mmu_read(g, 0xFF55) == 0xFF && !g->cgb.hdma_active && g->mem.vram[0x211F] == 0xBF);

    /* Escribir bit 7 = 0 durante un HDMA de HBlank lo detiene. */
    mmu_write(g, 0xFF55, 0x83);
    mmu_write(g, 0xFF55, 0x00);
    CHECK(t, !g->cgb.hdma_active && mmu_read(g, 0xFF55) == 0x83);
    gb_destroy(g);
}

static void double_speed(struct ut *t)
{
    /* LD A,1; LDH (4D),A; STOP; JR -2 */
    static const uint8_t prog[] = { 0x3E, 0x01, 0xE0, 0x4D, 0x10, 0x00, 0x18, 0xFE };
    gb *g = load_cgb(0x80, GB_MODEL_CGB, prog, sizeof prog);
    CHECK(t, g != NULL);
    if (!g)
        return;
    gb_run_cycles(g, 200);
    CHECK(t, g->cgb.double_speed && !g->cpu.stopped && mmu_read(g, 0xFF4D) == 0xFE);
    /* DIV va al doble que el tiempo real; el frame sigue durando 70224 T-ciclos. */
    uint16_t c0 = g->timer.counter;
    uint32_t ran = gb_run_cycles(g, 1000);
    CHECK(t, (uint16_t)(g->timer.counter - c0) == (uint16_t)(2 * ran));
    gb_run_frame(g);
    uint64_t f0 = gb_cycle_count(g);
    gb_run_frame(g);
    uint64_t df = gb_cycle_count(g) - f0;
    CHECK(t, df >= GB_CYCLES_PER_FRAME - 8 && df <= GB_CYCLES_PER_FRAME + 8);
    gb_destroy(g);

    /* En compatibilidad no hay cambio de velocidad: STOP para hasta pulsar un botón. */
    g = load_cgb(0x00, GB_MODEL_CGB, prog, sizeof prog);
    CHECK(t, g != NULL);
    if (g) {
        gb_run_cycles(g, 200);
        CHECK(t, !g->cgb.double_speed && g->cpu.stopped);
        gb_destroy(g);
    }
}

static gb *load_titled(const char *title, uint8_t old_lic, const char *new_lic)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, loop_prog, sizeof loop_prog);
    if (!rom)
        return NULL;
    memset(rom + 0x134, 0, 16);
    memcpy(rom + 0x134, title, strlen(title));
    rom[0x14B] = old_lic;
    rom[0x144] = (uint8_t)new_lic[0];
    rom[0x145] = (uint8_t)new_lic[1];
    ut_fix_header_checksum(rom);
    gb_options o;
    gb_options_default(&o);
    o.model = GB_MODEL_CGB;
    gb *g = gb_create();
    if (!g || gb_load_rom(g, rom, 0x8000, &o) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static void compat_palettes(struct ut *t)
{
    /* POKEMON RED (Nintendo, "01"): checksum 0x14 → combinación 13
     * (OBJ0 = paleta 3, OBJ1 y BG = paleta 4, la roja). */
    gb *g = load_titled("POKEMON RED", 0x33, "01");
    CHECK(t, g != NULL);
    if (g) {
        CHECK(t, g->cpu.b == 0x14 && g->cpu.h == 0x00 && g->cpu.l == 0x7C);
        CHECK(t, g->cgb.bg_rgba[0] == 0xFFFFFFFFu && g->cgb.bg_rgba[1] == 0xFF8484FFu);
        CHECK(t, g->cgb.bg_rgba[2] == 0xFF393994u && g->cgb.bg_rgba[3] == 0xFF000000u);
        CHECK(t, g->cgb.obj_rgba[1] == 0xFF31FF7Bu && g->cgb.obj_rgba[5] == 0xFF8484FFu);
        /* Selección manual 5 (→ + A) = combinación 0: BG verde/azul de la referencia. */
        gb_set_compat_palette(g, 5);
        CHECK(t, g->cgb.bg_rgba[1] == 0xFF31FF7Bu && g->cgb.bg_rgba[2] == 0xFFC66300u);
        gb_set_compat_palette(g, GB_COMPAT_PALETTE_AUTO);
        CHECK(t, g->cgb.bg_rgba[1] == 0xFF8484FFu);
        gb_destroy(g);
    }
    /* Mismo título sin licencia Nintendo: combinación 0 y B = 0. */
    g = load_titled("POKEMON RED", 0x00, "00");
    CHECK(t, g != NULL);
    if (g) {
        CHECK(t, g->cpu.b == 0x00 && g->cgb.bg_rgba[1] == 0xFF31FF7Bu);
        gb_destroy(g);
    }
    /* Checksum con duplicado: decide la 4.ª letra (POKEMON BLUE → combinación 11). */
    g = load_titled("POKEMON BLUE", 0x01, "00");
    CHECK(t, g != NULL);
    if (g) {
        /* combinación 11: BG = paleta 28 (0x7FFF, 0x7E8C, 0x7C00, 0x0000) */
        CHECK(t, g->cgb.bg_rgba[2] == 0xFFFF0000u);
        gb_destroy(g);
    }
    /* La selección manual no afecta a un ROM en CGB nativo. */
    g = load_cgb(0x80, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    if (g) {
        gb_set_compat_palette(g, 5);
        CHECK(t, g->cgb.bg_rgba[1] == 0xFFFFFFFFu);
        gb_destroy(g);
    }
}

static void states(struct ut *t)
{
    gb *g = load_cgb(0x80, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    gb *h = load_cgb(0x80, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    gb *d = load_cgb(0x80, GB_MODEL_DMG, loop_prog, sizeof loop_prog);
    gb *c = load_cgb(0x00, GB_MODEL_CGB, loop_prog, sizeof loop_prog);
    CHECK(t, g && h && d && c);
    size_t n = g ? gb_state_size(g) : 0;
    uint8_t *s = n ? malloc(n) : NULL;
    if (g && h && d && c && s) {
        mmu_write(g, 0xFF4F, 0x01);
        mmu_write(g, 0xFF70, 0x05);
        mmu_write(g, 0xD123, 0x5A);
        mmu_write(g, 0xFF68, 0x82);
        mmu_write(g, 0xFF69, 0x1F);
        gb_run_frame(g);
        CHECK(t, gb_state_save(g, s, n) == GB_OK);
        CHECK(t, gb_state_load(h, s, n) == GB_OK);
        CHECK(t, h->mem.vbk == 1 && h->mem.wram_bank == 5 && mmu_read(h, 0xD123) == 0x5A);
        CHECK(t, h->cgb.bcps == 0x83 && h->cgb.bg_rgba[1] == g->cgb.bg_rgba[1]);
        gb_run_frame(g);
        gb_run_frame(h);
        CHECK(t, memcmp(gb_framebuffer(g), gb_framebuffer(h), GB_SCREEN_W * GB_SCREEN_H * 4) == 0);
        /* Mismo ROM en otro modelo: se rechaza. */
        size_t nd = gb_state_size(d);
        CHECK(t, nd == n);
        CHECK(t, gb_state_load(d, s, n) == GB_ERR_STATE_ROM_MISMATCH);
        /* En compatibilidad, un banco de VRAM ≠ 0 es imposible: estado corrupto. */
        size_t nc = gb_state_size(c);
        uint8_t *sc = malloc(nc);
        if (sc) {
            c->mem.vbk = 1;
            CHECK(t, gb_state_save(c, sc, nc) == GB_OK && gb_state_load(c, sc, nc) == GB_ERR_STATE_CORRUPT);
            c->mem.vbk = 0;
            c->cgb.double_speed = true;
            CHECK(t, gb_state_save(c, sc, nc) == GB_OK && gb_state_load(c, sc, nc) == GB_ERR_STATE_CORRUPT);
            c->cgb.double_speed = false;
            CHECK(t, gb_state_save(c, sc, nc) == GB_OK && gb_state_load(c, sc, nc) == GB_OK);
            free(sc);
        }
    }
    free(s);
    gb_destroy(g);
    gb_destroy(h);
    gb_destroy(d);
    gb_destroy(c);
}

void unit_cgb(struct ut *t)
{
    model_and_boot(t);
    banks_and_palettes(t);
    hdma(t);
    double_speed(t);
    compat_palettes(t);
    states(t);
}
