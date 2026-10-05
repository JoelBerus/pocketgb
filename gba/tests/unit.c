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
    printf("%s unit: %d fallos\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
