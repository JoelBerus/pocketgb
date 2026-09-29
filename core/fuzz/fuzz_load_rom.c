/*
 * fuzz_load_rom.c — libFuzzer: los bytes son el ROM (docs/06-testing.md §Fuzzing).
 *
 * 1) gb_load_rom con los bytes tal cual (validación de cabecera).
 * 2) Si el archivo es corto para lo que declara, se rellena con ceros hasta el
 *    tamaño declarado (máx. 1 MiB) para que la CPU ejecute código arbitrario.
 * En ambos casos: 30 frames con botones derivados de los bytes, y los caminos
 * de SRAM y save state (guardar → cargar). El modelo (auto / DMG / CGB) sale
 * del byte 0x14C, así que un ROM DMG también corre en compatibilidad.
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb.h"

static void exercise(gb *g, const uint8_t *d, size_t n)
{
    for (int f = 0; f < 30; f++) {
        gb_set_buttons(g, n ? d[((size_t)f * 131u) % n] : 0);
        gb_run_frame(g);
    }
    size_t s = gb_sram_save_size(g);
    uint8_t *buf = malloc(s ? s : 1);
    if (buf && gb_sram_save(g, buf, s) == GB_OK)
        (void)gb_sram_load(g, buf, s);
    free(buf);
    size_t st = gb_state_size(g);
    uint8_t *state = malloc(st);
    if (state && gb_state_save(g, state, st) == GB_OK && gb_state_load(g, state, st) != GB_OK)
        abort();   /* un estado recién guardado siempre debe cargar */
    free(state);
    gb_rtc_set_time(g, 2000000000);
    gb_run_frame(g);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    gb *g = gb_create();
    if (!g)
        return 0;
    gb_options o;
    gb_options_default(&o);
    o.unix_time = 1700000000;
    if (size > 0x14C)
        o.model = (gb_model)(data[0x14C] % 3);
    if (size > 0x14E)
        o.compat_palette = data[0x14E] % (GB_COMPAT_PALETTES + 2);
    gb_result r = gb_load_rom(g, data, size, &o);
    if (r == GB_OK) {
        exercise(g, data, size);
    } else if ((r == GB_ERR_ROM_TRUNCATED || r == GB_ERR_ROM_TOO_SMALL) && size >= 0x150) {
        uint8_t code = data[0x148] > 5 ? 5 : data[0x148];   /* ≤ 1 MiB */
        size_t want = (size_t)32768 << code;
        uint8_t *rom = calloc(1, want);
        if (rom) {
            memcpy(rom, data, size < want ? size : want);
            rom[0x148] = code;
            if (gb_load_rom(g, rom, want, &o) == GB_OK)
                exercise(g, data, size);
            free(rom);
        }
    }
    gb_destroy(g);
    return 0;
}
