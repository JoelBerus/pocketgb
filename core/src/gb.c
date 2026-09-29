/*
 * gb.c — ciclo de vida de la instancia, carga del ROM y bucle de ejecución.
 * API pública: include/pocketgb.h. Spec: docs/03-core-spec.md.
 *
 * Pendiente por hito: gb_state_* y gb_rtc_set_time (M3), audio real (M5),
 * modo CGB (M8; hasta entonces un ROM solo-CGB devuelve GB_ERR_CGB_ONLY y uno
 * compatible corre como DMG).
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

static const uint32_t default_palette[4] = { 0xFFFFFFFFu, 0xFFAAAAAAu, 0xFF555555u, 0xFF000000u };

void gb_options_default(gb_options *opts)
{
    if (!opts)
        return;
    memset(opts, 0, sizeof *opts);
    opts->model = GB_MODEL_AUTO;
    opts->sample_rate = 48000;
    memcpy(opts->dmg_palette, default_palette, sizeof default_palette);
}

gb *gb_create(void)
{
    gb *g = calloc(1, sizeof *g);
    if (!g)
        return NULL;
    gb_options_default(&g->opts);
    ppu_reset(g);   /* pantalla en blanco hasta cargar un ROM */
    return g;
}

void gb_destroy(gb *g)
{
    if (!g)
        return;
    cart_free(g);
    free(g);
}

void gb_tick(gb *g, unsigned tcycles)
{
    for (; tcycles >= 4; tcycles -= 4) {
        timer_tick(g);
        dma_tick(g);
        ppu_tick(g, 4);
        g->cycles += 4;
    }
}

void gb_reset(gb *g)
{
    if (!g || !g->rom_loaded)
        return;
    mmu_reset(g);
    cart_reset(g);
    timer_reset(g);
    dma_reset(g);
    serial_reset(g);
    joypad_reset(g);
    ppu_reset(g);
    cpu_reset(g);
    memset(&g->dbg, 0, sizeof g->dbg);
}

static void unload(gb *g)
{
    cart_free(g);
    memset(&g->info, 0, sizeof g->info);
    g->rom_loaded = false;
}

gb_result gb_load_rom(gb *g, const uint8_t *data, size_t len, const gb_options *opts)
{
    if (!g || !data)
        return GB_ERR_NULL_ARG;
    unload(g);

    gb_options o;
    if (opts)
        o = *opts;
    else
        gb_options_default(&o);
    bool palette_set = false;
    for (int i = 0; i < 4; i++)
        palette_set |= o.dmg_palette[i] != 0;
    if (!palette_set)
        memcpy(o.dmg_palette, default_palette, sizeof default_palette);

    gb_result r = cart_load(g, data, len);
    if (r != GB_OK) {
        unload(g);
        return r;
    }
    /* Hasta M8 no hay modo CGB: un ROM solo-CGB no puede ejecutarse. */
    if (g->cart.rom[0x143] == 0xC0) {
        unload(g);
        return GB_ERR_CGB_ONLY;
    }
    g->info.cgb_mode = false;

    g->opts = o;
    g->rom_loaded = true;
    g->cycles = 0;
    g->joy.buttons = 0;
    gb_reset(g);
    return GB_OK;
}

gb_result gb_rom_info_get(const gb *g, gb_rom_info *out)
{
    if (!g || !out)
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    *out = g->info;
    return GB_OK;
}

void gb_set_buttons(gb *g, uint8_t mask)
{
    if (g && g->rom_loaded)
        joypad_set(g, mask);
}

void gb_run_frame(gb *g)
{
    if (!g || !g->rom_loaded)
        return;
    /* Hasta la entrada en VBlank; con el LCD apagado, un frame de T-ciclos. */
    uint64_t start = g->cycles;
    g->ppu.frame_done = false;
    while (!g->ppu.frame_done && g->cycles - start < GB_CYCLES_PER_FRAME)
        cpu_step(g);
}

uint32_t gb_run_cycles(gb *g, uint32_t cycles)
{
    if (!g || !g->rom_loaded)
        return 0;
    uint64_t start = g->cycles;
    while (g->cycles - start < cycles)
        cpu_step(g);
    return (uint32_t)(g->cycles - start);
}

uint64_t gb_cycle_count(const gb *g)
{
    return g ? g->cycles : 0;
}

bool gb_cpu_locked(const gb *g)
{
    return g && g->rom_loaded && g->cpu.locked;
}

const uint32_t *gb_framebuffer(const gb *g)
{
    return g ? g->framebuffer : NULL;
}

/* Sin APU hasta M5: no hay muestras. */
size_t gb_audio_read(gb *g, int16_t *out, size_t max_frames)
{
    (void)g;
    (void)out;
    (void)max_frames;
    return 0;
}

size_t gb_audio_available(const gb *g)
{
    (void)g;
    return 0;
}

const char *gb_result_str(gb_result r)
{
    switch (r) {
    case GB_OK:                     return "OK";
    case GB_ERR_NULL_ARG:           return "argumento nulo";
    case GB_ERR_OUT_OF_MEMORY:      return "sin memoria";
    case GB_ERR_ROM_TOO_SMALL:      return "ROM demasiado pequeño";
    case GB_ERR_ROM_TOO_LARGE:      return "ROM demasiado grande";
    case GB_ERR_ROM_TRUNCATED:      return "ROM truncado (la cabecera declara más bytes)";
    case GB_ERR_BAD_ROM_SIZE_CODE:  return "código de tamaño de ROM no válido";
    case GB_ERR_BAD_RAM_SIZE_CODE:  return "código de tamaño de RAM no válido";
    case GB_ERR_UNSUPPORTED_MBC:    return "tipo de cartucho no soportado";
    case GB_ERR_CGB_ONLY:           return "ROM solo para Game Boy Color";
    case GB_ERR_NO_ROM:             return "no hay ROM cargado";
    case GB_ERR_SRAM_SIZE:          return "tamaño de partida incorrecto";
    case GB_ERR_STATE_MAGIC:        return "estado: firma incorrecta";
    case GB_ERR_STATE_VERSION:      return "estado: versión no soportada";
    case GB_ERR_STATE_ROM_MISMATCH: return "estado: pertenece a otro ROM";
    case GB_ERR_STATE_CORRUPT:      return "estado: datos corruptos";
    case GB_ERR_BUFFER_TOO_SMALL:   return "búfer demasiado pequeño";
    }
    return "error desconocido";
}
