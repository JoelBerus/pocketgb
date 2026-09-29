/*
 * fuzz_state_load.c — libFuzzer: gb_state_load con bytes arbitrarios sobre un
 * ROM sintético fijo (MBC3+RTC+RAM, para cubrir todas las secciones).
 *
 * 1) Los bytes tal cual (magic, versión, CRC, huella…).
 * 2) Los mismos bytes con la cabecera y el CRC corregidos, para llegar al
 *    análisis de secciones y campos. Si la carga se acepta, se ejecutan frames.
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

static gb *fixed_instance(void)
{
    enum { SIZE = 0x8000 };
    uint8_t *rom = calloc(1, SIZE);
    if (!rom)
        return NULL;
    static const uint8_t entry[4] = { 0x00, 0xC3, 0x50, 0x01 };
    static const uint8_t prog[] = { 0x3C, 0xEA, 0x00, 0xC0, 0x18, 0xFA };
    memcpy(rom + 0x100, entry, sizeof entry);
    memcpy(rom + 0x150, prog, sizeof prog);
    rom[0x147] = 0x10;   /* MBC3+TIMER+RAM+BATTERY */
    rom[0x149] = 0x03;   /* 32 KiB */
    gb *g = gb_create();
    if (g && gb_load_rom(g, rom, SIZE, NULL) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static void put32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)(v >> (8 * i));
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    gb *g = fixed_instance();
    if (!g)
        return 0;
    if (gb_state_load(g, data, size) == GB_OK)
        for (int f = 0; f < 3; f++)
            gb_run_frame(g);

    if (size >= 48) {
        uint8_t *s = malloc(size);
        if (s) {
            memcpy(s, data, size);
            memcpy(s, "PGBS", 4);
            put32(s + 4, 1);
            memcpy(s + 8, g->info.fingerprint, 32);
            put32(s + 40, 1);
            put32(s + size - 4, crc32_update(0, s, size - 4));
            if (gb_state_load(g, s, size) == GB_OK)
                for (int f = 0; f < 3; f++)
                    gb_run_frame(g);
            free(s);
        }
    }
    gb_destroy(g);
    return 0;
}
