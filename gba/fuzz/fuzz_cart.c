/*
 * fuzz_cart.c — libFuzzer sobre el cartucho: secuencias arbitrarias de
 * lecturas y escrituras de 8/16/32 bits en SRAM/Flash (0x0E…), EEPROM (0x0D…),
 * GPIO del RTC (0x080000C4–C9) y DMA3 hacia/desde la EEPROM, con cada tipo de
 * medio y el RTC activo; al final se guardan la partida y el .rtc. Sin frames
 * enteros: miles de ejecuciones por segundo.
 */
#include "pocketgba.h"
#include <string.h>

uint32_t gba_fuzz_bus(gba *g, int op, uint32_t addr, uint32_t v);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t len)
{
    if (len < 2) return 0;
    static const char *tags[5] = {"SRAM_V113", "FLASH_V126", "FLASH1M_V103", "EEPROM_V124", "NONE"};
    uint8_t rom[0x400] = {0};
    rom[0] = 0xFE; rom[1] = 0xFF; rom[2] = 0xFF; rom[3] = 0xEA;
    rom[0xB2] = 0x96;
    const char *tag = tags[data[0] % 5];
    memcpy(rom + 0x200, tag, strlen(tag));
    gba_options o;
    gba_options_default(&o);
    o.rtc = GBA_RTC_ON;
    o.unix_time = (int64_t)data[1] * 86400 * 365;
    gba *g = gba_create();
    if (!g || gba_load_rom(g, rom, sizeof rom, &o) != GBA_OK) { gba_destroy(g); return 0; }
    static const uint32_t base[4] = {0x0E000000, 0x0D000000, 0x080000C4, 0x040000D4};
    for (size_t i = 2; i + 5 <= len; i += 5) {
        int op = data[i] & 7;
        uint32_t addr = base[(data[i] >> 3) & 3] + (((uint32_t)data[i + 1] | ((uint32_t)data[i + 2] << 8)) & 0xFFFFu);
        uint32_t v = (uint32_t)data[i + 3] | ((uint32_t)data[i + 4] << 8);
        gba_fuzz_bus(g, op, addr, v * 0x10001u);
        if (data[i] & 0x80) gba_run_cycles(g, (v & 0xFF) + 1);
    }
    uint8_t out[128 * 1024], rtc[GBA_RTC_BYTES];
    gba_save_write(g, out, sizeof out);
    if (gba_rtc_save(g, rtc, sizeof rtc) == GBA_OK) gba_rtc_load(g, rtc, sizeof rtc);
    gba_destroy(g);
    return 0;
}
