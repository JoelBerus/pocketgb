/*
 * gb.c — ciclo de vida de la instancia, carga del ROM y bucle de ejecución.
 * API pública: include/pocketgb.h. Spec: docs/03-core-spec.md.
 *
 * Modelo: DMG, CGB nativa o CGB en compatibilidad (ROM DMG con paleta de color).
 * En doble velocidad la CPU y el timer van a 2×: cada M-ciclo de CPU son 2 dots
 * de PPU/APU/RTC y 2 T-ciclos de gb_cycle_count (tiempo real).
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
    unsigned dots = g->cgb.double_speed ? 2 : 4;
    for (; tcycles >= 4; tcycles -= 4) {
        if (!g->cpu.stopped)
            timer_tick(g);      /* STOP pone DIV a 0 y lo congela hasta salir */
        dma_tick(g);
        g->apu.pending += dots; /* el APU se pone al día al tocar sus registros (apu_sync) */
        ppu_tick(g, dots);
        if (g->cgb.hdma_req) {
            /* HDMA de HBlank: un bloque al entrar en modo 0. Con la CPU en HALT
             * o STOP la transferencia se detiene y sigue en el siguiente HBlank
             * tras despertar (Pan Docs, FF55; auditoría M8, H2). */
            if (g->cpu.halted || g->cpu.stopped)
                g->cgb.hdma_req = false;
            else
                cgb_hdma_hblank(g);
        }
        if (g->cart.has_rtc)
            rtc_tick(g, dots);
        g->cycles += dots;
    }
}

void gb_reset(gb *g)
{
    if (!g || !g->rom_loaded)
        return;
    mmu_reset(g);
    cgb_reset(g);
    cart_reset(g);
    timer_reset(g);
    apu_reset(g);
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
    g->cgb.on = g->cgb.compat = false;
    g->cgb.double_speed = false;
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
    /* Frecuencia de salida razonable (0 = sin audio). */
    if (o.sample_rate && o.sample_rate < 8000)
        o.sample_rate = 8000;
    if (o.sample_rate > 192000)
        o.sample_rate = 192000;

    gb_result r = cart_load(g, data, len);
    if (r != GB_OK) {
        unload(g);
        return r;
    }
    rtc_reset(g, o.unix_time);
    /* Modelo: AUTO (o un valor desconocido) = CGB si el ROM la soporta. */
    uint8_t flag = g->cart.rom[0x143];
    bool cgb = o.model == GB_MODEL_CGB || (o.model != GB_MODEL_DMG && (flag & 0x80));
    if (!cgb && flag == 0xC0) {
        unload(g);
        return GB_ERR_CGB_ONLY;
    }
    g->cgb.on = cgb;
    g->cgb.compat = cgb && !(flag & 0x80);
    g->info.cgb_mode = cgb;
    g->info.cgb_compat = g->cgb.compat;

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
    apu_sync(g);
}

uint32_t gb_run_cycles(gb *g, uint32_t cycles)
{
    if (!g || !g->rom_loaded)
        return 0;
    uint64_t start = g->cycles;
    while (g->cycles - start < cycles)
        cpu_step(g);
    apu_sync(g);
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
