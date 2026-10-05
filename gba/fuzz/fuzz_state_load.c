/*
 * fuzz_state_load.c — libFuzzer: gba_state_load con datos arbitrarios y con
 * estados válidos mutados a los que se recalcula el CRC (así se llega a la
 * validación de cada campo). Tras una carga aceptada se ejecuta un frame:
 * ningún estado aceptado puede provocar accesos fuera de búfer ni cuelgues.
 */
#include "pocketgba.h"
#include <stdlib.h>
#include <string.h>

static uint32_t crc32(const uint8_t *p, size_t n)
{
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
    return ~c;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t len)
{
    static const char tag[] = "FLASH1M_V103";
    uint8_t rom[0x400] = {0};
    rom[0] = 0xFE; rom[1] = 0xFF; rom[2] = 0xFF; rom[3] = 0xEA;     /* b . */
    rom[0xB2] = 0x96;
    memcpy(rom + 0x200, tag, sizeof tag - 1);
    gba *g = gba_create();
    if (!g || gba_load_rom(g, rom, sizeof rom, NULL) != GBA_OK) { gba_destroy(g); return 0; }
    gba_run_frame(g);
    /* 1) Datos arbitrarios tal cual. */
    if (gba_state_load(g, data, len) == GBA_OK) gba_run_frame(g);
    /* 2) Estado válido con mutaciones dirigidas por la entrada y CRC correcto. */
    size_t sz = gba_state_size(g);
    uint8_t *st = malloc(sz);
    if (st && gba_state_save(g, st, sz) == GBA_OK && len >= 4) {
        for (size_t i = 0; i + 4 <= len; i += 4) {
            size_t off = 44 + ((size_t)data[i] | ((size_t)data[i + 1] << 8) | ((size_t)data[i + 2] << 16)) % (sz - 48);
            st[off] ^= data[i + 3];
        }
        uint32_t c = crc32(st, sz - 4);
        st[sz - 4] = (uint8_t)c; st[sz - 3] = (uint8_t)(c >> 8); st[sz - 2] = (uint8_t)(c >> 16); st[sz - 1] = (uint8_t)(c >> 24);
        if (gba_state_load(g, st, sz) == GBA_OK) {
            gba_run_frame(g);
            gba_run_frame(g);
        }
    }
    free(st);
    gba_destroy(g);
    return 0;
}
