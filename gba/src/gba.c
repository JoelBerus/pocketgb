/*
 * gba.c — ciclo de vida, carga del ROM y bucle de ejecución del núcleo GBA.
 */
#include "gba_internal.h"
#include <stdlib.h>

gba *gba_create(void)
{
    gba *g = calloc(1, sizeof *g);
    if (!g) return NULL;
    gba_arm_init_tables(g);
    gba_options_default(&g->opts);
    return g;
}

void gba_destroy(gba *g)
{
    if (!g) return;
    free(g->rom);
    free(g);
}

void gba_options_default(gba_options *opts)
{
    if (!opts) return;
    memset(opts, 0, sizeof *opts);
    opts->sample_rate = 48000;
    opts->save_type = GBA_SAVE_AUTO;
}

gba_result gba_load_bios(gba *g, const uint8_t *data, size_t len)
{
    if (!g || !data) return GBA_ERR_NULL_ARG;
    if (len != GBA_BIOS_BYTES) return GBA_ERR_BIOS_SIZE;
    memcpy(g->bios, data, GBA_BIOS_BYTES);
    g->bios_loaded = true;
    return GBA_OK;
}

static void gba_unload(gba *g)
{
    free(g->rom);
    g->rom = NULL;
    g->rom_size = 0;
    g->rom_mask = 0;
}

static void gba_power_on(gba *g)
{
    memset(g->ewram, 0, sizeof g->ewram);
    memset(g->iwram, 0, sizeof g->iwram);
    gba_io_reset(g);
    gba_apu_reset(g);
    memset(g->pal, 0, sizeof g->pal);
    memset(g->vram, 0, sizeof g->vram);
    memset(g->oam, 0, sizeof g->oam);
    memset(g->framebuffer, 0, sizeof g->framebuffer);
    g->cycles = 0;
    g->line_cycles = 0;
    g->vcount = 0;
    g->hblank = false;
    g->frame_done = false;
    g->last_was_fetch = false;
    g->dma_active = false;
    g->hle_waiting = false;
    if (!g->bios_loaded) gba_hle_install(g);
    g->bios_last = 0xE129F000u;            /* lo que deja el arranque de la BIOS */
    gba_arm_reset(g, true);
}

gba_result gba_load_rom(gba *g, const uint8_t *data, size_t len, const gba_options *opts)
{
    if (!g || !data) return GBA_ERR_NULL_ARG;
    gba_unload(g);
    if (len < GBA_ROM_MIN_BYTES) return GBA_ERR_ROM_TOO_SMALL;
    if (len > GBA_ROM_MAX_BYTES) return GBA_ERR_ROM_TOO_LARGE;
    if (data[0xB2] != 0x96) return GBA_ERR_BAD_HEADER;
    uint8_t *rom = malloc(len);
    if (!rom) return GBA_ERR_OUT_OF_MEMORY;
    memcpy(rom, data, len);
    g->rom = rom;
    g->rom_size = (uint32_t)len;
    uint32_t mask = 1;
    while (mask < g->rom_size) mask <<= 1;
    g->rom_mask = mask - 1u;
    if (opts) g->opts = *opts;
    else gba_options_default(&g->opts);
    /* Frecuencia de salida acotada (0 = sin audio): fuera de rango, la más cercana. */
    if (g->opts.sample_rate && g->opts.sample_rate < 8000) g->opts.sample_rate = 8000;
    if (g->opts.sample_rate > 192000) g->opts.sample_rate = 192000;
    /* Ajustes fuera de rango (p. ej. de un archivo de ajustes dañado): automático. */
    if ((unsigned)g->opts.save_type > (unsigned)GBA_SAVE_EEPROM8K) g->opts.save_type = GBA_SAVE_AUTO;
    if (g->opts.rtc > GBA_RTC_OFF) g->opts.rtc = GBA_RTC_AUTO;
    sha256(rom, len, g->fingerprint);
    gba_cart_init(g);
    gba_power_on(g);
    return GBA_OK;
}

gba_result gba_rom_info_get(const gba *g, gba_rom_info *out)
{
    if (!g || !out) return GBA_ERR_NULL_ARG;
    if (!g->rom) return GBA_ERR_NO_ROM;
    memset(out, 0, sizeof *out);
    for (int i = 0; i < 12; i++) {
        uint8_t ch = g->rom[0xA0 + i];
        out->title[i] = (ch >= 0x20 && ch < 0x7F) ? (char)ch : '\0';
        if (!ch) break;
    }
    for (int i = 0; i < 4; i++) {
        uint8_t ch = g->rom[0xAC + i];
        out->game_code[i] = (ch >= 0x20 && ch < 0x7F) ? (char)ch : '?';
    }
    for (int i = 0; i < 2; i++) {
        uint8_t ch = g->rom[0xB0 + i];
        out->maker_code[i] = (ch >= 0x20 && ch < 0x7F) ? (char)ch : '?';
    }
    out->version = g->rom[0xBC];
    out->rom_bytes = g->rom_size;
    uint8_t chk = 0;
    for (int i = 0xA0; i <= 0xBC; i++) chk = (uint8_t)(chk - g->rom[i]);
    chk = (uint8_t)(chk - 0x19);
    out->header_checksum_ok = chk == g->rom[0xBD];
    out->save_type = g->save_type;
    out->save_bytes = g->save_bytes;
    out->has_rtc = g->has_rtc;
    out->bios_loaded = g->bios_loaded;
    memcpy(out->fingerprint, g->fingerprint, sizeof out->fingerprint);
    return GBA_OK;
}

void gba_reset(gba *g)
{
    if (!g || !g->rom) return;
    gba_power_on(g);
}

void gba_set_buttons(gba *g, uint16_t mask)
{
    if (!g) return;
    g->keys = mask & 0x3FFu;
    if (g->keycnt & 0x4000u) {
        uint16_t sel = g->keycnt & 0x3FFu, pressed = g->keys & sel;
        bool hit = (g->keycnt & 0x8000u) ? (pressed == sel && sel) : pressed != 0;
        if (hit) gba_irq_raise(g, GBA_IRQ_KEYPAD);
    }
}

/* Un paso: IRQ pendiente, instrucción (o salto hasta el próximo evento si la
 * CPU está parada) y avance de vídeo y timers. */
static void gba_step(gba *g)
{
    gba_arm *c = &g->cpu;
    if (c->halted) {
        if (gba_irq_pending(g)) {
            c->halted = false;
        } else {
            uint32_t n = gba_cycles_to_event(g);
            g->cycles += n;
            gba_tick(g, n);
            return;
        }
    }
    uint64_t before = g->cycles;
    if ((g->ime & 1u) && gba_irq_pending(g) && !(c->cpsr & ARM_I)) gba_arm_irq(g);
    else gba_arm_step(g);
    gba_tick(g, (uint32_t)(g->cycles - before));
}

uint32_t gba_run_cycles(gba *g, uint32_t cycles)
{
    if (!g || !g->rom) return 0;
    uint64_t start = g->cycles;
    while (g->cycles - start < cycles) gba_step(g);
    gba_apu_sync(g);
    return (uint32_t)(g->cycles - start);
}

void gba_run_frame(gba *g)
{
    if (!g || !g->rom) return;
    g->frame_done = false;
    uint64_t start = g->cycles;
    /* Tope de seguridad: dos frames (el VBlank llega siempre antes). */
    while (!g->frame_done && g->cycles - start < 2u * GBA_CYCLES_PER_FRAME) gba_step(g);
    gba_apu_sync(g);
}

#ifdef GBA_FUZZ_HOOKS
/* Solo para los fuzzers: un acceso al bus como lo haría la CPU. */
uint32_t gba_fuzz_bus(gba *g, int op, uint32_t addr, uint32_t v);
uint32_t gba_fuzz_bus(gba *g, int op, uint32_t addr, uint32_t v)
{
    switch (op) {
    case 0: return gba_bus_read8(g, addr);
    case 1: return gba_bus_read16(g, addr);
    case 2: return gba_bus_read32(g, addr);
    case 3: gba_bus_write8(g, addr, (uint8_t)v); return 0;
    case 4: gba_bus_write16(g, addr, (uint16_t)v); return 0;
    default: gba_bus_write32(g, addr, v); return 0;
    }
}

/* Solo para fuzz_io: escritura de 8, 16 o 32 bits en el bus como la haría la CPU. */
void gba_fuzz_poke(gba *g, uint32_t addr, uint16_t v, int width);
void gba_fuzz_poke(gba *g, uint32_t addr, uint16_t v, int width)
{
    if (width == 0) gba_bus_write8(g, addr, (uint8_t)v);
    else if (width == 1) gba_bus_write16(g, addr, v);
    else gba_bus_write32(g, addr, v * 0x10001u);
}
#endif

uint64_t gba_cycle_count(const gba *g) { return g ? g->cycles : 0; }
const uint32_t *gba_framebuffer(const gba *g) { return g ? g->framebuffer : NULL; }


const char *gba_result_str(gba_result r)
{
    switch (r) {
    case GBA_OK: return "ok";
    case GBA_ERR_NULL_ARG: return "argumento nulo";
    case GBA_ERR_OUT_OF_MEMORY: return "sin memoria";
    case GBA_ERR_ROM_TOO_SMALL: return "ROM demasiado pequeño";
    case GBA_ERR_ROM_TOO_LARGE: return "ROM de más de 32 MiB";
    case GBA_ERR_BAD_HEADER: return "cabecera de GBA no válida";
    case GBA_ERR_NO_ROM: return "no hay ROM cargado";
    case GBA_ERR_BIOS_SIZE: return "la BIOS debe tener 16 KiB";
    case GBA_ERR_SAVE_SIZE: return "tamaño de partida incorrecto";
    case GBA_ERR_STATE_MAGIC: return "no es un estado de PocketGB (GBA)";
    case GBA_ERR_STATE_VERSION: return "versión de estado no soportada";
    case GBA_ERR_STATE_ROM_MISMATCH: return "el estado es de otro juego";
    case GBA_ERR_STATE_CORRUPT: return "estado corrupto";
    case GBA_ERR_BUFFER_TOO_SMALL: return "búfer demasiado pequeño";
    case GBA_ERR_STATE_CONFIG: return "el estado es de otra configuración (partida, reloj o BIOS)";
    }
    return "error desconocido";
}
