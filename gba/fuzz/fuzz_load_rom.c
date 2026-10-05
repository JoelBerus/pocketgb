/*
 * fuzz_load_rom.c — libFuzzer: un ROM arbitrario (con el byte fijo de la
 * cabecera) se carga y se ejecuta 3 frames con botones derivados de la
 * entrada; también se cargan una partida y un RTC del tamaño esperado.
 * Ningún acceso puede salir de los búferes del núcleo (regla dura 3).
 */
#include "pocketgba.h"
#include <stdlib.h>
#include <string.h>

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t len)
{
    if (len < GBA_ROM_MIN_BYTES) return 0;
    uint8_t *rom = malloc(len);
    if (!rom) return 0;
    memcpy(rom, data, len);
    rom[0xB2] = 0x96;
    gba *g = gba_create();
    gba_options o;
    gba_options_default(&o);
    o.save_type = (gba_save_type)(data[0xB3] % 7);
    o.rtc = data[0xB4] % 3;
    if (g && gba_load_rom(g, rom, len, &o) == GBA_OK) {
        size_t sz = gba_save_size(g);
        if (sz) {
            uint8_t *sav = malloc(sz);
            if (sav) {
                for (size_t i = 0; i < sz; i++) sav[i] = data[i % len];
                gba_save_load(g, sav, sz);
                free(sav);
            }
        }
        uint8_t rtc[GBA_RTC_BYTES];
        memcpy(rtc, data, sizeof rtc);
        gba_rtc_load(g, rtc, sizeof rtc);
        for (int f = 0; f < 3; f++) {
            gba_set_buttons(g, (uint16_t)(data[(0xB5 + f) % len] | (data[(0xB8 + f) % len] << 8)));
            gba_run_frame(g);
        }
        uint8_t out[128 * 1024];
        gba_save_write(g, out, sizeof out);
    }
    gba_destroy(g);
    free(rom);
    return 0;
}
