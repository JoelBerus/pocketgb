/* unit_common.c — utilidades compartidas por los unit tests. */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

void ut_fix_header_checksum(uint8_t *rom)
{
    uint8_t x = 0;
    for (int i = 0x134; i <= 0x14C; i++)
        x = (uint8_t)(x - rom[i] - 1);
    rom[0x14D] = x;
}

uint8_t *ut_make_rom(size_t size, uint8_t type, uint8_t rom_code, uint8_t ram_code,
                     const uint8_t *prog, size_t prog_len)
{
    if (size < 0x150 + prog_len)
        return NULL;
    uint8_t *rom = calloc(1, size);
    if (!rom)
        return NULL;
    static const uint8_t entry[4] = { 0x00, 0xC3, 0x50, 0x01 };   /* NOP; JP 0x0150 */
    memcpy(rom + 0x100, entry, sizeof entry);
    memcpy(rom + 0x134, "TEST", 4);
    rom[0x147] = type;
    rom[0x148] = rom_code;
    rom[0x149] = ram_code;
    if (prog_len)
        memcpy(rom + 0x150, prog, prog_len);
    ut_fix_header_checksum(rom);
    return rom;
}
