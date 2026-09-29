/*
 * cgb.c — Game Boy Color: registros exclusivos (KEY1, VBK, HDMA, paletas, OPRI,
 * SVBK), HDMA, doble velocidad y paletas de compatibilidad para ROMs DMG
 * (docs/03-core-spec.md §Arranque y §PPU CGB).
 *
 * Tablas de paletas de compatibilidad: transcritas de SameBoy v1.0.3,
 * BootROMs/cgb_boot.asm (reimplementación libre del arranque de la CGB, no el
 * boot ROM de Nintendo). Algoritmo: Pan Docs → Power Up Sequence §Compatibility
 * palettes.
 *   Copyright (c) 2015-2026 Lior Halphon. Expat License (MIT):
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions: The above copyright
 *   notice and this permission notice shall be included in all copies or
 *   substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
 *   WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
 */
#include <string.h>

#include "internal.h"

/* ---- Paletas de compatibilidad ---- */

/* Checksum (suma de 0x134–0x143) de los títulos con paleta propia. Desde el
 * índice FIRST_DUP el checksum se repite y desempata la 4.ª letra del título. */
enum { FIRST_DUP = 65, CHECKSUMS = 94 };

static const uint8_t title_checksums[CHECKSUMS] = {
    0x00, 0x88, 0x16, 0x36, 0xD1, 0xDB, 0xF2, 0x3C, 0x8C, 0x92, 0x3D, 0x5C,
    0x58, 0xC9, 0x3E, 0x70, 0x1D, 0x59, 0x69, 0x19, 0x35, 0xA8, 0x14, 0xAA,
    0x75, 0x95, 0x99, 0x34, 0x6F, 0x15, 0xFF, 0x97, 0x4B, 0x90, 0x17, 0x10,
    0x39, 0xF7, 0xF6, 0xA2, 0x49, 0x4E, 0x43, 0x68, 0xE0, 0x8B, 0xF0, 0xCE,
    0x0C, 0x29, 0xE8, 0xB7, 0x86, 0x9A, 0x52, 0x01, 0x9D, 0x71, 0x9C, 0xBD,
    0x5D, 0x6D, 0x67, 0x3F, 0x6B,
    /* con duplicado */
    0xB3, 0x46, 0x28, 0xA5, 0xC6, 0xD3, 0x27, 0x61, 0x18, 0x66, 0x6A, 0xBF,
    0x0D, 0xF4, 0xB3, 0x46, 0x28, 0xA5, 0xC6, 0xD3, 0x27, 0x61, 0x18, 0x66,
    0x6A, 0xBF, 0x0D, 0xF4, 0xB3,
};

static const char dup_4th_letter[CHECKSUMS - FIRST_DUP + 1] = "BEFAARBEKEK R-URAR INAILICE R";

/* Combinación de paletas para cada checksum (el bit 7 del arranque real, "copiar
 * el mapa del logo", no se emula: solo afecta a dos juegos, ver ESTADO.md). */
static const uint8_t checksum_combo[CHECKSUMS] = {
     0,  4,  5, 35, 34,  3, 31, 15, 10,  5, 19, 36,
     7, 37, 30, 44, 21, 32, 31, 20,  5, 33, 13, 14,
     5, 29,  5, 18,  9,  3,  2, 26, 25, 25, 41, 42,
    26, 45, 42, 45, 36, 38, 26, 42, 30, 41, 34, 34,
     5, 42,  6,  5, 33, 25, 42, 42, 40,  2, 16, 25,
    42, 42,  5,  0, 39,
    36, 22, 25,  6, 32, 12, 36, 11, 39, 18, 39, 24,
    31, 50, 17, 46,  6, 27,  0, 47, 41, 41,  0,  0,
    19, 34, 23, 18, 29,
};

/* Combinaciones: offset (en colores) de OBJ0, OBJ1 y BG dentro de palette_words.
 * Casi todas empiezan en una paleta (P(n)); 22 y 34–36 empiezan a mitad, como en
 * el arranque real. */
#define P(n) ((n) * 4)
enum { COMBOS = 51 };
static const uint8_t combos[COMBOS][3] = {
    { P(4), P(4), P(29) },   { P(18), P(18), P(18) }, { P(20), P(20), P(20) }, { P(24), P(24), P(24) },
    { P(9), P(9), P(9) },    { P(0), P(0), P(0) },    { P(27), P(27), P(27) }, { P(5), P(5), P(5) },
    { P(12), P(12), P(12) }, { P(26), P(26), P(26) }, { P(16), P(8), P(8) },   { P(4), P(28), P(28) },
    { P(4), P(2), P(2) },    { P(3), P(4), P(4) },    { P(4), P(29), P(29) },  { P(28), P(4), P(28) },
    { P(2), P(17), P(2) },   { P(16), P(16), P(8) },  { P(4), P(4), P(7) },    { P(4), P(4), P(18) },
    { P(4), P(4), P(20) },   { P(19), P(19), P(9) },  { 15, 15, 44 },          { P(17), P(17), P(2) },
    { P(4), P(4), P(2) },    { P(4), P(4), P(3) },    { P(28), P(28), P(0) },  { P(3), P(3), P(0) },
    { P(0), P(0), P(1) },    { P(18), P(22), P(18) }, { P(20), P(22), P(20) }, { P(24), P(22), P(24) },
    { P(16), P(22), P(8) },  { P(17), P(4), P(13) },  { 111, 0, 56 },          { 111, 16, 60 },
    { 76, 91, 36 },          { P(16), P(28), P(10) }, { P(4), P(23), P(28) },  { P(17), P(22), P(2) },
    { P(4), P(0), P(2) },    { P(4), P(28), P(3) },   { P(28), P(3), P(0) },   { P(3), P(28), P(4) },
    { P(21), P(28), P(4) },  { P(3), P(28), P(0) },   { P(25), P(3), P(28) },  { P(0), P(28), P(8) },
    { P(4), P(3), P(28) },   { P(28), P(3), P(6) },   { P(4), P(28), P(29) },
};
#undef P

static const uint16_t palette_words[30 * 4] = {
    0x7FFF, 0x32BF, 0x00D0, 0x0000,  0x639F, 0x4279, 0x15B0, 0x04CB,
    0x7FFF, 0x6E31, 0x454A, 0x0000,  0x7FFF, 0x1BEF, 0x0200, 0x0000,
    0x7FFF, 0x421F, 0x1CF2, 0x0000,  0x7FFF, 0x5294, 0x294A, 0x0000,
    0x7FFF, 0x03FF, 0x012F, 0x0000,  0x7FFF, 0x03EF, 0x01D6, 0x0000,
    0x7FFF, 0x42B5, 0x3DC8, 0x0000,  0x7E74, 0x03FF, 0x0180, 0x0000,
    0x67FF, 0x77AC, 0x1A13, 0x2D6B,  0x7ED6, 0x4BFF, 0x2175, 0x0000,
    0x53FF, 0x4A5F, 0x7E52, 0x0000,  0x4FFF, 0x7ED2, 0x3A4C, 0x1CE0,
    0x03ED, 0x7FFF, 0x255F, 0x0000,  0x036A, 0x021F, 0x03FF, 0x7FFF,
    0x7FFF, 0x01DF, 0x0112, 0x0000,  0x231F, 0x035F, 0x00F2, 0x0009,
    0x7FFF, 0x03EA, 0x011F, 0x0000,  0x299F, 0x001A, 0x000C, 0x0000,
    0x7FFF, 0x027F, 0x001F, 0x0000,  0x7FFF, 0x03E0, 0x0206, 0x0120,
    0x7FFF, 0x7EEB, 0x001F, 0x7C00,  0x7FFF, 0x3FFF, 0x7E00, 0x001F,
    0x7FFF, 0x03FF, 0x001F, 0x0000,  0x03FF, 0x001F, 0x000C, 0x0000,
    0x7FFF, 0x033F, 0x0193, 0x0000,  0x0000, 0x4200, 0x037F, 0x7FFF,
    0x7FFF, 0x7E8C, 0x7C00, 0x0000,  0x7FFF, 0x1BEF, 0x6180, 0x0000,
};

/* Combinación para cada selección manual (GB_COMPAT_PALETTES, 1..12). */
static const uint8_t key_combos[GB_COMPAT_PALETTES] = { 1, 48, 5, 8, 0, 40, 43, 3, 6, 7, 28, 49 };

uint8_t cgb_title_checksum(const gb *g, bool *nintendo)
{
    const uint8_t *rom = g->cart.rom;
    if (rom[0x14B] == 0x33)
        *nintendo = rom[0x144] == '0' && rom[0x145] == '1';
    else
        *nintendo = rom[0x14B] == 0x01;
    uint8_t sum = 0;
    for (unsigned i = 0x134; i <= 0x143; i++)
        sum = (uint8_t)(sum + rom[i]);
    return sum;
}

/* Combinación que elige el arranque: solo para licencia Nintendo; si no, la 0. */
static uint8_t auto_combo(const gb *g)
{
    bool nintendo;
    uint8_t sum = cgb_title_checksum(g, &nintendo);
    if (!nintendo)
        return 0;
    for (unsigned i = 0; i < CHECKSUMS; i++) {
        if (title_checksums[i] != sum)
            continue;
        if (i >= FIRST_DUP && g->cart.rom[0x137] != (uint8_t)dup_4th_letter[i - FIRST_DUP])
            continue;
        return checksum_combo[i];
    }
    return 0;
}

static void put_palette(uint8_t *dst, unsigned word_off)
{
    for (unsigned i = 0; i < 4; i++) {
        uint16_t c = palette_words[word_off + i];
        dst[i * 2] = (uint8_t)c;
        dst[i * 2 + 1] = (uint8_t)(c >> 8);
    }
}

static void load_compat_palettes(gb *g)
{
    uint8_t id = g->opts.compat_palette;
    uint8_t combo = (id >= 1 && id <= GB_COMPAT_PALETTES) ? key_combos[id - 1] : auto_combo(g);
    if (combo >= COMBOS)
        combo = 0;   /* defensa: las tablas son constantes */
    put_palette(g->cgb.obj_pal, combos[combo][0]);
    put_palette(g->cgb.obj_pal + 8, combos[combo][1]);
    put_palette(g->cgb.bg_pal, combos[combo][2]);
    cgb_update_rgba(g);
}

void gb_set_compat_palette(gb *g, uint8_t id)
{
    if (!g)
        return;
    g->opts.compat_palette = id;
    if (g->rom_loaded && g->cgb.on && g->cgb.compat)
        load_compat_palettes(g);
}

/* ---- Paletas ---- */

static uint32_t rgb555_to_rgba(uint16_t c)
{
    uint32_t r = c & 31, gr = (c >> 5) & 31, b = (c >> 10) & 31;
    r = (r << 3) | (r >> 2);
    gr = (gr << 3) | (gr >> 2);
    b = (b << 3) | (b >> 2);
    return 0xFF000000u | b << 16 | gr << 8 | r;
}

static void update_color(uint32_t *rgba, const uint8_t *pal, unsigned byte_index)
{
    unsigned i = (byte_index & 0x3F) >> 1;
    rgba[i] = rgb555_to_rgba((uint16_t)(pal[i * 2] | pal[i * 2 + 1] << 8));
}

void cgb_update_rgba(gb *g)
{
    for (unsigned i = 0; i < CGB_PAL_BYTES; i += 2) {
        update_color(g->cgb.bg_rgba, g->cgb.bg_pal, i);
        update_color(g->cgb.obj_rgba, g->cgb.obj_pal, i);
    }
}

void cgb_reset(gb *g)
{
    struct gb_cgb *c = &g->cgb;
    bool on = c->on, compat = c->compat;
    memset(c, 0, sizeof *c);
    c->on = on;
    c->compat = compat;
    g->mem.vbk = 0;
    g->mem.svbk = 0;
    g->mem.wram_bank = 1;
    if (!on)
        return;
    c->hdma_len = 0x7F;     /* HDMA5 lee 0xFF */
    c->hdma_src = 0xFFF0;
    c->hdma_dst = 0x1FF0;
    c->rp = 0x00;
    c->bcps = c->ocps = 0x00;
    /* El arranque deja las paletas de fondo en blanco; las de objetos no las
     * toca (en el hardware quedan con basura): también blanco, para ser deterministas. */
    memset(c->bg_pal, 0xFF, sizeof c->bg_pal);
    memset(c->obj_pal, 0xFF, sizeof c->obj_pal);
    for (unsigned i = 1; i < CGB_PAL_BYTES; i += 2) {
        c->bg_pal[i] = 0x7F;
        c->obj_pal[i] = 0x7F;
    }
    if (compat) {
        c->opri = 1;        /* prioridad de objetos DMG */
        load_compat_palettes(g);
    } else {
        cgb_update_rgba(g);
    }
}

/* ---- HDMA (FF51–FF55) ---- */

/* Origen: 0000–7FFF y A000–DFFF. VRAM no es un origen válido (lee 0xFF);
 * E000–FFFF se lee como A000–BFFF (el bus externo, Pan Docs). */
static uint8_t hdma_source(gb *g, uint16_t addr)
{
    if (addr >= 0x8000 && addr < 0xA000)
        return 0xFF;
    if (addr >= 0xE000)
        addr = (uint16_t)(addr - 0x4000);
    return mmu_read(g, addr);
}

static void hdma_block(gb *g)
{
    struct gb_cgb *c = &g->cgb;
    uint8_t *bank = g->mem.vram + (g->mem.vbk ? 0x2000 : 0);
    for (unsigned i = 0; i < 16; i++) {
        bank[(c->hdma_dst + i) & 0x1FFF] = hdma_source(g, (uint16_t)(c->hdma_src + i));
    }
    c->hdma_src = (uint16_t)(c->hdma_src + 16);
    c->hdma_dst = (uint16_t)((c->hdma_dst + 16) & 0x1FF0);
    /* 8 M-ciclos por bloque a velocidad normal, 16 en doble velocidad (mismo tiempo real). */
    c->stall = (uint16_t)(c->stall + (c->double_speed ? 16 : 8));
    c->hdma_len = (uint8_t)((c->hdma_len - 1) & 0x7F);
}

void cgb_hdma_hblank(gb *g)
{
    struct gb_cgb *c = &g->cgb;
    c->hdma_req = false;
    if (!c->hdma_active)
        return;
    bool last = c->hdma_len == 0;
    hdma_block(g);
    if (last)
        c->hdma_active = false;   /* hdma_len vuelve a 0x7F: FF55 lee 0xFF */
}

static void hdma_start(gb *g, uint8_t v)
{
    struct gb_cgb *c = &g->cgb;
    if (c->hdma_active && !(v & 0x80)) {
        c->hdma_active = false;   /* detiene el HDMA de HBlank: FF55 lee 0x80 | restantes */
        return;
    }
    c->hdma_len = v & 0x7F;
    if (v & 0x80) {
        c->hdma_active = true;
        c->hdma_req = false;
        return;
    }
    /* General: todo de golpe, con la CPU parada el tiempo equivalente. */
    unsigned blocks = (unsigned)c->hdma_len + 1;
    for (unsigned i = 0; i < blocks; i++)
        hdma_block(g);
}

/* ---- Doble velocidad ---- */

void cgb_speed_switch(gb *g)
{
    struct gb_cgb *c = &g->cgb;
    c->double_speed = !c->double_speed;
    c->speed_prepare = false;
}

/* ---- Registros FF4C–FF7F ---- */

uint8_t cgb_io_read(gb *g, uint16_t addr)
{
    struct gb_cgb *c = &g->cgb;
    if (!c->on)
        return 0xFF;
    bool native = !c->compat;
    switch (addr) {
    case 0xFF4D: return native ? (uint8_t)(0x7E | (c->double_speed ? 0x80 : 0) | (c->speed_prepare ? 1 : 0)) : 0xFF;
    case 0xFF4F: return native ? (uint8_t)(0xFE | g->mem.vbk) : 0xFF;
    case 0xFF55: return native ? (uint8_t)((c->hdma_active ? 0 : 0x80) | c->hdma_len) : 0xFF;
    case 0xFF56: return native ? (uint8_t)(0x3E | (c->rp & 0xC1)) : 0xFF;
    case 0xFF68: return native ? (uint8_t)(0x40 | c->bcps) : 0xFF;
    case 0xFF69:
        if (!native || ppu_vram_blocked(g))
            return 0xFF;
        return c->bg_pal[c->bcps & 0x3F];
    case 0xFF6A: return native ? (uint8_t)(0x40 | c->ocps) : 0xFF;
    case 0xFF6B:
        if (!native || ppu_vram_blocked(g))
            return 0xFF;
        return c->obj_pal[c->ocps & 0x3F];
    case 0xFF6C: return native ? (uint8_t)(0xFE | c->opri) : 0xFF;
    case 0xFF70: return native ? (uint8_t)(0xF8 | g->mem.svbk) : 0xFF;
    case 0xFF72: return c->ff72;
    case 0xFF73: return c->ff73;
    case 0xFF74: return native ? c->ff74 : 0xFF;
    case 0xFF75: return (uint8_t)(0x8F | c->ff75);
    case 0xFF76: case 0xFF77: return 0x00;   /* PCM12/PCM34: sin emular (salida digital en 0) */
    default: return 0xFF;
    }
}

/* Escritura en BCPD/OCPD: bloqueada en modo 3, pero el autoincremento avanza igual. */
static void palette_write(gb *g, uint8_t *spec, uint8_t *pal, uint32_t *rgba, uint8_t v)
{
    unsigned i = *spec & 0x3F;
    if (!ppu_vram_blocked(g)) {
        pal[i] = v;
        update_color(rgba, pal, i);
    }
    if (*spec & 0x80)
        *spec = (uint8_t)(0x80 | ((i + 1) & 0x3F));
}

void cgb_io_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_cgb *c = &g->cgb;
    if (!c->on)
        return;
    bool native = !c->compat;
    switch (addr) {
    case 0xFF72: c->ff72 = v; return;
    case 0xFF73: c->ff73 = v; return;
    case 0xFF75: c->ff75 = v & 0x70; return;
    default: break;
    }
    if (!native)
        return;   /* KEY0=4: el resto de registros de CGB quedan bloqueados */
    switch (addr) {
    case 0xFF4D: c->speed_prepare = v & 1; break;
    case 0xFF4F: g->mem.vbk = v & 1; break;
    case 0xFF51: c->hdma_src = (uint16_t)((c->hdma_src & 0x00F0) | v << 8); break;
    case 0xFF52: c->hdma_src = (uint16_t)((c->hdma_src & 0xFF00) | (v & 0xF0)); break;
    case 0xFF53: c->hdma_dst = (uint16_t)((c->hdma_dst & 0x00F0) | (v & 0x1F) << 8); break;
    case 0xFF54: c->hdma_dst = (uint16_t)((c->hdma_dst & 0x1F00) | (v & 0xF0)); break;
    case 0xFF55: hdma_start(g, v); break;
    case 0xFF56: c->rp = v & 0xC1; break;
    case 0xFF68: c->bcps = v & 0xBF; break;
    case 0xFF69: palette_write(g, &c->bcps, c->bg_pal, c->bg_rgba, v); break;
    case 0xFF6A: c->ocps = v & 0xBF; break;
    case 0xFF6B: palette_write(g, &c->ocps, c->obj_pal, c->obj_rgba, v); break;
    case 0xFF6C: c->opri = v & 1; break;
    case 0xFF70:
        g->mem.svbk = v & 7;
        g->mem.wram_bank = g->mem.svbk ? g->mem.svbk : 1;
        break;
    case 0xFF74: c->ff74 = v; break;
    default: break;
    }
}
