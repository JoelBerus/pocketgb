/*
 * cart.c — cabecera del cartucho, ROM-only, MBC1, MBC3 (+RTC) y MBC5, SRAM.
 *
 * El ROM es entrada no confiable (docs/03-core-spec.md §Cabecera, §Seguridad):
 * los tamaños se validan contra la cabecera y contra el archivo, y todo índice
 * de banco se reduce con % contra el número real de bancos en
 * cart_update_banks, que precalcula los offsets tras cada escritura de registro.
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

enum { ROM_BANK = 0x4000, RAM_BANK = 0x2000 };

static const uint32_t ram_sizes[6] = { 0, 0, 8u * 1024, 32u * 1024, 128u * 1024, 64u * 1024 };

static bool is_mfr_char(uint8_t ch)
{
    return (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
}

static void parse_title(const uint8_t *rom, char out[17])
{
    size_t n = 16;
    uint8_t flag = rom[0x143];
    if (flag == 0x80 || flag == 0xC0) {
        n = 15;
        if (is_mfr_char(rom[0x13F]) && is_mfr_char(rom[0x140]) &&
            is_mfr_char(rom[0x141]) && is_mfr_char(rom[0x142]))
            n = 11;
    }
    size_t i = 0;
    for (; i < n && rom[0x134 + i] != 0x00; i++) {
        uint8_t ch = rom[0x134 + i];
        out[i] = (ch >= 0x20 && ch <= 0x7E) ? (char)ch : '?';
    }
    out[i] = '\0';
}

void *cart_alloc(gb *g, size_t size)
{
#ifdef GB_TEST_HOOKS
    if (g->dbg.fail_alloc_at > 0 && ++g->dbg.alloc_count == g->dbg.fail_alloc_at)
        return NULL;
#else
    (void)g;
#endif
    return malloc(size);
}

void cart_free(gb *g)
{
    free(g->cart.rom);
    free(g->cart.ram);
    memset(&g->cart, 0, sizeof g->cart);
}

/* Tipo de cartucho (0x147) → MBC y extras. Falso si no está soportado. */
static bool cart_features(uint8_t type, struct gb_cart *c)
{
    switch (type) {
    case 0x00: c->mbc = MBC_NONE; break;
    case 0x01: c->mbc = MBC_MBC1; break;
    case 0x02: c->mbc = MBC_MBC1; c->has_ram = true; break;
    case 0x03: c->mbc = MBC_MBC1; c->has_ram = c->has_battery = true; break;
    case 0x0F: c->mbc = MBC_MBC3; c->has_rtc = c->has_battery = true; break;
    case 0x10: c->mbc = MBC_MBC3; c->has_rtc = c->has_ram = c->has_battery = true; break;
    case 0x11: c->mbc = MBC_MBC3; break;
    case 0x12: c->mbc = MBC_MBC3; c->has_ram = true; break;
    case 0x13: c->mbc = MBC_MBC3; c->has_ram = c->has_battery = true; break;
    case 0x19: c->mbc = MBC_MBC5; break;
    case 0x1A: c->mbc = MBC_MBC5; c->has_ram = true; break;
    case 0x1B: c->mbc = MBC_MBC5; c->has_ram = c->has_battery = true; break;
    case 0x1C: c->mbc = MBC_MBC5; c->has_rumble = true; break;
    case 0x1D: c->mbc = MBC_MBC5; c->has_rumble = c->has_ram = true; break;
    case 0x1E: c->mbc = MBC_MBC5; c->has_rumble = c->has_ram = c->has_battery = true; break;
    default: return false;
    }
    return true;
}

gb_result cart_load(gb *g, const uint8_t *data, size_t len)
{
    if (len < 0x150)
        return GB_ERR_ROM_TOO_SMALL;
    if (len > GB_ROM_MAX_BYTES)
        return GB_ERR_ROM_TOO_LARGE;

    uint8_t rom_code = data[0x148];
    if (rom_code > 8)
        return GB_ERR_BAD_ROM_SIZE_CODE;   /* incluye 0x52–0x54, fuera de alcance */
    uint32_t rom_size = 32u * 1024u << rom_code;
    if (rom_size > len)
        return GB_ERR_ROM_TRUNCATED;

    uint8_t ram_code = data[0x149];
    if (ram_code >= sizeof ram_sizes / sizeof ram_sizes[0])
        return GB_ERR_BAD_RAM_SIZE_CODE;

    struct gb_cart c;
    memset(&c, 0, sizeof c);
    uint8_t type = data[0x147];
    if (!cart_features(type, &c))
        return GB_ERR_UNSUPPORTED_MBC;

    c.rom_size = rom_size;
    c.rom_banks = rom_size / ROM_BANK;
    c.ram_size = c.has_ram ? ram_sizes[ram_code] : 0;
    c.ram_banks = c.ram_size / RAM_BANK;
    if (c.ram_size == 0)
        c.has_ram = false;

#ifdef GB_TEST_HOOKS
    g->dbg.alloc_count = 0;
#endif
    c.rom = cart_alloc(g, rom_size);
    if (!c.rom)
        return GB_ERR_OUT_OF_MEMORY;
    memcpy(c.rom, data, rom_size);   /* el excedente del archivo se ignora */
    if (c.ram_size) {
        c.ram = cart_alloc(g, c.ram_size);
        if (!c.ram) {
            free(c.rom);
            return GB_ERR_OUT_OF_MEMORY;
        }
        memset(c.ram, 0x00, c.ram_size);
    }
    g->cart = c;

    /* Información para el frontend */
    gb_rom_info *info = &g->info;
    memset(info, 0, sizeof *info);
    parse_title(c.rom, info->title);
    info->cgb_flag = c.rom[0x143];
    info->cart_type = type;
    info->rom_bytes = rom_size;
    info->sram_bytes = c.has_battery ? c.ram_size : 0;
    info->has_battery = c.has_battery;
    info->has_rtc = c.has_rtc;

    uint8_t x = 0;
    for (uint32_t i = 0x134; i <= 0x14C; i++)
        x = (uint8_t)(x - c.rom[i] - 1);
    info->header_checksum_ok = x == c.rom[0x14D];

    uint16_t sum = 0;
    for (uint32_t i = 0; i < rom_size; i++)
        if (i != 0x14E && i != 0x14F)
            sum = (uint16_t)(sum + c.rom[i]);
    info->global_checksum_ok = sum == (uint16_t)(c.rom[0x14E] << 8 | c.rom[0x14F]);

    sha256(c.rom, rom_size, info->fingerprint);
    return GB_OK;
}

void cart_update_banks(gb *g)
{
    struct gb_cart *c = &g->cart;
    uint32_t rom0 = 0, romx = 1, ram_bank = 0;
    c->rtc_reg = -1;
    switch (c->mbc) {
    case MBC_NONE:
        break;
    case MBC_MBC1:
        /* El 0→1 solo mira los 5 bits bajos: 0x20/0x40/0x60 no se ven en 4000. */
        romx = (uint32_t)c->bank_hi << 5 | (c->bank_lo & 0x1F ? c->bank_lo & 0x1F : 1);
        if (c->mode) {
            rom0 = (uint32_t)c->bank_hi << 5;
            ram_bank = c->bank_hi;
        }
        break;
    case MBC_MBC3:
        romx = c->bank_lo ? c->bank_lo : 1;
        if (c->ram_sel >= 0x08 && c->ram_sel <= 0x0C)
            c->rtc_reg = c->has_rtc ? (int8_t)(c->ram_sel - 0x08) : -1;
        else
            ram_bank = c->ram_sel & 0x07;
        break;
    case MBC_MBC5:
        romx = (uint32_t)(c->bank_hi & 1) << 8 | c->bank_lo;   /* 0 es válido */
        ram_bank = c->ram_sel & 0x0F;
        break;
    }
    c->rom0_off = (rom0 % c->rom_banks) * ROM_BANK;
    c->romx_off = (romx % c->rom_banks) * ROM_BANK;
    c->ram_mapped = c->has_ram && c->ram_enabled && c->rtc_reg < 0 &&
                    !(c->mbc == MBC_MBC3 && c->ram_sel > 0x07);
    c->ram_off = c->ram_banks ? (ram_bank % c->ram_banks) * RAM_BANK : 0;
    if (!c->ram_enabled)
        c->rtc_reg = -1;
}

void cart_reset(gb *g)
{
    struct gb_cart *c = &g->cart;
    c->ram_enabled = false;
    c->ram_written = false;
    c->sram_dirty = false;
    c->bank_lo = 1;
    c->bank_hi = 0;
    c->ram_sel = 0;
    c->mode = 0;
    c->rumble_on = false;
    cart_update_banks(g);
}

uint8_t cart_rom_read(const gb *g, uint16_t addr)
{
    const struct gb_cart *c = &g->cart;
    uint32_t off = addr < 0x4000 ? c->rom0_off : c->romx_off;
    return c->rom[off + (addr & 0x3FFF)];
}

void cart_rom_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_cart *c = &g->cart;
    if (c->mbc == MBC_NONE)
        return;
    if (addr < 0x2000) {
        bool enable = (v & 0x0F) == 0x0A;   /* MBC1/3/5: solo el nibble bajo */
        if (c->ram_enabled && !enable && c->ram_written && c->has_battery) {
            c->sram_dirty = true;
            c->ram_written = false;
        }
        c->ram_enabled = enable;
    } else if (addr < 0x4000) {
        if (c->mbc == MBC_MBC1)
            c->bank_lo = v & 0x1F;
        else if (c->mbc == MBC_MBC3)
            c->bank_lo = c->rom_banks > 128 ? v : v & 0x7F;   /* > 2 MiB: MBC30, 8 bits */
        else if (addr < 0x3000)
            c->bank_lo = v;                 /* MBC5: 8 bits bajos */
        else
            c->bank_hi = v & 0x01;          /* MBC5: bit 8 */
    } else if (addr < 0x6000) {
        if (c->mbc == MBC_MBC1) {
            c->bank_hi = v & 0x03;
        } else if (c->mbc == MBC_MBC3) {
            c->ram_sel = v & 0x0F;
        } else {
            c->rumble_on = c->has_rumble && (v & 0x08);
            c->ram_sel = c->has_rumble ? v & 0x07 : v & 0x0F;
        }
    } else {
        if (c->mbc == MBC_MBC1)
            c->mode = v & 0x01;
        else if (c->mbc == MBC_MBC3 && c->has_rtc)
            rtc_latch_write(g, v);
    }
    cart_update_banks(g);
}

uint8_t cart_ram_read(const gb *g, uint16_t addr)
{
    const struct gb_cart *c = &g->cart;
    if (c->ram_mapped)
        return c->ram[c->ram_off + (addr & 0x1FFF)];
    if (c->rtc_reg >= 0)
        return rtc_read(g);
    return 0xFF;
}

void cart_ram_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_cart *c = &g->cart;
    if (c->ram_mapped) {
        c->ram[c->ram_off + (addr & 0x1FFF)] = v;
        c->ram_written = true;
    } else if (c->rtc_reg >= 0) {
        rtc_write(g, v);
    }
}

/* ---- API pública de SRAM ---- */

size_t gb_sram_save_size(const gb *g)
{
    if (!g || !g->rom_loaded)
        return 0;
    return g->info.sram_bytes + (g->cart.has_rtc ? RTC_SAVE_BYTES : 0);
}

gb_result gb_sram_load(gb *g, const uint8_t *data, size_t len)
{
    if (!g || (!data && len))
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    size_t ram = g->info.sram_bytes;
    /* Con RTC se aceptan el bloque de 48 bytes (hora u64), el antiguo de 44 (hora u32)
     * y el .sav sin bloque (reloj a cero): rechazar una partida válida es peor. */
    bool rtc48 = g->cart.has_rtc && len == ram + RTC_SAVE_BYTES;
    bool rtc44 = g->cart.has_rtc && len == ram + RTC_SAVE_BYTES - 4;
    if (len != ram && !rtc48 && !rtc44)
        return GB_ERR_SRAM_SIZE;
    if (ram)
        memcpy(g->cart.ram, data, ram);
    if (rtc48 || rtc44) {
        uint8_t block[RTC_SAVE_BYTES] = { 0 };
        memcpy(block, data + ram, len - ram);   /* 44 bytes: los 4 altos de la hora quedan a 0 */
        int64_t now = g->cart.rtc.unix;
        rtc_deserialize(&g->cart.rtc, block);
        if (!rtc_unix_valid(g->cart.rtc.unix))
            g->cart.rtc.unix = now;   /* hora guardada inválida: "sin hora", no se avanza */
        gb_rtc_set_time(g, now);   /* el reloj avanza lo que pasó con la consola apagada */
    }
    return GB_OK;
}

gb_result gb_sram_save(const gb *g, uint8_t *out, size_t cap)
{
    if (!g || (!out && cap))
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    size_t n = gb_sram_save_size(g), ram = g->info.sram_bytes;
    if (cap < n)
        return GB_ERR_BUFFER_TOO_SMALL;
    if (ram)
        memcpy(out, g->cart.ram, ram);
    if (g->cart.has_rtc)
        rtc_serialize(&g->cart.rtc, out + ram);
    return GB_OK;
}

bool gb_sram_dirty(const gb *g)
{
    return g && g->rom_loaded && g->cart.sram_dirty;
}

void gb_sram_clear_dirty(gb *g)
{
    if (g)
        g->cart.sram_dirty = false;
}

void gb_rtc_set_time(gb *g, int64_t unix_time)
{
    if (!g || !g->rom_loaded || !g->cart.has_rtc)
        return;
    struct gb_rtc *r = &g->cart.rtc;
    if (!rtc_unix_valid(unix_time) || !rtc_unix_valid(r->unix))
        return;   /* ambos en [0, 2^40): la resta no puede desbordar */
    if (unix_time > r->unix) {
        if (!(r->reg[RTC_DH] & 0x40))
            rtc_add_seconds(r, (uint64_t)unix_time - (uint64_t)r->unix);
        r->unix = unix_time;
    }
}
