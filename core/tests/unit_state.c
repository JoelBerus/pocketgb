/* unit_state.c — save states (round-trip y rechazos), CRC-32, RTC y OOM (M3). */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

static const uint8_t counter_prog[] = {
    0x3C,             /* INC A */
    0xEA, 0x00, 0xC0, /* LD (C000),A */
    0x18, 0xFA        /* JR -6 */
};

static gb *load(uint8_t *rom, size_t size)
{
    gb *g = gb_create();
    if (g && gb_load_rom(g, rom, size, NULL) != GB_OK) {
        gb_destroy(g);
        return NULL;
    }
    return g;
}

static void put32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)(v >> (8 * i));
}

static void fix_crc(uint8_t *s, size_t n)
{
    put32(s + n - 4, crc32_update(0, s, n - 4));
}

static void state_tests(struct ut *t)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, counter_prog, sizeof counter_prog);
    gb *g = rom ? load(rom, 0x8000) : NULL;
    gb *h = rom ? load(rom, 0x8000) : NULL;
    CHECK(t, g && h);
    if (!g || !h) {
        gb_destroy(g); gb_destroy(h); free(rom);
        return;
    }
    for (int i = 0; i < 10; i++)
        gb_run_frame(g);
    size_t n = gb_state_size(g);
    CHECK(t, n > 44 + 4 && n == gb_state_size(h));
    uint8_t *s = malloc(n), *s2 = malloc(n);
    CHECK(t, s && s2);
    if (!s || !s2) {
        gb_destroy(g); gb_destroy(h); free(rom); free(s); free(s2);
        return;
    }
    CHECK(t, gb_state_save(g, s, n - 1) == GB_ERR_BUFFER_TOO_SMALL);
    CHECK(t, gb_state_save(g, s, n) == GB_OK);
    CHECK(t, memcmp(s, "PGBS", 4) == 0);

    /* Round-trip: cargar en otra instancia y avanzar lo mismo → mismo estado y framebuffer. */
    CHECK(t, gb_state_load(h, s, n) == GB_OK);
    CHECK(t, gb_cycle_count(h) == gb_cycle_count(g));
    for (int i = 0; i < 20; i++) {
        gb_run_frame(g);
        gb_run_frame(h);
    }
    CHECK(t, memcmp(gb_framebuffer(g), gb_framebuffer(h), GB_SCREEN_W * GB_SCREEN_H * 4) == 0);
    CHECK(t, gb_state_save(g, s, n) == GB_OK && gb_state_save(h, s2, n) == GB_OK);
    CHECK(t, memcmp(s, s2, n) == 0);
    CHECK(t, g->mem.wram[0] == h->mem.wram[0] && g->cpu.a == h->cpu.a);

    /* Rechazos: la instancia no cambia si la carga falla. */
    uint64_t before = gb_cycle_count(h);
    memcpy(s2, s, n);
    s2[0] = 'X';
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_MAGIC);
    memcpy(s2, s, n);
    put32(s2 + 4, 3);
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_VERSION);
    memcpy(s2, s, n);
    s2[100] ^= 0x01;                                  /* CRC alterado */
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_CORRUPT);
    memcpy(s2, s, n);
    s2[8] ^= 0xFF;                                    /* huella distinta, CRC válido */
    fix_crc(s2, n);
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_ROM_MISMATCH);
    memcpy(s2, s, n);
    put32(s2 + 44 + 4, 0x7FFFFFFF);                   /* longitud de sección > archivo */
    fix_crc(s2, n);
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_CORRUPT);
    memcpy(s2, s, n);
    put32(s2 + 44 + 4, 1);                            /* longitud de sección incoherente */
    fix_crc(s2, n);
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_CORRUPT);
    memcpy(s2, s, n);
    s2[44 + 8 + 1] = 0x0F;                            /* F con nibble bajo ≠ 0 */
    fix_crc(s2, n);
    CHECK(t, gb_state_load(h, s2, n) == GB_ERR_STATE_CORRUPT);
    CHECK(t, gb_state_load(h, s, n - 1) == GB_ERR_STATE_CORRUPT);   /* truncado */
    CHECK(t, gb_state_load(h, s, 10) == GB_ERR_STATE_CORRUPT);
    CHECK(t, gb_state_load(h, s, 3) == GB_ERR_STATE_MAGIC);
    CHECK(t, gb_cycle_count(h) == before);

    /* Regresión (fuzzer): tras un OAM DMA completo, index = 160 con active = 0 es válido;
     * con active = 1 sería una escritura fuera de OAM y se rechaza. */
    g->dma.active = false;
    g->dma.index = 160;
    CHECK(t, gb_state_save(g, s, n) == GB_OK && gb_state_load(h, s, n) == GB_OK);
    g->dma.active = true;
    CHECK(t, gb_state_save(g, s, n) == GB_OK && gb_state_load(h, s, n) == GB_ERR_STATE_CORRUPT);
    g->dma.active = false;
    g->dma.index = 0;

    /* Regresión (auditoría M3, H1): LY=143, dot=452 y modo 3 escritos a mano con CRC
     * válido. El modo se recalcula al cargar, así que no se renderiza la línea 144
     * (antes: escritura fuera del framebuffer). */
    h->ppu.ly = 143; h->ppu.dot = 452; h->ppu.mode = 3;
    CHECK(t, gb_state_save(h, s2, n) == GB_OK);
    CHECK(t, gb_state_load(h, s2, n) == GB_OK);
    CHECK(t, h->ppu.mode == 0 && h->ppu.next_event == 456);
    gb_run_frame(h);                                    /* con ASan: sin desborde */
    h->ppu.dot = 450;                                   /* dot no múltiplo de 4 */
    CHECK(t, gb_state_save(h, s2, n) == GB_OK && gb_state_load(g, s2, n) == GB_ERR_STATE_CORRUPT);
    h->ppu.dot = 0;
    h->ppu.mode3_end = 100;                             /* modo 3 imposible */
    CHECK(t, gb_state_save(h, s2, n) == GB_OK && gb_state_load(g, s2, n) == GB_ERR_STATE_CORRUPT);
    h->ppu.mode3_end = 252;

    /* Estado de otro ROM */
    uint8_t *rom2 = ut_make_rom(0x8000, 0x00, 0x00, 0x00, NULL, 0);
    gb *o = rom2 ? load(rom2, 0x8000) : NULL;
    CHECK(t, o != NULL);
    if (o) {
        CHECK(t, gb_state_load(o, s, n) == GB_ERR_STATE_ROM_MISMATCH);
        gb_destroy(o);
    }
    free(rom2);
    free(s);
    free(s2);
    gb_destroy(g);
    gb_destroy(h);
    free(rom);
}

static void rtc_tests(struct ut *t)
{
    /* MBC3+TIMER+RAM+BATTERY, 32 KiB de RAM */
    uint8_t *rom = ut_make_rom(0x8000, 0x10, 0x00, 0x03, NULL, 0);
    gb_options o;
    gb_options_default(&o);
    o.unix_time = 1000000;
    gb *g = gb_create();
    CHECK(t, rom && g && gb_load_rom(g, rom, 0x8000, &o) == GB_OK);
    if (!rom || !g || !g->rom_loaded) {
        free(rom); gb_destroy(g);
        return;
    }
    gb_rom_info info;
    CHECK(t, gb_rom_info_get(g, &info) == GB_OK && info.has_rtc && info.sram_bytes == 32768);
    CHECK(t, gb_sram_save_size(g) == 32768 + 48);

    struct gb_rtc *r = &g->cart.rtc;
    /* 1 día, 23:59:59 + 1 s → día 2, 00:00:00 */
    r->reg[RTC_S] = 59; r->reg[RTC_M] = 59; r->reg[RTC_H] = 23; r->reg[RTC_DL] = 1;
    rtc_add_seconds(r, 1);
    CHECK(t, r->reg[RTC_S] == 0 && r->reg[RTC_M] == 0 && r->reg[RTC_H] == 0 && r->reg[RTC_DL] == 2);
    /* Desborde del día 511 → 0 con acarreo */
    r->reg[RTC_DL] = 0xFF; r->reg[RTC_DH] = 0x01;
    rtc_add_seconds(r, 86400);
    CHECK(t, r->reg[RTC_DL] == 0 && r->reg[RTC_DH] == 0x80);
    /* Segundos fuera de rango: 63 → 0 sin acarreo */
    r->reg[RTC_S] = 63; r->reg[RTC_M] = 5;
    rtc_add_seconds(r, 1);
    CHECK(t, r->reg[RTC_S] == 0 && r->reg[RTC_M] == 5);

    /* Latch y lectura por el bus */
    r->reg[RTC_DH] = 0; r->reg[RTC_S] = 12;
    mmu_write(g, 0x0000, 0x0A);
    mmu_write(g, 0x4000, 0x08);
    mmu_write(g, 0x6000, 0x00);
    mmu_write(g, 0x6000, 0x01);
    CHECK(t, mmu_read(g, 0xA000) == 12);
    r->reg[RTC_S] = 30;
    CHECK(t, mmu_read(g, 0xA000) == 12);           /* sin nuevo latch no cambia */
    mmu_write(g, 0xA000, 0xFF);                    /* escribir S aplica la máscara */
    CHECK(t, r->reg[RTC_S] == 0x3F && r->sub == 0);

    /* Avance con la hora de pared; en halt no avanza */
    r->reg[RTC_S] = 0; r->reg[RTC_M] = 0; r->reg[RTC_H] = 0;
    gb_rtc_set_time(g, r->unix + 3661);
    CHECK(t, r->reg[RTC_H] == 1 && r->reg[RTC_M] == 1 && r->reg[RTC_S] == 1);
    r->reg[RTC_DH] = 0x40;
    gb_rtc_set_time(g, r->unix + 100);
    CHECK(t, r->reg[RTC_S] == 1);
    r->reg[RTC_DH] = 0x00;

    /* .sav con bloque RTC: round-trip y avance del tiempo transcurrido */
    uint8_t *sav = malloc(32768 + 48);
    CHECK(t, sav != NULL);
    if (sav) {
        g->cart.ram[5] = 0x77;
        CHECK(t, gb_sram_save(g, sav, 32768 + 48) == GB_OK);
        int64_t saved_at = r->unix;
        gb *h = gb_create();
        o.unix_time = saved_at + 60;               /* la consola "estuvo apagada" 60 s */
        CHECK(t, h && gb_load_rom(h, rom, 0x8000, &o) == GB_OK);
        if (h && h->rom_loaded) {
            CHECK(t, gb_sram_load(h, sav, 32768 + 48) == GB_OK);
            CHECK(t, h->cart.ram[5] == 0x77);
            CHECK(t, h->cart.rtc.reg[RTC_H] == 1 && h->cart.rtc.reg[RTC_M] == 2 &&
                     h->cart.rtc.reg[RTC_S] == 1);
            CHECK(t, gb_sram_load(h, sav, 32768) == GB_OK);        /* sin bloque RTC */
            CHECK(t, gb_sram_load(h, sav, 32768 + 44) == GB_OK);   /* bloque antiguo de 44 */
            CHECK(t, h->cart.rtc.reg[RTC_H] == 1);
            CHECK(t, gb_sram_load(h, sav, 32768 + 47) == GB_ERR_SRAM_SIZE);
        }
        gb_destroy(h);
        free(sav);
    }

    /* Regresión (auditoría M3, H2): hora guardada fuera de rango en el .sav → "sin hora",
     * sin desbordes al restar (UBSan) y sin tocar el reloj. */
    uint8_t *bad = calloc(1, 32768 + 48);
    if (bad) {
        bad[32768 + 47] = 0x80;                          /* u64 = INT64_MIN */
        int64_t now = r->unix;
        CHECK(t, gb_sram_load(g, bad, 32768 + 48) == GB_OK);
        CHECK(t, r->unix == now && r->reg[RTC_S] == 0);
        gb_rtc_set_time(g, INT64_MIN);
        gb_rtc_set_time(g, INT64_MAX);
        CHECK(t, r->unix == now);
        free(bad);
    }
    /* Escribir un registro del RTC cuenta como "el juego guardó" al deshabilitar. */
    gb_sram_clear_dirty(g);
    mmu_write(g, 0x0000, 0x0A);
    mmu_write(g, 0x4000, 0x0A);
    mmu_write(g, 0xA000, 5);
    mmu_write(g, 0x0000, 0x00);
    CHECK(t, gb_sram_dirty(g));

    /* El RTC también viaja en los save states */
    size_t n = gb_state_size(g);
    uint8_t *s = malloc(n);
    if (s) {
        r->reg[RTC_DL] = 0x42;
        CHECK(t, gb_state_save(g, s, n) == GB_OK);
        r->reg[RTC_DL] = 0x00;
        CHECK(t, gb_state_load(g, s, n) == GB_OK && r->reg[RTC_DL] == 0x42);
        int64_t keep = r->unix;
        r->unix = -1;                                    /* hora inválida en un estado */
        CHECK(t, gb_state_save(g, s, n) == GB_OK && gb_state_load(g, s, n) == GB_ERR_STATE_CORRUPT);
        r->unix = keep;
        free(s);
    }
    gb_destroy(g);
    free(rom);
}

static void oom_tests(struct ut *t)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x03, 0x00, 0x03, NULL, 0);   /* ROM + RAM: 2 reservas */
    gb *g = gb_create();
    CHECK(t, rom && g);
    if (!rom || !g) {
        free(rom); gb_destroy(g);
        return;
    }
    for (int fail = 1; fail <= 2; fail++) {
        g->dbg.fail_alloc_at = fail;
        CHECK(t, gb_load_rom(g, rom, 0x8000, NULL) == GB_ERR_OUT_OF_MEMORY);
        gb_rom_info info;
        CHECK(t, gb_rom_info_get(g, &info) == GB_ERR_NO_ROM);
        uint64_t c = gb_cycle_count(g);
        gb_run_frame(g);                         /* sin ROM no hace nada */
        CHECK(t, gb_cycle_count(g) == c && gb_state_size(g) == 0);
    }
    g->dbg.fail_alloc_at = 0;
    CHECK(t, gb_load_rom(g, rom, 0x8000, NULL) == GB_OK);   /* la instancia sigue usable */
    gb_run_frame(g);
    gb_destroy(g);
    free(rom);
}

void unit_state(struct ut *t)
{
    CHECK(t, crc32_update(0, (const uint8_t *)"123456789", 9) == 0xCBF43926u);
    state_tests(t);
    rtc_tests(t);
    oom_tests(t);
}
