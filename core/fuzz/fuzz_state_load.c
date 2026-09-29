/*
 * fuzz_state_load.c — libFuzzer: gb_state_load con bytes arbitrarios sobre un
 * ROM sintético: MBC3+RTC+RAM, MBC1+RAM, MBC5+RAM o ROM-only según el primer
 * byte de la entrada, para cubrir todas las variantes de la sección CART.
 *
 * 1) Los bytes tal cual (magic, versión, CRC, huella…).
 * 2) Los mismos bytes con la cabecera y el CRC corregidos, para llegar al
 *    análisis de secciones.
 * 3) Mutación estructurada: se parte de un estado VÁLIDO de la instancia y cada
 *    4 bytes de entrada son (sección, desplazamiento, desplazamiento, valor): se
 *    ASIGNA ese byte dentro de la sección elegida (en las secciones grandes, solo
 *    en sus primeros o últimos 32 bytes, donde están los campos pequeños) y se
 *    recalcula el CRC. Así se llega a combinaciones de campos incoherentes
 *    (auditoría M3, H1/H3).
 * 4) gb_sram_load con los bytes como .sav (RAM + bloque RTC) y gb_rtc_set_time.
 * Si una carga se acepta, se ejecutan frames.
 * (Las variables estáticas de este archivo son del fuzzer, no del núcleo.)
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

static gb *fixed_instance(uint8_t selector)
{
    static const uint8_t types[4][2] = {   /* {tipo 0x147, RAM 0x149} */
        { 0x10, 0x03 }, { 0x03, 0x03 }, { 0x1B, 0x03 }, { 0x00, 0x00 }
    };
    enum { SIZE = 0x8000 };
    uint8_t *rom = calloc(1, SIZE);
    if (!rom)
        return NULL;
    static const uint8_t entry[4] = { 0x00, 0xC3, 0x50, 0x01 };
    static const uint8_t prog[] = { 0x3C, 0xEA, 0x00, 0xC0, 0x18, 0xFA };
    memcpy(rom + 0x100, entry, sizeof entry);
    memcpy(rom + 0x150, prog, sizeof prog);
    rom[0x147] = types[selector % 4][0];   /* MBC3+RTC / MBC1 / MBC5 / ROM-only */
    rom[0x149] = types[selector % 4][1];
    gb *g = gb_create();
    if (g && gb_load_rom(g, rom, SIZE, NULL) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static void put32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)(v >> (8 * i));
}

static void run_frames(gb *g)
{
    for (int f = 0; f < 3; f++)
        gb_run_frame(g);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    gb *g = fixed_instance(size ? data[0] : 0);
    if (!g)
        return 0;
    /* Estado base válido (tras unos frames), antes de cualquier mutación. */
    run_frames(g);
    size_t base_n = gb_state_size(g);
    uint8_t *base = malloc(base_n);
    if (!base || gb_state_save(g, base, base_n) != GB_OK) {
        free(base);
        gb_destroy(g);
        return 0;
    }

    if (gb_state_load(g, data, size) == GB_OK)
        run_frames(g);

    /* 3) mutación estructurada por secciones */
    enum { MAX_SECTIONS = 16 };
    size_t sec_off[MAX_SECTIONS], sec_len[MAX_SECTIONS], nsec = 0;
    for (size_t pos = 44; pos + 8 <= base_n - 4 && nsec < MAX_SECTIONS;) {
        size_t len = (size_t)base[pos + 4] | (size_t)base[pos + 5] << 8 |
                     (size_t)base[pos + 6] << 16 | (size_t)base[pos + 7] << 24;
        sec_off[nsec] = pos + 8;
        sec_len[nsec] = len;
        nsec++;
        pos += 8 + len;
    }
    for (size_t i = 0; nsec && i + 4 <= size && i < 4 * 64; i += 4) {
        size_t k = data[i] % nsec, len = sec_len[k], within;
        if (len == 0)
            continue;
        if (len > 256)
            within = (data[i + 1] & 1) ? len - 1 - data[i + 2] % 32 : data[i + 2] % 32;
        else
            within = ((size_t)data[i + 1] << 8 | data[i + 2]) % len;
        base[sec_off[k] + within] = data[i + 3];
    }
    put32(base + base_n - 4, crc32_update(0, base, base_n - 4));
    if (gb_state_load(g, base, base_n) == GB_OK)
        run_frames(g);
    free(base);

    /* 4) .sav hostil: tamaño forzado a RAM + 48 */
    size_t sav_n = gb_sram_save_size(g);
    uint8_t *sav = calloc(1, sav_n ? sav_n : 1);
    if (sav) {
        if (size)
            for (size_t i = 0; i < sav_n; i++)
                sav[i] = data[(i * 7919u) % size];
        if (size >= 48 && sav_n >= 48)   /* bloque RTC al final (solo si cabe) */
            memcpy(sav + sav_n - 48, data + size - 48, 48);
        if (gb_sram_load(g, sav, sav_n) == GB_OK) {
            gb_rtc_set_time(g, size >= 8 ? (int64_t)((uint64_t)data[0] << 56 | (uint64_t)data[1] << 40 |
                                                     (uint64_t)data[2] << 24 | data[3])
                                         : 0);
            run_frames(g);
        }
        free(sav);
    }

    if (size >= 48) {
        uint8_t *s = malloc(size);
        if (s) {
            memcpy(s, data, size);
            memcpy(s, "PGBS", 4);
            put32(s + 4, 1);
            memcpy(s + 8, g->info.fingerprint, 32);
            put32(s + 40, 1);
            put32(s + size - 4, crc32_update(0, s, size - 4));
            if (gb_state_load(g, s, size) == GB_OK)
                run_frames(g);
            free(s);
        }
    }
    gb_destroy(g);
    return 0;
}
