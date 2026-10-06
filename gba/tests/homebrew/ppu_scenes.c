/*
 * ppu_scenes.c — ROM homebrew propia (MIT) para comparar la PPU de PocketGB con
 * el oráculo mGBA (G3). Cada 32 frames monta una escena distinta en el VBlank:
 * 0 cuatro fondos de texto · 1 modo 1 afín · 2 modo 2 · 3 modo 3 + objetos ·
 * 4 modo 4 página 1 · 5 modo 5 rotado · 6 objetos 1D · 7 objetos 2D y afines ·
 * 8 ventanas + alfa · 9 aclarar + semitransparentes + mosaico · 10 oscurecer +
 * mosaico de objetos · 11 efectos por línea (IRQ de HBlank/VCount, DMA de HBlank) ·
 * 12 modo 1 con mosaico en el afín y objetos de 256 colores con tesela impar en 1D ·
 * 13 modo 3 con mosaico (sin rotación: con rotación mGBA usa otro muestreo, ver la spec).
 * Se compara en el frame 120 (tools/gba-compare.py).
 */
#include "gba.h"

static uint16_t color(int i, int s)
{
    return (uint16_t)(((i * 7 + s) & 31) | (((i * 13 + s * 3) & 31) << 5) | (((i * 3 + 5 + s) & 31) << 10));
}

static void pal_init(int seed)
{
    for (int i = 0; i < 256; i++) {
        PAL[i] = color(i, seed);
        PAL[256 + i] = color(i, seed + 11);
    }
}

static void clear_vram(void)
{
    for (int i = 0; i < 0xC000; i++) VRAM[i] = 0;
    for (int i = 0; i < 512; i++) OAM[i] = 0x0200;     /* objetos desactivados */
}

/* 4bpp: nibble = (x + y + t + seed) & 15, transparente si (x ^ y) & 3 == 0. */
static void tiles4(uint32_t byte, int n, int seed)
{
    volatile uint16_t *p = VRAM + byte / 2;
    for (int t = 0; t < n; t++)
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x += 4) {
                uint16_t w = 0;
                for (int k = 0; k < 4; k++) {
                    int v = (x + k + y + t + seed) & 15;
                    if ((((x + k) ^ y) & 3) == 0) v = 0;
                    w |= (uint16_t)(v << (4 * k));
                }
                *p++ = w;
            }
}

/* 8bpp: byte = (3x + 5y + t + seed) & 255, transparente si (x + y) & 7 == 0. */
static void tiles8(uint32_t byte, int n, int seed)
{
    volatile uint16_t *p = VRAM + byte / 2;
    for (int t = 0; t < n; t++)
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x += 2) {
                int a = (3 * x + 5 * y + t + seed) & 255, b = (3 * (x + 1) + 5 * y + t + seed) & 255;
                if (((x + y) & 7) == 0) a = 0;
                if (((x + 1 + y) & 7) == 0) b = 0;
                *p++ = (uint16_t)(a | (b << 8));
            }
}

static void text_map(int sb, int blocks, int ntiles, int flips)
{
    volatile uint16_t *m = VRAM + sb * 0x400;
    for (int i = 0; i < blocks * 1024; i++) {
        int t = (i * 7 + i / 32) % ntiles;
        int e = t | ((i & 15) << 12);
        if (flips) e |= ((i >> 2) & 3) << 10;
        m[i] = (uint16_t)e;
    }
}

static void affine_map(int sb, int tiles_per_side)
{
    volatile uint16_t *m = VRAM + sb * 0x400;
    int n = tiles_per_side * tiles_per_side;
    for (int i = 0; i < n; i += 2) m[i / 2] = (uint16_t)(((i * 5) % 63 + 1) | ((((i + 1) * 5) % 63 + 1) << 8));
}

static void obj_set(int i, int a0, int a1, int a2)
{
    OAM[i * 4] = (uint16_t)a0;
    OAM[i * 4 + 1] = (uint16_t)a1;
    OAM[i * 4 + 2] = (uint16_t)a2;
}

static void obj_affine(int grp, int pa, int pb, int pc, int pd)
{
    OAM[grp * 16 + 3] = (uint16_t)pa;
    OAM[grp * 16 + 7] = (uint16_t)pb;
    OAM[grp * 16 + 11] = (uint16_t)pc;
    OAM[grp * 16 + 15] = (uint16_t)pd;
}

static void many_objs(int affine_too, int tile_base)
{
    for (int i = 0; i < 128; i++) {
        int shape = i % 3, size = (i / 3) % 4;
        int x = (i * 37) % 300 - 30, y = (i * 23) % 180;
        int a0 = (y & 255) | (shape << 14);
        int a1 = (x & 511) | (size << 14);
        int c256 = i % 5 == 0;
        if (c256) a0 |= 0x2000;
        if (affine_too && i % 4 == 0) {
            a0 |= 0x100;
            if (i % 8 == 0) a0 |= 0x200;
            a1 |= ((i / 4) % 32) << 9;
        } else {
            a1 |= ((i & 1) << 12) | (((i >> 1) & 1) << 13);
            if (i % 17 == 0) a0 |= 0x200;            /* desactivado */
        }
        int tile = tile_base + ((i * 4) % 256);
        if (c256) tile &= ~1;
        obj_set(i, a0, a1, tile | ((i % 4) << 10) | ((i % 16) << 12));
    }
    obj_set(1, 250 | (0 << 14), 100 | (2 << 14), tile_base);        /* y=250: aparece arriba */
    obj_set(2, 40 | (0 << 14), 500 | (2 << 14), tile_base + 8);      /* x=500: entra por la izquierda */
    for (int g = 0; g < 32; g++) {
        int c[8] = {256, 237, 181, 98, 0, -98, -181, -237};
        int s[8] = {0, 98, 181, 237, 256, 237, 181, 98};
        int k = g % 8, sc = 200 + g * 4;
        obj_affine(g, c[k] * 256 / sc, -s[k] * 256 / sc, s[k] * 256 / sc, c[k] * 256 / sc);
    }
}

static void reset_regs(void)
{
    DISPCNT = 0x80;
    for (int n = 0; n < 4; n++) { BGCNT(n) = 0; BGHOFS(n) = 0; BGVOFS(n) = 0; }
    BG2PA = 0x100; BG2PB = 0; BG2PC = 0; BG2PD = 0x100; BG2X = 0; BG2Y = 0;
    BG3PA = 0x100; BG3PB = 0; BG3PC = 0; BG3PD = 0x100; BG3X = 0; BG3Y = 0;
    WIN0H = 0; WIN1H = 0; WIN0V = 0; WIN1V = 0; WININ = 0; WINOUT = 0;
    MOSAIC = 0; BLDCNT = 0; BLDALPHA = 0; BLDY = 0;
    IME = 0; IE = 0; DISPSTAT = 0;
    REG16(0x040000BA) = 0;
}

/* ------------------------------------------------------------ efectos por línea */

static uint16_t line_colors[228];

__attribute__((target("arm"), section(".iwram"), noinline)) void irq_handler(void)
{
    uint16_t f = IF;
    if (f & 2) BGHOFS(0) = (uint16_t)((VCOUNT * 3) & 511);
    if (f & 4) BGVOFS(1) = 40;
    if (f & 1) BGVOFS(1) = 0;
    IF = f;
}

static void scene(int s)
{
    reset_regs();
    clear_vram();
    pal_init(s * 3);
    PAL[0] = color(s, 1);
    switch (s) {
    case 0:
        tiles4(0x0000, 64, 0);
        tiles8(0x4000, 64, 0);
        text_map(16, 1, 64, 0);
        text_map(18, 2, 64, 0);
        text_map(20, 2, 64, 1);
        for (int i = 0; i < 4096; i++) VRAM[24 * 0x400 + i] = (uint16_t)((i % 19 == 0) ? (i % 64) : 64);
        BGCNT(0) = (uint16_t)(3 | (0 << 2) | (16 << 8));
        BGCNT(1) = (uint16_t)(2 | (1 << 2) | 0x80 | (18 << 8) | (1 << 14));
        BGCNT(2) = (uint16_t)(1 | (0 << 2) | (20 << 8) | (2 << 14));
        BGCNT(3) = (uint16_t)(0 | (0 << 2) | (24 << 8) | (3 << 14));
        BGHOFS(0) = 13; BGVOFS(0) = 7; BGHOFS(1) = 300; BGVOFS(1) = 50;
        BGHOFS(2) = 5; BGVOFS(2) = 300; BGHOFS(3) = 400; BGVOFS(3) = 450;
        DISPCNT = 0x0F00;
        break;
    case 1:
        tiles4(0x0000, 64, 1);
        tiles8(0x4000, 64, 2);
        text_map(16, 1, 64, 1);
        affine_map(20, 32);
        BGCNT(0) = (uint16_t)(1 | (16 << 8));
        BGCNT(2) = (uint16_t)(0 | (1 << 2) | (20 << 8) | (1 << 14) | 0x2000);
        BG2PA = 0xB5; BG2PB = (uint16_t)-0xB5; BG2PC = 0xB5; BG2PD = 0xB5;
        BG2X = 0x2000; BG2Y = (uint32_t)-0x1000;
        DISPCNT = 0x0501;
        break;
    case 2:
        tiles8(0x4000, 64, 3);
        affine_map(20, 16);
        affine_map(24, 64);
        BGCNT(2) = (uint16_t)(1 | (1 << 2) | (20 << 8) | (0 << 14));
        BGCNT(3) = (uint16_t)(0 | (1 << 2) | (24 << 8) | (2 << 14) | 0x2000);
        BG2PA = 0x80; BG2PD = 0x80; BG2X = (uint32_t)-0x800; BG2Y = (uint32_t)-0x400;
        BG3PA = 0xDD; BG3PB = (uint16_t)-0x80; BG3PC = 0x80; BG3PD = 0xDD; BG3X = 0x5000; BG3Y = 0x1800;
        DISPCNT = 0x0C02;
        break;
    case 3:
        for (int y = 0, i = 0; y < 160; y++)
            for (int x = 0; x < 240; x++, i++)   /* sin divisiones: el ARM7 no divide */
                VRAM[i] = (uint16_t)((x >> 3) | (((y >> 3) & 31) << 5) | ((((x + y) >> 4) & 31) << 10));
        tiles4(0x14000, 64, 4);
        obj_set(0, 60 | (0 << 14), 100 | (2 << 14), 512 | (1 << 12));
        obj_set(1, 10 | (1 << 14), 10 | (1 << 14), 520);         /* < 512 en el borde: visible */
        obj_set(2, 100, 200, 4);                                  /* tile < 512: invisible en bitmap */
        BG2PA = 0xF0; BG2PD = 0xF8; BG2X = 0x300;
        DISPCNT = 0x1403 | 0x40;
        break;
    case 4:
        for (int i = 0; i < 240 * 160 / 2; i++) {
            VRAM[i] = (uint16_t)(((i * 2) & 255) | (((i * 2 + 1) & 255) << 8));
            VRAM[0x5000 + i] = (uint16_t)(((i * 3) & 255) | (((i >> 6) & 255) << 8));
        }
        DISPCNT = 0x0414;
        break;
    case 5:
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 160; x++) VRAM[y * 160 + x] = (uint16_t)(((x >> 2) & 31) | (((y >> 2) & 31) << 5) | 0x4000);
        BG2PA = 0xF1; BG2PB = (uint16_t)-0x57; BG2PC = 0x57; BG2PD = 0xF1;
        BG2X = (uint32_t)(-20 * 256); BG2Y = (uint32_t)(10 << 8);
        DISPCNT = 0x0405;
        break;
    case 6:
    case 7:
        tiles4(0x0000, 64, 5);
        text_map(16, 1, 64, 0);
        BGCNT(0) = (uint16_t)(2 | (16 << 8));
        tiles4(0x10000, 512, 6);
        tiles8(0x10000 + 512 * 32, 128, 7);
        many_objs(s == 7, 0);
        DISPCNT = (uint16_t)(0x1100 | (s == 6 ? 0x40 : 0));
        break;
    case 8:
        tiles4(0x0000, 64, 8);
        tiles8(0x4000, 64, 9);
        text_map(16, 1, 64, 0);
        text_map(18, 1, 64, 1);
        BGCNT(0) = (uint16_t)(1 | (16 << 8));
        BGCNT(1) = (uint16_t)(2 | (1 << 2) | 0x80 | (18 << 8));
        tiles4(0x10000, 128, 10);
        many_objs(0, 0);
        obj_set(127, 10 | (0 << 14) | (2 << 10), 150 | (3 << 14), 0);   /* ventana de objeto 64x64 */
        WIN0H = (20 << 8) | 120; WIN0V = (30 << 8) | 100;
        WIN1H = (80 << 8) | 200; WIN1V = (60 << 8) | 140;
        WININ = 0x31 | (0x22 << 8);
        WINOUT = 0x03 | (0x32 << 8);
        BLDCNT = 0x11 | (1 << 6) | (0x22 << 8);
        BLDALPHA = 9 | (7 << 8);
        DISPCNT = 0xF340;
        break;
    case 9:
    case 10:
        tiles4(0x0000, 64, 11);
        tiles8(0x4000, 64, 12);
        text_map(16, 1, 64, 1);
        text_map(18, 1, 64, 0);
        BGCNT(0) = (uint16_t)(1 | (16 << 8) | 0x40);
        BGCNT(1) = (uint16_t)(3 | (1 << 2) | 0x80 | (18 << 8));
        tiles4(0x10000, 128, 13);
        many_objs(s == 10, 0);
        for (int i = 0; i < 128; i += 3) OAM[i * 4] = (uint16_t)(OAM[i * 4] | (1 << 10) | (s == 10 ? 0x1000 : 0));
        MOSAIC = (uint16_t)(s == 9 ? (3 | (2 << 4)) : (1 | (3 << 4) | (2 << 8) | (4 << 12)));
        BLDCNT = (uint16_t)(0x21 | ((s == 9 ? 2 : 3) << 6) | (0x02 << 8));
        BLDALPHA = 12 | (6 << 8);
        BLDY = s == 9 ? 6 : 11;
        DISPCNT = 0x1340;
        break;
    case 12:
        tiles8(0x4000, 64, 16);
        affine_map(20, 32);
        BGCNT(2) = (uint16_t)(0 | (1 << 2) | (20 << 8) | (1 << 14) | 0x2000 | 0x40);
        BG2PA = 0xE0; BG2PB = (uint16_t)-0x60; BG2PC = 0x60; BG2PD = 0xE0;
        BG2X = 0x1800; BG2Y = (uint32_t)-0x800;
        MOSAIC = 2 | (3 << 4);
        tiles8(0x10000, 64, 17);
        for (int i = 0; i < 8; i++)
            obj_set(i, (20 + i * 16) | 0x2000, (10 + i * 28) | (1 << 14), (2 * i + 1) | ((i % 4) << 10));
        DISPCNT = 0x1441;
        break;
    case 13:
        for (int y = 0, i = 0; y < 160; y++)
            for (int x = 0; x < 240; x++, i++)
                VRAM[i] = (uint16_t)(((x >> 2) & 31) | ((((x ^ y) >> 1) & 31) << 5) | (((y >> 2) & 31) << 10));
        BGCNT(2) = 0x40;
        MOSAIC = 4 | (2 << 4);
        BG2PA = 0x100; BG2PD = 0x100; BG2X = 0x300; BG2Y = 0x200;
        DISPCNT = 0x0403;
        break;
    case 11:
        tiles4(0x0000, 64, 14);
        tiles8(0x4000, 64, 15);
        text_map(16, 1, 64, 0);
        text_map(18, 1, 64, 1);
        BGCNT(0) = (uint16_t)(1 | (16 << 8));
        BGCNT(1) = (uint16_t)(2 | (1 << 2) | 0x80 | (18 << 8));
        for (int i = 0; i < 228; i++) line_colors[i] = color(i, 5);
        IRQ_HANDLER = (uint32_t)irq_handler;
        DISPSTAT = (uint16_t)(0x08 | 0x10 | 0x20 | (80 << 8));
        IE = 7;
        IME = 1;
        DISPCNT = 0x0300;
        break;
    }
}

#ifndef SCENE
#define SCENE 0
#endif

int main(void)
{
    REG16(0x04000204) = 0x4317;          /* WAITCNT habitual de los juegos */
    vsync();
    scene(SCENE);
    for (;;) {
        vsync();
        if (SCENE == 11) {
            /* DMA0 de HBlank: un color de fondo por línea, rearmada cada frame. */
            REG16(0x040000BA) = 0;
            DMA0SAD = (uint32_t)line_colors;
            DMA0DAD = 0x05000000;
            DMA0CNT = 1 | ((uint32_t)(0x8000 | 0x2000 | 0x0200 | 0x0040) << 16);
        }
    }
}
