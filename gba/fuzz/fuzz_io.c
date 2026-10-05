/*
 * fuzz_io.c — libFuzzer: escrituras arbitrarias en la E/S (DMA, timers, PPU,
 * APU, IRQ, WAITCNT, HALTCNT…) y en VRAM/OAM/paleta, intercaladas con avances
 * cortos de la emulación. Busca accesos fuera de búfer, UB y cuelgues.
 */
#include "pocketgba.h"
#include <string.h>

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t len)
{
    uint8_t rom[0x400] = {0};
    rom[0] = 0xFE; rom[1] = 0xFF; rom[2] = 0xFF; rom[3] = 0xEA;     /* b . */
    rom[0xB2] = 0x96;
    memcpy(rom + 0x200, "EEPROM_V124", 11);
    gba *g = gba_create();
    if (!g || gba_load_rom(g, rom, sizeof rom, NULL) != GBA_OK) { gba_destroy(g); return 0; }
    /* Programa: registros de 5 bytes {tipo, dirección 16 bits, valor 16 bits}. */
    for (size_t i = 0; i + 5 <= len; i += 5) {
        uint16_t addr = (uint16_t)(data[i + 1] | (data[i + 2] << 8));
        uint16_t val = (uint16_t)(data[i + 3] | (data[i + 4] << 8));
        uint8_t region = data[i] & 7u;
        static const uint32_t base[8] = {0x04000000, 0x04000000, 0x04000000, 0x05000000,
                                         0x06000000, 0x07000000, 0x0D000000, 0x0E000000};
        gba_options o; (void)o;
        extern void gba_fuzz_poke(gba *, uint32_t, uint16_t, int);
        gba_fuzz_poke(g, base[region] + (region < 3 ? (addr & 0x3FFu) : addr), val, (data[i] >> 3) & 3);
        if (data[i] & 0x20) gba_run_cycles(g, (uint32_t)(val & 0x3FF) + 1);
    }
    gba_run_frame(g);
    int16_t pcm[4096];
    gba_audio_read(g, pcm, 2048);
    gba_destroy(g);
    return 0;
}
