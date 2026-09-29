/* unit_cart.c — validación de cabecera, título, MBC1 y SRAM con ROMs sintéticos. */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

static gb_result load(gb *g, const uint8_t *rom, size_t len)
{
    return gb_load_rom(g, rom, len, NULL);
}

static void header_errors(struct ut *t, gb *g)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, NULL, 0);
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    gb_rom_info info;

    CHECK(t, gb_load_rom(NULL, rom, 0x8000, NULL) == GB_ERR_NULL_ARG);
    CHECK(t, load(g, NULL, 0x8000) == GB_ERR_NULL_ARG);
    CHECK(t, load(g, rom, 0x14F) == GB_ERR_ROM_TOO_SMALL);
    CHECK(t, gb_rom_info_get(g, &info) == GB_ERR_NO_ROM);

    /* 0x148 fuera de rango, incluidos 0x52–0x54 (fuera de alcance) */
    const uint8_t bad_rom_codes[] = { 0x09, 0x52, 0x53, 0x54, 0xFF };
    for (size_t i = 0; i < sizeof bad_rom_codes; i++) {
        rom[0x148] = bad_rom_codes[i];
        CHECK(t, load(g, rom, 0x8000) == GB_ERR_BAD_ROM_SIZE_CODE);
    }
    /* la cabecera declara 64 KiB y el archivo tiene 32 KiB */
    rom[0x148] = 0x01;
    CHECK(t, load(g, rom, 0x8000) == GB_ERR_ROM_TRUNCATED);
    rom[0x148] = 0x00;

    rom[0x149] = 0x06;
    CHECK(t, load(g, rom, 0x8000) == GB_ERR_BAD_RAM_SIZE_CODE);
    rom[0x149] = 0x00;

    const uint8_t bad_types[] = { 0x05, 0x06, 0x08, 0x20, 0xFF };
    for (size_t i = 0; i < sizeof bad_types; i++) {
        rom[0x147] = bad_types[i];
        CHECK(t, load(g, rom, 0x8000) == GB_ERR_UNSUPPORTED_MBC);
    }
    rom[0x147] = 0x00;

    rom[0x143] = 0xC0;
    CHECK(t, load(g, rom, 0x8000) == GB_ERR_CGB_ONLY);
    CHECK(t, gb_rom_info_get(g, &info) == GB_ERR_NO_ROM);
    rom[0x143] = 0x00;

    /* Tras un error la instancia queda sin ROM y sin ejecutar nada. */
    uint64_t cycles = gb_cycle_count(g);
    gb_run_frame(g);
    CHECK(t, gb_cycle_count(g) == cycles);

    /* Archivo más grande que la cabecera: se acepta y se ignora el excedente. */
    uint8_t *big = calloc(1, 0x9000);
    CHECK(t, big != NULL);
    if (big) {
        memcpy(big, rom, 0x8000);
        CHECK(t, load(g, big, 0x9000) == GB_OK);
        CHECK(t, gb_rom_info_get(g, &info) == GB_OK && info.rom_bytes == 0x8000);
        free(big);
    }

    /* > 8 MiB */
    uint8_t *huge = calloc(1, GB_ROM_MAX_BYTES + 1);
    CHECK(t, huge != NULL);
    if (huge) {
        memcpy(huge, rom, 0x8000);
        CHECK(t, load(g, huge, GB_ROM_MAX_BYTES + 1) == GB_ERR_ROM_TOO_LARGE);
        free(huge);
    }
    free(rom);
}

static void header_info(struct ut *t, gb *g)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, NULL, 0);
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    gb_rom_info info;

    /* 16 bytes de título sin flag CGB */
    memcpy(rom + 0x134, "ABCDEFGHIJKLMNOP", 16);
    ut_fix_header_checksum(rom);
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, strcmp(info.title, "ABCDEFGHIJKLMNOP") == 0);
    CHECK(t, info.header_checksum_ok);
    CHECK(t, !info.cgb_mode && !info.has_battery && info.sram_bytes == 0);

    /* 15 bytes con flag CGB 0x80 (0x13F–0x142 no son código de fabricante) */
    memcpy(rom + 0x134, "POKEMON yellow\0", 15);
    rom[0x143] = 0x80;
    ut_fix_header_checksum(rom);
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, strcmp(info.title, "POKEMON yellow") == 0);
    CHECK(t, info.cgb_flag == 0x80);

    memcpy(rom + 0x134, "ABCDEFGHIJKlmno", 15);   /* minúsculas: no es código de fabricante */
    ut_fix_header_checksum(rom);
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, strcmp(info.title, "ABCDEFGHIJKlmno") == 0);

    /* 11 bytes: 0x13F–0x142 es un código de fabricante */
    memcpy(rom + 0x134, "SOMEGAME\x01\x02\x03" "AXVE", 15);
    ut_fix_header_checksum(rom);
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, strcmp(info.title, "SOMEGAME???") == 0);

    /* Checksum de cabecera incorrecto: se carga igual y se informa. */
    rom[0x14D] ^= 0xFF;
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, !info.header_checksum_ok);

    /* Checksum global correcto */
    rom[0x143] = 0x00;
    ut_fix_header_checksum(rom);
    uint16_t sum = 0;
    for (size_t i = 0; i < 0x8000; i++)
        if (i != 0x14E && i != 0x14F)
            sum = (uint16_t)(sum + rom[i]);
    rom[0x14E] = (uint8_t)(sum >> 8);
    rom[0x14F] = (uint8_t)sum;
    CHECK(t, load(g, rom, 0x8000) == GB_OK && gb_rom_info_get(g, &info) == GB_OK);
    CHECK(t, info.global_checksum_ok && info.header_checksum_ok);

    /* La huella es el SHA-256 del ROM completo. */
    uint8_t d[32];
    sha256(rom, 0x8000, d);
    CHECK(t, memcmp(d, info.fingerprint, 32) == 0);
    free(rom);
}

/* ROM de `banks` bancos de 16 KiB con el número de banco en cada byte (salvo la cabecera). */
static uint8_t *banked_rom(uint32_t banks, uint8_t type, uint8_t rom_code, uint8_t ram_code)
{
    size_t size = (size_t)banks * 0x4000;
    uint8_t *rom = ut_make_rom(size, type, rom_code, ram_code, NULL, 0);
    if (!rom)
        return NULL;
    for (uint32_t b = 1; b < banks; b++)
        memset(rom + (size_t)b * 0x4000, (int)b, 0x4000);
    return rom;
}

static void mbc1_banks(struct ut *t, gb *g)
{
    /* 512 KiB = 32 bancos */
    uint8_t *rom = banked_rom(32, 0x01, 0x04, 0x00);
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    CHECK(t, load(g, rom, 32u * 0x4000) == GB_OK);
    CHECK(t, mmu_read(g, 0x4000) == 1);            /* banco 0 → 1 */
    mmu_write(g, 0x2000, 0x05);
    CHECK(t, mmu_read(g, 0x4000) == 5 && mmu_read(g, 0x7FFF) == 5);
    mmu_write(g, 0x2000, 0x1F);
    CHECK(t, mmu_read(g, 0x5000) == 31);
    mmu_write(g, 0x2000, 0x20);                    /* 5 bits bajos = 0 → 1 */
    CHECK(t, mmu_read(g, 0x4000) == 1);
    mmu_write(g, 0x4000, 0x03);                    /* bits altos sin efecto en 512 KiB */
    mmu_write(g, 0x2000, 0x07);
    CHECK(t, mmu_read(g, 0x4000) == 7);
    CHECK(t, mmu_read(g, 0x0000) == 0);
    free(rom);

    /* 2 MiB = 128 bancos: bits altos y modo 1 */
    rom = banked_rom(128, 0x01, 0x06, 0x00);
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    CHECK(t, load(g, rom, 128u * 0x4000) == GB_OK);
    mmu_write(g, 0x4000, 0x01);
    mmu_write(g, 0x2000, 0x00);
    CHECK(t, mmu_read(g, 0x4000) == 0x21);         /* 0x20 inaccesible: da 0x21 */
    mmu_write(g, 0x4000, 0x03);
    mmu_write(g, 0x2000, 0x02);
    CHECK(t, mmu_read(g, 0x4000) == 0x62);
    CHECK(t, mmu_read(g, 0x0000) == 0x00);         /* modo 0: banco 0 fijo */
    mmu_write(g, 0x6000, 0x01);
    CHECK(t, mmu_read(g, 0x0000) == 0x60);         /* modo 1: banco 0x60 en 0000 */
    free(rom);
}

static void mbc1_ram(struct ut *t, gb *g)
{
    uint8_t *rom = banked_rom(4, 0x03, 0x01, 0x03);   /* MBC1+RAM+BATTERY, 32 KiB de RAM */
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    CHECK(t, load(g, rom, 4u * 0x4000) == GB_OK);
    gb_rom_info info;
    CHECK(t, gb_rom_info_get(g, &info) == GB_OK && info.has_battery && info.sram_bytes == 32768);
    CHECK(t, gb_sram_save_size(g) == 32768);

    CHECK(t, mmu_read(g, 0xA000) == 0xFF);            /* deshabilitada */
    mmu_write(g, 0xA000, 0x12);
    mmu_write(g, 0x0000, 0x0A);
    CHECK(t, mmu_read(g, 0xA000) == 0x00);            /* la escritura anterior se ignoró */
    CHECK(t, !gb_sram_dirty(g));
    mmu_write(g, 0xA000, 0x12);
    CHECK(t, mmu_read(g, 0xA000) == 0x12);
    mmu_write(g, 0x6000, 0x01);                       /* modo 1: banco de RAM = bits altos */
    mmu_write(g, 0x4000, 0x02);
    CHECK(t, mmu_read(g, 0xA000) == 0x00);
    mmu_write(g, 0xBFFF, 0x34);
    mmu_write(g, 0x4000, 0x00);
    CHECK(t, mmu_read(g, 0xA000) == 0x12);
    CHECK(t, !gb_sram_dirty(g));
    mmu_write(g, 0x0000, 0x00);                       /* flanco de deshabilitar con datos sucios */
    CHECK(t, gb_sram_dirty(g));
    gb_sram_clear_dirty(g);
    CHECK(t, !gb_sram_dirty(g));
    mmu_write(g, 0x0000, 0x0A);
    mmu_write(g, 0x0000, 0x00);                       /* sin escrituras: no se marca */
    CHECK(t, !gb_sram_dirty(g));

    uint8_t *sav = malloc(32768);
    CHECK(t, sav != NULL);
    if (sav) {
        CHECK(t, gb_sram_save(g, sav, 100) == GB_ERR_BUFFER_TOO_SMALL);
        CHECK(t, gb_sram_save(g, sav, 32768) == GB_OK);
        CHECK(t, sav[0] == 0x12 && sav[2 * 0x2000 + 0x1FFF] == 0x34);
        CHECK(t, gb_sram_load(g, sav, 32767) == GB_ERR_SRAM_SIZE);
        sav[1] = 0x99;
        CHECK(t, gb_sram_load(g, sav, 32768) == GB_OK);
        mmu_write(g, 0x0000, 0x0A);
        CHECK(t, mmu_read(g, 0xA001) == 0x99);
        free(sav);
    }
    free(rom);

    /* Sin batería no hay .sav */
    rom = banked_rom(4, 0x02, 0x01, 0x02);
    CHECK(t, rom != NULL);
    if (!rom)
        return;
    CHECK(t, load(g, rom, 4u * 0x4000) == GB_OK);
    CHECK(t, gb_sram_save_size(g) == 0);
    free(rom);
}

void unit_cart(struct ut *t)
{
    gb *g = gb_create();
    CHECK(t, g != NULL);
    if (!g)
        return;
    header_errors(t, g);
    header_info(t, g);
    mbc1_banks(t, g);
    mbc1_ram(t, g);
    gb_destroy(g);
}
