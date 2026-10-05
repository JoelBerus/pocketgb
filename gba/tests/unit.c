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

static gba *boot_rom(const uint32_t *words, size_t n, uint32_t at)
{
    static const size_t len = 0x400;
    uint8_t *rom = malloc(len);
    make_rom(rom, len);
    /* 0x00: b 0x080000C0 */
    rom[0] = 0x2E; rom[1] = 0x00; rom[2] = 0x00; rom[3] = 0xEA;
    for (size_t i = 0; i < n; i++) {
        uint32_t w = words[i];
        size_t o = at + i * 4;
        rom[o] = (uint8_t)w; rom[o + 1] = (uint8_t)(w >> 8); rom[o + 2] = (uint8_t)(w >> 16); rom[o + 3] = (uint8_t)(w >> 24);
    }
    gba *g = gba_create();
    CHECK(gba_load_rom(g, rom, len, NULL) == GBA_OK);
    free(rom);
    return g;
}

static void test_timers(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    gba_io_write16(g, 0x100, 0xFFF0);          /* TM0 recarga */
    gba_io_write16(g, 0x102, 0x00C0);          /* activo, IRQ, prescaler 1 */
    gba_io_write16(g, 0x104, 0xFFFE);          /* TM1 recarga */
    gba_io_write16(g, 0x106, 0x00C4);          /* activo, IRQ, cascada */
    CHECK(gba_io_read16(g, 0x100) == 0xFFF0);
    gba_tick(g, 0x10);                         /* desborda TM0 una vez */
    CHECK(g->if_ & GBA_IRQ_TIMER0);
    CHECK(gba_io_read16(g, 0x104) == 0xFFFF);
    gba_tick(g, 0x10);                         /* segundo desborde: TM1 desborda */
    CHECK(g->if_ & (GBA_IRQ_TIMER0 << 1));
    CHECK(gba_io_read16(g, 0x104) == 0xFFFE);
    /* Prescaler 64 */
    gba_io_write16(g, 0x108, 0);
    gba_io_write16(g, 0x10A, 0x0081);
    gba_tick(g, 64 * 10 + 5);
    CHECK(gba_io_read16(g, 0x108) == 10);
    gba_destroy(g);
}

static void test_dma(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    for (int i = 0; i < 16; i++) g->ewram[i] = (uint8_t)(i + 1);
    gba_bus_write32(g, 0x040000D4, 0x02000000);   /* DMA3 SAD */
    gba_bus_write32(g, 0x040000D8, 0x03000100);   /* DMA3 DAD */
    gba_bus_write16(g, 0x040000DC, 4);            /* 4 unidades */
    uint64_t before = g->cycles;
    gba_bus_write16(g, 0x040000DE, 0xC400);       /* activa, 32 bits, IRQ, inmediata */
    CHECK(memcmp(&g->iwram[0x100], g->ewram, 16) == 0);
    CHECK(g->if_ & (GBA_IRQ_DMA0 << 3));
    CHECK(!(g->dma[3].cnt_h & 0x8000u));
    CHECK(g->cycles - before >= 4 * 6);           /* EWRAM: 6 ciclos por palabra */
    /* HBlank con repetición: una media palabra por línea visible. */
    gba_bus_write32(g, 0x040000B0, 0x02000000);
    gba_bus_write32(g, 0x040000B4, 0x03000200);
    gba_bus_write16(g, 0x040000B8, 1);
    gba_bus_write16(g, 0x040000BA, 0xA000 | 0x0200 | 0x0040);   /* activa, HBlank, repetir, destino fijo */
    CHECK(g->iwram[0x200] == 0);
    gba_tick(g, g->hblank ? 1232 - g->line_cycles + 1006 : 1006 - g->line_cycles);   /* hasta el próximo HBlank */
    CHECK(g->iwram[0x200] == 1 && g->iwram[0x201] == 2);
    CHECK(g->dma[0].cnt_h & 0x8000u);
    gba_destroy(g);
}

static void test_waitstates(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    uint64_t c0 = g->cycles;
    gba_bus_read16(g, 0x08000100);
    CHECK(g->cycles - c0 == 5);                  /* WAITCNT=0: N de WS0 = 4 esperas */
    c0 = g->cycles;
    gba_bus_read32(g, 0x08000100);
    CHECK(g->cycles - c0 == 5 + 3);              /* N + S */
    gba_io_write16(g, 0x204, 0x4317);            /* el valor que usan casi todos los juegos */
    c0 = g->cycles;
    gba_bus_read16(g, 0x08000100);
    CHECK(g->cycles - c0 == 4);                  /* WS0 N = 3 esperas */
    c0 = g->cycles;
    gba_bus_read32(g, 0x03000000);
    CHECK(g->cycles - c0 == 1);
    gba_destroy(g);
}

static void test_hle_math(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    uint32_t *r = g->cpu.r;
    r[0] = (uint32_t)-7; r[1] = 2; gba_hle_swi(g, 0x06);
    CHECK((int32_t)r[0] == -3 && (int32_t)r[1] == -1 && r[3] == 3);
    r[0] = 1000000; gba_hle_swi(g, 0x08);
    CHECK(r[0] == 1000);
    r[0] = 0xFFFFFFFFu; gba_hle_swi(g, 0x08);
    CHECK(r[0] == 0xFFFF);
    r[0] = 0x4000; r[1] = 0; gba_hle_swi(g, 0x0A); CHECK(r[0] == 0);
    r[0] = 0; r[1] = 0x4000; gba_hle_swi(g, 0x0A); CHECK(r[0] == 0x4000);
    r[0] = (uint32_t)-0x4000; r[1] = 0; gba_hle_swi(g, 0x0A); CHECK(r[0] == 0x8000);
    r[0] = 0x4000; r[1] = 0x4000; gba_hle_swi(g, 0x0A);
    CHECK(r[0] >= 0x1FF0 && r[0] <= 0x2010);
    r[0] = (uint32_t)-0x4000; r[1] = (uint32_t)-0x4000; gba_hle_swi(g, 0x0A);
    CHECK(r[0] >= 0x9FF0 && r[0] <= 0xA010);
    r[0] = 1; r[1] = 0; gba_hle_swi(g, 0x06);      /* división por cero: sin cuelgue */
    r[0] = 0x80000000u; r[1] = 1; gba_hle_swi(g, 0x06);
    CHECK(r[0] == 0x80000000u && r[3] == 0x80000000u);
    /* MidiKey2Freq: tecla 180 da la frecuencia base; 12 semitonos menos, la mitad. */
    g->ewram[4] = 0x00; g->ewram[5] = 0x00; g->ewram[6] = 0x01; g->ewram[7] = 0x00;   /* 0x10000 */
    r[0] = 0x02000000; r[1] = 180; r[2] = 0; gba_hle_swi(g, 0x1F); CHECK(r[0] == 0x10000);
    r[0] = 0x02000000; r[1] = 168; r[2] = 0; gba_hle_swi(g, 0x1F); CHECK(r[0] == 0x8000);
    g->ewram[4] = 0xFF; g->ewram[5] = 0xFF; g->ewram[6] = 0xFF; g->ewram[7] = 0xFF;
    r[0] = 0x02000000; r[1] = 255; r[2] = 255; gba_hle_swi(g, 0x1F); CHECK(r[0] == 0xFFFFFFFFu);
    /* Tecla > 180 con base pequeña: b=7, k=229, f=255 → 125,7 según GBATEK. */
    g->ewram[4] = 7; g->ewram[5] = 0; g->ewram[6] = 0; g->ewram[7] = 0;
    r[0] = 0x02000000; r[1] = 229; r[2] = 255; gba_hle_swi(g, 0x1F); CHECK(r[0] == 125 || r[0] == 126);
    gba_destroy(g);
}

static void test_hle_decompress(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    /* LZ77 de "ABCABCABC": 3 literales y una referencia (longitud 6, distancia 3). */
    static const uint8_t lz[] = {0x10, 9, 0, 0, 0x10, 'A', 'B', 'C', 0x30, 0x02};
    memcpy(g->ewram, lz, sizeof lz);
    uint32_t *r = g->cpu.r;
    r[0] = 0x02000000; r[1] = 0x03000000; gba_hle_swi(g, 0x11);
    CHECK(memcmp(g->iwram, "ABCABCABC", 9) == 0);
    r[0] = 0x02000000; r[1] = 0x06000000; gba_hle_swi(g, 0x12);
    CHECK(memcmp(g->vram, "ABCABCAB", 8) == 0);
    /* RL: 4 copias de 'Z' y 2 bytes sin comprimir. */
    static const uint8_t rl[] = {0x30, 6, 0, 0, 0x81, 'Z', 0x01, 'x', 'y'};
    memcpy(g->ewram + 0x100, rl, sizeof rl);
    r[0] = 0x02000100; r[1] = 0x03000040; gba_hle_swi(g, 0x14);
    CHECK(memcmp(&g->iwram[0x40], "ZZZZxy", 6) == 0);
    /* CpuSet de 16 bits con relleno y CpuFastSet de copia. */
    g->ewram[0x200] = 0x34; g->ewram[0x201] = 0x12;
    r[0] = 0x02000200; r[1] = 0x03000080; r[2] = (1u << 24) | 3; gba_hle_swi(g, 0x0B);
    CHECK(g->iwram[0x80] == 0x34 && g->iwram[0x85] == 0x12 && g->iwram[0x86] == 0);
    r[0] = 0x02000000; r[1] = 0x03000400; r[2] = 1; gba_hle_swi(g, 0x0C);   /* se redondea a 8 palabras */
    CHECK(memcmp(&g->iwram[0x400], g->ewram, 32) == 0);
    /* Huffman válido de 8 bits: hojas 'A' (0) y 'B' (1); "ABBA" = 0110... */
    static const uint8_t hv[] = {0x28, 4, 0, 0, 0x01, 0xC0, 'A', 'B', 0x00, 0x00, 0x00, 0x60};
    memcpy(g->ewram + 0x380, hv, sizeof hv);
    r[0] = 0x02000380; r[1] = 0x03000700; gba_hle_swi(g, 0x13);
    CHECK(memcmp(&g->iwram[0x700], "ABBA", 4) == 0);
    /* LZ77 a VRAM con distancia 1 (repite el byte pendiente de la media palabra). */
    static const uint8_t lz1[] = {0x10, 6, 0, 0, 0x40, 'Q', 0x20, 0x00};
    memcpy(g->ewram + 0x3C0, lz1, sizeof lz1);
    r[0] = 0x020003C0; r[1] = 0x06000100; gba_hle_swi(g, 0x12);
    CHECK(memcmp(&g->vram[0x100], "QQQQQQ", 6) == 0);
    /* Huffman con un árbol que nunca llega a una hoja: termina y no escribe. */
    g->iwram[0x600] = 0x5A;
    static const uint8_t hf[] = {0x28, 0x00, 0x10, 0x00, 0x01, 0x00, 0x00, 0x00};
    memcpy(g->ewram + 0x300, hf, sizeof hf);
    r[0] = 0x02000300; r[1] = 0x03000600; gba_hle_swi(g, 0x13);
    CHECK(g->iwram[0x600] == 0x5A);
    /* La BIOS no copia desde su propia zona. */
    g->iwram[0x500] = 0xAA;
    r[0] = 0x00000000; r[1] = 0x03000500; r[2] = 1; gba_hle_swi(g, 0x0B);
    CHECK(g->iwram[0x500] == 0xAA);
    gba_destroy(g);
}

/* IntrWait(1, VBlank) desde un programa ARM: la CPU se para, el manejador del
 * juego confirma la IRQ y marca 0x03007FF8, y la SWI vuelve. */
static void test_intr_wait(void)
{
    static const uint32_t prog[] = {
        0xE3A00001u, /* C0: mov r0, #1 */
        0xE3A01001u, /* C4: mov r1, #1 */
        0xEF040000u, /* C8: swi 0x04 (IntrWait) */
        0xE2855001u, /* CC: add r5, r5, #1 */
        0xEAFFFFFEu, /* D0: b . */
    };
    static const uint32_t isr[] = {
        0xE3A00301u, 0xE2800C02u, 0xE3A01001u, 0xE1C010B2u,   /* IF = 1 */
        0xE3A02403u, 0xE2822C7Fu, 0xE28220F8u, 0xE1C210B0u,   /* [0x03007FF8] = 1 */
        0xE12FFF1Eu,                                          /* bx lr */
    };
    gba *g = boot_rom(prog, 5, 0xC0);
    uint8_t *rom = g->rom;
    for (size_t i = 0; i < 9; i++) {
        size_t o = 0x100 + i * 4;
        rom[o] = (uint8_t)isr[i]; rom[o + 1] = (uint8_t)(isr[i] >> 8);
        rom[o + 2] = (uint8_t)(isr[i] >> 16); rom[o + 3] = (uint8_t)(isr[i] >> 24);
    }
    g->iwram[0x7FFC] = 0x00; g->iwram[0x7FFD] = 0x01; g->iwram[0x7FFE] = 0x00; g->iwram[0x7FFF] = 0x08;
    gba_io_write16(g, 0x200, 1);       /* IE = VBlank */
    gba_io_write16(g, 0x004, 0x0008);  /* DISPSTAT: IRQ de VBlank */
    gba_run_cycles(g, 2000);
    CHECK(g->cpu.halted);
    CHECK(g->cpu.r[5] == 0);
    gba_run_frame(g);
    gba_run_cycles(g, 2000);
    CHECK(!g->cpu.halted);
    CHECK(g->cpu.r[5] == 1);
    CHECK(g->iwram[0x7FF8] == 0);      /* IntrWait limpia el bit al volver */
    CHECK(g->vcount >= 160);
    CHECK((g->cpu.cpsr & 0x1Fu) == ARM_MODE_SYS);
    gba_destroy(g);
}

/* Igual que test_intr_wait pero con VBlankIntrWait (SWI 05h): debe volver. */
static void test_vblank_intr_wait(void)
{
    static const uint32_t prog[] = {
        0xEF050000u, /* C0: swi 0x05 */
        0xE2855001u, /* C4: add r5, r5, #1 */
        0xEAFFFFFCu, /* C8: b C0 */
    };
    static const uint32_t isr[] = {
        0xE3A00301u, 0xE2800C02u, 0xE3A01001u, 0xE1C010B2u,
        0xE3A02403u, 0xE2822C7Fu, 0xE28220F8u, 0xE1C210B0u, 0xE12FFF1Eu,
    };
    gba *g = boot_rom(prog, 3, 0xC0);
    for (size_t i = 0; i < 9; i++) {
        size_t o = 0x100 + i * 4;
        g->rom[o] = (uint8_t)isr[i]; g->rom[o + 1] = (uint8_t)(isr[i] >> 8);
        g->rom[o + 2] = (uint8_t)(isr[i] >> 16); g->rom[o + 3] = (uint8_t)(isr[i] >> 24);
    }
    g->iwram[0x7FFC] = 0x00; g->iwram[0x7FFD] = 0x01; g->iwram[0x7FFE] = 0x00; g->iwram[0x7FFF] = 0x08;
    gba_io_write16(g, 0x200, 1);
    gba_io_write16(g, 0x004, 0x0008);
    for (int f = 0; f < 5; f++) gba_run_frame(g);
    CHECK(g->cpu.r[5] >= 4 && g->cpu.r[5] <= 6);   /* una vuelta por frame */
    gba_destroy(g);
}

/* Una DMA3 que escribe en su propio CNT_H {0, 0x8040}: se desactiva y se
 * reactiva a sí misma. Antes era recursión infinita (auditoría G2 H1). */
static void test_dma_self_retrigger(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    g->ewram[0] = 0x00; g->ewram[1] = 0x00; g->ewram[2] = 0x40; g->ewram[3] = 0x80;
    gba_bus_write32(g, 0x040000D4, 0x02000000);
    gba_bus_write32(g, 0x040000D8, 0x040000DE);
    gba_bus_write16(g, 0x040000DC, 2);
    gba_bus_write16(g, 0x040000DE, 0x8040);          /* inmediata, destino fijo, 16 bits */
    uint64_t c0 = g->cycles;
    for (int i = 0; i < 1000; i++) gba_tick(g, 4);  /* acotado: una ejecución por paso */
    gba_run_frame(g);
    CHECK(g->dma_pending == 0);
    CHECK(!(g->dma[3].cnt_h & 0x8000u));
    CHECK(g->cycles - c0 < 3u * GBA_CYCLES_PER_FRAME);
    gba_destroy(g);
}

/* ROM mínima con una cadena de la biblioteca de guardado (4 bytes alineada). */
static gba *rom_with_tag(const char *tag, const gba_options *o)
{
    size_t len = 0x400;
    uint8_t *rom = malloc(len);
    make_rom(rom, len);
    if (tag) memcpy(rom + 0x200, tag, strlen(tag));
    gba *g = gba_create();
    CHECK(gba_load_rom(g, rom, len, o) == GBA_OK);
    free(rom);
    return g;
}

static void test_save_api(void)
{
    gba_rom_info info;
    struct { const char *tag; gba_save_type t; uint32_t bytes; } cases[] = {
        {NULL, GBA_SAVE_NONE, 0}, {"SRAM_V113", GBA_SAVE_SRAM, 32768}, {"SRAM_F_V100", GBA_SAVE_SRAM, 32768},
        {"FLASH_V126", GBA_SAVE_FLASH64, 65536}, {"FLASH512_V130", GBA_SAVE_FLASH64, 65536},
        {"FLASH1M_V103", GBA_SAVE_FLASH128, 131072}, {"EEPROM_V124", GBA_SAVE_EEPROM512, 512}};
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        gba *g = rom_with_tag(cases[i].tag, NULL);
        CHECK(gba_rom_info_get(g, &info) == GBA_OK);
        CHECK(info.save_type == cases[i].t);
        CHECK(info.save_bytes == cases[i].bytes);
        CHECK(gba_save_size(g) == cases[i].bytes);
        gba_destroy(g);
    }
    /* Tamaño incorrecto: se rechaza y la partida en memoria no cambia. */
    gba *g = rom_with_tag("SRAM_V113", NULL);
    uint8_t *buf = malloc(131072);
    memset(buf, 0x5A, 32768);
    CHECK(gba_save_load(g, buf, 32768) == GBA_OK);
    CHECK(!gba_save_dirty(g));
    memset(buf, 0x11, 65536);
    CHECK(gba_save_load(g, buf, 65536) == GBA_ERR_SAVE_SIZE);
    CHECK(gba_save_load(g, buf, 32767) == GBA_ERR_SAVE_SIZE);
    CHECK(g->save[0] == 0x5A && g->save[32767] == 0x5A);
    gba_bus_write8(g, 0x0E000010, 0x77);
    CHECK(gba_save_dirty(g));
    CHECK(gba_save_write(g, buf, 100) == GBA_ERR_BUFFER_TOO_SMALL);
    CHECK(gba_save_write(g, buf, 131072) == GBA_OK && buf[0x10] == 0x77);
    gba_save_clear_dirty(g);
    CHECK(!gba_save_dirty(g));
    /* Espejo de 32 KiB y bus de 8 bits. */
    CHECK(gba_bus_read8(g, 0x0E008010) == 0x77 && gba_bus_read8(g, 0x0F000010) == 0x77);
    CHECK(gba_bus_read16(g, 0x0E000010) == 0x7777);
    gba_destroy(g);
    /* EEPROM sin ajuste: el .sav fija el tamaño (512 u 8192). */
    g = rom_with_tag("EEPROM_V124", NULL);
    memset(buf, 0x33, 8192);
    CHECK(gba_save_load(g, buf, 8192) == GBA_OK);
    CHECK(gba_save_size(g) == 8192 && g->eeprom.addr_bits == 14);
    CHECK(gba_save_load(g, buf, 1000) == GBA_ERR_SAVE_SIZE);
    /* Tamaño confirmado: una recarga de otro tamaño se rechaza (auditoría G4, A1). */
    CHECK(gba_save_load(g, buf, 512) == GBA_ERR_SAVE_SIZE);
    CHECK(gba_save_size(g) == 8192);
    gba_destroy(g);
    /* Sin .sav: 512 hasta la primera DMA, que confirma 8 KiB; después no cambia. */
    g = rom_with_tag("EEPROM_V124", NULL);
    CHECK(gba_save_size(g) == 512);
    gba_eeprom_dma(g, 81);
    CHECK(gba_save_size(g) == 8192 && g->eeprom.addr_bits == 14);
    gba_eeprom_dma(g, 9);
    CHECK(gba_save_size(g) == 8192);
    CHECK(gba_save_load(g, buf, 512) == GBA_ERR_SAVE_SIZE);
    gba_destroy(g);
    /* Con ajuste por juego el tamaño es fijo. */
    gba_options o;
    gba_options_default(&o);
    o.save_type = GBA_SAVE_EEPROM512;
    g = rom_with_tag("EEPROM_V124", &o);
    CHECK(gba_save_load(g, buf, 8192) == GBA_ERR_SAVE_SIZE);
    CHECK(gba_save_load(g, buf, 512) == GBA_OK);
    gba_destroy(g);
    /* Flash: modo ID (Panasonic 64 KiB / Sanyo 128 KiB) y escritura fuera de comando ignorada. */
    g = rom_with_tag("FLASH1M_V103", NULL);
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0x90);
    CHECK(gba_bus_read8(g, 0x0E000000) == 0x62 && gba_bus_read8(g, 0x0E000001) == 0x13);
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0xF0);
    gba_bus_write8(g, 0x0E000123, 0x00);
    CHECK(gba_bus_read8(g, 0x0E000123) == 0xFF && !gba_save_dirty(g));
    /* Programar solo baja bits a 0; reescribir lo mismo no marca la partida. */
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0xA0);
    gba_bus_write8(g, 0x0E000123, 0x0F);
    CHECK(gba_bus_read8(g, 0x0E000123) == 0x0F && gba_save_dirty(g));
    gba_save_clear_dirty(g);
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0xA0);
    gba_bus_write8(g, 0x0E000123, 0xF5);
    CHECK(gba_bus_read8(g, 0x0E000123) == 0x05 && gba_save_dirty(g));
    gba_save_clear_dirty(g);
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0xA0);
    gba_bus_write8(g, 0x0E000123, 0x05);
    CHECK(!gba_save_dirty(g));
    /* Borrado armado y secuencia rota: el 0x10 posterior no borra. */
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0x80);
    gba_bus_write8(g, 0x0E001234, 0x00);
    gba_bus_write8(g, 0x0E005555, 0xAA); gba_bus_write8(g, 0x0E002AAA, 0x55); gba_bus_write8(g, 0x0E005555, 0x10);
    CHECK(gba_bus_read8(g, 0x0E000123) == 0x05);
    gba_destroy(g);
    /* RTC: guardar y cargar el desplazamiento y el estado. */
    o.save_type = GBA_SAVE_AUTO;
    o.rtc = GBA_RTC_ON;
    g = rom_with_tag(NULL, &o);
    CHECK(g->has_rtc);
    g->rtc.offset = -123456789;
    g->rtc.status = 0x40;
    uint8_t r16[GBA_RTC_BYTES];
    CHECK(gba_rtc_save(g, r16, sizeof r16) == GBA_OK);
    g->rtc.offset = 0; g->rtc.status = 0;
    CHECK(gba_rtc_load(g, r16, sizeof r16) == GBA_OK);
    CHECK(g->rtc.offset == -123456789 && g->rtc.status == 0x40);
    CHECK(gba_rtc_load(g, r16, 15) == GBA_ERR_SAVE_SIZE);
    /* Desplazamiento absurdo (dañado u hostil): se rechaza (auditoría G4, M2). */
    memset(r16, 0xFF, 8); r16[7] = 0x7F;
    CHECK(gba_rtc_load(g, r16, sizeof r16) == GBA_ERR_SAVE_SIZE);
    CHECK(g->rtc.offset == -123456789);
    gba_destroy(g);
    /* SRAM: reescribir el mismo byte no marca la partida. */
    g = rom_with_tag("SRAM_V113", NULL);
    gba_bus_write8(g, 0x0E000040, 0xFF);
    CHECK(!gba_save_dirty(g));
    gba_bus_write8(g, 0x0E000040, 0x12);
    CHECK(gba_save_dirty(g));
    gba_destroy(g);
    free(buf);
}

static void test_apu(void)
{
    uint32_t nop[] = {0xEAFFFFFEu};
    gba *g = boot_rom(nop, 1, 0xC0);
    /* Apagada: los registros PSG no se escriben. */
    gba_bus_write16(g, 0x04000062, 0xF080);
    CHECK(gba_bus_read16(g, 0x04000062) == 0);
    gba_bus_write16(g, 0x04000084, 0x80);
    gba_bus_write16(g, 0x04000062, 0xF080);
    CHECK(gba_bus_read16(g, 0x04000062) == 0xF080);
    CHECK((gba_bus_read16(g, 0x04000084) & 0x80) != 0);
    /* Bancos de onda: con el banco 0 sonando, se escribe y se lee el 1. */
    gba_bus_write16(g, 0x04000070, 0x0000);
    gba_bus_write16(g, 0x04000090, 0x1234);
    CHECK(g->apu.wave[1][0] == 0x34 && g->apu.wave[1][1] == 0x12 && g->apu.wave[0][0] == 0);
    gba_bus_write16(g, 0x04000070, 0x0040);
    CHECK(gba_bus_read16(g, 0x04000090) == 0);
    /* SOUNDBIAS: valor por defecto y máscara. */
    CHECK(gba_bus_read16(g, 0x04000088) == 0x200);
    gba_bus_write16(g, 0x04000088, 0xFFFF);
    CHECK(gba_bus_read16(g, 0x04000088) == 0xC3FE);
    /* FIFO A por DMA1 en modo especial, timer 0 a 32768 Hz. */
    for (int i = 0; i < 256; i++) g->ewram[i] = (uint8_t)i;
    gba_bus_write16(g, 0x04000082, 0x0B06);
    gba_bus_write32(g, 0x040000BC, 0x02000000);
    gba_bus_write32(g, 0x040000C0, 0x040000A0);
    gba_bus_write16(g, 0x040000C6, 0x8000 | 0x3000 | 0x0200 | 0x0400);
    CHECK(g->apu.fifo_len[0] == 0);
    gba_bus_write16(g, 0x04000100, (uint16_t)(65536 - 512));
    gba_bus_write16(g, 0x04000102, 0x80);
    gba_tick(g, 512);                          /* primer desborde: FIFO vacía → DMA de 16 bytes */
    CHECK(g->apu.fifo_len[0] == 16);
    CHECK(g->dma[1].src == 0x02000010);
    gba_tick(g, 512);                          /* saca el byte 0 y vuelve a pedir: 31 */
    CHECK(g->apu.fifo_sample[0] == 0);
    CHECK(g->apu.fifo_len[0] == 31);
    gba_tick(g, 512 * 4);
    CHECK(g->apu.fifo_sample[0] == 4);
    CHECK(g->apu.fifo_len[0] == 27);
    /* Vaciar la FIFO con el bit de reinicio. */
    gba_bus_write16(g, 0x04000082, 0x0B06);
    CHECK(g->apu.fifo_len[0] == 0);
    /* Ritmo de salida: ~804 frames estéreo por frame de vídeo a 48 kHz. */
    int16_t pcm[4096];
    gba_run_frame(g);                          /* alinear con el VBlank */
    while (gba_audio_read(g, pcm, 2048)) {}
    gba_run_frame(g);
    gba_run_frame(g);
    size_t n = gba_audio_read(g, pcm, 2048);
    if (n < 1600 || n > 1610) printf("  frames de audio en 2 frames: %zu\n", n);
    CHECK(n >= 1600 && n <= 1610);
    gba_destroy(g);
}

static void test_states(void)
{
    gba *g = rom_with_tag("FLASH1M_V103", NULL);
    for (int f = 0; f < 3; f++) gba_run_frame(g);
    size_t sz = gba_state_size(g);
    CHECK(sz > 600 * 1024);
    uint8_t *st = malloc(sz), *st2 = malloc(sz);
    CHECK(gba_state_save(g, st, sz - 1) == GBA_ERR_BUFFER_TOO_SMALL);
    CHECK(gba_state_save(g, st, sz) == GBA_OK);
    /* Ida y vuelta exacta. */
    CHECK(gba_state_load(g, st, sz) == GBA_OK);
    CHECK(gba_state_save(g, st2, sz) == GBA_OK);
    CHECK(memcmp(st, st2, sz) == 0);
    /* Errores: magia, versión, otro ROM, longitud, CRC y un campo fuera de rango. */
    uint8_t keep = g->iwram[0x100];
    g->iwram[0x100] = 0xAB;
    memcpy(st2, st, sz); st2[0] = 'X';
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_MAGIC);
    memcpy(st2, st, sz); st2[4] = 9;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_VERSION);
    memcpy(st2, st, sz); st2[8] ^= 1;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_ROM_MISMATCH);
    CHECK(gba_state_load(g, st, sz - 1) == GBA_ERR_STATE_CORRUPT);
    memcpy(st2, st, sz); st2[100] ^= 0xFF;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    /* vcount = 300 con un CRC correcto: rango inválido, no se aplica nada. */
    memcpy(st2, st, sz);
    size_t vc_off = 44 + 16 * 4 + 2 * 4 + 10 * 4 + 18 * 4 + 2 * 4 + 1 + 3 +
                    sizeof g->ewram + sizeof g->iwram + sizeof g->io + sizeof g->pal + sizeof g->vram + sizeof g->oam + 4 + 8 + 4;
    st2[vc_off] = 0x2C; st2[vc_off + 1] = 0x01;
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < sz - 4; i++) {
        crc ^= st2[i];
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    crc = ~crc;
    st2[sz - 4] = (uint8_t)crc; st2[sz - 3] = (uint8_t)(crc >> 8); st2[sz - 2] = (uint8_t)(crc >> 16); st2[sz - 1] = (uint8_t)(crc >> 24);
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    CHECK(g->iwram[0x100] == 0xAB);              /* la instancia no cambió */
    st2[vc_off] = 0x10; st2[vc_off + 1] = 0x00;  /* el mismo archivo con vcount válido sí carga */
    crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < sz - 4; i++) {
        crc ^= st2[i];
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    crc = ~crc;
    st2[sz - 4] = (uint8_t)crc; st2[sz - 3] = (uint8_t)(crc >> 8); st2[sz - 2] = (uint8_t)(crc >> 16); st2[sz - 1] = (uint8_t)(crc >> 24);
    CHECK(gba_state_load(g, st2, sz) == GBA_OK);
    CHECK(g->vcount == 0x10 && g->iwram[0x100] == keep);
    /* Auditoría G6: campos con CRC correcto pero incoherentes se rechazan. */
    CHECK(gba_state_save(g, st, sz) == GBA_OK);
    uint8_t pos = g->apu.ch[0].pos;
    g->apu.ch[0].pos = 9;                        /* duty de 8 pasos: desplazamiento negativo */
    CHECK(gba_state_save(g, st2, sz) == GBA_OK);
    g->apu.ch[0].pos = pos;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    int64_t off = g->rtc.offset;
    g->rtc.offset = INT64_MAX;                   /* rtc_now() desbordaría */
    CHECK(gba_state_save(g, st2, sz) == GBA_OK);
    g->rtc.offset = off;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    int64_t base = g->rtc_base;
    g->rtc_base = INT64_MIN;
    CHECK(gba_state_save(g, st2, sz) == GBA_OK);
    g->rtc_base = base;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    bool rtc = g->has_rtc;
    g->has_rtc = !rtc;                           /* otra configuración de RTC */
    CHECK(gba_state_save(g, st2, sz) == GBA_OK);
    g->has_rtc = rtc;
    CHECK(gba_state_load(g, st2, sz) == GBA_ERR_STATE_CORRUPT);
    CHECK(gba_state_load(g, st, sz) == GBA_OK);
    free(st);
    free(st2);
    gba_destroy(g);
}

int gba_unit_run(void)
{
    test_load_rom();
    test_banks();
    test_timers();
    test_dma();
    test_waitstates();
    test_hle_math();
    test_hle_decompress();
    test_intr_wait();
    test_vblank_intr_wait();
    test_dma_self_retrigger();
    test_save_api();
    test_apu();
    test_states();
    printf("%s unit: %d fallos\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
