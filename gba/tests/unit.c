/*
 * unit.c — tests unitarios del núcleo GBA (se amplían en cada hito G).
 */
#include "internal.h"
#include <stdio.h>
#include <stdlib.h>

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("  FALLO %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static void make_rom(uint8_t *rom, size_t len)
{
    memset(rom, 0, len);
    rom[0xB2] = 0x96;
    memcpy(rom + 0xA0, "POCKETGBTEST", 12);
    memcpy(rom + 0xAC, "PGBE", 4);
    memcpy(rom + 0xB0, "01", 2);
    uint8_t chk = 0;
    for (int i = 0xA0; i <= 0xBC; i++) chk = (uint8_t)(chk - rom[i]);
    rom[0xBD] = (uint8_t)(chk - 0x19);
}

static void test_load_rom(void)
{
    gba *g = gba_create();
    CHECK(g != NULL);
    uint8_t *rom = malloc(0x200);
    make_rom(rom, 0x200);
    CHECK(gba_load_rom(g, rom, 0xBF, NULL) == GBA_ERR_ROM_TOO_SMALL);
    CHECK(gba_load_rom(g, rom, (size_t)GBA_ROM_MAX_BYTES + 1, NULL) == GBA_ERR_ROM_TOO_LARGE);
    rom[0xB2] = 0;
    CHECK(gba_load_rom(g, rom, 0x200, NULL) == GBA_ERR_BAD_HEADER);
    rom[0xB2] = 0x96;
    CHECK(gba_load_rom(g, rom, 0x200, NULL) == GBA_OK);
    gba_rom_info info;
    CHECK(gba_rom_info_get(g, &info) == GBA_OK);
    CHECK(strcmp(info.title, "POCKETGBTEST") == 0);
    CHECK(strcmp(info.game_code, "PGBE") == 0);
    CHECK(info.header_checksum_ok);
    CHECK(info.rom_bytes == 0x200);
    CHECK(g->rom_mask == 0x1FF);
    /* Lecturas fuera del ROM: patrón del bus abierto, nunca fuera del búfer. */
    CHECK(gba_bus_read16(g, 0x08000400u) == 0x0200u);
    CHECK(gba_bus_read16(g, 0x09FFFFFEu) == (uint16_t)(0x09FFFFFEu >> 1));
    free(rom);
    gba_destroy(g);
}

static void test_banks(void)
{
    gba *g = gba_create();
    gba_arm *c = &g->cpu;
    memset(c, 0, sizeof *c);
    c->cpsr = ARM_MODE_SYS;
    c->bank = ARM_BANK_USR;
    for (int i = 0; i < 16; i++) c->r[i] = (uint32_t)i;
    gba_arm_set_cpsr(g, ARM_MODE_FIQ);
    CHECK(c->r[8] == 0 && c->r[13] == 0 && c->r[7] == 7);
    c->r[8] = 0x88; c->r[13] = 0xDD;
    gba_arm_set_cpsr(g, ARM_MODE_IRQ);
    CHECK(c->r[8] == 8 && c->r[13] == 0);
    gba_arm_set_cpsr(g, ARM_MODE_FIQ);
    CHECK(c->r[8] == 0x88 && c->r[13] == 0xDD);
    gba_arm_set_cpsr(g, ARM_MODE_USR);
    CHECK(c->r[13] == 13 && c->r[14] == 14 && c->r[12] == 12);
    gba_destroy(g);
}

int gba_unit_run(void)
{
    test_load_rom();
    test_banks();
    printf("%s unit: %d fallos\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
